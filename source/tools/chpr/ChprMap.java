import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.mocktroid.format.*;
import com.mocktroid.format.chpr.*;
import com.mocktroid.format.pack.*;
import com.mocktroid.library.*;
import com.mocktroid.model.*;

/** Maps a CHPR's bytes into known regions and reports what's left. args: romfs chprGuid [dumpPath] */
public class ChprMap {
    static byte[] d;
    static String[] label;
    static List<String> strings;

    public static void main(String[] a) throws Exception {
        AssetLibrary lib = RomfsLibrary.scan(Path.of(a[0]));
        PakAssetRef ref = lib.lookup(UUID.fromString(a[1]));
        d = PackCatalog.extractDecompressed(ref.pakFile(), ref.asset());
        if (a.length > 2) Files.write(Path.of(a[2]), d);
        label = new String[d.length];
        System.out.println("CHPR " + a[1] + " " + d.length + " bytes, pak " + ref.pakFile().getFileName());
        StringBuilder hx = new StringBuilder();
        for (int i = 0; i < 0x2C; i++) hx.append(String.format("%02x", d[i])).append(i % 4 == 3 ? " " : "");
        System.out.println("header: " + hx + "  ascii " + new String(d, 0, 4, StandardCharsets.ISO_8859_1) + " / " + new String(d, 20, 4, StandardCharsets.ISO_8859_1));
        mark(0, 0x2C, "RFRM header");
        // triples
        int o = 0x2C; int triples = 0;
        while (o + 12 <= d.length) {
            long idx = LE.u32(d, o), off = LE.u32(d, o + 4), size = LE.u32(d, o + 8);
            if (off > 0x30 && off < d.length && off + size <= d.length && idx < 0x100000) {
                System.out.println("  segment/blob idx " + idx + " @" + Long.toHexString(off) + " size " + size);
                mark(o, 12, "segment triple"); o += 12; triples++;
            } else break;
        }
        long n = LE.u32(d, o);
        strings = List.of(new String(d, o + 4, (int) n, StandardCharsets.ISO_8859_1).split("\0", -1));
        mark(o, (int) (4 + n), "string table");
        int strEnd = (int) (o + 4 + n);
        System.out.println("strings: " + strings.size() + " (" + n + " bytes), first: " + strings.subList(0, Math.min(8, strings.size())));

        // name lists: hash arrays
        Map<Integer, Integer> hashes = new HashMap<>();
        for (int i = 0; i < strings.size(); i++) hashes.putIfAbsent(fnv(strings.get(i)), i);
        List<String> lists = new ArrayList<>();
        o = strEnd;
        while (o < d.length - 8) {
            if (!hashes.containsKey(LE.i32(d, o))) { o++; continue; }
            int k = 0;
            while (o + 4 * k < d.length - 4 && hashes.containsKey(LE.i32(d, o + 4 * k))) k++;
            if (k >= 2 && o + 8 * k <= d.length) {
                boolean ok = true; List<String> names = new ArrayList<>();
                for (int i = 0; i < k; i++) { long idx = LE.u32(d, o + 4 * k + 4 * i); if (idx >= strings.size()) { ok = false; break; } names.add(strings.get((int) idx)); }
                if (ok) {
                    mark(o, 8 * k, "name list (" + k + ")");
                    lists.add(String.format("@%x n=%d %s%s", o, k, names.subList(0, Math.min(4, k)), k > 4 ? "..." : ""));
                    o += 8 * k; continue;
                }
            }
            o += 4 * Math.max(k, 1);
        }
        System.out.println("name lists: " + lists.size());
        lists.forEach(s -> System.out.println("  " + s));

        // node table(s): runs of 4-byte records with kind 0x60/0x61/0x62
        for (int i = strEnd; i + 8 <= d.length; i++) {
            if ((d[i + 2] & 0xFF) == 0x62 && (d[i + 4] & 0xFF) == 0x80 && d[i + 5] == 1 && (d[i + 6] & 0xFF) == 0x62 && label[i] == null) {
                int k = 0;
                while (i + 4 * k + 4 <= d.length && ((d[i + 4 * k + 2] & 0xFF) >= 0x60 && (d[i + 4 * k + 2] & 0xFF) <= 0x62)) k++;
                mark(i, 4 * k, "node table (" + k + ")");
                Map<Integer, Integer> kinds = new TreeMap<>(); Map<Integer, Integer> flags = new TreeMap<>();
                for (int q = 0; q < k; q++) { kinds.merge(d[i + 4 * q + 2] & 0xFF, 1, Integer::sum); flags.merge(d[i + 4 * q + 3] & 0xFF, 1, Integer::sum); }
                System.out.printf("node table @%x: %d records, kinds %s, flag bytes %s%n", i, k, hex(kinds), hex(flags));
                i += 4 * k;
            }
        }

        // transforms: runs of unit quaternion records
        for (int i = strEnd; i + 28 * 8 <= d.length; ) {
            int k = 0;
            while (i + 28 * (k + 1) <= d.length && validTransform(i + 28 * k)) k++;
            if (k >= 8 && free(i, 28 * k)) { mark(i, 28 * k, "transform table (" + k + ")"); System.out.printf("transforms @%x: %d records%n", i, k); i += 28 * k; }
            else i++;
        }
        // 3x4 matrices runs (inverse binds)
        for (int i = strEnd; i + 48 * 2 <= d.length; ) {
            int k = 0;
            while (i + 48 * (k + 1) <= d.length && orthoMatrix(i + 48 * k)) k++;
            if (k >= 2 && free(i, 48 * k)) { mark(i, 48 * k, "inverse bind matrices (" + k + ")"); System.out.printf("matrices @%x: %d%n", i, k); i += 48 * k; }
            else i++;
        }
        // constant pools
        for (int i = 0; i + 26 <= d.length; i++) {
            if (d[i] != 1 || d[i + 1] != 0 || d[i + 2] != 0 || d[i + 3] != 0 || d[i + 4] != 0 || d[i + 5] != 1 || d[i + 7] != 0 || d[i + 8] != 0 || d[i + 9] != 0 || d[i + 10] != 0 || d[i + 11] != 0) continue;
            int pn = d[i + 6] & 0xFF; int q = i + 10; boolean ok = pn > 0 && pn <= 16; List<Integer> sizes = new ArrayList<>();
            for (int k = 0; k < pn && ok; k++) { if (LE.u16(d, q) != k) { ok = false; break; } int c = LE.u16(d, q + 2); if (LE.f32(d, q + 4) != 1f) { ok = false; break; } sizes.add(c); q += 4 + 4 * c; }
            if (ok) { mark(i, q - i, "constant pools"); System.out.printf("constant pools @%x: %d pools, floats %s%n", i, pn, sizes); }
        }
        // clips
        int clips = 0; long clipBytes = 0;
        for (AnimClip.Located l : AnimClip.locate(d)) { mark(l.offset() - 10, 10, "clip header record"); mark(l.offset(), l.size(), "clip payload"); clips++; clipBytes += l.size(); }
        System.out.println("clip payloads: " + clips + ", " + clipBytes + " bytes");
        // clip list records (the 0x23-byte headers ChprInfo reads)
        ChprInfo info = ChprInfo.read(d);
        for (ChprInfo.Clip c : info.clips()) if (label[c.offset()] == null) mark(c.offset(), 0x23, "clip list record");
        System.out.println("clip list entries: " + info.clips().size());

        // GUID references
        Map<String, Integer> guidTypes = new TreeMap<>(); int guids = 0;
        for (int i = 0; i + 16 <= d.length; i++) {
            if (label[i] != null) continue;
            UUID u = GuidUtil.fromBytesLe(d, i);
            PakAssetRef r = lib.lookup(u);
            if (r != null) { guidTypes.merge(r.asset().assetType().toString(), 1, Integer::sum); mark(i, 16, "GUID " + r.asset().assetType()); guids++; i += 15; }
        }
        System.out.println("GUID refs: " + guids + " " + guidTypes);

        // summary
        Map<String, Long> bytesBy = new TreeMap<>();
        long unknown = 0;
        for (String s : label) { if (s == null) unknown++; else bytesBy.merge(s.replaceAll(" \\(.*", ""), 1L, Long::sum); }
        System.out.println("--- coverage ---");
        bytesBy.forEach((k, v) -> System.out.printf("  %-28s %10d bytes %5.1f%%%n", k, v, 100.0 * v / d.length));
        System.out.printf("  %-28s %10d bytes %5.1f%%%n", "UNKNOWN", unknown, 100.0 * unknown / d.length);
        // gaps
        System.out.println("--- largest unknown gaps ---");
        List<int[]> gaps = new ArrayList<>();
        for (int i = 0; i < d.length; ) { if (label[i] != null) { i++; continue; } int j = i; while (j < d.length && label[j] == null) j++; gaps.add(new int[]{i, j - i}); i = j; }
        gaps.sort((x, y) -> y[1] - x[1]);
        for (int[] g : gaps.subList(0, Math.min(14, gaps.size()))) {
            String before = before(g[0]);
            System.out.printf("  @%x len %d after [%s]: %s | %s%n", g[0], g[1], before, hexAt(g[0], 24), classify(g[0], g[1]));
        }
        System.out.println("gaps total " + gaps.size());
    }

