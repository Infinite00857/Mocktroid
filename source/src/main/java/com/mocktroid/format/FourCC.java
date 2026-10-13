package com.mocktroid.format;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** A 4-byte ASCII tag, e.g. "RFRM", "PACK", "TXTR". */
public final class FourCC {
    public static final int SIZE = 4;

    private final byte[] bytes;

    public FourCC(byte[] bytes) {
        if (bytes.length != 4) {
            throw new IllegalArgumentException("FourCC must be exactly 4 bytes, got " + bytes.length);
        }
        this.bytes = bytes.clone();
    }

    public FourCC(String ascii) {
        this(ascii.getBytes(StandardCharsets.US_ASCII));
    }

    /** Reverses byte order. Used only for PACK::TOCC::STRG's `kind` field, which
     *  retrotool's Rust source stores byte-swapped relative to every other FourCC
     *  field in the format (see StringTableEntry in pack.rs). */
    public FourCC reversed() {
        byte[] r = new byte[4];
        for (int i = 0; i < 4; i++) {
            r[i] = bytes[3 - i];
        }
        return new FourCC(r);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FourCC other)) return false;
        return Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(4);
        for (byte b : bytes) {
            sb.append((char) (b & 0xFF));
        }
        return sb.toString();
    }
}
