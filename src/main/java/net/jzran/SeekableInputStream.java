package net.jzran;

import java.io.IOException;
import java.io.InputStream;

public abstract class SeekableInputStream extends InputStream {
    /**
     * Seeks to the indicated position in the stream.
     *
     * @param position The position the nest read() should start reading.
     * @throws IOException In case reading failed.
     */
    public abstract void seek(long position) throws IOException;

    /**
     *
     * @return
     * @throws IOException
     */
    public abstract long length() throws IOException;
}
