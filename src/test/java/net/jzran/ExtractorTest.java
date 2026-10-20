package net.jzran;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static net.jzran.Extractor.toIndexMap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

import com.sun.jna.Memory;

import org.junit.jupiter.api.Test;

class ExtractorTest {
    private static final long SPAN = 1_048_576;

    @Test
    void testResumeAtOffsetsDeflate() throws Exception {
        RandomAccessDeflateTest.TestPair pair = new RandomAccessDeflateTest.TestPair();
        int len = pair.uncompressed.length;
        long[] size = new long[1];
        List<CheckPoint> index = ZRan.buildDeflateIndex(new ByteArrayInputStream(pair.compressed), SPAN, size, p -> true);
        assertEquals(len, size[0]);

        long[] offsets = {0, 1, len / 4, len / 2, 3 * len / 4, len - 1, len};
        for (long offset : offsets) {
            try (Extractor extractor = new Extractor(new ByteArraySeekableInputStream(pair.compressed), index, offset)) {
                byte[] tail = readAll(extractor);
                byte[] expected = Arrays.copyOfRange(pair.uncompressed, (int) offset, len);
                assertArrayEquals(expected, tail, "offset " + offset);
                assertTrue(extractor.isFinished(), "offset " + offset + " should be finished");
                assertEquals(len, extractor.totalBytesOut(), "offset " + offset + " totalBytesOut");
            }
        }
    }

    @Test
    void testResumeAtOffsetGZip() throws Exception {
        RandomAccessGZipTest.TestPair pair = new RandomAccessGZipTest.TestPair();
        int len = pair.uncompressed.length;
        long[] size = new long[1];
        List<CheckPoint> index = ZRan.buildGZipIndex(new ByteArrayInputStream(pair.compressed), SPAN, size, p -> true);
        assertEquals(len, size[0]);

        long offset = len / 3;
        try (Extractor extractor = new Extractor(new ByteArraySeekableInputStream(pair.compressed), index, offset)) {
            byte[] tail = readAll(extractor);
            byte[] expected = Arrays.copyOfRange(pair.uncompressed, (int) offset, len);
            assertArrayEquals(expected, tail);
            assertTrue(extractor.isFinished());
            assertEquals(len, extractor.totalBytesOut());
        }
    }

    @Test
    void testResumeCapturingDeflate() throws Exception {
        RandomAccessDeflateTest.TestPair pair = new RandomAccessDeflateTest.TestPair();
        int len = pair.uncompressed.length;
        long[] size = new long[1];
        List<CheckPoint> index = ZRan.buildDeflateIndex(new ByteArrayInputStream(pair.compressed), SPAN, size, p -> true);
        assertEquals(len, size[0]);

        long offset = len / 2;
        try (Extractor extractor = new Extractor(new ByteArraySeekableInputStream(pair.compressed), toIndexMap(index), offset, SPAN, -15, true)) {
            assertArrayEquals(Arrays.copyOfRange(pair.uncompressed, (int) offset, len), readAll(extractor));
            assertTrue(extractor.isFinished());
            assertEquals(len, extractor.totalBytesOut());

            // With the same span, the resumed extractor continues the
            // existing checkpoint series: no new positions are introduced.
            assertSameIndex(index, extractor.getCheckpoints());
        }
    }

    @Test
    void testResumeCapturingGZip() throws Exception {
        RandomAccessGZipTest.TestPair pair = new RandomAccessGZipTest.TestPair();
        int len = pair.uncompressed.length;
        long[] size = new long[1];
        List<CheckPoint> index = ZRan.buildGZipIndex(new ByteArrayInputStream(pair.compressed), SPAN, size, p -> true);
        assertEquals(len, size[0]);

        long offset = len / 3;
        try (Extractor extractor = new Extractor(new ByteArraySeekableInputStream(pair.compressed), toIndexMap(index), offset, SPAN, 32 + 15, true)) {
            assertArrayEquals(Arrays.copyOfRange(pair.uncompressed, (int) offset, len), readAll(extractor));
            assertTrue(extractor.isFinished());
            assertEquals(len, extractor.totalBytesOut());

            assertSameIndex(index, extractor.getCheckpoints());
        }
    }

