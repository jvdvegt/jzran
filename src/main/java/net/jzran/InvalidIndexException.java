package net.jzran;

import java.io.IOException;

/**
 * Thrown when a stored index is corrupt or does not belong to the data it is used with.
 */
public class InvalidIndexException extends IOException {
    public InvalidIndexException(String message) {
        super(message);
    }

    public InvalidIndexException(String message, Throwable cause) {
        super(message, cause);
    }
}
