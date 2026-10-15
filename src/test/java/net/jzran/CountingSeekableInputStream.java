package net.jzran;

import java.io.IOException;

/**
 * Test instrumentation: a {@link SeekableInputStream} wrapper that counts the bytes
 * read from, and the seeks done on, the wrapped stream.
 */
class CountingSeekableInputStream extends SeekableInputStream {
    private final SeekableInputStream delegate;
    private long bytesRead;
    private long seeks;

    CountingSeekableInputStream(SeekableInputStream delegate) {
        this.delegate = delegate;
    }

    long getBytesRead() {
        return bytesRead;
    }

    long getSeeks() {
        return seeks;
    }

    void resetCounters() {
        bytesRead = 0;
        seeks = 0;
    }

    @Override
    public int read() throws IOException {
        int b = delegate.read();
        if (b >= 0) {
            bytesRead++;
        }
        return b;
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = delegate.read(b, off, len);
        if (n > 0) {
            bytesRead += n;
        }
        return n;
    }

    @Override
    public void seek(long offset) throws IOException {
        seeks++;
        delegate.seek(offset);
    }

    @Override
    public long length() throws IOException {
        return delegate.length();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
