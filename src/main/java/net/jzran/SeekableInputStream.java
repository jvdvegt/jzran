package net.jzran;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public abstract class SeekableInputStream extends InputStream {
    public abstract void seek(long offset) throws IOException;

    public abstract long length() throws IOException;

    /**
     * Positional read: seek to {@code position} and read up to {@code len} bytes,
     * looping until {@code len} bytes have been read or the end of the stream is reached.
     * The stream is left positioned after the last byte read.
     *
     * @return the number of bytes read, 0 if {@code len} is 0, or -1 if {@code position}
     *   is at or beyond the end of the stream.
     * @throws IllegalArgumentException if {@code position} is negative.
     */
    public int readAt(long position, byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (position < 0) {
            throw new IllegalArgumentException("Negative position: " + position);
        }
        if (len == 0) {
            return 0;
        }
        if (position >= length()) {
            return -1;
        }
        seek(position);
        int total = 0;
        while (total < len) {
            int n = read(b, off + total, len - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total == 0 ? -1 : total;
    }

    /**
     * Positional read of exactly {@code len} bytes.
     *
     * @throws EOFException if the stream ends before {@code len} bytes could be read.
     * @throws IllegalArgumentException if {@code position} is negative.
     */
    public void readFullyAt(long position, byte[] b, int off, int len) throws IOException {
        int n = readAt(position, b, off, len);
        if (n < len) {
            throw new EOFException("Requested " + len + " bytes at position " + position
                + " but only " + Math.max(n, 0) + " are available (length " + length() + ")");
        }
    }
}
