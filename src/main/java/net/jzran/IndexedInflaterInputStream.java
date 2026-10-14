package net.jzran;

import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.List;

import com.sun.jna.Memory;

/**
 * This class implements a stream filter for uncompressing data in the
 * "deflate" compression format. Unlike the {@link java.util.zip.InflaterInputStream}, this InputStream
 * uses an index to quickly seek to a random location in the compressed input stream.
 */
public class IndexedInflaterInputStream extends SeekableInputStream implements Serializable {
    private static final int BUF_SIZE = 1_048_576;

    private final List<CheckPoint> idx;
    private final long decompressedSize;

    private final Memory mem;
    private final ByteBuffer memBuf;
    private final SeekableInputStream delegate;
    private ZRan.Extractor extractor;
    private long pos;
    private boolean closed = false;

    IndexedInflaterInputStream(SeekableInputStream delegate, List<CheckPoint> idx, long decompressedSize) throws IOException {
        this.delegate = delegate;
        this.idx = List.copyOf(idx);
        this.decompressedSize = decompressedSize;
        this.mem = new Memory(BUF_SIZE);
        this.mem.clear();
        this.memBuf = mem.getByteBuffer(0, BUF_SIZE);

        this.pos = 0;
        this.extractor = new ZRan.Extractor(delegate, idx, 0);
    }

    @Override
    public void seek(long offset) throws IOException {
        ensureOpen();

        this.pos = offset;
        this.extractor = new ZRan.Extractor(delegate, idx, offset);
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
    public long skip(long n) throws IOException {
        ensureOpen();
        long toSkip = Math.min(n, available());
        seek(pos + toSkip);
        return toSkip;
    }

    @Override
    public int available() {
        ensureOpen();
        return (int) Math.min(Integer.MAX_VALUE, length() - pos);
    }

    @Override
    public void close() {
        extractor.close();
        closed = true;
    }

    @Override
    public int read(byte[] buf, int offset, int len) throws IOException {
        ensureOpen();
        int n = extractor.extract(mem, Math.min(len, BUF_SIZE));
        if (n < 0) {
            return -1;  // End of stream
        }
        memBuf.position(0);
        memBuf.get(buf, offset, n);
        pos += n;
        return n;
    }

    @Override
    public long length() {
        return decompressedSize;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Stream must be open");
        }
    }
}