package com.mocktroid.format.audio;

import com.mocktroid.format.ChunkDescriptor;
import com.mocktroid.format.FormDescriptor;
import com.mocktroid.format.LE;

/**
 * Reads a CSMP's length straight from its header, without decoding -- enough
 * to sort ~9,500 sounds by duration in a couple of seconds.
 * <p>
 * Layout (RFRM "CSMP"): an FMTA chunk, an optional CRMS chunk, then DATA,
 * whose body starts with one standard Nintendo DSP-ADPCM header per channel:
 * {@code u32 sample count, u32 nibble count, u32 sample rate, u16 loop flag,
 * u16 format, u32 loop start nibble, u32 loop end nibble, ...}. The sample
 * count is taken from the nibble count (8-byte frames = 2 header nibbles +
 * 14 samples) because that's what vgmstream plays; the header's own sample
 * field is sometimes shorter.
 */
public final class CsmpDuration {

    private CsmpDuration() {}

    /** Duration in seconds, or -1 if {@code data} (which may be just the
     *  asset's first few KB) doesn't reach a readable DSP header. */
    public static double seconds(byte[] data) {
        try {
            if (data.length < FormDescriptor.HEADER_SIZE || !"RFRM".equals(LE.fourCC(data, 0).toString())
                || !"CSMP".equals(LE.fourCC(data, 20).toString())) {
                return -1;
            }
            int offset = FormDescriptor.HEADER_SIZE;
            while (offset + ChunkDescriptor.HEADER_SIZE <= data.length) {
                ChunkDescriptor chunk = ChunkDescriptor.read(data, offset);
                if ("DATA".equals(chunk.id().toString())) {
                    int h = chunk.bodyStart();
                    if (h + 12 > data.length) {
                        return -1;
                    }
                    long nibbles = LE.u32(data, h + 4);
                    long rate = LE.u32(data, h + 8);
                    if (rate < 1000 || rate > 192000) {
                        return -1;
                    }
                    long samples = nibbles / 16 * 14 + Math.max(0, nibbles % 16 - 2);
                    return (double) samples / rate;
                }
                offset = chunk.nextOffset();
            }
        } catch (RuntimeException e) {
            // Truncated prefix or an unexpected layout -- caller falls back.
        }
        return -1;
    }
}
