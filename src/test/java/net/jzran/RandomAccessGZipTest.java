package net.jzran;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import net.jzran.TestUtil.TestPair;

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
        try (OutputStream os = Files.newOutputStream(tmp.toPath())) {
            os.write(testPair.zipped);
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
}