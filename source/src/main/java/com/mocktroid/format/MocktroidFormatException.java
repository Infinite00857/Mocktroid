package com.mocktroid.format;

/** Thrown when a binary container doesn't match the expected format --
 *  wrong magic, an unsupported version, a truncated read, etc. */
public class MocktroidFormatException extends RuntimeException {
    public MocktroidFormatException(String message) {
        super(message);
    }

    public MocktroidFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
