package com.mocktroid.format.compress;

/**
 * The two LZSS decompressor variants, ported from retrotool's lzss.rs.
 * {@code m} is the group-size exponent (group size = 1 &lt;&lt; m):
 * m=0/1/2 correspond to modes 1-3 (plain) and 12-14 (Huffman).
 *
 * @see <a href="https://wiki.axiodl.com/w/LZSS_Compression">LZSS Compression (axiodl wiki)</a>
 */
public final class Lzss {
    private Lzss() {}

    /** Plain LZSS (compression modes 1/2/3). */
    public static boolean decompress(int m, byte[] input, int inputOffset, byte[] output) {
        int groupSize = 1 << m;
        BitReader state = new BitReader(input, inputOffset);
        int outCur = 0;
        while (outCur < output.length) {
            int isBackref = state.readBit();
            if (isBackref < 0) {
                return false;
            }
            if (isBackref == 1) {
                byte[] b = new byte[2];
                if (!state.readBytesDirect(b, 0, 2)) {
                    return false;
                }
                int b0 = b[0] & 0xFF;
                int b1 = b[1] & 0xFF;
                int count = (b0 >> 4) + (3 - m);
                int offset = (((b0 & 0xF) << 8) | b1) << m;
                if (offset > outCur) {
                    return false;
                }
                int copyLen = count * groupSize;
                if (copyLen > output.length - outCur) {
                    return false;
                }
                int srcStart = outCur - offset;
                for (int i = 0; i < copyLen; i++) {
                    output[outCur + i] = output[srcStart + i];
                }
                outCur += copyLen;
            } else {
                if (!state.readBytesDirect(output, outCur, groupSize)) {
                    return false;
                }
                outCur += groupSize;
            }
        }
        return true;
    }

    /** Huffman-coded LZSS variant introduced in Metroid Prime 4 (compression
     *  modes 12/13/14). */
    public static boolean decompressHuffman(int m, byte[] input, int inputOffset, byte[] output) {
        int groupSize = 1 << m;
        BitReader state = new BitReader(input, inputOffset);
        int[] outCur = {0};
        while (outCur[0] < output.length) {
            int isBackref = state.readBit();
            if (isBackref < 0) {
                return false;
            }
            if (isBackref == 1) {
                if (!state.decodeBackref(m, output, outCur)) {
                    return false;
                }
            } else {
                // NOTE: mirrors the Rust source's branch on bitsRemaining here
                // (bitwise literal read while mid-byte, direct read once
                // byte-aligned) -- an implementation detail of the bitstream,
                // not something this port second-guesses.
                if (bitsRemainingNonZero(state)) {
                    for (int i = 0; i < groupSize; i++) {
                        int b = state.readByteBitwise();
                        if (b < 0) {
                            return false;
                        }
                        output[outCur[0] + i] = (byte) b;
                    }
                    outCur[0] += groupSize;
                } else {
                    if (!state.readBytesDirect(output, outCur[0], groupSize)) {
                        return false;
                    }
                    outCur[0] += groupSize;
                }
            }
        }
        return true;
    }

    private static boolean bitsRemainingNonZero(BitReader state) {
        return state.hasPendingBits();
    }
}
