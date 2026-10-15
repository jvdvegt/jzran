package net.jzran;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;

/**
 * A reusable DEFLATE index for one ZIP entry, together with a fingerprint of the
 * entry it was built for. The fingerprint is checked before the index is used, so
 * an index is never silently applied to different data.
 * <p>
 * The checkpoint positions are relative to the start of the entry's compressed data,
 * so the index stays valid if the entry is moved within its archive, and does not
 * depend on any outer archive.
 */
public final class ZipEntryIndex implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /** Number of compressed bytes at the start and at the end of the entry covered by the fingerprint. */
    static final int SAMPLE_SIZE = 4096;

    private final String entryName;
    private final int method;
    private final long crc;
    private final long compressedSize;
    private final long size;
    private final long span;
    private final long headSampleCrc;
    private final long tailSampleCrc;
    private final DeflateIndex index;
    private final long checksum;

    ZipEntryIndex(ZipArchiveEntry entry, long span, long headSampleCrc, long tailSampleCrc, DeflateIndex index)
        throws InvalidIndexException {
        this.entryName = entry.name();
        this.method = entry.method();
        this.crc = entry.crc();
        this.compressedSize = entry.compressedSize();
        this.size = entry.size();
        this.span = span;
        this.headSampleCrc = headSampleCrc;
        this.tailSampleCrc = tailSampleCrc;
        this.index = index;
        this.checksum = computeChecksum();
        validateStructure();
    }

    public String getEntryName() {
        return entryName;
    }

    /** The uncompressed size of the indexed entry. */
    public long getSize() {
        return size;
    }

    /** The checkpoint spacing, in uncompressed bytes, this index was built with. */
    public long getSpan() {
        return span;
    }

    public int getCheckPointCount() {
        return index.getCheckPointCount();
    }

    /** A new stream over the entry's uncompressed data; the caller must {@code open()} it. */
    DeflateIndex newStream() {
        return index.copy();
    }

    /**
     * Check that this index belongs to {@code entry}, whose compressed bytes are {@code raw}.
     *
     * @throws InvalidIndexException if it does not.
     */
    void validateFor(ZipArchiveEntry entry, SeekableInputStream raw) throws IOException {
        if (!entryName.equals(entry.name())
            || method != entry.method()
            || crc != entry.crc()
            || compressedSize != entry.compressedSize()
            || size != entry.size()) {
            throw new InvalidIndexException("Index for '" + entryName + "' (crc=" + Long.toHexString(crc)
                + ", compressedSize=" + compressedSize + ", size=" + size + ") does not match entry '"
                + entry.name() + "' (crc=" + Long.toHexString(entry.crc()) + ", compressedSize="
                + entry.compressedSize() + ", size=" + entry.size() + ")");
        }
        if (headSampleCrc != headSampleCrc(raw) || tailSampleCrc != tailSampleCrc(raw)) {
            throw new InvalidIndexException("Index for '" + entryName
                + "' does not match the entry's compressed data");
        }
    }

    static long headSampleCrc(SeekableInputStream raw) throws IOException {
        return sampleCrc(raw, 0, (int) Math.min(SAMPLE_SIZE, raw.length()));
    }

    static long tailSampleCrc(SeekableInputStream raw) throws IOException {
        int n = (int) Math.min(SAMPLE_SIZE, raw.length());
        return sampleCrc(raw, raw.length() - n, n);
    }

    private static long sampleCrc(SeekableInputStream raw, long position, int length) throws IOException {
        byte[] b = new byte[length];
        raw.readFullyAt(position, b, 0, length);
        CRC32 crc32 = new CRC32();
        crc32.update(b);
        return crc32.getValue();
    }

    private void validateStructure() throws InvalidIndexException {
        if (entryName == null || index == null) {
            throw new InvalidIndexException("Incomplete index");
        }
        if (index.length() != size) {
            throw new InvalidIndexException("Index of '" + entryName + "' covers " + index.length()
                + " bytes, entry has " + size);
        }
        List<CheckPoint> points = index.checkPoints();
        if (points == null || points.isEmpty()) {
            throw new InvalidIndexException("Index of '" + entryName + "' has no checkpoints");
        }
        long lastIn = -1;
        long lastOut = -1;
        for (int i = 0; i < points.size(); i++) {
            CheckPoint p = points.get(i);
            if (p == null || p.getWindow() == null || p.getWindow().length != CheckPoint.WINDOW_SIZE
                || p.getBits() < 0 || p.getBits() > 7
                || p.getCompressedPosition() < (p.getBits() != 0 ? 1 : 0)
                || p.getCompressedPosition() > compressedSize
                || p.getDecompressedPosition() > size
                || p.getCompressedPosition() < lastIn || p.getDecompressedPosition() < lastOut
                || (i == 0 && p.getDecompressedPosition() != 0)) {
                throw new InvalidIndexException("Index of '" + entryName + "' has an invalid checkpoint #" + i);
            }
            lastIn = p.getCompressedPosition();
            lastOut = p.getDecompressedPosition();
        }
        if (checksum != computeChecksum()) {
            throw new InvalidIndexException("Index of '" + entryName + "' is corrupt (checksum mismatch)");
        }
    }

    /** CRC-32 over all fields and checkpoints, to detect corruption of a persisted index. */
    private long computeChecksum() {
        ByteBuffer b = ByteBuffer.allocate(8 * 8);
        b.putLong(method).putLong(crc).putLong(compressedSize).putLong(size).putLong(span)
            .putLong(headSampleCrc).putLong(tailSampleCrc).putLong(index.length());
        CRC32 crc32 = new CRC32();
        crc32.update(entryName.getBytes(StandardCharsets.UTF_8));
        crc32.update(b.array());
        ByteBuffer p = ByteBuffer.allocate(8 + 8 + 4);
        for (CheckPoint point : index.checkPoints()) {
            p.clear();
            p.putLong(point.getCompressedPosition()).putLong(point.getDecompressedPosition()).putInt(point.getBits());
            crc32.update(p.array());
            crc32.update(point.getWindow());
        }
        return crc32.getValue();
    }

    @Serial
    private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
        ois.defaultReadObject();
        try {
            validateStructure();
        } catch (InvalidIndexException e) {
            InvalidObjectException ioe = new InvalidObjectException(e.getMessage());
            ioe.initCause(e);
            throw ioe;
        }
    }
}
