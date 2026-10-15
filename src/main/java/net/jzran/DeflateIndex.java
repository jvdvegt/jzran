package net.jzran;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.List;

import com.sun.jna.Memory;

public class DeflateIndex extends SeekableInputStream implements Serializable {

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
    private transient Extractor extractor;
    private transient long pos;

    DeflateIndex(List<CheckPoint> idx, long decompressedSize) {
        this.idx = idx;
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

    @Override
    public void seek(long position) throws IOException {
        if (state == State.VOID) {
            throw new IllegalStateException("Call open() before seeking");
        }
        if (state == State.CLOSED) {
            throw new IllegalStateException("Stream closed");
        }
        if (position < 0 || position > decompressedSize) {
            throw new IllegalArgumentException(
                "Position " + position + " outside decompressed data of length " + decompressedSize);
        }

        this.pos = position;
        this.extractor = new Extractor(inStream, idx, position);
        state = State.OPEN;
    }

    @Override
    public int read() throws IOException {
        byte[] b = new byte[1];
        int n = read(b);
        return n > 0 ? b[0] & 0xFF : n;
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public long skip(long n) throws IOException {
        ensureOpen();
        long toSkip = Math.min(n, available());
        seek(pos + toSkip);
        return toSkip;
    }

    @Override
    public int available() {
        ensureOpen();
        return (int) Math.min(Integer.MAX_VALUE, length() - pos);
    }

    @Override
    public void close() {
        if (state != State.OPEN) {
            throw new IllegalStateException("Can only close an open stream");
        }
        extractor.close();
        state = State.CLOSED;
    }

    @Override
    public int read(byte[] buf, int offset, int len) throws IOException {
        ensureOpen();
        int n = extractor.extract(mem, Math.min(len, BUF_SIZE));
        if (n < 0) {
            return -1;  // End of stream
        }
        memBuf.position(0);
        memBuf.get(buf, offset, n);
        pos += n;
        return n;
    }

    @Override
    public long length() {
        return decompressedSize;
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