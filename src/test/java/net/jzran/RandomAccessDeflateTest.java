package net.jzran;

import static net.jzran.TestUtil.testCorrectness;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import org.junit.jupiter.api.Test;

public class RandomAccessDeflateTest {

    @Test
    public void testDeflateStream() throws Exception {
        TestPair testPair = new TestPair();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try (DeflaterOutputStream output = new DeflaterOutputStream(baos, deflater)) {
            output.write(testPair.uncompressed);
        }
        finally {
            deflater.end();
        }
        byte[] deflated = baos.toByteArray();
        SeekableInputStream sis = new ByteArraySeekableInputStream(deflated);
        DeflateIndex index = RandomAccessDeflate.index(sis, 1_048_576);
        index.open(sis);
        testCorrectness(testPair.uncompressed, index);

        ByteArrayOutputStream indexData = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(indexData)) {
            oos.writeObject(index);
        }
        byte[] indexBytes = indexData.toByteArray();

        DeflateIndex deflateIndex = (DeflateIndex) new ObjectInputStream(
            new ByteArrayInputStream(indexBytes)).readObject();
        SeekableInputStream seekableInputStream = new ByteArraySeekableInputStream(deflated);
        deflateIndex.open(seekableInputStream);
        testCorrectness(testPair.uncompressed, deflateIndex);
    }

    static class TestPair {
        final byte[] uncompressed;
        final byte[] compressed;

        public TestPair() throws IOException {
            uncompressed = new byte[10_485_739];
            new Random().nextBytes(uncompressed);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);

            try (DeflaterOutputStream outputStream = new DeflaterOutputStream(baos, deflater)) {
                outputStream.write(uncompressed);
            }
            finally {
                deflater.end();
            }
            compressed = baos.toByteArray();
        }
    }

}