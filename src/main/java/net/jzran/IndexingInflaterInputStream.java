package net.jzran;

import static net.jzran.ZLib.Z_STREAM_END;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.sun.jna.Memory;

/**
 * This class implements a stream filter for uncompressing data in the
 * "deflate" compression format.
 * While reading the data, an index is build to enable fast accessing the deflated input stream.
 */
public class IndexingInflaterInputStream extends SeekableInputStream {
    private static final ZLib Z = ZLib.INSTANCE;

    private static final int CHUNK = 65536;
    private static final int WINDOW_BITS = 15;
    static final int WINDOW_SIZE = 1 << WINDOW_BITS;
    public static final int DEFAULT_SPAN = 1_048_576;

    private final InputStream delegate;
    private final long span;
    private final int windowBits;
    private final z_stream stream = new z_stream();

    private final Memory input = new Memory(CHUNK);
    private final ByteBuffer inputBuffer = input.getByteBuffer(0, CHUNK);
    private final byte[] inputChunkBuf = new byte[CHUNK];

    private final Memory window = new Memory(WINDOW_SIZE);
    
    /* Output buffer to accumulate decompressed data */
    private byte[] outBuf = new byte[CHUNK];
    private int outLen = 0;
    private int outPos = 0;
    
    private final List<CheckPoint> index = new ArrayList<>();  // access points being generated

    private long totalBytesIn;
    private long totalBytesOut;
    private long last;
    private int windowCopied;
    private boolean finished;
    private boolean closed = false;

    protected IndexingInflaterInputStream(InputStream delegate, long span, int windowBits) throws IOException {
        this.delegate = delegate;
        this.span = span;
        this.windowBits = windowBits;

        stream.zalloc = null;
        stream.zfree = null;
        stream.opaque = null;
        stream.avail_in = 0;
        stream.next_in = null;

        // JNA Memory is not guaranteed to be zeroed (the native heap may
        // reuse a dirty chunk), and checkpoints copy the whole window
        window.clear();
        input.clear();

        // see https://www.zlib.net/manual.html#Advanced for window bits
        int ret = Z.inflateInit2_(stream, windowBits, Z.zlibVersion(), ZLib.STREAM_SIZE);
        if (ret != ZLib.Z_OK) {
            throw new IOException("zlib error: " + ret);
        }
    }

    public List<CheckPoint> getIndex() {
        return index;
    }

    @Override
    public void seek(long offset) throws IOException {
        // TODO go to the indicated offset in the decompressed stream
    }

    @Override
    public long length() throws IOException {
        // TODO return the decompressed length
        return 0;
    }

