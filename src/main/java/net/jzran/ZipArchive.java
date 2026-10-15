package net.jzran;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipException;

/**
 * Random access to the entries of a ZIP archive read from a {@link SeekableInputStream}.
 * <p>
 * Only the end of central directory record, the central directory and the local header
 * of an opened entry are read; entry data is read on demand. Because an opened entry is
 * itself a {@link SeekableInputStream}, an entry that is a ZIP archive can be opened with
 * {@link #openNestedArchive(ZipArchiveEntry)}, to any depth, without extracting it.
 * <ul>
 *   <li>Stored entries are read directly from the archive.</li>
 *   <li>Deflated entries are read through a {@link DeflateIndex}. Building that index
 *       decompresses the whole entry once (and verifies its CRC-32); later reads only
 *       decompress from the nearest checkpoint. Indexes are kept in a {@link ZipIndexStore}
 *       that is shared with nested archives and can be persisted.</li>
 * </ul>
 * Supports ZIP64 and archive comments. Multi-disk archives, encrypted entries and
 * compression methods other than stored and deflate are rejected with a {@link ZipException}.
 * <p>
 * Not thread-safe: an archive, its nested archives and the streams opened from them share
 * the position of the underlying stream.
 */
public final class ZipArchive implements Closeable {
    private static final int LOCAL_HEADER_SIG = 0x04034b50;
    private static final int CENTRAL_HEADER_SIG = 0x02014b50;
    private static final int EOCD_SIG = 0x06054b50;
    private static final int ZIP64_EOCD_SIG = 0x06064b50;
    private static final int ZIP64_LOCATOR_SIG = 0x07064b50;

    private static final int LOCAL_HEADER_SIZE = 30;
    private static final int CENTRAL_HEADER_SIZE = 46;
    private static final int EOCD_SIZE = 22;
    private static final int ZIP64_LOCATOR_SIZE = 20;
    private static final int ZIP64_EOCD_SIZE = 56;
    private static final int MAX_COMMENT = 0xFFFF;
    private static final int ZIP64_EXTRA_ID = 0x0001;
    private static final int INITIAL_TAIL = 8192;

    /** Separator between the path of a nested archive and the path of an entry inside it. */
    public static final String NESTED_SEPARATOR = "!/";

    private final SeekableInputStream source;
    private final String path;
    private final ZipIndexStore indexStore;
    private final long span;

    private final List<ZipArchiveEntry> entryList = new ArrayList<>();
    private final Set<ZipArchiveEntry> entrySet = new HashSet<>();
    private final Map<String, ZipArchiveEntry> entries = new LinkedHashMap<>();
    private final Map<ZipArchiveEntry, Long> dataOffsets = new HashMap<>();
    private final Set<String> validatedIndexes = new HashSet<>();
    private final Map<String, ZipArchive> nestedArchives = new HashMap<>();
    private long centralDirectoryOffset;
    private byte[] tail;
    private long tailPos;
    private boolean closed;

    private ZipArchive(SeekableInputStream source, String path, ZipIndexStore indexStore, long span)
        throws IOException {
        if (span <= 0) {
            throw new IllegalArgumentException("span must be positive: " + span);
        }
        this.source = source;
        this.path = path;
        this.indexStore = indexStore;
        this.span = span;
        readCentralDirectory();
    }

    /** Open a ZIP file. Closing the archive closes the file. */
    public static ZipArchive open(Path file) throws IOException {
        return open(file, new ZipIndexStore(), IndexingInflaterInputStream.DEFAULT_SPAN);
    }

