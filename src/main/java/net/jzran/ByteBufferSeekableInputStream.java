package net.jzran;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created on: 05.11.10 22:51
 */
public class ByteBufferSeekableInputStream extends SeekableInputStream {
    private final ByteBuffer buf;
    private boolean isClosed;

    public ByteBufferSeekableInputStream(ByteBuffer buf) {
        this.buf = buf;
    }

    @Override
    public int read() throws IOException {
        ensureOpen();
        return buf.remaining() > 0 ? (buf.get() & 0xFF) : -1;
    }

    @Override
    public int read(byte[] b) throws IOException {
        ensureOpen();
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        int rem = Math.min(len, buf.remaining());
        buf.get(b, off, rem);
        return (rem == 0) ? -1 : rem;
    }

    @Override
    public long skip(long n) throws IOException {
        long origPos = buf.position();
        seek(origPos + n);

        return buf.position() - origPos;
    }

    @Override
    public int available() {
        ensureOpen();
        return buf.limit() - buf.position();
    }

    @Override
    public void close() {
        isClosed = true;
    }

    @Override
    public void seek(long position) throws IOException {
        ensureOpen();
        if (position < 0 || position > length()) {
            throw new IllegalArgumentException("Position outside buffer: " + position + " (length " + length() + ")");
        }
        buf.position((int)position);
    }

    @Override
    public long length() throws IOException {
        return buf.limit();
    }

    private void ensureOpen() {
        if (isClosed) {
            throw new IllegalStateException("Stream closed");
        }
    }
}
