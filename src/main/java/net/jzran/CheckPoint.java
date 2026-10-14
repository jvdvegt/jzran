package net.jzran;

import java.io.Serializable;

import com.sun.jna.Memory;

/**
 * Represent a 'check point' for the deflate inflater. It can be used for random-access to a DEFLATE stream.
 */
public class CheckPoint implements Serializable {
    public static final int WINDOW_SIZE = IndexingInflaterInputStream.WINDOW_SIZE;
    private final long decompressedPosition;
    private final long compressedPosition;
    private final int bits;
    private final byte[] window = new byte[WINDOW_SIZE];

    private CheckPoint(long decompressedPosition, long compressedPosition, int bits) {
        this.decompressedPosition = decompressedPosition;
        this.compressedPosition = compressedPosition;
        this.bits = bits;
    }

    public long getDecompressedPosition() {
        return decompressedPosition;
    }

    public long getCompressedPosition() {
        return compressedPosition;
    }

    public int getBits() {
        return bits;
    }

    public byte[] getWindow() {
        return window;
    }

    static CheckPoint newPoint(long compressedPosition, long decompressedPosition, int bits, int left, Memory window) {
        CheckPoint p = new CheckPoint(decompressedPosition, compressedPosition, bits);
        if (left != 0) {
            System.arraycopy(window.getByteArray(0, WINDOW_SIZE), WINDOW_SIZE - left, p.window, 0, left);
        }
        if (left < WINDOW_SIZE) {
            System.arraycopy(window.getByteArray(0, WINDOW_SIZE), 0, p.window, left, WINDOW_SIZE - left);
        }
        return p;
    }
}
