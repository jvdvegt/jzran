package net.jzran;

import com.sun.jna.Memory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * This file is a nearly identical reimplementation of 'zran.c'
 * from the examples to zlib 1.2.3.
 */
class ZRan {
    private static final ZLib Z = ZLib.INSTANCE;

    private static final int CHUNK = 65536;
    private static final int WINDOW_BITS = 15;
    private static final int WINSIZE = 1 << WINDOW_BITS;

    static class Point implements Serializable {
        private final long out;
        private final long in;
        private final int bits;
        private final byte[] window = new byte[WINSIZE];

        private Point(long out, long in, int bits) {
            this.out = out;
            this.in = in;
            this.bits = bits;
        }
    }

    static void addpoint(List<Point> index, int bits, long in, long out, int left, Memory window) {
        Point p = new Point(out, in, bits);
        index.add(p);
        if (left != 0) {
            System.arraycopy(window.getByteArray(0, WINSIZE), WINSIZE - left, p.window, 0, left);
        }
        if (left < WINSIZE) {
            System.arraycopy(window.getByteArray(0, WINSIZE), 0, p.window, left, WINSIZE - left);
        }
    }

    static List<Point> buildGzipIndex(InputStream in, long span, long[] decompressedSize, ProgressListener<Long> listener)
        throws IOException {
        return build_index(in, span, decompressedSize, listener, 32 + WINDOW_BITS);/* automatic zlib or gzip decoding */
    }

    static List<Point> buildDeflateIndex(InputStream in, long span, long[] decompressedSize, ProgressListener<Long> listener)
        throws IOException {
        return build_index(in, span, decompressedSize, listener, -WINDOW_BITS);
    }

    private static List<Point> build_index(InputStream in, long span, long[] decompressedSize, ProgressListener<Long> listener, int windowBits)
            throws IOException {
        int ret;
        long totin, totout;  /* our own total counters to avoid 4GB limit */
        long last;                 /* totout value of last access Point */
        List<Point> index;       /* access points being generated */
        z_stream strm = new z_stream();
        Memory input = new Memory(CHUNK);
        ByteBuffer bb = input.getByteBuffer(0, CHUNK);
        Memory window = new Memory(WINSIZE);
        byte[] buf = new byte[CHUNK];

        /* initialize inflate */
        strm.zalloc = null;
        strm.zfree = null;
        strm.opaque = null;
        strm.avail_in = 0;
        strm.next_in = null;

//        see https://www.zlib.net/manual.html#Advanced for window bits
        ret = Z.inflateInit2_(strm, windowBits, Z.zlibVersion(), ZLib.STREAM_SIZE);
        try {
            if (ret != ZLib.Z_OK) {
                throw new IOException("zlib error: " + ret);
            }

/* inflate the input, maintain a sliding window, and build an index -- this
              also validates the integrity of the compressed data using the check
              information at the end of the gzip or zlib stream */
            totin = totout = last = 0;
            index = new ArrayList<Point>();               /* will be allocated by first addpoint() */
            
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
                        strm.avail_out = WINSIZE;
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
                        strm.avail_out = WINSIZE;
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
                       data_type indicates an end-of-block, then all of the
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
                        addpoint(index, strm.data_type & 7, totin, totout, strm.avail_out, window);
                        last = totout;
                    }
                    /* For raw deflate without a header, totout may jump past 0 so we always
                       add a starting checkpoint at out==0 if we haven't yet */
                    if (windowBits < 0 && index.isEmpty() && totout > 0 && totout <= span) {
                        addpoint(index, 0, 0, 0L, WINSIZE, window);
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

    private static final Memory discard = new Memory(WINSIZE);

    static class Extractor {
        private final byte[] bbuf = new byte[CHUNK];
        private final Memory input = new Memory(CHUNK);
        private final ByteBuffer bb = input.getByteBuffer(0, CHUNK);
        private final z_stream stream;
        private final SeekableInputStream inStream;
        private boolean finished = false;

        Extractor(SeekableInputStream inStream, List<Point> index, long offset) throws IOException {
            this.inStream = inStream;

            Point here = findIndexPoint(index, offset);

            z_stream strm = new z_stream();
            strm.zalloc = null;
            strm.zfree = null;
            strm.opaque = null;
            strm.avail_in = 0;
            strm.next_in = null;
            int init = Z.inflateInit2_(
                    strm, -15, Z.zlibVersion(), ZLib.STREAM_SIZE);
            if (init != ZLib.Z_OK)
                throw new IOException("zlib error: " + init);
            this.stream = strm;

            inStream.seek(here.in - ((here.bits != 0) ? 1 : 0));
            if (here.bits != 0) {
                int prime = inStream.read();
                if (prime == -1)
                    throw new IOException("End of stream");
                Z.inflatePrime(stream, here.bits, prime >>> (8 - here.bits));
            }
            Z.inflateSetDictionary(stream, here.window, WINSIZE);

            for (long rem = offset - here.out; rem > 0; ) {
                rem -= extract(discard, (rem > WINSIZE) ? WINSIZE : (int) rem);
            }
        }

        public void close() {
            Z.inflateEnd(stream);
        }

public int extract(Memory buf, int len) throws IOException {
            if (finished)
                return -1;

            stream.next_out = buf;
            stream.avail_out = len;
            if (stream.avail_in == 0) {
                int nr = inStream.read(bbuf);
                if (nr == -1) {
                    finished = true;
                    // If we've decompressed everything, return the difference as the remaining bytes
                    return len - stream.avail_out;
                }
                bb.position(0);
                bb.put(bbuf, 0, nr);
                stream.next_in = input;
                stream.avail_in = nr;
            }
            int ret = Z.inflate(stream, ZLib.Z_NO_FLUSH);       /* normal inflate */
            if (ret == ZLib.Z_STREAM_END) {
                finished = true;
                return len - stream.avail_out;
            }
            switch (ret) {
                case ZLib.Z_NEED_DICT:
                case ZLib.Z_MEM_ERROR:
                case ZLib.Z_DATA_ERROR:
                    throw new IOException("zlib error: " + ret);
            }
            return len - stream.avail_out;
        }

        private static Point findIndexPoint(List<Point> index, long offset) {
            for (int j = 0; j < index.size() - 1; ++j) {
                if (index.get(j + 1).out > offset)
                    return index.get(j);
            }
            return index.getLast();
        }
    }

}