    @Test
    void testResumeCapturingRegeneratesMissingCheckpoints() throws Exception {
        RandomAccessDeflateTest.TestPair pair = new RandomAccessDeflateTest.TestPair();
        int len = pair.uncompressed.length;
        long[] size = new long[1];
        List<CheckPoint> index = ZRan.buildDeflateIndex(new ByteArrayInputStream(pair.compressed), SPAN, size, p -> true);
        assertEquals(len, size[0]);
        assertTrue(index.size() > 4, "expected several checkpoints");

        // Drop every other checkpoint after the resume point, as if they
        // had been lost from the index.
        int resumeIdx = 1;
        long offset = index.get(resumeIdx).getDecompressedPosition();
        List<CheckPoint> pruned = new ArrayList<>(index);
        for (int i = index.size() - 1; i > resumeIdx; i -= 2) {
            pruned.remove(i);
        }
        assertTrue(pruned.size() < index.size(), "expected to have dropped checkpoints");

        try (Extractor extractor = new Extractor(new ByteArraySeekableInputStream(pair.compressed), toIndexMap(pruned), offset, SPAN, -15, true)) {
            assertArrayEquals(Arrays.copyOfRange(pair.uncompressed, (int) offset, len), readAll(extractor));
            assertTrue(extractor.isFinished());
            assertEquals(len, extractor.totalBytesOut());

            // The missing checkpoints are regenerated at their original
            // positions as the stream is read.
            assertSameIndex(index, extractor.getCheckpoints());
        }
    }

    @Test
    void testResumeCapturingFinerSpanGrowsIndex() throws Exception {
        RandomAccessDeflateTest.TestPair pair = new RandomAccessDeflateTest.TestPair();
        int len = pair.uncompressed.length;
        long[] size = new long[1];
        List<CheckPoint> index = ZRan.buildDeflateIndex(new ByteArrayInputStream(pair.compressed), SPAN, size, p -> true);
        assertEquals(len, size[0]);

        long offset = len / 4;
        try (Extractor extractor = new Extractor(new ByteArraySeekableInputStream(pair.compressed), toIndexMap(index), offset, 65536, -15, true)) {
            assertArrayEquals(Arrays.copyOfRange(pair.uncompressed, (int) offset, len), readAll(extractor));
            assertTrue(extractor.isFinished());
            assertEquals(len, extractor.totalBytesOut());

            List<CheckPoint> grown = extractor.getCheckpoints();
            assertTrue(grown.size() > index.size(), "expected the index to grow: " + grown.size() + " vs " + index.size());
            assertIndexContains(index, grown);
        }
    }

    @Test
    void testCapturingBuildsIndexMatchingZranDeflate() throws Exception {
        RandomAccessDeflateTest.TestPair pair = new RandomAccessDeflateTest.TestPair();
        int len = pair.uncompressed.length;

        long[] refSize = new long[1];
        List<CheckPoint> reference = ZRan.buildDeflateIndex(new ByteArrayInputStream(pair.compressed), SPAN, refSize, p -> true);
        assertEquals(len, refSize[0]);

        List<CheckPoint> built;
        try (Extractor extractor = new Extractor(new ByteArraySeekableInputStream(pair.compressed), new TreeMap<>(), 0, SPAN, -15)) {
            assertFalse(extractor.isFinished(), "fresh capturing extractor should not be finished");

            try (Memory mem = new Memory(16)) {
                assertEquals(0, extractor.extract(mem, 0));
                assertEquals(0, extractor.extract(mem, -3));
            }

            assertArrayEquals(pair.uncompressed, readAll(extractor));
            assertTrue(extractor.isFinished());
            assertEquals(len, extractor.totalBytesOut());

             try (Memory mem = new Memory(16)) {
                assertEquals(-1, extractor.extract(mem, 16), "extract after end should return -1");
            }

            built = extractor.getCheckpoints();
        }
        assertTrue(built.size() > 1, "expected multiple checkpoints");
        assertSameIndex(reference, built);
    }

    @Test
    void testCapturingBuildsIndexMatchingZranGZip() throws Exception {
        RandomAccessGZipTest.TestPair pair = new RandomAccessGZipTest.TestPair();
        int len = pair.uncompressed.length;

        long[] refSize = new long[1];
        List<CheckPoint> reference = ZRan.buildGZipIndex(new ByteArrayInputStream(pair.compressed), SPAN, refSize, p -> true);
        assertEquals(len, refSize[0]);

        List<CheckPoint> built;
        try (Extractor extractor = new Extractor(new ByteArraySeekableInputStream(pair.compressed), new TreeMap<>(), 0, SPAN, 32 + 15)) {
            assertArrayEquals(pair.uncompressed, readAll(extractor));
            assertTrue(extractor.isFinished());
            assertEquals(len, extractor.totalBytesOut());
            built = extractor.getCheckpoints();
        }
        assertTrue(built.size() > 1, "expected multiple checkpoints");
        assertSameIndex(reference, built);
    }

