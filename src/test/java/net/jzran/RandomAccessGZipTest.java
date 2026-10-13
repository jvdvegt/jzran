package net.jzran;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;

public class RandomAccessGZipTest {
    @Test
    public void testGzipByteArray() throws Exception {
        TestPair testPair = new TestPair();
        testCorrectness(testPair.uncompressed, new ByteArraySeekableInputStream(testPair.compressed, 0, testPair.compressed.length));
    }

    @Test
    public void testGzipByteBuffer() throws Exception {
        TestPair testPair = new TestPair();
        testCorrectness(testPair.uncompressed, new ByteBufferSeekableInputStream(ByteBuffer.wrap(testPair.compressed)));
    }

    @Test
    public void testGzipFile() throws Exception {
        TestPair testPair = new TestPair();
        File tmp = File.createTempFile("zip-test", ".zip");
        tmp.deleteOnExit();
        try (OutputStream os = Files.newOutputStream(tmp.toPath())) {
            os.write(testPair.compressed);
        }
        testCorrectness(testPair.uncompressed, new FileSeekableInputStream(new RandomAccessFile(tmp, "r")));
    }

    private static void testCorrectness(byte[] expected, SeekableInputStream sis) throws Exception {
        DeflateIndex index = RandomAccessGZip.index(sis, 1_048_576);
        index.open(sis);

        TestUtil.testCorrectness(expected, index);

        ByteArrayOutputStream indexData = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(indexData)) {
            oos.writeObject(index);
        }
        byte[] indexBytes = indexData.toByteArray();

        index = (DeflateIndex) new ObjectInputStream(new ByteArrayInputStream(indexBytes)).readObject();
        index.open(sis);

        TestUtil.testCorrectness(expected, index);
    }

    static class TestPair {
        final byte[] uncompressed;
        final byte[] compressed;

        public TestPair() throws IOException {
            uncompressed = new byte[10_485_739];
            new Random().nextBytes(uncompressed);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DeflaterOutputStream outputStream = new GZIPOutputStream(baos)) {
                outputStream.write(uncompressed);
            }
            compressed = baos.toByteArray();
        }
    }
}