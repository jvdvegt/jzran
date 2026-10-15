package net.jzran;

import java.io.EOFException;
import java.io.IOException;
import java.util.Objects;

/**
 * A bounded, seekable view of the bytes {@code [start, start + length)} of another
 * {@link SeekableInputStream}. Positions in this view start at 0 and are translated
 * to positions in the parent on every read.
 * <p>
 * Several slices may share one parent: each read re-positions the parent first.
 * Closing a slice does not close the parent. Not thread-safe.
 */
public class SeekableInputStreamSlice extends SeekableInputStream {
    private final SeekableInputStream parent;
    private final long start;
    private final long length;

    private long pos;
    private boolean closed;

    public SeekableInputStreamSlice(SeekableInputStream parent, long start, long length) throws IOException {
        this.parent = Objects.requireNonNull(parent, "parent");
        if (start < 0 || length < 0 || start > Long.MAX_VALUE - length) {
            throw new IllegalArgumentException("Invalid slice: start=" + start + ", length=" + length);
        }
        long parentLength = parent.length();
        if (start + length > parentLength) {
            throw new IllegalArgumentException("Slice [" + start + ", " + (start + length)
                + ") exceeds parent length " + parentLength);
        }
        this.start = start;
        this.length = length;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Stream closed");
        }
    }

    @Override
    public int read() throws IOException {
        byte[] b = new byte[1];
        int n = read(b, 0, 1);
        return n > 0 ? b[0] & 0xFF : -1;
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return 0;
        }
        long rem = length - pos;
        if (rem <= 0) {
            return -1;
        }
        parent.seek(start + pos);
        int n = parent.read(b, off, (int) Math.min(len, rem));
        if (n < 0) {
            throw new EOFException("Underlying stream ended at " + (start + pos)
                + ", before the end of the slice at " + (start + length));
        }
        pos += n;
        return n;
    }

    @Override
    public long skip(long n) {
        ensureOpen();
        if (n <= 0) {
            return 0;
        }
        long toSkip = Math.min(n, length - pos);
        pos += toSkip;
        return toSkip;
    }

    @Override
    public int available() {
        ensureOpen();
        return (int) Math.min(Integer.MAX_VALUE, length - pos);
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public void seek(long offset) {
        ensureOpen();
        if (offset < 0 || offset > length) {
            throw new IllegalArgumentException("Offset " + offset + " outside slice of length " + length);
        }
        pos = offset;
    }

    @Override
    public long length() {
        return length;
    }

    /** The start of this slice in the parent stream. */
    public long getStart() {
        return start;
    }
}
