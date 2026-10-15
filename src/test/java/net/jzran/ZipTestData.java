package net.jzran;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builders for the ZIP archives used by the tests. Everything is built in memory. */
final class ZipTestData {
    private ZipTestData() {
    }

    /**
     * Deterministic data that deflate compresses to roughly half its size, so the
     * compressed stream consists of real Huffman blocks (not just stored blocks).
     */
    static byte[] compressibleData(int size, long seed) {
        Random r = new Random(seed);
        byte[] alphabet = "abcdefghijklmnop \n".getBytes(StandardCharsets.US_ASCII);
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = alphabet[r.nextInt(alphabet.length)];
        }
        return data;
    }

    record Entry(String name, byte[] data, int method) {
    }

    static Entry stored(String name, byte[] data) {
        return new Entry(name, data, ZipEntry.STORED);
    }

    static Entry deflated(String name, byte[] data) {
        return new Entry(name, data, ZipEntry.DEFLATED);
    }

    /** A ZIP archive written by {@link ZipOutputStream}; deflated entries use data descriptors. */
    static byte[] zip(Entry... entries) throws IOException {
        return zip(null, entries);
    }

    static byte[] zip(String comment, Entry... entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            if (comment != null) {
                zos.setComment(comment);
            }
            for (Entry e : entries) {
                ZipEntry ze = new ZipEntry(e.name());
                ze.setMethod(e.method());
                if (e.method() == ZipEntry.STORED) {
                    ze.setSize(e.data().length);
                    ze.setCompressedSize(e.data().length);
                    ze.setCrc(crc(e.data()));
                }
                zos.putNextEntry(ze);
                zos.write(e.data());
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    /**
     * Wrap {@code innermost} (stored as {@code target.bin}) in {@code methods.length} archives:
     * {@code methods[0]} is the method of the innermost entry, the last one that of the entry
     * in the outermost archive. Archive {@code i} is stored as {@code level<i>.zip}.
     */
    static byte[] nested(byte[] innermost, int... methods) throws IOException {
        byte[] content = zip(new Entry("target.bin", innermost, methods[0]),
            stored("readme.txt", "unrelated".getBytes(StandardCharsets.US_ASCII)));
        for (int level = 1; level < methods.length; level++) {
            content = zip(stored("padding-" + level + ".txt", compressibleData(10_000, level)),
                new Entry("dir/level" + level + ".zip", content, methods[level]));
        }
        return content;
    }

    static long crc(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    /**
     * A hand-written ZIP64 archive with one stored entry whose sizes and local header
     * offset are given only in ZIP64 extra fields, plus a ZIP64 end of central directory
     * record, and an archive comment.
     */
    static byte[] zip64Stored(String name, byte[] data, String comment) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] commentBytes = comment.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(1024 + data.length + nameBytes.length * 2 + commentBytes.length)
            .order(ByteOrder.LITTLE_ENDIAN);
        long crc = crc(data);

        // local file header
        b.putInt(0x04034b50).putShort((short) 45).putShort((short) 0).putShort((short) 0)
            .putInt(0).putInt((int) crc).putInt(-1).putInt(-1)
            .putShort((short) nameBytes.length).putShort((short) 20).put(nameBytes)
            .putShort((short) 1).putShort((short) 16).putLong(data.length).putLong(data.length);
        b.put(data);

        // central directory
        long cdOffset = b.position();
        b.putInt(0x02014b50).putShort((short) 45).putShort((short) 45).putShort((short) 0).putShort((short) 0)
            .putInt(0).putInt((int) crc).putInt(-1).putInt(-1)
            .putShort((short) nameBytes.length).putShort((short) 28).putShort((short) 0)
            .putShort((short) 0).putShort((short) 0).putInt(0).putInt(-1).put(nameBytes)
            .putShort((short) 1).putShort((short) 24).putLong(data.length).putLong(data.length).putLong(0);
        long cdSize = b.position() - cdOffset;

        // ZIP64 end of central directory record and locator
        long zip64EocdOffset = b.position();
        b.putInt(0x06064b50).putLong(44).putShort((short) 45).putShort((short) 45).putInt(0).putInt(0)
            .putLong(1).putLong(1).putLong(cdSize).putLong(cdOffset);
        b.putInt(0x07064b50).putInt(0).putLong(zip64EocdOffset).putInt(1);

        // end of central directory record
        b.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) -1).putShort((short) -1)
            .putInt(-1).putInt(-1).putShort((short) commentBytes.length).put(commentBytes);

        byte[] result = new byte[b.position()];
        b.flip().get(result);
        return result;
    }

    /** Offset of the first central directory header in {@code zip}. */
    static int centralHeaderOffset(byte[] zip) {
        for (int i = zip.length - 22; i >= 0; i--) {
            if (u32(zip, i) == 0x06054b50L) {
                return (int) u32(zip, i + 16);
            }
        }
        throw new IllegalArgumentException("not a zip");
    }

    static long u32(byte[] b, int off) {
        return (b[off] & 0xFFL) | (b[off + 1] & 0xFFL) << 8 | (b[off + 2] & 0xFFL) << 16 | (b[off + 3] & 0xFFL) << 24;
    }

    static void putU16(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
    }
}
