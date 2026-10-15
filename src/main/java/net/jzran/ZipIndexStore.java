package net.jzran;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamException;
import java.io.OutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;

/**
 * The DEFLATE indexes built for the entries of a {@link ZipArchive} and of the archives
 * nested in it, keyed by entry path. Nested paths are joined with {@code "!/"}, for example
 * {@code "directory/nested.zip!/another.zip!/large_file.bin"}.
 * <p>
 * A store can be written to a stream and read back later to reuse the indexes across
 * process restarts. Every index is checked against its entry before it is used.
 * Not thread-safe.
 */
public final class ZipIndexStore implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private static final ObjectInputFilter FILTER = ObjectInputFilter.Config.createFilter(
        "maxdepth=16;"
            + "net.jzran.ZipIndexStore;net.jzran.ZipEntryIndex;net.jzran.DeflateIndex;"
            + "net.jzran.DeflateIndex$State;net.jzran.CheckPoint;"
            + "java.util.HashMap;java.util.Map$Entry;java.util.ArrayList;java.lang.Object;java.lang.String;java.lang.Enum;!*");

    private final HashMap<String, ZipEntryIndex> indexes = new HashMap<>();

    public ZipEntryIndex get(String path) {
        return indexes.get(path);
    }

    public void put(String path, ZipEntryIndex index) {
        indexes.put(path, index);
    }

    public ZipEntryIndex remove(String path) {
        return indexes.remove(path);
    }

    public Set<String> paths() {
        return Collections.unmodifiableSet(indexes.keySet());
    }

    public int size() {
        return indexes.size();
    }

    public void writeTo(OutputStream out) throws IOException {
        ObjectOutputStream oos = new ObjectOutputStream(out);
        oos.writeObject(this);
        oos.flush();
    }

    /**
     * Read a store written by {@link #writeTo(OutputStream)}. Only the classes that make up
     * a store are accepted during deserialization.
     *
     * @throws InvalidIndexException if the data is not a valid store.
     */
    public static ZipIndexStore readFrom(InputStream in) throws IOException {
        try {
            ObjectInputStream ois = new ObjectInputStream(in);
            ois.setObjectInputFilter(FILTER);
            return (ZipIndexStore) ois.readObject();
        } catch (ClassNotFoundException | ObjectStreamException | EOFException | RuntimeException e) {
            throw new InvalidIndexException("Not a valid index store: " + e, e);
        }
    }
}
