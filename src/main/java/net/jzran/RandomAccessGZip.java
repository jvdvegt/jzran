package net.jzran;

import com.sun.jna.Memory;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * The entry point. Use {@link #index(java.io.InputStream,
 * long, ProgressListener)} to create an index.
 *
 * The index is a regular {@link net.jzran.SeekableInputStream}.
 *
 * If you have trouble loading the zlib library, just specify
 * the jzran.zlib.file property, for example
 * <code>java -Djzran.zlib.file=/path/to/zlib-mac.dylib</code>.
 */
public class RandomAccessGZip {

    /**
     * Use this method to create an index and monitor progress :)
     *
     * @param span A "checkpoint" will take place every span bytes of
     *   decompressed data. A checkpoint takes about 32kb.
     * @param listener It will be frequently notified of how many bytes of
     *   compressed input have been read so far.
     * @return an index, or null if progress listener returns false
     *   along the way ("cancel").
     */
    public static DeflateIndex index(InputStream input, long span, ProgressListener<Long> listener) throws IOException {
        long[] holder = {0L};
        List<ZRan.Point> idx = ZRan.buildGzipIndex(input, span, holder, listener);
        return idx == null ? null : new DeflateIndex(idx, holder[0]);
    }

    /**
     * Use this method to create an index :)
     *
     * @param span A "checkpoint" will take place every span bytes of
     *   decompressed data. A checkpoint takes about 32kb.
     */
    public static DeflateIndex index(InputStream input, long span) throws IOException {
        return index(input, span, new NullProgressListener());
    }
}
