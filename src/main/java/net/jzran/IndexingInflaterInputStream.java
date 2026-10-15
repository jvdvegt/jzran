package net.jzran;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.sun.jna.Memory;

/**
 * This class implements a stream filter for uncompressing data in the
 * "deflate" compression format.
 * While reading the data, an index is built to enable fast accessing the deflated input stream.
 * Once the stream has been read to the end, the index is complete and
 * {@link #seek(long)} can be used to reposition the stream to any offset in
 * the decompressed data.
 *
 * All reading is delegated to an {@link Extractor}: initially one that builds
 * the index while decompressing, and after a {@link #seek(long)} one that
 * resumes from the nearest checkpoint.
 *
 * The delegate must be positioned at the start of the compressed data.
 */
public class IndexingInflaterInputStream extends SeekableInputStream {
    private static final int WINDOW_BITS = 15;
    static final int WINDOW_SIZE = 1 << WINDOW_BITS;
    public static final int DEFAULT_SPAN = 1_048_576;
    private static final int BUF_SIZE = 1_048_576;

    private final SeekableInputStream delegate;

    /* access points being generated (by the capturing extractor) */
    private final List<CheckPoint> index = new ArrayList<>();

    /* Buffer used to read data via the extractor */
    private final Memory mem = new Memory(BUF_SIZE);
    private final ByteBuffer memBuf = mem.getByteBuffer(0, BUF_SIZE);

    private Extractor extractor;
    private long pos;
    private long size = -1;
    private boolean closed = false;

    protected IndexingInflaterInputStream(SeekableInputStream delegate, long span, int windowBits) throws IOException {
        this.delegate = delegate;
        // The index is empty, so this extractor captures checkpoints while
        // decompressing, reading the delegate from the start.
        this.extractor = new Extractor(delegate, index, 0, span, windowBits);
    }

    public List<CheckPoint> getIndex() {
        return index;
    }

    @Override
    public void seek(long position) throws IOException {
        if (closed) {
            throw new IllegalStateException("Cannot seek on a closed stream");
        }
        long size = length();
        if (size < 0) {
            throw new IllegalStateException("Cannot seek until the index is complete; read the stream to the end first");
        }
        if (position < 0 || position > size) {
            throw new IllegalArgumentException("Offset out of range: " + position + " (size " + size + ")");
        }
        extractor.close();
        extractor = new Extractor(delegate, index, position);
        pos = position;
    }

    @Override
    public long length() {
        if (size < 0 && extractor.isFinished()) {
            size = extractor.totalBytesOut();
        }
        return size;
    }

    @Override
    public int read() throws IOException {
        byte[] b = new byte[1];
        int n = read(b);
        return n > 0 ? b[0] & 0xFF : n;
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] buf, int offset, int len) throws IOException {
        if (closed) {
            throw new IllegalStateException("Attempting to read from a closed stream");
        }
        if (len <= 0) {
            return 0;
        }
        int n = extractor.extract(mem, Math.min(len, BUF_SIZE));
        if (n < 0) {
            return -1;
        }
        memBuf.position(0);
        memBuf.get(buf, offset, n);
        pos += n;
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        if (closed) {
            throw new IllegalStateException("Cannot skip on a closed stream");
        }
        if (n <= 0) {
            return 0;
        }
        if (length() >= 0) {
            long toSkip = Math.min(n, size - pos);
            if (toSkip > 0) {
                seek(pos + toSkip);
            }
            return toSkip;
        }
        return super.skip(n);
    }

    @Override
    public int available() {
        if (closed || size < 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, size - pos);
    }

    @Override
    public void close() {
        if (closed) {
            throw new IllegalStateException("Can only close an open stream");
        }
        extractor.close();
        mem.close();
        closed = true;
    }

    public static IndexingInflaterInputStream forGZip(SeekableInputStream delegate) throws IOException {
        return new IndexingInflaterInputStream(delegate, DEFAULT_SPAN, 32 + WINDOW_BITS);
    }

    public static IndexingInflaterInputStream forDeflate(SeekableInputStream delegate) throws IOException {
        return new IndexingInflaterInputStream(delegate, DEFAULT_SPAN, -WINDOW_BITS);
    }
}
