package net.jzran;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.sun.jna.Memory;

public class DeflateIndex extends SeekableInputStream implements Serializable {

    // Pinned to the value the JVM computed for jzran 0.3, so indexes serialized
    // by earlier versions can still be read after methods were added.
    @Serial
    private static final long serialVersionUID = 4615420128533336607L;

    protected enum State {
        // inStream is not set
        VOID,
        // inStream is set but no seek was done
        SEMI_OPEN,
        // ready for reading data
        OPEN,
        CLOSED
    }
    // transitions: state -> state + 1

    private static final int BUF_SIZE = 1_048_576;

    private final List<CheckPoint> idx;
    private final long decompressedSize;

    private State state;

    private transient Memory mem;
    private transient ByteBuffer memBuf;
    private transient SeekableInputStream inStream;
    private transient ZRan.Extractor extractor;
    private transient long pos;
    private transient long inflatedByClosedExtractors;

    DeflateIndex(List<CheckPoint> idx, long decompressedSize) {
        this.idx = new ArrayList<>(idx);
        this.decompressedSize = decompressedSize;
        this.state = State.VOID;
        this.mem = new Memory(BUF_SIZE);
        this.memBuf = mem.getByteBuffer(0, BUF_SIZE);
    }

    /**
     * Prepare this index for working with the compressed stream.
     * Optionally also seek to an origin.
     *
     * @param inStream compressed data stream.
     * @param offset   if greater than zero, then also seek to this
     *                 position in the decompressed data.
     */
    public void open(SeekableInputStream inStream, long offset) throws IOException {
        if (state != State.VOID) {
            throw new IllegalStateException("Can only call open() once");
        }

        this.inStream = inStream;
        state = State.SEMI_OPEN;
        if (offset >= 0) {
            seek(offset);
        }
    }

    /**
     * Prepare this index for working with the compressed stream.
     * Do not seek anywhere yet (you'll have to seek somewhere before
     * the stream is usable).
     */
    public void open(SeekableInputStream inStream) throws IOException {
        open(inStream, -1);
    }

    /**
     * Position the stream at {@code offset} in the decompressed data.
     * <p>
     * If the stream is already positioned at or before {@code offset} and no checkpoint
     * lies between the current position and {@code offset}, decompression continues
     * from the current position. Otherwise decompression restarts at the last
     * checkpoint at or before {@code offset}.
     *
     * @throws IllegalArgumentException if {@code offset} is negative or beyond {@link #length()}.
     */
    public void seek(long offset) throws IOException {
        if (state == State.VOID) {
            throw new IllegalStateException("Call open() before seeking");
        }
        if (state == State.CLOSED) {
            throw new IllegalStateException("Stream closed");
        }
        if (offset < 0 || offset > decompressedSize) {
            throw new IllegalArgumentException(
                "Offset " + offset + " outside decompressed data of length " + decompressedSize);
        }

        if (extractor != null && offset >= pos
            && ZRan.Extractor.findIndexPoint(idx, offset).getDecompressedPosition() <= pos) {
            extractor.skip(offset - pos);
            this.pos = offset;
            return;
        }

        closeExtractor();
        this.extractor = new ZRan.Extractor(inStream, idx, offset);
        this.pos = offset;
        state = State.OPEN;
    }

    @Override
    public int read() throws IOException {
        byte[] b = new byte[1];
        int n = read(b, 0, 1);
        return n > 0 ? b[0] & 0xFF : -1;
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public long skip(long n) throws IOException {
        ensureOpen();
        if (n <= 0) {
            return 0;
        }
        long toSkip = Math.min(n, available());
        seek(pos + toSkip);
        return toSkip;
    }

    @Override
    public int available() {
        ensureOpen();
        return (int) Math.min(Integer.MAX_VALUE, length() - pos);
    }

    /**
     * Release the native resources. Closing an index that was never opened,
     * or closing it twice, has no effect.
     */
    @Override
    public void close() {
        if (state == State.CLOSED) {
            return;
        }
        closeExtractor();
        mem.close();
        state = State.CLOSED;
    }

    private void closeExtractor() {
        if (extractor != null) {
            inflatedByClosedExtractors += extractor.inflatedBytes();
            extractor.close();
            extractor = null;
        }
    }

    @Override
    public int read(byte[] buf, int offset, int len) throws IOException {
        ensureOpen();
        if (len == 0) {
            return 0;
        }
        int n = extractor.extract(mem, Math.min(len, BUF_SIZE));
        if (n < 0)
            return -1;  // End of stream
        memBuf.position(0);
        memBuf.get(buf, offset, n);
        pos += n;
        return n;
    }

    public long length() {
        return decompressedSize;
    }

    /** The number of checkpoints in this index. */
    public int getCheckPointCount() {
        return idx.size();
    }

    /**
     * Instrumentation: the total number of bytes decompressed by this stream since it was
     * opened, including bytes decompressed and discarded while seeking forward from a checkpoint.
     */
    public long getInflatedByteCount() {
        return inflatedByClosedExtractors + (extractor == null ? 0 : extractor.inflatedBytes());
    }

    List<CheckPoint> checkPoints() {
        return idx;
    }

    /** A new, unopened stream over the same checkpoints (the checkpoints themselves are not copied). */
    DeflateIndex copy() {
        return new DeflateIndex(idx, decompressedSize);
    }

    private void ensureOpen() {
        if (state != State.OPEN) {
            throw new IllegalStateException("Stream must be open");
        }
    }

    @Serial
    private void writeObject(ObjectOutputStream oos) throws IOException {
        oos.defaultWriteObject();
    }

    @Serial
    private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
        ois.defaultReadObject();
        this.mem = new Memory(BUF_SIZE);
        this.memBuf = mem.getByteBuffer(0, BUF_SIZE);
        this.state = State.VOID;
    }
}