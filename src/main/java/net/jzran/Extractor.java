package net.jzran;

import com.sun.jna.Memory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Decompresses data from a checkpointed deflate stream, starting at a
 * given decompressed offset.
 *
 * If the given index is empty, a new index is built while decompressing:
 * a checkpoint is generated every {@code span} bytes of decompressed data.
 */
public class Extractor implements Closeable {
    private static final ZLib Z = ZLib.INSTANCE;

    private static final int CHUNK = 65536;
    private static final int WINDOW_SIZE = CheckPoint.WINDOW_SIZE;

    private final byte[] bbuf = new byte[CHUNK];
    private final Memory input = new Memory(CHUNK);
    private final ByteBuffer bb = input.getByteBuffer(0, CHUNK);

    /* Decompressed data is produced in this sliding window and copied out
       as requested. At any point the window holds the most recent
       WINDOW_SIZE bytes of output, oldest first, which is what checkpoint
       capture relies on. */
    private final Memory window = new Memory(WINDOW_SIZE);

    private final z_stream stream;
    private final SeekableInputStream inStream;
    private final List<CheckPoint> index;
    private final boolean capturing;
    private final long span;
    private final int windowBits;

    private int windowCopied;
    private long totalBytesIn;
    private long totalBytesOut;
    private long last;
    private boolean finished;
    private boolean eof;

    /**
     * Creates an extractor that resumes decompression at the given
     * decompressed offset, using the given (non-empty) index.
     * No new checkpoints are generated.
     */
    public Extractor(SeekableInputStream inStream, List<CheckPoint> index, long offset) throws IOException {
        this(inStream, index, offset, 0, 0, false);
    }

    /**
     * Creates an extractor for the given index.
     * <p>
     * If the index is empty, a new index is built while decompressing, with
     * a checkpoint every {@code span} bytes of decompressed data. In that
     * case {@code offset} must be 0, the stream must be positioned at the
     * start of the compressed data, and {@code windowBits} selects the
     * format (for example 32 + 15 for gzip, -15 for raw deflate).
     * <p>
     * If the index is non-empty, decompression resumes at the given
     * decompressed offset, and no new checkpoints are generated.
     */
    public Extractor(SeekableInputStream inStream, List<CheckPoint> index, long offset, long span, int windowBits) throws IOException {
        this(inStream, index, offset, span, windowBits, index.isEmpty());
    }

    private Extractor(SeekableInputStream inStream, List<CheckPoint> index, long offset, long span, int windowBits, boolean capturing) throws IOException {
        this.inStream = inStream;
        this.index = index;
        this.capturing = capturing;
        this.span = span;
        this.windowBits = windowBits;

        stream = new z_stream();
        stream.zalloc = null;
        stream.zfree = null;
        stream.opaque = null;
        stream.avail_in = 0;
        stream.next_in = null;

        CheckPoint here = null;
        if (capturing) {
            if (offset != 0) {
                throw new IllegalArgumentException("Offset must be 0 when building an index: " + offset);
            }
            // see https://www.zlib.net/manual.html#Advanced for window bits
            if (Z.inflateInit2_(stream, windowBits, Z.zlibVersion(), ZLib.STREAM_SIZE) != ZLib.Z_OK) {
                throw new IOException("zlib error: " + windowBits);
            }
        } else {
            if (index.isEmpty()) {
                throw new IllegalArgumentException("Index must not be empty");
            }
            here = findIndexPoint(index, offset);
            // all format context is in the window, so raw deflate suffices
            if (Z.inflateInit2_(stream, -15, Z.zlibVersion(), ZLib.STREAM_SIZE) != ZLib.Z_OK) {
                throw new IOException("zlib error: " + -15);
            }
            inStream.seek(here.getCompressedPosition() - ((here.getBits() != 0) ? 1 : 0));
            if (here.getBits() != 0) {
                int prime = inStream.read();
                if (prime == -1) {
                    throw new IOException("End of stream");
                }
                Z.inflatePrime(stream, here.getBits(), prime >>> (8 - here.getBits()));
            }
            Z.inflateSetDictionary(stream, here.getWindow(), WINDOW_SIZE);
            totalBytesIn = here.getCompressedPosition();
            totalBytesOut = here.getDecompressedPosition();
            last = index.getLast().getDecompressedPosition();
        }
        // JNA Memory is not guaranteed to be zeroed (the native heap may
        // reuse a dirty chunk), and checkpoints copy the whole window
        window.clear();
        stream.next_out = window;
        stream.avail_out = WINDOW_SIZE;

        if (here != null) {
            try (Memory discard = new Memory(WINDOW_SIZE)) {
                for (long rem = offset - here.getDecompressedPosition(); rem > 0; ) {
                    int n = extract(discard, (rem > WINDOW_SIZE) ? WINDOW_SIZE : (int) rem);
                    if (n < 0) {
                        throw new IOException("Offset " + offset + " is beyond the end of the stream");
                    }
                    rem -= n;
                }
            }
        }
    }

