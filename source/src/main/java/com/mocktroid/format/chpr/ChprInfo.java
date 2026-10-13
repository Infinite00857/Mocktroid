package com.mocktroid.format.chpr;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.mocktroid.format.LE;

/**
 * What Mocktroid reads from a CHPR (character project) so far: its string
 * table and its animation clip list. Layout per mp4x's FORMATS.md plus our
 * own findings:
 * <pre>
 *   RFRM header with size 0 (custom body), u32 0, u32 x2,
 *   {u32 index, u32 offset, u32 size} side-blob triples,
 *   u32 string table size, NUL-separated strings
 * </pre>
 * The string table starts with "Primary", then the character's skin names in
 * the same order as the SMDL models the CHPR references by GUID (Samus:
 * default, psychic, psychic_glove, violasuit, ...). Large multi-segment
 * projects use a different header, so the table is also found by content.
 * <p>
 * <b>Clips.</b> Each animation clip starts with a record:
 * <pre>
 *   +0  u16 0      +2  u16 clip index   +4  00 00 00   +7  01 01
 *   +9  u32 name   (string table index)  +13 ff ff ff ff
 *   +0x1d f32 1.0  +0x21 u16 frame count, then the compressed key data
 * </pre>
 * Small projects store each clip as a side blob; big ones also keep clips
 * inline in the body, so records are found by that signature. The key data
 * itself (NAnimComp / NAnimStream fragments in the game's code) isn't decoded
 * yet -- see roadmap.md.
 */
public final class ChprInfo {

    /** One animation clip: its name and length in frames. */
    public record Clip(int index, String name, int frames, int offset) {}

    private final List<String> strings;
    private final List<Clip> clips;

    private ChprInfo(List<String> strings, List<Clip> clips) {
        this.strings = strings;
        this.clips = clips;
    }

    public List<String> strings() { return strings; }
    public List<Clip> clips() { return clips; }

    /** Skin names: the strings after "Primary", in the order the CHPR references its SMDLs. */
    public List<String> skinNames(int count) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= count && i < strings.size(); i++) {
            out.add(strings.get(i));
        }
        return out;
    }

    public static ChprInfo read(byte[] d) {
        List<String> strings = readStrings(d);
        return new ChprInfo(strings, readClips(d, strings));
    }

    /** Only the string table -- cheap enough for name indexing. */
    public static List<String> readStrings(byte[] d) {
        int o = 0x2C;
        while (o + 12 <= d.length) {
            long index = LE.u32(d, o);
            long off = LE.u32(d, o + 4);
            long size = LE.u32(d, o + 8);
            if (off > 0x30 && off < d.length && off + size <= d.length && index < 0x100000) {
                o += 12;
            } else {
                break;
            }
        }
        List<String> s = tableAt(d, o + 4, o + 4 <= d.length ? LE.u32(d, o) : -1);
        if (!s.isEmpty() && s.get(0).equals("Primary")) {
            return s;
        }
        byte[] needle = "Primary\0".getBytes(StandardCharsets.ISO_8859_1);
        for (int i = indexOf(d, needle, 4); i >= 4; i = indexOf(d, needle, i + 1)) {
            List<String> t = tableAt(d, i, LE.u32(d, i - 4));
            if (!t.isEmpty()) {
                return t;
            }
        }
        return List.of();
    }

    private static List<String> tableAt(byte[] d, int start, long size) {
        if (size <= 0 || start + size > d.length || d[(int) (start + size - 1)] != 0) {
            return List.of();
        }
        String all = new String(d, start, (int) size - 1, StandardCharsets.ISO_8859_1);
        return List.of(all.split("\0", -1));
    }

    private static List<Clip> readClips(byte[] d, List<String> strings) {
        Map<Integer, Clip> found = new TreeMap<>();
        byte[] sig = {0, 0, 0, 1, 1};
        for (int i = indexOf(d, sig, 4); i >= 0; i = indexOf(d, sig, i + 1)) {
            int o = i - 4;
            if (o < 0 || o + 0x23 > d.length || d[o] != 0 || d[o + 1] != 0) {
                continue;
            }
            if ((d[o + 13] & d[o + 14] & d[o + 15] & d[o + 16]) != (byte) 0xFF) {
                continue;
            }
            long name = LE.u32(d, o + 9);
            if (name >= strings.size()) {
                continue;
            }
            int index = LE.u16(d, o + 2);
            found.putIfAbsent(index, new Clip(index, strings.get((int) name), LE.u16(d, o + 0x21), o));
        }
        return new ArrayList<>(found.values());
    }

    private static int indexOf(byte[] d, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= d.length - needle.length; i++) {
            for (int k = 0; k < needle.length; k++) {
                if (d[i + k] != needle[k]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
