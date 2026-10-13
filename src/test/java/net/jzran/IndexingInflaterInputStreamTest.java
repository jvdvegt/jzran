package net.jzran;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

class IndexingInflaterInputStreamTest {

    @Test
    void test() throws IOException {
        var testPair = new RandomAccessDeflateTest.TestPair();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ByteArrayInputStream bais = new ByteArrayInputStream(testPair.compressed);
        IndexingInflaterInputStream input = IndexingInflaterInputStream.forDeflate(bais);
        try (input) {
//        try (InputStream input = new InflaterInputStream(bais, new Inflater(true))) {
            input.transferTo(baos);
        }
        System.out.println("num indices build: "+ input.getIndex().size());
        assertArrayEquals(testPair.uncompressed, baos.toByteArray());
    }
}