    @Test
    void testCapturingSpanAffectsCheckpointDensity() throws Exception {
        RandomAccessDeflateTest.TestPair pair = new RandomAccessDeflateTest.TestPair();

        List<CheckPoint> fine;
        try (Extractor extractor = new Extractor(new ByteArraySeekableInputStream(pair.compressed), new TreeMap<>(), 0, 65536, -15)) {
            readAll(extractor);
            fine = extractor.getCheckpoints();
        }

        List<CheckPoint> coarse;
        try (Extractor extractor = new Extractor(new ByteArraySeekableInputStream(pair.compressed), new TreeMap<>(), 0, 2 * 65536, -15)) {
            readAll(extractor);
            coarse = extractor.getCheckpoints();
        }

        assertTrue(fine.size() > coarse.size(),
            "smaller span (" + fine.size() + ") should yield more checkpoints than larger span (" + coarse.size() + ")");

        assertPointsEqual(fine.get(2), coarse.get(1));
        assertPointsEqual(fine.get(4), coarse.get(2));
    }

    @Test
    void testPartialReadState() throws Exception {
        RandomAccessDeflateTest.TestPair pair = new RandomAccessDeflateTest.TestPair();
        int len = pair.uncompressed.length;
        int target = len / 5;

        int bufferSize = 65536;
        try (Extractor extractor = new Extractor(new ByteArraySeekableInputStream(pair.compressed), new TreeMap<>(), 0, SPAN, -15);
             Memory mem = new Memory(bufferSize)) {
                int delivered = 0;
                while (delivered < target) {
                    int n = extractor.extract(mem, Math.min(bufferSize, target - delivered));
                    if (n < 0) {
                        break;
                    }
                    delivered += n;
                }

                assertEquals(target, delivered);
                assertFalse(extractor.isFinished(), "should not be finished before the end");
                assertTrue(extractor.totalBytesOut() >= target, "totalBytesOut (" + extractor.totalBytesOut() + ") should be >= delivered (" + target + ")");
                assertTrue(extractor.totalBytesOut() < len, "totalBytesOut (" + extractor.totalBytesOut() + ") should be < length (" + len + ") while unfinished");
        }
    }

    @Test
    void testCapturingWithNonZeroOffsetIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new Extractor(new ByteArraySeekableInputStream(new byte[0]), new TreeMap<>(), 42, SPAN, -15));
    }

    @Test
    void testResumeWithEmptyIndexIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new Extractor(new ByteArraySeekableInputStream(new byte[0]), new ArrayList<>(), 0));
    }

    @Test
    void testResumeBeyondEndThrows() throws Exception {
        RandomAccessDeflateTest.TestPair pair = new RandomAccessDeflateTest.TestPair();
        long[] size = new long[1];
        List<CheckPoint> index = ZRan.buildDeflateIndex(new ByteArrayInputStream(pair.compressed), SPAN, size, p -> true);
        int len = pair.uncompressed.length;

        assertThrows(IOException.class,
            () -> new Extractor(new ByteArraySeekableInputStream(pair.compressed), index, len + 1));
    }

    private static byte[] readAll(Extractor extractor) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int bufferSize = 65536;
        try (Memory mem = new Memory(bufferSize)) {
            int n;
            while ((n = extractor.extract(mem, bufferSize)) > 0) {
                byte[] chunk = mem.getByteArray(0, n);
                baos.write(chunk, 0, n);
            }
        }
        return baos.toByteArray();
    }

    private static void assertSameIndex(List<CheckPoint> expected, List<CheckPoint> actual) {
        assertEquals(expected.size(), actual.size(), "index size");
        for (int i = 0; i < expected.size(); i++) {
            assertPointsEqual(expected.get(i), actual.get(i));
        }
    }

    private static void assertIndexContains(List<CheckPoint> expected, List<CheckPoint> actual) {
        long prev = -1;
        int matched = 0;
        for (CheckPoint p : actual) {
            long pos = p.getDecompressedPosition();
            assertTrue(pos > prev, "checkpoints must be strictly increasing by decompressed position");
            prev = pos;
            if (matched < expected.size() && pos == expected.get(matched).getDecompressedPosition()) {
                matched++;
            }
        }
        assertEquals(expected.size(), matched, "all original checkpoints must be present");
    }

    private static void assertPointsEqual(CheckPoint expected, CheckPoint actual) {
        assertEquals(expected.getDecompressedPosition(), actual.getDecompressedPosition(), "point decompressed position");
        assertEquals(expected.getCompressedPosition(), actual.getCompressedPosition(), "point compressed position");
        assertEquals(expected.getBits(), actual.getBits(), "point bits");
        assertArrayEquals(expected.getWindow(), actual.getWindow(), "point window");
    }
}