    static String before(int at) { for (int i = at - 1; i >= 0; i--) if (label[i] != null) return label[i]; return "start"; }
    static String classify(int o, int len) {
        int floats = 0, zeros = 0, n = Math.min(len / 4, 4096);
        for (int i = 0; i < n; i++) { float f = LE.f32(d, o + 4 * i); int v = LE.i32(d, o + 4 * i); if (v == 0) zeros++; else if (Float.isFinite(f) && Math.abs(f) > 1e-4 && Math.abs(f) < 1e4) floats++; }
        int strIdx = 0; for (int i = 0; i < n; i++) { long v = LE.u32(d, o + 4 * i); if (v > 0 && v < strings.size()) strIdx++; }
        return String.format("aligned floats %d%%, zero words %d%%, small ints<strings %d%%", 100 * floats / Math.max(1, n), 100 * zeros / Math.max(1, n), 100 * strIdx / Math.max(1, n));
    }
    static String hexAt(int o, int n) { StringBuilder sb = new StringBuilder(); for (int i = o; i < Math.min(d.length, o + n); i++) sb.append(String.format("%02x", d[i])); return sb.toString(); }
    static String hex(Map<Integer, Integer> m) { StringBuilder sb = new StringBuilder("{"); m.forEach((k, v) -> sb.append(String.format("%02x:%d ", k, v))); return sb.append("}").toString(); }
    static boolean free(int o, int len) { for (int i = o; i < o + len; i++) if (label[i] != null) return false; return true; }
    static void mark(int o, int len, String what) { for (int i = Math.max(0, o); i < Math.min(d.length, o + len); i++) if (label[i] == null) label[i] = what; }
    static int fnv(String s) { int h = 0x811C9DC5; for (byte b : s.getBytes(StandardCharsets.ISO_8859_1)) h = (h ^ (b & 0xFF)) * 0x01000193; return h; }
    static boolean validTransform(int p) {
        double qq = 0, t = 0;
        for (int k = 0; k < 7; k++) { float f = LE.f32(d, p + 4 * k); if (!Float.isFinite(f)) return false; if (k < 4) qq += f * f; else t = Math.max(t, Math.abs(f)); }
        return Math.abs(qq - 1) < 2e-3 && t < 1e3;
    }
    static boolean orthoMatrix(int p) {
        double[] m = new double[12];
        for (int k = 0; k < 12; k++) { float f = LE.f32(d, p + 4 * k); if (!Float.isFinite(f)) return false; m[k] = f; }
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) { double dot = m[r * 4] * m[c * 4] + m[r * 4 + 1] * m[c * 4 + 1] + m[r * 4 + 2] * m[c * 4 + 2]; if (Math.abs(dot - (r == c ? 1 : 0)) > 2e-3) return false; }
        return true;
    }
}
