package net.jzran;

import static net.jzran.ZLib.Z_ERRNO;
import static net.jzran.ZLib.Z_STREAM_END;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.sun.jna.Memory;

/**
 * This class implements a stream filter for uncompressing data in the
 * "deflate" compression format.
 * While reading the data, and index is build to enable fast accessing the deflated input stream.
 */
public class IndexingInflaterInputStream extends FilterInputStream {
    private static final ZLib Z = ZLib.INSTANCE;

    private static final int CHUNK = 65536;
    private static final int WINDOW_BITS = 15;
    private static final int WINDOW_SIZE = 1 << WINDOW_BITS;
    private static final int DEFAULT_SPAN = 1_048_576;

    private final InputStream delegate;
    private final long span;
    private final int windowBits;
    private final z_stream stream = new z_stream();

    private final Memory input = new Memory(CHUNK);
    private final ByteBuffer inputBuffer = input.getByteBuffer(0, CHUNK);
    private final byte[] inputChunkBuf = new byte[CHUNK];

    private final Memory window = new Memory(WINDOW_SIZE);
    private final ByteBuffer windowBuf = window.getByteBuffer(0, WINDOW_SIZE);
    private final List<Point> index = new ArrayList<>();                /* access points being generated */

    private long totalBytesIn;
    private long totalBytesOut;
    private long last;
    private DeflateIndex.State state = DeflateIndex.State.VOID;

    /**
     * Default constructor.
     * @param delegate the input stream to read deflated data from.
     * @param span A "checkpoint" will take place every span bytes of
     *         decompressed data. A checkpoint takes about 32kb.
     * @param windowBits
     * @throws IOException When
     */
    private IndexingInflaterInputStream(InputStream delegate, long span, int windowBits) throws IOException {
        super(delegate);
        this.delegate = delegate;
        this.span = span;
        this.windowBits = windowBits;

        /* initialize inflate */
        stream.zalloc = null;
        stream.zfree = null;
        stream.opaque = null;
        stream.avail_in = 0;
        stream.next_in = null;

        // see https://www.zlib.net/manual.html#Advanced for window bits
        int ret = Z.inflateInit2_(stream, windowBits, Z.zlibVersion(), ZLib.STREAM_SIZE);
        if (ret != ZLib.Z_OK) {
            throw new IOException("zlib error: " + ret);
        }
        state = DeflateIndex.State.OPEN;
    }

    public List<Point> getIndex() {
        return index;
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
        if (len <= 0) {
            return 0;
        }

        if (stream.avail_out == 0) {
            if (inflateChunk()) {
                return -1;
            }
        }

        int toCopy = Math.min(len, stream.avail_out);
        windowBuf.position(0);
        windowBuf.get(buf, offset, toCopy);
        return toCopy;
    }

