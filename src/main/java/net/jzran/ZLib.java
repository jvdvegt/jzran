package net.jzran;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;

interface ZLib extends Library {
    Logger LOG = Logger.getLogger(ZLib.class.getName());

    class Helper {
        static ZLib loadLibrary() {
            String overridden = System.getProperty("jzran.zlib.file");
            if (overridden != null) {
                File overriddenLib = new File(overridden);
                try {
                    return Native.load(overriddenLib.getAbsolutePath(), ZLib.class);
                } catch (Throwable t) {
                    throw new RuntimeException("Library " + overriddenLib + " exists but could not be loaded");
                }
            }

            try {
                ZLib zLib = Native.load("z", ZLib.class);
                LOG.info("Using native system zlib library");
                return zLib;
            } catch (Throwable t) {
                // platform-native library not found, try a bundled resource
            }

            File res = getZlibFile().getAbsoluteFile();
            if (!res.exists()) {
                throw new RuntimeException(
                    "Library " + res + " doesn't exist, " +
                        "override property jzran.zlib.file, " +
                        "for example: java -Djzran.zlib.file=/path/to/zlib-mac.dylib");
            }
            try {
                return Native.load(res.getAbsolutePath(), ZLib.class);
            } catch (Throwable t) {
                throw new RuntimeException("Library " + res + " exists but could not be loaded");
            }
        }

        private static File getZlibFile() {
            String overridden = System.getProperty("jzran.zlib.file");
            if (overridden != null) {
                return new File(overridden);
            }
            String libraryName = getLibraryName();
            try {
                Path dir = Files.createTempDirectory("zlib-tmp-folder");
                Path extractedLibraryFile = dir.resolve(libraryName);
                try (InputStream stream = ZLib.class.getClassLoader().getResourceAsStream(libraryName)) {
                    if (stream == null) {
                        throw new UnsupportedOperationException(
                            "Versions of zlib are bundled only for Windows and Linux for now. " +
                                "Report to the developers or override system property jzran.zlib.file, " +
                                "for example: java -Djzran.zlib.file=/path/to/zlib-mac.dylib");
                    }
                    Files.copy(stream, extractedLibraryFile);
                }
                return extractedLibraryFile.toFile();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static String getLibraryName() {
            if (Platform.isWindows()) {
                return Platform.is64Bit() ? "zlibwapi-1.2.5-64.dll" : "zlibwapi-1.2.5-32.dll";
            } else if (Platform.isLinux()) {
                return Platform.is64Bit() ? "libz-64.so.1.2.11" : "libz-32.so.1.2.5";
            } else {
                throw new UnsupportedOperationException(
                    "Versions of zlib are bundled only for Windows and Linux for now. " +
                        "Report to the developers or override system property jzran.zlib.file, " +
                        "for example: java -Djzran.zlib.file=/path/to/zlib-mac.dylib");
            }
        }
    }

    ZLib INSTANCE = Helper.loadLibrary();

    int inflatePrime(z_stream stream, int bits, int value);

    int inflateSetDictionary(z_stream stream, byte[] dictionary, int length);

    int inflateInit2_(z_stream stream, int windowBits, String version, int streamSize);

    int inflate(z_stream stream, int flush);

    int inflateEnd(z_stream stream);

    String zlibVersion();

    int Z_OK = 0;
    int Z_STREAM_END = 1;
    int Z_NEED_DICT = 2;
    int Z_ERRNO = -1;
    int Z_STREAM_ERROR = -2;
    int Z_DATA_ERROR = -3;
    int Z_MEM_ERROR = -4;
    int Z_BUF_ERROR = -5;
    int Z_VERSION_ERROR = -6;

    int Z_BLOCK = 5;
    int Z_NO_FLUSH = 0;

    int STREAM_SIZE = new z_stream().size();
}