    @Override
    public void close() {
        Z.inflateEnd(stream);
        input.close();
        window.close();
    }

    /**
     * @return the total number of bytes decompressed so far, which is the
     * full size of the decompressed data once {@link #isFinished()}
     */
    public long totalBytesOut() {
        return totalBytesOut;
    }

    /**
     * @return true if the end of the decompressed data has been reached
     */
    public boolean isFinished() {
        return finished;
    }

    /**
     * Decompresses up to {@code len} bytes into {@code buf}.
     *
     * @return the number of bytes written to {@code buf}, or -1 if the end
     * of the decompressed data has been reached
     */
    public int extract(Memory buf, int len) throws IOException {
        if (finished) {
            return -1;
        }
        if (len <= 0) {
            return 0;
        }
        int produced = 0;
        while (true) {
            /* copy out whatever is already produced and not yet delivered */
            int toCopy = WINDOW_SIZE - stream.avail_out - windowCopied;
            if (toCopy > len - produced) {
                toCopy = len - produced;
            }
            if (toCopy > 0) {
                buf.getByteBuffer(produced, toCopy).put(window.getByteBuffer(windowCopied, toCopy));
                windowCopied += toCopy;
                produced += toCopy;
            }
            if (produced >= len || finished) {
                break;
            }
            if (stream.avail_out == 0) {
                /* the window is full and fully delivered, so it can be reused */
                stream.next_out = window;
                stream.avail_out = WINDOW_SIZE;
                windowCopied = 0;
            }
            if (stream.avail_in == 0) {
                int numBytesRead;
                do {
                    numBytesRead = eof ? 0 : inStream.read(bbuf);
                } while (numBytesRead == 0 && !eof);

                if (numBytesRead == -1) {
                    eof = true;
                } else if (numBytesRead > 0) {
                    bb.position(0);
                    bb.put(bbuf, 0, numBytesRead);
                    stream.next_in = input;
                    stream.avail_in = numBytesRead;
                }
            }
            int availInBefore = stream.avail_in;
            int availOutBefore = stream.avail_out;
            totalBytesIn += stream.avail_in;
            totalBytesOut += stream.avail_out;
            int ret = Z.inflate(stream, eof ? ZLib.Z_NO_FLUSH : ZLib.Z_BLOCK);
            totalBytesIn -= stream.avail_in;
            totalBytesOut -= stream.avail_out;

            switch (ret) {
                case ZLib.Z_STREAM_END:
                    finished = true;
                    break;
                case ZLib.Z_BUF_ERROR:
                    if (stream.avail_in == 0) {
                        // no input left, and inflate can make no progress:
                        // the compressed data is truncated
                        throw new IOException("Premature end of stream, stream truncated?");
                    }
                    break;
                case ZLib.Z_OK:
                    if (stream.avail_in == availInBefore && stream.avail_out == availOutBefore) {
                        throw new IOException("No progress. Bad DEFLATE stream?");
                    }
                    break;
                default:
                    throw new IOException("ZLib error: " + ret + " (" + ZLib.nameForCode(ret) + ")");
            }

            if (capturing && !finished) {
                capture();
            }
        }
        return produced > 0 ? produced : -1;
    }

    /* If at end of block, consider adding an index entry (note that if
       data_type indicates an end-of-block, then all the uncompressed data
       from that block has been delivered, and none of the compressed data
       after that block has been consumed, except for up to seven bits) --
       the totalBytesOut == 0 provides an entry point after the zlib or
       gzip header, and assures that the index always has at least one
       access point; we avoid creating an access point after the last block
       by checking bit 6 of data_type (for raw deflate, bit 6 is always set,
       so we need to handle it specially)
     */
    private void capture() {
        boolean isEndOfBlock = 0 != (stream.data_type & 128);
        boolean isNotStored = 0 == (stream.data_type & 64);
        if (isEndOfBlock && (isNotStored || windowBits < 0) &&
            (totalBytesOut == 0 || totalBytesOut - last > span)) {
            index.add(CheckPoint.newPoint(totalBytesIn, totalBytesOut, stream.data_type & 7, stream.avail_out, window));
            last = totalBytesOut;
        }
        /* For raw deflate without a header, totalBytesOut may jump past 0
           so we always add a starting checkpoint at out==0 if we haven't yet */
        if (windowBits < 0 && index.isEmpty() && totalBytesOut > 0 && totalBytesOut <= span) {
            index.add(CheckPoint.newPoint(0, 0L, 0, WINDOW_SIZE, window));
            last = 0;
        }
    }

    private static CheckPoint findIndexPoint(List<CheckPoint> index, long offset) {
        for (int j = 0; j < index.size() - 1; ++j) {
            if (index.get(j + 1).getDecompressedPosition() > offset)
                return index.get(j);
        }
        return index.getLast();
    }
}
