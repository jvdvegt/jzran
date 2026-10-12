package net.jzran;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

final class TestUtil {
    private TestUtil() {

    }

    static void testCorrectness(byte[] expected, DeflateIndex index) throws IOException {
        assertEquals(expected.length, index.length());

        Random r = new Random(56738138L);
        for(int i = 0; i < 100; ++i) {
            int origin = r.nextInt(expected.length);

            index.seek(origin);

            byte[] dest = new byte[Math.min(135763, expected.length - origin)];

            int rem = dest.length;
            int offset = 0;
            while(rem > 0) {
                int n = index.read(dest, offset, rem);
                rem -= n;
                offset += n;
            }

            for(int j = 0; j < dest.length; ++j) {
                assertEquals(dest[j], expected[origin+j]);
            }
        }
    }

    static class TestPair {
        final byte[] uncompressed;
        final byte[] zipped;

        public TestPair() throws IOException {
            uncompressed = new byte[10_485_739];
            new Random().nextBytes(uncompressed);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream zos = new GZIPOutputStream(baos)) {
                zos.write(uncompressed);
            }
            zipped = baos.toByteArray();
        }
    }
}