    @Override
    public int read() throws IOException {
        byte[] b = new byte[1];
        return (read(b) > 0 ? b[0] : -1) & 0xFF;
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] buf, int offset, int len) throws IOException {
        if (closed) {
            throw new IllegalStateException("Attempting to read fro a closed stream");
        }
        if (len <= 0) {
            return 0;
        }

        if (outPos >= outLen) {
            if (finished) {
                return -1;
            }
            inflateChunk();
            if (outPos >= outLen) {
                return -1;
            }
        }

        int toCopy = Math.min(len, outLen - outPos);
        System.arraycopy(outBuf, outPos, buf, offset, toCopy);
        outPos += toCopy;
        return toCopy;
    }

    /**
     * Inflate until decompressed data is available in outBuf, the stream
     * has ended, or a zlib error occurs.
     */
    private void inflateChunk() throws IOException {
        outLen = 0;
        outPos = 0;
        outBuf = new byte[CHUNK];
        int totalProduced = 0;

        while (true) {
            /* get some compressed data from input file */
            if (stream.avail_in == 0) {
                int n = delegate.read(inputChunkBuf, 0, CHUNK);
                if (n < 0) {
                    /* no more input: drain until inflate signals Z_STREAM_END */
                    drainInput(totalProduced);
                    return;
                }
                inputBuffer.position(0);
                inputBuffer.put(inputChunkBuf, 0, n);
                stream.next_in = input;
                stream.avail_in = n;
            }

            // reset sliding window if necessary
            if (stream.avail_out == 0) {
                stream.avail_out = WINDOW_SIZE;
                stream.next_out = window;
                windowCopied = 0;
            }

            /* inflate until out of input, output, or at end of block --
               update the total input and output counters */
            totalBytesIn += stream.avail_in;
            totalBytesOut += stream.avail_out;
            int result = Z.inflate(stream, ZLib.Z_BLOCK);      /* return at end of block */
            totalBytesIn -= stream.avail_in;
            totalBytesOut -= stream.avail_out;

            switch (result) {
                case ZLib.Z_OK:
                case ZLib.Z_BUF_ERROR:
                    break;
                case Z_STREAM_END:
                    totalProduced += copyProduced(totalProduced);
                    outLen = totalProduced;
                    finished = true;
                    return;
                default:
                    throw new IOException("zlib error: " + result + " (" + ZLib.nameForCode(result) + ")");
            }

            /* copy newly produced data */
            totalProduced += copyProduced(totalProduced);

            /* if at end of block, consider adding an index entry */
            boolean isEndOfBlock = 0 != (stream.data_type & 128);
            boolean isNotStored = 0 == (stream.data_type & 64);
            if (isEndOfBlock && (isNotStored || windowBits < 0) &&
                (totalBytesOut == 0 || totalBytesOut - last > span)) {
                index.add(CheckPoint.newPoint(totalBytesIn, totalBytesOut, stream.data_type & 7, stream.avail_out, window));
                last = totalBytesOut;
            }
            if (windowBits < 0 && index.isEmpty() && totalBytesOut > 0 && totalBytesOut <= span) {
                index.add(CheckPoint.newPoint(0, 0L, 0, WINDOW_SIZE, window));
                last = 0;
            }

            if (totalProduced > 0) {
                outLen = totalProduced;
                return;
            }
        }
    }

    private void drainInput(int totalProduced) throws IOException {
        while(true) {
            if (stream.avail_out == 0) {
                stream.avail_out = WINDOW_SIZE;
                stream.next_out = window;
                windowCopied = 0;
            }
            totalBytesOut += stream.avail_out;
            int result = Z.inflate(stream, ZLib.Z_NO_FLUSH);
            totalBytesOut -= stream.avail_out;
            totalProduced += copyProduced(totalProduced);
            if (result == Z_STREAM_END) {
                outLen = totalProduced;
                finished = true;
                return;
            }
            if (result != ZLib.Z_OK) {
                throw new IOException("zlib error: " + result + " (" + ZLib.nameForCode(result) + ")");
            }
        }
    }

    /**
     * Copies the decompressed bytes produced since the last call from the
     * sliding window to outBuf.
     *
     * @param offset the offset to start writing in the {@link #outBuf}.
     * @return the number of bytes copied
     */
    private int copyProduced(int offset) {
        int produced = WINDOW_SIZE - stream.avail_out;
        int toCopy = produced - windowCopied;
        if (toCopy > 0) {
            if (outBuf.length < offset + toCopy) {
                outBuf = new byte[Math.max(outBuf.length * 2, offset + toCopy)];
            }
            System.arraycopy(window.getByteArray(windowCopied, toCopy), 0, outBuf, offset, toCopy);
            windowCopied = produced;
        }
        return toCopy;
    }

    @Override
    public void close() {
        if (closed) {
            throw new IllegalStateException("Can only close an open stream");
        }
        Z.inflateEnd(stream);
        input.close();
        window.close();
        closed = true;
    }

    public static IndexingInflaterInputStream forGZip(InputStream delegate) throws IOException {
        return new IndexingInflaterInputStream(delegate, DEFAULT_SPAN, 32 + WINDOW_BITS);
    }

    public static IndexingInflaterInputStream forDeflate(InputStream delegate) throws IOException {
        return new IndexingInflaterInputStream(delegate, DEFAULT_SPAN, -WINDOW_BITS);
    }
}