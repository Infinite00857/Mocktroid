package com.mocktroid.format.compress;

/**
 * Bit-level cursor over a compressed byte buffer, ported method-for-method
 * from retrotool's {@code DecompressionState} (lzss.rs). A couple of these
 * operations look surprising in isolation -- notably {@link #readByteBitwise}
 * returning the *old* flagByte rather than the freshly-read byte when
 * already byte-aligned -- but this is a faithful port of logic that's
 * verified against real files by the wider decomp community, not a
 * reinterpretation, so those quirks are preserved deliberately rather than
 * "fixed".
 */
final class BitReader {
    private final byte[] input;
    private int pos;
    private int flagByte;
    private int bitsRemaining;

    BitReader(byte[] input, int offset) {
        this.input = input;
        this.pos = offset;
    }

    /** True if there are unconsumed bits left in the current flagByte
     *  (i.e. the cursor is mid-byte, not aligned to a byte boundary). */
    boolean hasPendingBits() {
        return bitsRemaining != 0;
    }

    private int readByteRaw() {
        if (pos < input.length) {
            return input[pos++] & 0xFF;
        }
        return -1;
    }

    /** Reads `count` bytes straight from the input into output[pos..pos+count),
     *  bypassing the bit cursor entirely (used for byte-aligned reads). */
    boolean readBytesDirect(byte[] output, int pos, int count) {
        if (input.length - this.pos >= count && output.length >= pos + count) {
            System.arraycopy(input, this.pos, output, pos, count);
            this.pos += count;
            return true;
        }
        return false;
    }

    /** Returns 0 or 1, or -1 on end-of-input (Rust's Option<bool>::None). */
    int readBit() {
        if (bitsRemaining == 0) {
            int b = readByteRaw();
            if (b < 0) {
                return -1;
            }
            flagByte = b;
            bitsRemaining = 7;
        } else {
            bitsRemaining--;
        }
        return (flagByte >> bitsRemaining) & 1;
    }

    /** Returns 0-255, or -1 on end-of-input. */
    int readByteBitwise() {
        int nextByte = readByteRaw();
        if (nextByte < 0) {
            return -1;
        }
        int result;
        if (bitsRemaining == 0) {
            result = flagByte;
        } else {
            int bitsFromCur = 8 - bitsRemaining;
            int mask = (~(0xFF << bitsFromCur)) & 0xFF;
            result = ((flagByte << bitsFromCur) | ((nextByte >> bitsRemaining) & mask)) & 0xFF;
        }
        flagByte = nextByte;
        return result;
    }

    /** Returns the decoded symbol (0-255), or -1 on end-of-input / a
     *  malformed stream (table walked off the end without resolving). */
    int decodeHuffman(HuffmanEntry[] table) {
        int idx = 0;
        int bitsRead = 0;
        int codeValue = 0;
        while (idx < table.length) {
            HuffmanEntry entry = table[idx];
            int targetBits = entry.bitCount();
            if (targetBits > bitsRead) {
                int remainingBits = targetBits - bitsRead;
                while (remainingBits >= 8) {
                    int b = readByteBitwise();
                    if (b < 0) {
                        return -1;
                    }
                    codeValue = (codeValue << 8) | b;
                    remainingBits -= 8;
                }
                while (remainingBits > 0) {
                    int bit = readBit();
                    if (bit < 0) {
                        return -1;
                    }
                    codeValue = (codeValue << 1) | bit;
                    remainingBits--;
                }
                bitsRead = targetBits;
            }
            int nextIdx = idx + entry.nextOffset();
            if (nextIdx - 1 < 0 || nextIdx - 1 >= table.length) {
                return -1;
            }
            long threshold = table[nextIdx - 1].threshold();
            if (codeValue <= threshold) {
                int symbolIdx = (int) (codeValue + nextIdx - 1 - threshold);
                if (symbolIdx < 0 || symbolIdx >= table.length) {
                    return -1;
                }
                return table[symbolIdx].symbol() & 0xFF;
            }
            idx = nextIdx;
        }
        return -1;
    }

    /** Decodes one Huffman-coded back-reference and copies it into output,
     *  advancing outCur[0]. Returns false on failure. */
    boolean decodeBackref(int m, byte[] output, int[] outCur) {
        int lengthCode = decodeHuffman(HuffmanTables.HUFFMAN_LENGTH);
        if (lengthCode < 0) return false;
        int offsetLo = decodeHuffman(HuffmanTables.HUFFMAN_OFFSET_LO);
        if (offsetLo < 0) return false;
        int offsetHi = decodeHuffman(HuffmanTables.HUFFMAN_OFFSET_HI);
        if (offsetHi < 0) return false;

        int offset = (offsetHi << (8 + m)) | (offsetLo << m);
        int cur = outCur[0];
        if (offset > cur) {
            return false;
        }
        int copyLen = (lengthCode + (3 - m)) * (1 << m);
        if (copyLen > output.length - cur) {
            return false;
        }
        for (int i = 0; i < copyLen; i++) {
            output[cur + i] = output[cur - offset + i];
        }
        outCur[0] = cur + copyLen;
        return true;
    }
}
