package net.jzran;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import org.junit.jupiter.api.Test;

class IndexingInflaterInputStreamTest {

    @Test
    void testDeflate() throws Exception {
        var testPair = new RandomAccessDeflateTest.TestPair();

        long[] decompressedSize = new long[1];
        List<CheckPoint> expected = ZRan.buildDeflateIndex(
            new ByteArrayInputStream(testPair.compressed), 1_048_576, decompressedSize, p -> true);
        assertEquals(testPair.uncompressed.length, decompressedSize[0]);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ByteArrayInputStream bais = new ByteArrayInputStream(testPair.compressed);
        IndexingInflaterInputStream input = IndexingInflaterInputStream.forDeflate(bais);
        try (input) {
            input.transferTo(baos);
        }
        assertArrayEquals(testPair.uncompressed, baos.toByteArray());

        List<CheckPoint> actual = input.getIndex();
        assertTrue(actual.size() > 1);
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            CheckPoint exp = expected.get(i);
            CheckPoint act = actual.get(i);
            assertEquals(exp.getDecompressedPosition(), act.getDecompressedPosition(), "point " + i + " DecompressedPosition");
            assertEquals(exp.getCompressedPosition(), act.getCompressedPosition(), "point " + i + " CompressedPosition");
            assertEquals(exp.getBits(), act.getBits(), "point " + i + " bits");
            assertArrayEquals(exp.getWindow(), act.getWindow(), "point " + i + " window");
        }
    }
}
