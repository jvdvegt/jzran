package net.jzran;

/**
 * An entry of a {@link ZipArchive}, as described by the archive's central directory.
 * All sizes and offsets are already resolved from ZIP64 extra fields where present.
 *
 * @param name               the entry name.
 * @param method             the compression method (0 = stored, 8 = deflate).
 * @param flags              the general purpose bit flags.
 * @param crc                the CRC-32 of the uncompressed data.
 * @param compressedSize     the size of the compressed data.
 * @param size               the size of the uncompressed data.
 * @param localHeaderOffset  the offset of the entry's local file header in the archive.
 */
public record ZipArchiveEntry(
    String name,
    int method,
    int flags,
    long crc,
    long compressedSize,
    long size,
    long localHeaderOffset) {

    public static final int STORED = 0;
    public static final int DEFLATED = 8;

    /** True if the entry is encrypted (traditional or strong encryption). */
    public boolean isEncrypted() {
        return (flags & 0x0001) != 0 || (flags & 0x0040) != 0;
    }

    public boolean isDirectory() {
        return name.endsWith("/");
    }

    /** True if this library can decompress the entry. */
    public boolean isSupported() {
        return !isEncrypted() && (method == STORED || method == DEFLATED);
    }
}
