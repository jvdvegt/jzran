package net.jzran;

import static net.jzran.CheckPoint.newPoint;

import com.sun.jna.Memory;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * This file is a nearly identical reimplementation of 'zran.c'
 * from the examples to zlib 1.2.3.
 */
class ZRan {
    static final int WINDOW_BITS = 15;
    private static final ZLib Z = ZLib.INSTANCE;

    private static final int CHUNK = 65536;
    private static final int WINDOW_SIZE = 1 << WINDOW_BITS;

    static List<CheckPoint> buildDeflateIndex(InputStream in, long span, long[] decompressedSize, ProgressListener<Long> listener) throws IOException {
        return buildIndex(in, span, decompressedSize, listener, -ZRan.WINDOW_BITS);
    }

    static List<CheckPoint> buildGZipIndex(InputStream in, long span, long[] decompressedSize, ProgressListener<Long> listener) throws IOException {
        return buildIndex(in, span, decompressedSize, listener, 32 + ZRan.WINDOW_BITS);         /* automatic zlib or gzip decoding */
    }

    private static List<CheckPoint> buildIndex(InputStream in, long span, long[] decompressedSize, ProgressListener<Long> listener, int windowBits) throws IOException {
        int ret;
        long totin, totout;  /* our own total counters to avoid 4GB limit */
        long last;                 /* totout value of last access Point */
        List<CheckPoint> index;       /* access points being generated */
        z_stream strm = new z_stream();
        Memory input = new Memory(CHUNK);
        ByteBuffer bb = input.getByteBuffer(0, CHUNK);
        Memory window = new Memory(WINDOW_SIZE);
        // JNA Memory is not guaranteed to be zeroed (the native heap may
        // reuse a dirty chunk), and checkpoints copy the whole window
        window.clear();
        byte[] buf = new byte[CHUNK];

        /* initialize inflate */
        strm.zalloc = null;
        strm.zfree = null;
        strm.opaque = null;
        strm.avail_in = 0;
        strm.next_in = null;

        // see https://www.zlib.net/manual.html#Advanced for window bits
        ret = Z.inflateInit2_(strm, windowBits, Z.zlibVersion(), ZLib.STREAM_SIZE);
        try {
            if (ret != ZLib.Z_OK) {
                throw new IOException("zlib error: " + ret);
            }

            /* inflate the input, maintain a sliding window, and build an index -- this
              also validates the integrity of the compressed data using the check
              information at the end of the gzip or zlib stream */
            totin = totout = last = 0;
            index = new ArrayList<>();

            strm.avail_out = 0;
            int iteration = 0;
            do {
                iteration++;
                if (!listener.reportProgress(totin)) {
                    return null;
                }

                /* get some compressed data from input file */
                strm.avail_in = in.read(buf, 0, CHUNK);

                /* If no more data is available and inflate hasn't returned
                  Z_STREAMEND, we must check if the stream has actually ended.
                  This can happen with raw deflate: inflate with Z_BLOCK
                  returns Z_OK after the last block while all input is consumed,
                  but won't return Z_STREAMEND until it knows there's no more
                  input coming. We detect this by checking if there's no more
                  to read (EOF) or if inflate consumed all input but didn't
                  signal stream end. */
                if (strm.avail_in == -1) {
                    /* try one more inflate call to potentially get Z_STREAMEND */
                    if (strm.avail_out == 0) {
                        strm.avail_out = WINDOW_SIZE;
                        strm.next_out = window;
                    }
                    ret = Z.inflate(strm, ZLib.Z_NO_FLUSH);
                    break;
                }
                bb.position(0);
                bb.put(buf, 0, strm.avail_in);
                strm.next_in = input;

                /* process all of that, or until end of stream */
                do {
                    /* reset sliding window if necessary */
                    if (strm.avail_out == 0) {
                        strm.avail_out = WINDOW_SIZE;
                        strm.next_out = window;
                    }

                    /* inflate until out of input, output, or at end of block --
                       update the total input and output counters */
                    totin += strm.avail_in;
                    totout += strm.avail_out;
                    ret = Z.inflate(strm, ZLib.Z_BLOCK);      /* return at end of block */
                    totin -= strm.avail_in;
                    totout -= strm.avail_out;
                    if (ret == ZLib.Z_NEED_DICT) {
                        ret = ZLib.Z_DATA_ERROR;
                    }
                    if (ret == ZLib.Z_MEM_ERROR || ret == ZLib.Z_DATA_ERROR) {
                        throw new IOException("zlib error: " + ret);
                    }
                    if (ret == ZLib.Z_STREAM_END) {
                        break;
                    }

                    /* if at end of block, consider adding an index entry (note that if
                       data_type indicates an end-of-block, then all the
                       uncompressed data from that block has been delivered, and none
                       of the compressed data after that block has been consumed,
                       except for up to seven bits) -- the totout == 0 provides an
                       entry Point after the zlib or gzip header, and assures that the
                       index always has at least one access Point; we avoid creating an
                       access Point after the last block by checking bit 6 of data_type
                       (for raw deflate, bit 6 is always set, so we need to handle it specially)
                    */
                    boolean isEndOfBlock = 0 != (strm.data_type & 128);
                    boolean isNotStored = 0 == (strm.data_type & 64);
                    if (isEndOfBlock && (isNotStored || windowBits < 0) &&
                            (totout == 0 || totout - last > span)) {
                        index.add(newPoint(totin, totout, strm.data_type & 7, strm.avail_out, window));
                        last = totout;
                    }
                    /* For raw deflate without a header, totout may jump past 0 so we always
                       add a starting checkpoint at out==0 if we haven't yet */
                    if (windowBits < 0 && index.isEmpty() && totout > 0 && totout <= span) {
                        index.add(newPoint(0, 0L, 0, WINDOW_SIZE, window));
                        last = 0;
                    }
                } while (strm.avail_in != 0);
            } while (ret != ZLib.Z_STREAM_END);

            decompressedSize[0] = totout;
            return index;
        } finally {
            Z.inflateEnd(strm);
        }
    }

