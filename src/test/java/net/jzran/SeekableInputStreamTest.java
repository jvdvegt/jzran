package net.jzran;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

class SeekableInputStreamTest {
    private static final byte[] DATA = {(byte) 0xFF, 1, 2, 3, 4, 5, 6, 7, 8, (byte) 0x80};

    @Test
    void byteArrayStreamWithOffsetStartsAtItsOffset() throws Exception {
        ByteArraySeekableInputStream in = new ByteArraySeekableInputStream(DATA, 2, 5);
        assertEquals(2, in.read());
        in.seek(4);
        assertEquals(6, in.read());
        assertEquals(-1, in.read());
    }

    @Test
    void byteBufferReadReturnsUnsignedBytes() throws Exception {
        ByteBufferSeekableInputStream in = new ByteBufferSeekableInputStream(ByteBuffer.wrap(DATA));
        assertEquals(0xFF, in.read());
    }

    @Test
    void sliceTranslatesOffsetsAndStaysInBounds() throws Exception {
        SeekableInputStream parent = new ByteArraySeekableInputStream(DATA);
        SeekableInputStreamSlice slice = new SeekableInputStreamSlice(parent, 3, 4);
        assertEquals(4, slice.length());
        byte[] b = new byte[10];
        assertEquals(4, slice.readAt(0, b, 0, 10));
        assertArrayEquals(new byte[] {3, 4, 5, 6}, java.util.Arrays.copyOf(b, 4));
        assertEquals(-1, slice.read());
        slice.seek(1);
        assertEquals(4, slice.read());

        // a slice of a slice
        SeekableInputStreamSlice inner = new SeekableInputStreamSlice(slice, 2, 2);
        assertEquals(5, inner.read());
        assertEquals(6, inner.read());
        assertEquals(-1, inner.read());

        assertThrows(IllegalArgumentException.class, () -> new SeekableInputStreamSlice(parent, 8, 3));
        assertThrows(IllegalArgumentException.class, () -> new SeekableInputStreamSlice(parent, -1, 3));
        assertThrows(IllegalArgumentException.class, () -> slice.seek(5));
    }
}
