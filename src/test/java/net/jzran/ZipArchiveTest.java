package net.jzran;

import static net.jzran.ZipTestData.compressibleData;
import static net.jzran.ZipTestData.deflated;
import static net.jzran.ZipTestData.nested;
import static net.jzran.ZipTestData.stored;
import static net.jzran.ZipTestData.zip;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipArchiveTest {
    private static final int SPAN = 64 * 1024;
    private static final int LARGE = 8 * 1024 * 1024;
    private static final int CHUNK = 65536; // compressed read size of the extractor

    private static byte[] readAt(SeekableInputStream in, long position, int len) throws IOException {
        byte[] b = new byte[len];
        in.readFullyAt(position, b, 0, len);
        return b;
    }

    private static byte[] slice(byte[] data, long from, int len) {
        return Arrays.copyOfRange(data, (int) from, (int) from + len);
    }

    private static ZipArchive open(CountingSeekableInputStream source, ZipIndexStore store) throws IOException {
        return ZipArchive.open(source, store, SPAN);
    }

    private static CountingSeekableInputStream counting(byte[] zip) {
        return new CountingSeekableInputStream(new ByteArraySeekableInputStream(zip));
    }

    // TEST 1
    @Test
    void storedEntryRangeReadsTouchOnlyTheRequestedBytes() throws Exception {
        byte[] data = compressibleData(LARGE, 1);
        byte[] zip = zip(stored("before.bin", compressibleData(1_000_000, 2)), stored("big.bin", data),
            stored("after.bin", compressibleData(1_000_000, 3)));
        CountingSeekableInputStream source = counting(zip);
        try (ZipArchive archive = open(source, new ZipIndexStore())) {
            long metadataBytes = source.getBytesRead();
            assertTrue(metadataBytes <= 8192, "metadata read: " + metadataBytes);

            SeekableInputStream big = archive.openEntry("big.bin");
            assertEquals(data.length, big.length());
            for (long offset : new long[] {0, data.length / 2, data.length - 100}) {
                source.resetCounters();
                assertArrayEquals(slice(data, offset, 100), readAt(big, offset, 100));
                assertEquals(100, source.getBytesRead(), "a stored read must read exactly the requested bytes");
            }
        }
    }

    // TEST 2
    @Test
    void deflatedEntryRangeReads() throws Exception {
        byte[] data = compressibleData(LARGE, 4);
        byte[] zip = zip(deflated("big.bin", data));
        try (ZipArchive archive = open(counting(zip), new ZipIndexStore())) {
            ZipArchiveEntry entry = archive.getEntry("big.bin");
            assertTrue(entry.compressedSize() < data.length * 0.7, "test data must compress");
            ZipEntryIndex index = archive.getOrBuildIndex(entry);
            assertTrue(index.getCheckPointCount() >= LARGE / SPAN / 2, "checkpoints: " + index.getCheckPointCount());

            try (SeekableInputStream in = archive.openEntry(entry)) {
                assertEquals(data.length, in.length());
                for (long offset : new long[] {0, 1, SPAN - 3, SPAN + 7, data.length / 3, data.length / 2,
                    data.length - 1000, data.length - 1}) {
                    int len = (int) Math.min(1000, data.length - offset);
                    assertArrayEquals(slice(data, offset, len), readAt(in, offset, len), "offset " + offset);
                }
                // sequential read across many checkpoints
                byte[] all = new byte[data.length];
                in.readFullyAt(0, all, 0, all.length);
                assertArrayEquals(data, all);
            }
        }
    }

    // TEST 3 and TEST 10
    @Test
    void lastTenBytesOfIndexedEntryDoNotDecompressTheWholeEntry() throws Exception {
        byte[] data = compressibleData(LARGE, 5);
        byte[] zip = zip(deflated("big.bin", data));
        CountingSeekableInputStream source = counting(zip);
        try (ZipArchive archive = open(source, new ZipIndexStore())) {
            ZipArchiveEntry entry = archive.getEntry("big.bin");

            source.resetCounters();
            archive.getOrBuildIndex(entry);
            assertTrue(source.getBytesRead() >= entry.compressedSize(), "first access reads the whole entry");

            for (int round = 0; round < 3; round++) {
                source.resetCounters();
                try (DeflateIndex in = (DeflateIndex) archive.openEntry(entry)) {
                    byte[] last = readAt(in, data.length - 10, 10);
                    assertArrayEquals(slice(data, data.length - 10, 10), last);

                    long inflated = in.getInflatedByteCount();
                    long read = source.getBytesRead();
                    assertTrue(inflated < 2L * SPAN, "decompressed " + inflated + " of " + data.length);
                    assertTrue(read < 2L * SPAN + CHUNK, "read " + read + " of " + entry.compressedSize());
                    assertTrue(read < entry.compressedSize() / 10);
                }
            }
        }
    }

    // TEST 4
    @Test
    void threeLevelNestedArchive() throws Exception {
        byte[] data = compressibleData(LARGE, 6);
        byte[] outer = nested(data, ZipEntry.DEFLATED, ZipEntry.DEFLATED, ZipEntry.DEFLATED);
        try (ZipArchive archive = ZipArchive.open(new ByteArraySeekableInputStream(outer), new ZipIndexStore(), SPAN)) {
            ZipArchive level2 = archive.openNestedArchive("dir/level2.zip");
            ZipArchive level1 = level2.openNestedArchive("dir/level1.zip");
            assertEquals("dir/level2.zip!/dir/level1.zip!/", level1.getPath());
            try (SeekableInputStream target = level1.openEntry("target.bin")) {
                assertEquals(data.length, target.length());
                assertArrayEquals(slice(data, data.length - 10, 10), readAt(target, data.length - 10, 10));
                assertArrayEquals(slice(data, 12345, 100), readAt(target, 12345, 100));
            }
            assertSame(level2, archive.openNestedArchive("dir/level2.zip"));
            assertEquals(3, archive.getIndexStore().size());
        }
    }

    // TEST 5, TEST 6, TEST 7 (across restarts) and TEST 10
    @Test
    void deeplyNestedMixedMethodsWithPersistedIndexes() throws Exception {
        byte[] data = compressibleData(LARGE, 7);
        int[] methods = {ZipEntry.DEFLATED, ZipEntry.STORED, ZipEntry.DEFLATED, ZipEntry.STORED, ZipEntry.DEFLATED,
            ZipEntry.DEFLATED};
        byte[] outer = nested(data, methods);

        // first access: builds one index per deflated layer
        ZipIndexStore store = new ZipIndexStore();
        try (ZipArchive archive = ZipArchive.open(new ByteArraySeekableInputStream(outer), store, SPAN);
             SeekableInputStream target = openTarget(archive, methods.length)) {
            assertArrayEquals(slice(data, data.length - 10, 10), readAt(target, data.length - 10, 10));
        }
        assertEquals(4, store.size(), "one index per deflated entry on the path: " + store.paths());

        ByteArrayOutputStream persisted = new ByteArrayOutputStream();
        store.writeTo(persisted);

        // "after a restart": a new archive and a deserialized store
        ZipIndexStore restored = ZipIndexStore.readFrom(new ByteArrayInputStream(persisted.toByteArray()));
        CountingSeekableInputStream source = counting(outer);
        try (ZipArchive archive = ZipArchive.open(source, restored, SPAN);
             SeekableInputStream target = openTarget(archive, methods.length)) {
            long openCost = source.getBytesRead();
            assertArrayEquals(slice(data, data.length - 10, 10), readAt(target, data.length - 10, 10));
            assertArrayEquals(slice(data, 0, 10), readAt(target, 0, 10));
            assertArrayEquals(slice(data, data.length / 2, 10), readAt(target, data.length / 2, 10));
            long total = source.getBytesRead();
            System.out.printf("nested depth %d: outer %d bytes, opened with %d bytes read, 3 reads total %d%n",
                methods.length, outer.length, openCost, total);
            assertTrue(total < outer.length / 3, "read " + total + " of " + outer.length);
        }
        assertEquals(4, restored.size(), "no index was rebuilt");
    }

    private static SeekableInputStream openTarget(ZipArchive archive, int depth) throws IOException {
        ZipArchive current = archive;
        for (int level = depth - 1; level >= 1; level--) {
            current = current.openNestedArchive("dir/level" + level + ".zip");
        }
        return current.openEntry("target.bin");
    }

    @Test
    void storedNestingReadsOnlyTheRequestedBytes() throws Exception {
        byte[] data = compressibleData(LARGE, 8);
        byte[] outer = nested(data, ZipEntry.STORED, ZipEntry.STORED, ZipEntry.STORED, ZipEntry.STORED);
        CountingSeekableInputStream source = counting(outer);
        try (ZipArchive archive = open(source, new ZipIndexStore());
             SeekableInputStream target = openTarget(archive, 4)) {
            source.resetCounters();
            assertArrayEquals(slice(data, data.length - 10, 10), readAt(target, data.length - 10, 10));
            assertEquals(10, source.getBytesRead());
            assertEquals(0, archive.getIndexStore().size());
        }
    }

    // TEST 7
    @Test
    void indexIsReusedAcrossReadsAndOpens() throws Exception {
        byte[] data = compressibleData(LARGE, 9);
        byte[] zip = zip(deflated("big.bin", data));
        CountingSeekableInputStream source = counting(zip);
        try (ZipArchive archive = open(source, new ZipIndexStore())) {
            ZipArchiveEntry entry = archive.getEntry("big.bin");
            ZipEntryIndex index = archive.getOrBuildIndex(entry);
            source.resetCounters();
            long totalInflated = 0;
            for (int i = 0; i < 20; i++) {
                try (DeflateIndex in = (DeflateIndex) archive.openEntry(entry)) {
                    long offset = (long) (i * 7919L * 1013L) % (data.length - 50);
                    assertArrayEquals(slice(data, offset, 50), readAt(in, offset, 50));
                    totalInflated += in.getInflatedByteCount();
                }
                assertSame(index, archive.getOrBuildIndex(entry));
            }
            assertTrue(totalInflated < data.length, "20 reads decompressed " + totalInflated);
            assertTrue(source.getBytesRead() < entry.compressedSize(), "20 reads read " + source.getBytesRead());
        }
    }

    @Test
    void forwardSeekWithinSpanContinuesDecompression() throws Exception {
        byte[] data = compressibleData(LARGE, 10);
        try (ZipArchive archive = open(counting(zip(deflated("big.bin", data))), new ZipIndexStore());
             DeflateIndex in = (DeflateIndex) archive.openEntry("big.bin")) {
            long base = 3L * SPAN + 1000;
            readAt(in, base, 100);
            long before = in.getInflatedByteCount();
            for (int i = 1; i <= 50; i++) {
                assertArrayEquals(slice(data, base + i * 200L, 100), readAt(in, base + i * 200L, 100));
            }
            assertTrue(in.getInflatedByteCount() - before <= 50 * 200, "forward seeks restarted decompression");
        }
    }

    // TEST 8
    @Test
    void boundaryOffsets() throws Exception {
        byte[] data = compressibleData(100_000, 11);
        byte[] zip = zip(deflated("d.bin", data), stored("s.bin", data), deflated("empty-d", new byte[0]),
            stored("empty-s", new byte[0]), stored("dir/", new byte[0]));
        try (ZipArchive archive = open(counting(zip), new ZipIndexStore())) {
            for (String name : new String[] {"d.bin", "s.bin"}) {
                try (SeekableInputStream in = archive.openEntry(name)) {
                    byte[] b = new byte[10];
                    assertEquals(0, in.readAt(5, b, 0, 0), "zero-length read");
                    assertThrows(IllegalArgumentException.class, () -> in.readAt(-1, b, 0, 1));
                    assertThrows(IndexOutOfBoundsException.class, () -> in.readAt(0, b, 5, 10));
                    assertEquals(-1, in.readAt(data.length, b, 0, 10), "read at EOF");
                    assertEquals(-1, in.readAt(data.length + 1000L, b, 0, 10), "read beyond EOF");
                    assertEquals(4, in.readAt(data.length - 4, b, 0, 10), "read across EOF is short");
                    assertArrayEquals(slice(data, data.length - 4, 4), Arrays.copyOf(b, 4));
                    assertThrows(EOFException.class, () -> in.readFullyAt(data.length - 4, b, 0, 10));
                    assertThrows(IllegalArgumentException.class, () -> in.seek(data.length + 1L));
                    assertThrows(IllegalArgumentException.class, () -> in.seek(-1));
                    in.seek(data.length);
                    assertEquals(-1, in.read());
                    assertArrayEquals(slice(data, 0, 10), readAt(in, 0, 10), "usable after boundary errors");
                }
            }
            for (String name : new String[] {"empty-d", "empty-s", "dir/"}) {
                try (SeekableInputStream in = archive.openEntry(name)) {
                    assertEquals(0, in.length());
                    assertEquals(-1, in.read());
                    assertEquals(-1, in.readAt(0, new byte[1], 0, 1));
                }
            }
            assertTrue(archive.getEntry("dir/").isDirectory());
            assertThrows(ZipException.class, () -> archive.openEntry("missing"));
        }
    }

    // TEST 9
    @Test
    void truncatedAndCorruptArchives() throws Exception {
        byte[] data = compressibleData(200_000, 12);
        byte[] zip = zip(deflated("d.bin", data), stored("s.bin", data));

        assertThrows(ZipException.class, () -> ZipArchive.open(new ByteArraySeekableInputStream(new byte[10])));
        assertThrows(ZipException.class,
            () -> ZipArchive.open(new ByteArraySeekableInputStream(Arrays.copyOf(zip, zip.length - 30))));
        assertThrows(ZipException.class,
            () -> ZipArchive.open(new ByteArraySeekableInputStream(Arrays.copyOfRange(zip, 1000, zip.length))));

        // broken central directory signature
        byte[] badCd = zip.clone();
        badCd[ZipTestData.centralHeaderOffset(badCd)] = 0;
        assertThrows(ZipException.class, () -> ZipArchive.open(new ByteArraySeekableInputStream(badCd)));

        // entry claims more compressed data than the archive holds
        byte[] tooBig = zip.clone();
        int cd = ZipTestData.centralHeaderOffset(tooBig);
        ZipTestData.putU16(tooBig, cd + 22, 0x7FFF);
        try (ZipArchive archive = ZipArchive.open(new ByteArraySeekableInputStream(tooBig))) {
            assertThrows(ZipException.class, () -> archive.openEntry("d.bin"));
        }

        // corrupt deflate data is detected when the index is built (CRC-32 or zlib error)
        byte[] badData = zip.clone();
        for (int i = 0; i < 200; i++) {
            badData[1000 + i] ^= 0x55;
        }
        try (ZipArchive archive = ZipArchive.open(new ByteArraySeekableInputStream(badData))) {
            assertThrows(IOException.class, () -> archive.openEntry("d.bin"));
        }
    }

    @Test
    void corruptAndMismatchedIndexesAreRejected() throws Exception {
        byte[] data = compressibleData(1_000_000, 13);
        byte[] zip = zip(deflated("d.bin", data));
        ZipIndexStore store = new ZipIndexStore();
        try (ZipArchive archive = open(counting(zip), store)) {
            archive.getOrBuildIndex(archive.getEntry("d.bin"));
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        store.writeTo(baos);
        byte[] persisted = baos.toByteArray();

        // corrupted or truncated serialized store
        byte[] garbled = persisted.clone();
        for (int i = garbled.length / 2; i < garbled.length / 2 + 64; i++) {
            garbled[i] ^= (byte) 0xA5;
        }
        assertThrows(InvalidIndexException.class, () -> ZipIndexStore.readFrom(new ByteArrayInputStream(garbled)));
        assertThrows(InvalidIndexException.class,
            () -> ZipIndexStore.readFrom(new ByteArrayInputStream(Arrays.copyOf(persisted, persisted.length / 2))));
        assertThrows(InvalidIndexException.class, () -> ZipIndexStore.readFrom(new ByteArrayInputStream(new byte[100])));

        // same entry name, different content
        byte[] other = zip(deflated("d.bin", compressibleData(1_000_000, 14)));
        try (ZipArchive archive = open(counting(other), ZipIndexStore.readFrom(new ByteArrayInputStream(persisted)))) {
            assertThrows(InvalidIndexException.class, () -> archive.openEntry("d.bin"));
        }

        // same central directory metadata, different compressed bytes
        byte[] tampered = zip.clone();
        tampered[100] ^= 1;
        try (ZipArchive archive = open(counting(tampered), ZipIndexStore.readFrom(new ByteArrayInputStream(persisted)))) {
            assertThrows(InvalidIndexException.class, () -> archive.openEntry("d.bin"));
        }

        // a valid store still works
        try (ZipArchive archive = open(counting(zip), ZipIndexStore.readFrom(new ByteArrayInputStream(persisted)));
             SeekableInputStream in = archive.openEntry("d.bin")) {
            assertArrayEquals(slice(data, data.length - 10, 10), readAt(in, data.length - 10, 10));
        }
    }

    @Test
    void unsupportedEntriesAreRejected() throws Exception {
        byte[] data = "hello".getBytes(StandardCharsets.US_ASCII);
        byte[] zip = zip(stored("a.txt", data));
        int cd = ZipTestData.centralHeaderOffset(zip);

        byte[] encrypted = zip.clone();
        ZipTestData.putU16(encrypted, cd + 8, 1);
        ZipTestData.putU16(encrypted, 6, 1);
        try (ZipArchive archive = ZipArchive.open(new ByteArraySeekableInputStream(encrypted))) {
            ZipArchiveEntry entry = archive.getEntry("a.txt");
            assertTrue(entry.isEncrypted());
            assertFalse(entry.isSupported());
            assertThrows(ZipException.class, () -> archive.openEntry(entry));
            assertNotNull(archive.openRawEntry(entry), "raw bytes stay accessible");
        }

        byte[] bzip2 = zip.clone();
        ZipTestData.putU16(bzip2, cd + 10, 12);
        ZipTestData.putU16(bzip2, 8, 12);
        try (ZipArchive archive = ZipArchive.open(new ByteArraySeekableInputStream(bzip2))) {
            assertThrows(ZipException.class, () -> archive.openEntry("a.txt"));
        }
    }

    @Test
    void zip64AndComments() throws Exception {
        byte[] data = compressibleData(50_000, 15);
        byte[] zip64 = ZipTestData.zip64Stored("big.bin", data, "comment");
        try (ZipArchive archive = ZipArchive.open(new ByteArraySeekableInputStream(zip64));
             SeekableInputStream in = archive.openEntry("big.bin")) {
            assertEquals(data.length, archive.getEntry("big.bin").size());
            assertArrayEquals(slice(data, data.length - 10, 10), readAt(in, data.length - 10, 10));
        }

        // a comment that contains a fake end of central directory signature
        String comment = "x PK\u0005\u0006 y".repeat(100);
        byte[] commented = zip(comment, deflated("d.bin", data), stored("s.bin", data));
        try (ZipArchive archive = ZipArchive.open(new ByteArraySeekableInputStream(commented));
             SeekableInputStream in = archive.openEntry("d.bin")) {
            assertEquals(2, archive.getEntries().size());
            assertArrayEquals(data, readAt(in, 0, data.length));
        }
    }

    @Test
    void zip64EndOfCentralDirectoryWithManyEntries() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (int i = 0; i < 70_000; i++) {
                zos.putNextEntry(new ZipEntry("e" + i));
                zos.closeEntry();
            }
        }
        try (ZipArchive archive = ZipArchive.open(new ByteArraySeekableInputStream(baos.toByteArray()))) {
            assertEquals(70_000, archive.getEntries().size());
            assertNotNull(archive.getEntry("e69999"));
        }
    }

    @Test
    void openFileFromDisk(@TempDir Path dir) throws Exception {
        byte[] data = compressibleData(1_000_000, 16);
        Path file = dir.resolve("outer.zip");
        Files.write(file, nested(data, ZipEntry.DEFLATED, ZipEntry.DEFLATED));
        try (ZipArchive archive = ZipArchive.open(file);
             SeekableInputStream in = archive.openNestedArchive("dir/level1.zip").openEntry("target.bin")) {
            assertArrayEquals(slice(data, data.length - 10, 10), readAt(in, data.length - 10, 10));
        }
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "nothing was extracted to disk");
        }
    }
}
