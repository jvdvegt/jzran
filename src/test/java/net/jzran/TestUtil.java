package net.jzran;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.Random;

final class TestUtil {
    private TestUtil() {

    }

    static void testCorrectness(byte[] expected, SeekableInputStream stream) throws IOException {
        assertEquals(expected.length, stream.length());

        Random r = new Random(56738138L);
        for(int i = 0; i < 100; ++i) {
            int origin = r.nextInt(expected.length);

            stream.seek(origin);

            byte[] dest = new byte[Math.min(135763, expected.length - origin)];

            int rem = dest.length;
            int offset = 0;
            while(rem > 0) {
                int n = stream.read(dest, offset, rem);
                rem -= n;
                offset += n;
            }

            for(int j = 0; j < dest.length; ++j) {
                assertEquals(dest[j], expected[origin+j]);
            }
        }
    }

}
