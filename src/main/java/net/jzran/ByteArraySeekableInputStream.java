package net.jzran;

import java.io.IOException;

public class ByteArraySeekableInputStream extends SeekableInputStream {
    private final byte[] buf;
    private final int offset;
    private final int len;

    private int pos;

    private boolean isClosed;

    public ByteArraySeekableInputStream(byte[] buf) {
        this(buf, 0, buf.length);
    }

    public ByteArraySeekableInputStream(byte[] buf, int offset, int len) {
        this.buf = buf;
        this.offset = offset;
        this.len = len;
        this.pos = offset;
    }

    @Override
    public int read() throws IOException {
        ensureOpen();
        if (pos == offset + len) {
            return -1;
        }
        int result = buf[pos] & 0xFF;
        pos++;
        return result;
    }

    @Override
    public int read(byte[] b) throws IOException {
        ensureOpen();
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int readLen) throws IOException {
        ensureOpen();
        if (readLen == 0) {
            return 0;
        }
        int numBytesToCopy = Math.min(readLen, offset + len - pos);
        if (numBytesToCopy == 0) {
            return -1;
        }
        System.arraycopy(buf, pos, b, off, numBytesToCopy);
        pos += numBytesToCopy;
        return numBytesToCopy;
    }

    @Override
    public long skip(long n) throws IOException {
        int origPos = pos;
        seek(pos + n);

        return pos - origPos;
    }

    @Override
    public int available() {
        ensureOpen();
        return offset + len - pos;
    }

    @Override
    public void close() {
        isClosed = true;
    }

    @Override
    public void seek(long position) throws IOException {
        ensureOpen();
        if (position < 0 || position > len) {
            throw new IllegalArgumentException("Position outside buffer: " + position + " (length " + len + ")");
        }
        // Can't be more than Integer.MAX_VALUE because offset + len is less.
        pos = (int) (offset + position);
    }

    @Override
    public long length() throws IOException {
        return len;
    }

    private void ensureOpen() {
        if (isClosed) {
            throw new IllegalStateException("Stream closed");
        }
    }
}