    static class Extractor implements Closeable {
        private final byte[] bbuf = new byte[CHUNK];
        private final Memory input = new Memory(CHUNK);
        private final ByteBuffer bb = input.getByteBuffer(0, CHUNK);
        private final z_stream stream;
        private final SeekableInputStream inStream;
        private Memory discard;
        private int readSize = 4096;
        private boolean finished = false;
        private boolean closed = false;
        private long inflated;

        Extractor(SeekableInputStream inStream, List<CheckPoint> index, long offset) throws IOException {
            this.inStream = inStream;

            CheckPoint here = findIndexPoint(index, offset);

            z_stream strm = new z_stream();
            strm.zalloc = null;
            strm.zfree = null;
            strm.opaque = null;
            strm.avail_in = 0;
            strm.next_in = null;
            int init = Z.inflateInit2_(strm, -15, Z.zlibVersion(), ZLib.STREAM_SIZE);
            if (init != ZLib.Z_OK) {
                input.close();
                throw new IOException("zlib error: " + init);
            }
            this.stream = strm;

            try {
                inStream.seek(here.getCompressedPosition() - ((here.getBits() != 0) ? 1 : 0));
                if (here.getBits() != 0) {
                    int prime = inStream.read();
                    if (prime == -1) {
                        throw new EOFException("End of stream");
                    }
                    Z.inflatePrime(stream, here.getBits(), prime >>> (8 - here.getBits()));
                }
                Z.inflateSetDictionary(stream, here.getWindow(), WINDOW_SIZE);

                skip(offset - here.getDecompressedPosition());
            } catch (IOException | RuntimeException e) {
                close();
                throw e;
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            Z.inflateEnd(stream);
            input.close();
            if (discard != null) {
                discard.close();
            }
        }

        /**
         * Decompress and discard {@code n} bytes.
         *
         * @throws EOFException if the stream ends first.
         */
        void skip(long n) throws IOException {
            if (n <= 0) {
                return;
            }
            if (discard == null) {
                discard = new Memory(WINDOW_SIZE);
            }
            while (n > 0) {
                int r = extract(discard, (int) Math.min(n, WINDOW_SIZE));
                if (r < 0) {
                    throw new EOFException("Compressed stream ended " + n + " bytes before the requested offset");
                }
                n -= r;
            }
        }

        /** Total number of bytes decompressed by this extractor, including skipped bytes. */
        long inflatedBytes() {
            return inflated;
        }

        /**
         * Decompress up to {@code len} bytes into {@code buf}.
         *
         * @return the number of bytes produced (at least 1 if {@code len > 0}),
         *   or -1 at the end of the deflate stream.
         * @throws EOFException if the compressed input ends before the deflate stream does.
         */
        int extract(Memory buf, int len) throws IOException {
            if (finished) {
                return -1;
            }
            if (len <= 0) {
                return 0;
            }

            stream.next_out = buf;
            stream.avail_out = len;
            while (true) {
                if (stream.avail_in == 0) {
                    // start with small reads so a short read after a seek does not pull a whole
                    // chunk from the input, which matters when the input is itself decompressed
                    int nr = inStream.read(bbuf, 0, readSize);
                    readSize = Math.min(CHUNK, readSize * 2);
                    if (nr == -1) {
                        throw new EOFException("Compressed data ended before the end of the deflate stream");
                    }
                    if (nr == 0) {
                        continue;
                    }
                    bb.position(0);
                    bb.put(bbuf, 0, nr);
                    stream.next_in = input;
                    stream.avail_in = nr;
                }
                int ret = Z.inflate(stream, ZLib.Z_NO_FLUSH);       /* normal inflate */
                int produced = len - stream.avail_out;
                if (ret == ZLib.Z_STREAM_END) {
                    finished = true;
                    inflated += produced;
                    return produced > 0 ? produced : -1;
                }
                switch (ret) {
                    case ZLib.Z_NEED_DICT:
                    case ZLib.Z_MEM_ERROR:
                    case ZLib.Z_DATA_ERROR:
                    case ZLib.Z_STREAM_ERROR:
                        throw new IOException("zlib error: " + ret + " (" + ZLib.nameForCode(ret) + ")");
                    case ZLib.Z_BUF_ERROR:
                        if (stream.avail_in != 0 && produced == 0) {
                            throw new IOException("zlib made no progress (" + ZLib.nameForCode(ret) + ")");
                        }
                        break;
                    default:
                        break;
                }
                if (produced > 0) {
                    inflated += produced;
                    return produced;
                }
                // no output yet: inflate consumed all input, fetch more
            }
        }

        /**
         * Find the last checkpoint at or before {@code offset} (binary search; the
         * checkpoints are ordered by decompressed position).
         */
        static CheckPoint findIndexPoint(List<CheckPoint> index, long offset) throws IOException {
            if (index.isEmpty()) {
                throw new IOException("Index contains no checkpoints");
            }
            int lo = 0;
            int hi = index.size() - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (index.get(mid).getDecompressedPosition() <= offset) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return index.get(lo);
        }
    }
}