    /**
     * Fills the internal buffer with inflated data.
     *
     * @return {@code true} if end-of-stream is reached, {@code false} if more data is present.
     * @throws IOException when reading fails.
     */
    private boolean inflateChunk() throws IOException {

        /* get some compressed data from input file */
        stream.avail_in = delegate.read(inputChunkBuf, 0, CHUNK);

        /* If no more data is available and inflate hasn't returned
          Z_STREAMEND, we must check if the stream has actually ended.
          This can happen with raw deflate: inflate with Z_BLOCK
          returns Z_OK after the last block while all input is consumed,
          but won't return Z_STREAMEND until it knows there's no more
          input coming. We detect this by checking if there's no more
          to read (EOF) or if inflate consumed all input but didn't
          signal stream end. */
        int result;
        if (stream.avail_in == Z_ERRNO) {
            /* try one more inflate call to potentially get Z_STREAM_END */
            if (stream.avail_out == 0) {
                stream.avail_out = WINDOW_SIZE;
                stream.next_out = window;
            }
            result = Z.inflate(stream, ZLib.Z_NO_FLUSH);
            return result == Z_STREAM_END;
        }
        inputBuffer.position(0);
        inputBuffer.put(inputChunkBuf, 0, stream.avail_in);
//        inputBuffer.rewind();
        stream.next_in = input;
//        windowBuf.rewind();

        /* process all of that, or until end of stream */
        do {
            /* reset sliding window if necessary */
            if (stream.avail_out == 0) {
                stream.avail_out = WINDOW_SIZE;
                stream.next_out = window;
            }

            /* inflate until out of input, output, or at end of block --
               update the total input and output counters */
            totalBytesIn += stream.avail_in;
            totalBytesOut += stream.avail_out;
            result = Z.inflate(stream, ZLib.Z_BLOCK);      /* return at end of block */
            totalBytesIn -= stream.avail_in;
            totalBytesOut -= stream.avail_out;

            switch (result) {
                case ZLib.Z_NEED_DICT:
                case ZLib.Z_MEM_ERROR:
                case ZLib.Z_DATA_ERROR:
                    throw new IOException("zlib error: " + result + " (" + ZLib.nameForCode(result) + ")");
                case Z_STREAM_END:
                    return true;
            }

            /* if at end of block, consider adding an index entry (note that if
               data_type indicates an end-of-block, then all the
               uncompressed data from that block has been delivered, and none
               of the compressed data after that block has been consumed,
               except for up to seven bits) -- the totout == 0 provides an
               entry Point after the zlib or gzip header, and assures that the
               index always has at least one access Point; we avoid creating an
               access Point after the last block by checking bit 6 of data_type
               (for raw deflate, bit 6 is always set, so we need to handle it specially)
            */
            boolean isEndOfBlock = 0 != (stream.data_type & 128);
            boolean isNotStored = 0 == (stream.data_type & 64);
            if (isEndOfBlock && (isNotStored || windowBits < 0) &&
                (totalBytesOut == 0 || totalBytesOut - last > span)) {
                index.add(Point.newPoint( stream.data_type & 7, totalBytesIn, totalBytesOut, stream.avail_out, window));
                last = totalBytesOut;
            }
            /* For raw deflate without a header, totout may jump past 0 so we always
               add a starting checkpoint at out==0 if we haven't yet */
            if (windowBits < 0 && index.isEmpty() && totalBytesOut > 0 && totalBytesOut <= span) {
                index.add(Point.newPoint( 0, 0, 0L, WINDOW_SIZE, window));
                last = 0;
            }
        } while (stream.avail_in != 0);

        return result == Z_STREAM_END;
    }

    @Override
    public void close() {
        if (state != DeflateIndex.State.OPEN) {
            throw new IllegalStateException("Can only close an open stream");
        }
        Z.inflateEnd(stream);

        input.close();
        window.close();

        state = DeflateIndex.State.CLOSED;
    }

    public static IndexingInflaterInputStream forGZip(InputStream delegate) throws IOException {
        return new IndexingInflaterInputStream(delegate, DEFAULT_SPAN, 32 + WINDOW_BITS);
    }

    public static IndexingInflaterInputStream forDeflate(InputStream delegate) throws IOException {
        return new IndexingInflaterInputStream(delegate, DEFAULT_SPAN, -WINDOW_BITS);
    }

    static class Point implements Serializable {
        private final long out;
        private final long in;
        private final int bits;
        private final byte[] window = new byte[WINDOW_SIZE];

        private Point(long out, long in, int bits) {
            this.out = out;
            this.in = in;
            this.bits = bits;
        }

        private static Point newPoint(int bits, long in, long out, int left, Memory window) {
            Point p = new Point(out, in, bits);
            if (left != 0) {
                System.arraycopy(window.getByteArray(0, WINDOW_SIZE), WINDOW_SIZE - left, p.window, 0, left);
            }
            if (left < WINDOW_SIZE) {
                System.arraycopy(window.getByteArray(0, WINDOW_SIZE), 0, p.window, left, WINDOW_SIZE - left);
            }
            return p;
        }
    }
}