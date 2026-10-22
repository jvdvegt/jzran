package net.jzran;

import static net.jzran.TestUtil.testCorrectness;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
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
        IndexingInflaterInputStream input = IndexingInflaterInputStream.forDeflate(
            new ByteArraySeekableInputStream(testPair.compressed));
        try (input) {
            input.transferTo(baos);
        }
        assertArrayEquals(testPair.uncompressed, baos.toByteArray());

        List<CheckPoint> actual = input.getIndex();
        assertTrue(actual.size() > 1);
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            // the position-0 window is synthesized (all zeros) rather than
            // the reference's first 32K, so ignore just that window
            assertSameCheckpoint(expected.get(i), actual.get(i), i, expected.get(i).getDecompressedPosition() == 0);
        }
    }

    @Test
    void testDeflateIndexMatchesZranWithIrregularReads() throws Exception {
        var testPair = new RandomAccessDeflateTest.TestPair();

        long[] decompressedSize = new long[1];
        List<CheckPoint> expected = ZRan.buildDeflateIndex(
            new ByteArrayInputStream(testPair.compressed), 1_048_576, decompressedSize, p -> true);

        try (IndexingInflaterInputStream input = IndexingInflaterInputStream.forDeflate(
                new ByteArraySeekableInputStream(testPair.compressed))) {
            // Read the whole stream to the end using a repeating pattern of
            // irregular chunk sizes (including single-byte reads) so the
            // sliding window is drained at many non-aligned positions while
            // the capturing extractor builds the index.
            assertArrayEquals(testPair.uncompressed, readAllIrregular(input));
            assertEquals(testPair.uncompressed.length, input.length());

            // The index built this way must be identical to the reference one
            // produced by ZRan, regardless of how the data was read out.
            List<CheckPoint> actual = input.getIndex();
            assertEquals(expected.size(), actual.size());
            for (int i = 0; i < expected.size(); i++) {
                // the position-0 window is synthesized (all zeros) rather
                // than the reference's first 32K, so ignore just that window
                assertSameCheckpoint(expected.get(i), actual.get(i), i, expected.get(i).getDecompressedPosition() == 0);
            }

            // And random access must still work after the index was built.
            testCorrectness(testPair.uncompressed, input);
        }
    }

    @Test
    void testDeflateSeek() throws Exception {
        var testPair = new RandomAccessDeflateTest.TestPair();

        try (IndexingInflaterInputStream input = IndexingInflaterInputStream.forDeflate(
                new ByteArraySeekableInputStream(testPair.compressed))) {
            assertEquals(-1, input.length());

            assertArrayEquals(testPair.uncompressed, input.readAllBytes());

            assertEquals(testPair.uncompressed.length, input.length());
            assertEquals(-1, input.read());

            testCorrectness(testPair.uncompressed, input);

            input.seek(testPair.uncompressed.length - 1);
            assertEquals(testPair.uncompressed[testPair.uncompressed.length - 1] & 0xFF, input.read());
            assertEquals(-1, input.read());

            input.seek(testPair.uncompressed.length);
            assertEquals(-1, input.read());

            assertThrows(IllegalArgumentException.class, () -> input.seek(-1));
            assertThrows(IllegalArgumentException.class, () -> input.seek(input.length() + 1));
        }
    }

    @Test
    void testSeekContinuesCapturingDeflate() throws Exception {
        var testPair = new RandomAccessDeflateTest.TestPair();

        long[] decompressedSize = new long[1];
        List<CheckPoint> expected = ZRan.buildDeflateIndex(
            new ByteArrayInputStream(testPair.compressed), 1_048_576, decompressedSize, p -> true);

        try (IndexingInflaterInputStream input = IndexingInflaterInputStream.forDeflate(
                new ByteArraySeekableInputStream(testPair.compressed))) {
            assertArrayEquals(testPair.uncompressed, input.readAllBytes());
            assertEquals(testPair.uncompressed.length, input.length());

            List<CheckPoint> built = input.getIndex();
            assertEquals(expected.size(), built.size());

            // After a seek the extractor keeps capturing: with the same span
            // it continues the existing checkpoint series, so the index keeps
            // the same checkpoints.
            int offset = testPair.uncompressed.length / 2;
            input.seek(offset);
            assertArrayEquals(Arrays.copyOfRange(testPair.uncompressed, offset, testPair.uncompressed.length),
                input.readAllBytes());

            List<CheckPoint> after = input.getIndex();
            assertEquals(built.size(), after.size());
            for (int i = 0; i < built.size(); i++) {
                assertSameCheckpoint(built.get(i), after.get(i), i);
            }

            // And random access must still work after the seeking.
            testCorrectness(testPair.uncompressed, input);
        }
    }

    @Test
    void testGZipSeek() throws Exception {
        var testPair = new RandomAccessGZipTest.TestPair();

        try (IndexingInflaterInputStream input = IndexingInflaterInputStream.forGZip(
                new ByteArraySeekableInputStream(testPair.compressed))) {
            assertEquals(-1, input.length());

            assertArrayEquals(testPair.uncompressed, input.readAllBytes());

            assertEquals(testPair.uncompressed.length, input.length());

            testCorrectness(testPair.uncompressed, input);
        }
    }

    @Test
    void testSeekBeforeIndexComplete() throws Exception {
        var testPair = new RandomAccessDeflateTest.TestPair();

        try (IndexingInflaterInputStream input = IndexingInflaterInputStream.forDeflate(
                new ByteArraySeekableInputStream(testPair.compressed))) {
            byte[] buf = new byte[1_000_000];
            assertTrue(input.read(buf) > 0);
            assertEquals(-1, input.length());
            assertThrows(IllegalStateException.class, () -> input.seek(0));
        }
    }

    private static void assertSameCheckpoint(CheckPoint exp, CheckPoint act, int i) {
        assertSameCheckpoint(exp, act, i, false);
    }

    private static void assertSameCheckpoint(CheckPoint exp, CheckPoint act, int i, boolean ignoreWindow) {
        assertEquals(exp.getDecompressedPosition(), act.getDecompressedPosition(), "point " + i + " DecompressedPosition");
        assertEquals(exp.getCompressedPosition(), act.getCompressedPosition(), "point " + i + " CompressedPosition");
        assertEquals(exp.getBits(), act.getBits(), "point " + i + " bits");
        if (!ignoreWindow) {
            assertArrayEquals(exp.getWindow(), act.getWindow(), "point " + i + " window");
        }
    }

    private static byte[] readAllIrregular(IndexingInflaterInputStream input) throws IOException {
        int[] sizes = {1, 3, 17, 3000, 40000};
        byte[] buf = new byte[65536];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int i = 0;
        while (true) {
            int n = input.read(buf, 0, sizes[i % sizes.length]);
            if (n < 0) {
                break;
            }
            baos.write(buf, 0, n);
            i++;
        }
        return baos.toByteArray();
    }

    @Test
    void testClosedStream() throws Exception {
        var testPair = new RandomAccessDeflateTest.TestPair();

        IndexingInflaterInputStream input = IndexingInflaterInputStream.forDeflate(
            new ByteArraySeekableInputStream(testPair.compressed));
        input.close();
        assertThrows(IllegalStateException.class, input::read);
        assertThrows(IllegalStateException.class, () -> input.seek(0));
        assertThrows(IllegalStateException.class, input::close);
    }
}
