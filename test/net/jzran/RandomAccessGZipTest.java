package net.jzran;

import org.junit.Test;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static junit.framework.Assert.assertEquals;

public class RandomAccessGZipTest {
    @Test
    public void testGzipByteArray() throws Exception {
        TestPair testPair = new TestPair();
        testCorrectness(testPair.uncompressed, new ByteArraySeekableInputStream(testPair.zipped, 0, testPair.zipped.length));
    }

    @Test
    public void testGzipByteBuffer() throws Exception {
        TestPair testPair = new TestPair();
        testCorrectness(testPair.uncompressed, new ByteBufferSeekableInputStream(ByteBuffer.wrap(testPair.zipped)));
    }

    @Test
    public void testGzipFile() throws Exception {
        TestPair testPair = new TestPair();
        File tmp = File.createTempFile("zip-test", ".zip");
        tmp.deleteOnExit();
        try (OutputStream os = new FileOutputStream(tmp)) {
            os.write(testPair.zipped);
        }
        testCorrectness(testPair.uncompressed, new FileSeekableInputStream(new RandomAccessFile(tmp, "r")));
    }

    @Test
    public void testDeflateStream() throws Exception {
        byte[] uncompressed = new byte[10_485_739];
        new java.util.Random().nextBytes(uncompressed);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try (DeflaterOutputStream output = new DeflaterOutputStream(baos, deflater)) {
            output.write(uncompressed);
        }
        finally {
            deflater.end();
        }
        byte[] deflated = baos.toByteArray();

        SeekableInputStream sis = new ByteArraySeekableInputStream(deflated);
        DeflateIndex index = RandomAccessDeflate.index(sis, 1_048_576);

        ByteArrayOutputStream indexData = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(indexData)) {
            oos.writeObject(index);
        }
        byte[] indexBytes = indexData.toByteArray();

        DeflateIndex deflateIndex = (DeflateIndex) new ObjectInputStream(
            new ByteArrayInputStream(indexBytes)).readObject();
        SeekableInputStream seekableInputStream = new ByteArraySeekableInputStream(deflated);
        deflateIndex.open(seekableInputStream);

        // Test random access with several offsets
        int[] offsets = new int[]{0, 100, 500000, 2000000, 8000000, 10485600};
        for (int offset : offsets) {
            deflateIndex.seek( offset);
            int toRead = Math.min(10000, uncompressed.length - offset);

            byte[] buf = new byte[toRead];
            int off = 0;
            while (off < toRead) {
                int n = deflateIndex.read(buf, off, toRead - off);
                if (n <= 0) break;
                off += n;
            }
            for (int i = 0; i < off; i++) {
                assertEquals((byte) uncompressed[offset + i], buf[i]);
            }
        }
    }

    private void testCorrectness(byte[] buf, SeekableInputStream sis) throws Exception {
        DeflateIndex index = RandomAccessGZip.index(sis, 1048576);

        ByteArrayOutputStream indexData = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(indexData);
        oos.writeObject(index);
        oos.close();
        indexData.close();
        byte[] indexBytes = indexData.toByteArray();

        index = (DeflateIndex) new ObjectInputStream(new ByteArrayInputStream(indexBytes)).readObject();
        index.open(sis);

        assertEquals(buf.length, index.length());

        Random r = new Random(56738138L);
        for(int i = 0; i < 100; ++i) {
            int origin = r.nextInt(buf.length);

            index.seek(origin);

            byte[] dest = new byte[Math.min(135763, buf.length - origin)];

            int rem = dest.length;
            int offset = 0;
            while(rem > 0) {
                int n = index.read(dest, offset, rem);
                rem -= n;
                offset += n;
            }

            for(int j = 0; j < dest.length; ++j) {
                assertEquals(dest[j], buf[origin+j]);
            }
        }
    }

    private static class TestPair {
        private final byte[] uncompressed;
        private final byte[] zipped;

        private TestPair() throws IOException {
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