    /** Open a ZIP file, reusing the indexes in {@code indexStore}. Closing the archive closes the file. */
    public static ZipArchive open(Path file, ZipIndexStore indexStore, long span) throws IOException {
        FileSeekableInputStream in = new FileSeekableInputStream(new RandomAccessFile(file.toFile(), "r"));
        try {
            return open(in, indexStore, span);
        } catch (IOException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    /** Open a ZIP archive with a new index store and the default checkpoint span. */
    public static ZipArchive open(SeekableInputStream source) throws IOException {
        return open(source, new ZipIndexStore(), IndexingInflaterInputStream.DEFAULT_SPAN);
    }

    /**
     * Open a ZIP archive. Closing the archive closes {@code source}.
     *
     * @param indexStore where DEFLATE indexes are looked up and stored, for this archive
     *   and all archives nested in it.
     * @param span the distance in uncompressed bytes between checkpoints of new indexes.
     *   Each checkpoint takes about 32 KiB; reading at an arbitrary offset decompresses
     *   at most about {@code span} bytes more than requested.
     */
    public static ZipArchive open(SeekableInputStream source, ZipIndexStore indexStore, long span)
        throws IOException {
        return new ZipArchive(source, "", indexStore, span);
    }

    /** The entries, in central directory order. */
    public List<ZipArchiveEntry> getEntries() {
        return Collections.unmodifiableList(entryList);
    }

    /** The entry named {@code name}, or null. If several entries have that name, the first one. */
    public ZipArchiveEntry getEntry(String name) {
        return entries.get(name);
    }

    public ZipIndexStore getIndexStore() {
        return indexStore;
    }

    /** The path of this archive inside the outermost archive ("" for the outermost archive itself). */
    public String getPath() {
        return path;
    }

    /** The key under which the index of {@code entry} is kept in the index store. */
    public String indexPath(ZipArchiveEntry entry) {
        return path + entry.name();
    }

    /**
     * The compressed bytes of an entry, exactly as stored in the archive. For stored
     * entries these are also the uncompressed bytes.
     */
    public SeekableInputStream openRawEntry(ZipArchiveEntry entry) throws IOException {
        ensureOpen();
        checkOwnEntry(entry);
        return new SeekableInputStreamSlice(source, dataOffset(entry), entry.compressedSize());
    }

    /** Equivalent to {@code openEntry(getEntry(name))}. */
    public SeekableInputStream openEntry(String name) throws IOException {
        return openEntry(requireEntry(name));
    }

    /**
     * The uncompressed bytes of an entry, as a seekable stream positioned at 0.
     * For a deflated entry this builds its index first if the index store has none.
     * The caller must close the returned stream.
     *
     * @throws ZipException if the entry is encrypted, uses an unsupported compression
     *   method or has inconsistent metadata.
     * @throws InvalidIndexException if the stored index does not match the entry.
     */
    public SeekableInputStream openEntry(ZipArchiveEntry entry) throws IOException {
        ensureOpen();
        checkOwnEntry(entry);
        checkSupported(entry);
        if (entry.size() == 0) {
            return new ByteArraySeekableInputStream(new byte[0]);
        }
        SeekableInputStream raw = openRawEntry(entry);
        if (entry.method() == ZipArchiveEntry.STORED) {
            return raw;
        }
        DeflateIndex stream = getOrBuildIndex(entry, raw).newStream();
        stream.open(raw, 0);
        return stream;
    }

    /**
     * The index of a deflated entry: from the index store (after checking that it matches
     * the entry) or newly built, which decompresses the whole entry once.
     */
    public ZipEntryIndex getOrBuildIndex(ZipArchiveEntry entry) throws IOException {
        ensureOpen();
        checkOwnEntry(entry);
        checkSupported(entry);
        if (entry.method() != ZipArchiveEntry.DEFLATED || entry.size() == 0) {
            throw new IllegalArgumentException("Entry '" + entry.name() + "' needs no index");
        }
        try (SeekableInputStream raw = openRawEntry(entry)) {
            return getOrBuildIndex(entry, raw);
        }
    }

    /** Equivalent to {@code openNestedArchive(getEntry(name))}. */
    public ZipArchive openNestedArchive(String name) throws IOException {
        return openNestedArchive(requireEntry(name));
    }

    /**
     * Open an entry as a ZIP archive, without extracting it. The nested archive shares this
     * archive's index store and span, and is closed when this archive is closed.
     * Opening the same entry again returns the same instance.
     */
    public ZipArchive openNestedArchive(ZipArchiveEntry entry) throws IOException {
        ensureOpen();
        checkOwnEntry(entry);
        ZipArchive nested = nestedArchives.get(entry.name());
        if (nested != null && !nested.closed) {
            return nested;
        }
        SeekableInputStream content = openEntry(entry);
        try {
            nested = new ZipArchive(content, indexPath(entry) + NESTED_SEPARATOR, indexStore, span);
        } catch (IOException | RuntimeException e) {
            content.close();
            throw e;
        }
        nestedArchives.put(entry.name(), nested);
        return nested;
    }

    /** Close this archive, the archives nested in it, and the underlying stream. */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        for (ZipArchive nested : nestedArchives.values()) {
            try {
                nested.close();
            } catch (IOException e) {
                failure = e;
            }
        }
        nestedArchives.clear();
        source.close();
        if (failure != null) {
            throw failure;
        }
    }

    private ZipEntryIndex getOrBuildIndex(ZipArchiveEntry entry, SeekableInputStream raw) throws IOException {
        String key = indexPath(entry);
        ZipEntryIndex index = indexStore.get(key);
        if (index != null) {
            if (validatedIndexes.add(key)) {
                try {
                    index.validateFor(entry, raw);
                } catch (IOException | RuntimeException e) {
                    validatedIndexes.remove(key);
                    throw e;
                }
            }
            return index;
        }
        index = buildIndex(entry, raw);
        indexStore.put(key, index);
        validatedIndexes.add(key);
        return index;
    }

    private ZipEntryIndex buildIndex(ZipArchiveEntry entry, SeekableInputStream raw) throws IOException {
        long headCrc = ZipEntryIndex.headSampleCrc(raw);
        long tailCrc = ZipEntryIndex.tailSampleCrc(raw);
        raw.seek(0);
        CRC32 crc32 = new CRC32();
        long total = 0;
        IndexingInflaterInputStream inflater = new IndexingInflaterInputStream(raw, span, -ZRan.WINDOW_BITS);
        List<CheckPoint> points;
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = inflater.read(buf, 0, buf.length)) >= 0) {
                crc32.update(buf, 0, n);
                total += n;
                if (total > entry.size()) {
                    throw new ZipException("Entry '" + entry.name() + "' decompresses to more than its declared size "
                        + entry.size());
                }
            }
            points = inflater.getIndex();
        } finally {
            inflater.close();
        }
        if (total != entry.size()) {
            throw new ZipException("Entry '" + entry.name() + "' decompresses to " + total
                + " bytes, but its declared size is " + entry.size());
        }
        if (crc32.getValue() != entry.crc()) {
            throw new ZipException("CRC-32 mismatch for entry '" + entry.name() + "': expected "
                + Long.toHexString(entry.crc()) + ", got " + Long.toHexString(crc32.getValue()));
        }
        return new ZipEntryIndex(entry, span, headCrc, tailCrc, new DeflateIndex(points, total));
    }

    private ZipArchiveEntry requireEntry(String name) throws ZipException {
        ZipArchiveEntry entry = getEntry(name);
        if (entry == null) {
            throw new ZipException("No entry '" + name + "' in archive" + (path.isEmpty() ? "" : " '" + path + "'"));
        }
        return entry;
    }

    private void checkOwnEntry(ZipArchiveEntry entry) {
        if (!entrySet.contains(entry)) {
            throw new IllegalArgumentException("Entry '" + entry.name() + "' does not belong to this archive");
        }
    }

    private static void checkSupported(ZipArchiveEntry entry) throws ZipException {
        if (entry.isEncrypted()) {
            throw new ZipException("Encrypted entries are not supported: '" + entry.name() + "'");
        }
        if (entry.method() != ZipArchiveEntry.STORED && entry.method() != ZipArchiveEntry.DEFLATED) {
            throw new ZipException("Unsupported compression method " + entry.method()
                + " for entry '" + entry.name() + "'");
        }
        if (entry.method() == ZipArchiveEntry.STORED && entry.compressedSize() != entry.size()) {
            throw new ZipException("Stored entry '" + entry.name() + "' has compressed size "
                + entry.compressedSize() + " but size " + entry.size());
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Archive closed");
        }
    }

    /** Offset of the entry's data, read from its local header (once). */
    private long dataOffset(ZipArchiveEntry entry) throws IOException {
        Long cached = dataOffsets.get(entry);
        if (cached != null) {
            return cached;
        }
        long lho = entry.localHeaderOffset();
        if (lho > centralDirectoryOffset - LOCAL_HEADER_SIZE) {
            throw new ZipException("Local header of '" + entry.name() + "' at " + lho
                + " is outside the archive data");
        }
        byte[] h = read(lho, LOCAL_HEADER_SIZE);
        if (u32(h, 0) != LOCAL_HEADER_SIG) {
            throw new ZipException("No local header for '" + entry.name() + "' at offset " + lho);
        }
        if (u16(h, 8) != entry.method()) {
            throw new ZipException("Local header of '" + entry.name() + "' has compression method "
                + u16(h, 8) + ", central directory has " + entry.method());
        }
        long offset = lho + LOCAL_HEADER_SIZE + u16(h, 26) + u16(h, 28);
        if (offset > centralDirectoryOffset || entry.compressedSize() > centralDirectoryOffset - offset) {
            throw new ZipException("Data of '" + entry.name() + "' (" + entry.compressedSize()
                + " bytes at offset " + offset + ") exceeds the archive data, which ends at " + centralDirectoryOffset);
        }
        dataOffsets.put(entry, offset);
        return offset;
    }

    private void readCentralDirectory() throws IOException {
        long length = source.length();
        if (length < EOCD_SIZE) {
            throw new ZipException("Not a ZIP archive: only " + length + " bytes");
        }
        // A single read of the end of the archive usually covers the end of central directory
        // record, the ZIP64 records and, for small archives, the central directory itself.
        // Only if the record is not found there (a long archive comment) is a larger tail read.
        long eocdPos = -1;
        for (int tailLen : new int[] {INITIAL_TAIL, EOCD_SIZE + MAX_COMMENT + ZIP64_LOCATOR_SIZE + ZIP64_EOCD_SIZE}) {
            int len = (int) Math.min(length, tailLen);
            tailPos = length - len;
            tail = read(tailPos, len);
            eocdPos = findEndOfCentralDirectory(len == length || tailLen > INITIAL_TAIL);
            if (eocdPos >= 0 || len == length) {
                break;
            }
        }
        if (eocdPos < 0) {
            throw new ZipException("Not a ZIP archive: end of central directory record not found");
        }
        byte[] eocd = read(eocdPos, EOCD_SIZE);
        int disk = u16(eocd, 4);
        int cdDisk = u16(eocd, 6);
        long entriesOnDisk = u16(eocd, 8);
        long totalEntries = u16(eocd, 10);
        long cdSize = u32(eocd, 12);
        long cdOffset = u32(eocd, 16);
        long cdEnd = eocdPos;

        boolean needsZip64 = totalEntries == 0xFFFF || entriesOnDisk == 0xFFFF
            || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL;
        long zip64EocdPos = findZip64EndOfCentralDirectory(eocdPos);
        if (zip64EocdPos >= 0) {
            byte[] z = read(zip64EocdPos, ZIP64_EOCD_SIZE);
            disk = (int) u32(z, 16);
            cdDisk = (int) u32(z, 20);
            entriesOnDisk = u64(z, 24);
            totalEntries = u64(z, 32);
            cdSize = u64(z, 40);
            cdOffset = u64(z, 48);
            cdEnd = zip64EocdPos;
        } else if (needsZip64) {
            throw new ZipException("ZIP64 end of central directory record not found");
        }
        if (disk != 0 || cdDisk != 0 || entriesOnDisk != totalEntries) {
            throw new ZipException("Multi-disk ZIP archives are not supported");
        }
        if (cdOffset < 0 || cdSize < 0 || cdOffset > cdEnd || cdSize > cdEnd - cdOffset) {
            throw new ZipException("Central directory (" + cdSize + " bytes at offset " + cdOffset
                + ") lies outside the archive (" + length + " bytes)");
        }
        if (cdSize > Integer.MAX_VALUE - 8) {
            throw new ZipException("Central directory too large: " + cdSize + " bytes");
        }
        if (totalEntries < 0 || totalEntries > cdSize / CENTRAL_HEADER_SIZE) {
            throw new ZipException("Central directory of " + cdSize + " bytes cannot hold "
                + totalEntries + " entries");
        }
        this.centralDirectoryOffset = cdOffset;

        byte[] cd = read(cdOffset, (int) cdSize);
        int p = 0;
        for (long i = 0; i < totalEntries; i++) {
            ZipArchiveEntry entry = parseCentralHeader(cd, p);
            p += CENTRAL_HEADER_SIZE + u16(cd, p + 28) + u16(cd, p + 30) + u16(cd, p + 32);
            entryList.add(entry);
            entrySet.add(entry);
            entries.putIfAbsent(entry.name(), entry);
        }
        tail = null;
    }

    private ZipArchiveEntry parseCentralHeader(byte[] cd, int p) throws ZipException {
        if (p > cd.length - CENTRAL_HEADER_SIZE || u32(cd, p) != CENTRAL_HEADER_SIG) {
            throw new ZipException("Invalid central directory header at offset " + (centralDirectoryOffset + p));
        }
        int flags = u16(cd, p + 8);
        int method = u16(cd, p + 10);
        long crc = u32(cd, p + 16);
        long compressedSize = u32(cd, p + 20);
        long size = u32(cd, p + 24);
        int nameLen = u16(cd, p + 28);
        int extraLen = u16(cd, p + 30);
        int commentLen = u16(cd, p + 32);
        int diskStart = u16(cd, p + 34);
        long localHeaderOffset = u32(cd, p + 42);
        if (CENTRAL_HEADER_SIZE + nameLen + extraLen + commentLen > cd.length - p) {
            throw new ZipException("Truncated central directory header at offset " + (centralDirectoryOffset + p));
        }
        String name = new String(cd, p + CENTRAL_HEADER_SIZE, nameLen, StandardCharsets.UTF_8);

        // ZIP64 extended information: present fields appear in this fixed order
        int e = p + CENTRAL_HEADER_SIZE + nameLen;
        int extraEnd = e + extraLen;
        while (e + 4 <= extraEnd) {
            int id = u16(cd, e);
            int len = u16(cd, e + 2);
            int data = e + 4;
            if (len > extraEnd - data) {
                throw new ZipException("Invalid extra field in entry '" + name + "'");
            }
            if (id == ZIP64_EXTRA_ID) {
                int q = data;
                int end = data + len;
                if (size == 0xFFFFFFFFL) {
                    size = zip64Field(cd, q, end, name);
                    q += 8;
                }
                if (compressedSize == 0xFFFFFFFFL) {
                    compressedSize = zip64Field(cd, q, end, name);
                    q += 8;
                }
                if (localHeaderOffset == 0xFFFFFFFFL) {
                    localHeaderOffset = zip64Field(cd, q, end, name);
                    q += 8;
                }
                if (diskStart == 0xFFFF) {
                    if (q + 4 > end) {
                        throw new ZipException("Truncated ZIP64 extra field in entry '" + name + "'");
                    }
                    diskStart = (int) u32(cd, q);
                }
            }
            e = data + len;
        }
        if (diskStart != 0) {
            throw new ZipException("Multi-disk ZIP archives are not supported (entry '" + name + "')");
        }
        if (size < 0 || compressedSize < 0 || localHeaderOffset < 0) {
            throw new ZipException("Invalid size or offset in entry '" + name + "'");
        }
        return new ZipArchiveEntry(name, method, flags, crc, compressedSize, size, localHeaderOffset);
    }

    private static long zip64Field(byte[] b, int q, int end, String name) throws ZipException {
        if (q + 8 > end) {
            throw new ZipException("Truncated ZIP64 extra field in entry '" + name + "'");
        }
        return u64(b, q);
    }

    /**
     * Find the end of central directory record in {@link #tail}. It is at the very end of the
     * archive, unless the archive has a comment of up to 65535 bytes.
     *
     * @param lenient also accept a record followed by bytes beyond its comment.
     * @return its position, or -1.
     */
    private long findEndOfCentralDirectory(boolean lenient) {
        int tailLen = tail.length;
        int minStart = Math.max(0, tailLen - EOCD_SIZE - MAX_COMMENT);
        long candidate = -1;
        for (int i = tailLen - EOCD_SIZE; i >= minStart; i--) {
            if (u32(tail, i) == EOCD_SIG) {
                int end = i + EOCD_SIZE + u16(tail, i + 20);
                if (end == tailLen) {
                    return tailPos + i;
                }
                if (end < tailLen && candidate < 0) {
                    candidate = tailPos + i; // trailing bytes after the comment
                }
            }
        }
        return lenient ? candidate : -1;
    }

    /** The position of the ZIP64 end of central directory record, or -1 if there is none. */
    private long findZip64EndOfCentralDirectory(long eocdPos) throws IOException {
        if (eocdPos < ZIP64_LOCATOR_SIZE + ZIP64_EOCD_SIZE) {
            return -1;
        }
        byte[] loc = read(eocdPos - ZIP64_LOCATOR_SIZE, ZIP64_LOCATOR_SIZE);
        if (u32(loc, 0) != ZIP64_LOCATOR_SIG) {
            return -1;
        }
        long pos = u64(loc, 8);
        if (pos < 0 || pos > eocdPos - ZIP64_LOCATOR_SIZE - ZIP64_EOCD_SIZE) {
            throw new ZipException("Invalid ZIP64 end of central directory offset " + pos);
        }
        if (u32(read(pos, 4), 0) != ZIP64_EOCD_SIG) {
            throw new ZipException("No ZIP64 end of central directory record at offset " + pos);
        }
        return pos;
    }

    private byte[] read(long position, int len) throws IOException {
        if (tail != null && position >= tailPos && len <= tail.length - (position - tailPos)) {
            int from = (int) (position - tailPos);
            return Arrays.copyOfRange(tail, from, from + len);
        }
        byte[] b = new byte[len];
        source.readFullyAt(position, b, 0, len);
        return b;
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8;
    }

    private static long u32(byte[] b, int off) {
        return (u16(b, off) | (long) u16(b, off + 2) << 16) & 0xFFFFFFFFL;
    }

    private static long u64(byte[] b, int off) {
        return u32(b, off) | u32(b, off + 4) << 32;
    }
}
