package com.mocktroid.format.chpr;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mocktroid.format.LE;

/**
 * A CHPR's skeleton: joint names, hierarchy, local bind transforms and skin
 * palettes (joint per vertex palette slot + inverse bind matrices).
 * <p>
 * Layout as documented by mp4x ({@code E:\mp4x\FORMATS.md}, {@code chpr.py}).
 * The body is a packed serialization with no byte-aligned field table, so
 * each part is located by a signature scan:
 * <ul>
 *   <li><b>Name lists</b>: an FNV-1a 32 hash array followed by a string-index
 *       array of the same length. The node list starts with
 *       {@code root.anchorBlend} and holds joints and float channels.</li>
 *   <li><b>Node table</b>: one 4-byte record per node: {@code u16 (field
 *       offset << 7 | bank)}, {@code u8 kind} (0x62 joint, 0x60/0x61 channel),
 *       {@code u8 flags}. Joint index = 170 * bank + offset / 3.</li>
 *   <li><b>Hierarchy</b>: {@code 03 bank parentBank} selects banks;
 *       {@code 04/05 u16 self*6, u16 parent*6, u16 c} (07 adds a u16). For 04,
 *       {@code c = slot | row * 5 << 6} and the transforms are stored slot by slot:
 *       local transform index = rows in lower slots + row (mp4x's
 *       {@code (c >> 6) / 5} is the single-slot case -- verified against the inverse
 *       bind matrices on Sylux, whose joints span slots 5-8); {@code c / 7} otherwise.</li>
 *   <li><b>Transforms</b>: float32 (qw, qx, qy, qz, tx, ty, tz) records.</li>
 *   <li><b>Skins</b>: {@code 01, u8 count, 00, ...}; 16-bit palette entries
 *       103 bits after the count (low 9 bits joint field offset, high bits the
 *       next entry's bank), then count + 1 row-major 3x4 inverse bind matrices.</li>
 * </ul>
 * Matrices are row-major {@code double[16]}.
 */
public final class ChprSkeleton {

    private static final int BANK = 170;

    public record Skin(int offset, int[] palette, List<double[]> inverseBind) {}

    private final byte[] d;
    private List<String> strings = List.of();
    private int strEnd;
    private final List<String> nodes = new ArrayList<>();
    private final List<String> joints = new ArrayList<>();
    private final Map<Integer, Integer> parent = new HashMap<>();
    private final Map<Integer, Integer> localIndex = new HashMap<>();
    private final Map<Integer, Integer> poseAddress = new HashMap<>();
    private float[] transforms = new float[0];
    private final List<Skin> skins = new ArrayList<>();

    private ChprSkeleton(byte[] data) {
        this.d = data;
    }

    public static ChprSkeleton read(byte[] data) {
        ChprSkeleton s = new ChprSkeleton(data);
        s.readStrings();
        if (s.strings.isEmpty()) {
            return s;
        }
        s.readNames();
        s.readHierarchy();
        s.readTransforms();
        s.readSkins();
        s.assignLocalIndices();
        return s;
    }

    public List<String> joints() { return joints; }
    public List<String> nodes() { return nodes; }
    public List<Skin> skins() { return skins; }
    /** Parent joint, or -1 for a root / unknown. */
    public int parentOf(int joint) { return parent.getOrDefault(joint, -1); }
    public int transformCount() { return transforms.length / 7; }

    /**
     * The joint's pose address ({@code slot | index << 6}, the hierarchy
     * record's last field): where animation clips write this joint's
     * transform ({@link AnimClip.Pose#transforms}), or -1 if unknown.
     */
    public int poseAddress(int joint) { return poseAddress.getOrDefault(joint, -1); }

    /** The skin whose palette matches a model's skin joint count (SKHD), or null. */
    public Skin skinFor(int jointCount) {
        for (Skin s : skins) {
            if (s.palette().length == jointCount) {
                return s;
            }
        }
        return skins.isEmpty() ? null : skins.get(0);
    }

    // ---- parsing ---------------------------------------------------------------

    private void readStrings() {
        int o = 0x2C;
        while (o + 12 <= d.length) {
            long index = LE.u32(d, o), off = LE.u32(d, o + 4), size = LE.u32(d, o + 8);
            if (off > 0x30 && off < d.length && off + size <= d.length && index < 0x100000) {
                o += 12;
            } else {
                break;
            }
        }
        if (o + 4 > d.length) {
            return;
        }
        long n = LE.u32(d, o);
        if (n <= 0 || o + 4 + n > d.length) {
            return;
        }
        strEnd = (int) (o + 4 + n);
        strings = List.of(new String(d, o + 4, (int) n, StandardCharsets.ISO_8859_1).split("\0", -1));
    }

    static int fnv1a(String s) {
        int h = 0x811C9DC5;
        for (byte b : s.getBytes(StandardCharsets.ISO_8859_1)) {
            h = (h ^ (b & 0xFF)) * 0x01000193;
        }
        return h;
    }

    private void readNames() {
        Map<Integer, Integer> hashes = new HashMap<>();
        for (int i = 0; i < strings.size(); i++) {
            hashes.putIfAbsent(fnv1a(strings.get(i)), i);
        }
        List<String> best = List.of();
        int o = strEnd, end = d.length - 8;
        while (o < end) {
            if (!hashes.containsKey(LE.i32(d, o))) {
                o++;
                continue;
            }
            int n = 0;
            while (o + 4 * n < end && hashes.containsKey(LE.i32(d, o + 4 * n))) {
                n++;
            }
            if (n >= 3 && o + 8 * n <= d.length) {
                List<String> names = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    long idx = LE.u32(d, o + 4 * n + 4 * i);
                    if (idx >= strings.size()) {
                        names = null;
                        break;
                    }
                    names.add(strings.get((int) idx));
                }
                if (names != null) {
                    if (names.get(0).equals("root.anchorBlend") && names.size() > best.size()) {
                        best = names;
                    }
                    o += 8 * n;
                    continue;
                }
            }
            o += 4 * Math.max(n, 1);
        }
        nodes.addAll(best);
        if (nodes.isEmpty()) {
            return;
        }
        int t = -1;
        for (int i = indexOf(new byte[] {0, 0, 0x62}, strEnd); i >= 0; i = indexOf(new byte[] {0, 0, 0x62}, i + 1)) {
            if (i + 7 <= d.length && (d[i + 4] & 0xFF) == 0x80 && d[i + 5] == 1 && d[i + 6] == 0x62) {
                t = i;
                break;
            }
        }
        if (t < 0) {
            joints.addAll(nodes);
            return;
        }
        Map<Integer, String> byIndex = new HashMap<>();
        int max = -1;
        for (int i = 0; i < nodes.size() && t + 4 * i + 3 <= d.length; i++) {
            int v = LE.u16(d, t + 4 * i);
            int kind = d[t + 4 * i + 2] & 0xFF;
            if (kind == 0x62) {
                int j = BANK * (v & 0x7F) + (v >> 7) / 3;
                byIndex.put(j, nodes.get(i));
                max = Math.max(max, j);
            } else if (kind != 0x60 && kind != 0x61) {
                break;
            }
        }
        for (int j = 0; j <= max; j++) {
            joints.add(byIndex.getOrDefault(j, "joint" + j));
        }
    }

    private boolean isRec(int p) {
        int t = d[p] & 0xFF;
        return t == 4 || t == 5 || t == 7;
    }

    private void readHierarchy() {
        int start = -1;
        for (int o = strEnd; o < d.length - 32; o++) {
            if (isRec(o)) {
                int a = LE.u16(d, o + 1);
                if (a % 6 == 0 && a >= 6 && a <= 6 * 32 && run(o, a / 6) >= 20) {
                    start = o;
                    break;
                }
            }
        }
        if (start < 0) {
            return;
        }
        int q = start, sb = 0, pb = 0;
        int next = LE.u16(d, q + 1) / 6;
        while (q < d.length - 9) {
            if (d[q] == 3 && (d[q + 1] & 0xFF) < 16 && (d[q + 2] & 0xFF) < 16) {
                if ((d[q + 1] & 0xFF) != sb) {
                    next = 0;
                }
                sb = d[q + 1] & 0xFF;
                pb = d[q + 2] & 0xFF;
                q += 3;
                continue;
            }
            int hit = -1;
            for (int k = 0; k < 4; k++) {
                if (isRec(q + k) && LE.u16(d, q + k + 1) == 6 * next) {
                    hit = q + k;
                    break;
                }
            }
            if (hit < 0) {
                // Other record kinds (e.g. 0x0d) can interrupt a run; resume at the next
                // record that continues the numbering and is itself followed by a record.
                hit = resync(q, next);
                if (hit < 0) {
                    break;
                }
                next = LE.u16(d, hit + 1) / 6;
            }
            q = hit;
            int type = d[q] & 0xFF;
            int a = LE.u16(d, q + 1), b = LE.u16(d, q + 3), c = LE.u16(d, q + 5);
            int j = BANK * sb + a / 6;
            if (b % 6 == 0) {
                parent.put(j, BANK * pb + b / 6);
            }
            if (type == 4) {
                slotRows.put(j, new int[] {c & 63, (c >> 6) / 5});
            } else {
                localIndex.put(j, c / 7);
            }
            poseAddress.put(j, c);
            q += type == 7 ? 9 : 7;
            next++;
        }
    }

    /**
     * Type-04 transform indices: slot-major, each slot's rows after all lower slots' rows. Some
     * rows belong to joints whose records aren't parsed, so each slot's start is calibrated where
     * possible: skinned joints with a skinned parent have an exact local transform (from the
     * inverse bind matrices), and the table row it matches gives the slot's offset by vote.
     * Uncalibrated slots continue from the slot below.
     */
    private void assignLocalIndices() {
        int[] rows = new int[64];
        for (int[] sr : slotRows.values()) {
            rows[sr[0]] = Math.max(rows[sr[0]], sr[1] + 1);
        }
        Map<Integer, double[]> world = new HashMap<>();
        for (Skin skin : skins) {
            for (int i = 0; i < skin.palette().length && i < skin.inverseBind().size(); i++) {
                world.putIfAbsent(skin.palette()[i], invertAffine(skin.inverseBind().get(i)));
            }
        }
        List<Map<Integer, Integer>> votes = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            votes.add(new HashMap<>());
        }
        int n = transformCount();
        for (Map.Entry<Integer, int[]> e : slotRows.entrySet()) {
            int j = e.getKey(), p = parentOf(j);
            if (!world.containsKey(j) || !world.containsKey(p)) {
                continue;
            }
            double[] local = mul(invertAffine(world.get(p)), world.get(j));
            int match = -1;
            for (int r = 0; r < n; r++) {
                int o = r * 7;
                double[] m = quatMat(transforms[o], transforms[o + 1], transforms[o + 2], transforms[o + 3],
                    transforms[o + 4], transforms[o + 5], transforms[o + 6]);
                if (maxDiff(m, local) < 1e-3) {
                    if (match >= 0) {
                        match = -2; // ambiguous (e.g. several identity rows)
                        break;
                    }
                    match = r;
                }
            }
            if (match >= 0) {
                votes.get(e.getValue()[0]).merge(match - e.getValue()[1], 1, Integer::sum);
            }
        }
        int[] base = new int[64];
        boolean[] calibrated = new boolean[64];
        int nextBase = 0;
        for (int slot = 0; slot < 64; slot++) {
            Integer voted = votes.get(slot).entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            base[slot] = voted != null ? voted : nextBase;
            calibrated[slot] = voted != null;
            nextBase = base[slot] + rows[slot];
        }
        slotRows.forEach((j, sr) -> localIndex.put(j, base[sr[0]] + sr[1]));
        // Slots with no skinned joint of their own: when identity locals rebuild the skinned joints'
        // bind poses through them better than the guessed rows, they have no table rows at all (the
        // first-person rig's root / arm-base joints).
        if (world.isEmpty()) {
            return;
        }
        // Every combination of "guessed rows" / "identity" over those slots is tried (there are only
        // a handful), scored against all skinned joints: one slot's choice depends on the others'.
        // Only slots holding an ancestor of a skinned joint matter.
        java.util.Set<Integer> ancestors = new java.util.HashSet<>();
        for (int j : world.keySet()) {
            for (int q = parentOf(j), d = 0; q >= 0 && d < 256; q = parentOf(q), d++) {
                ancestors.add(q);
            }
        }
        List<Integer> open = new ArrayList<>();
        for (int slot = 0; slot < 64; slot++) {
            if (calibrated[slot] || rows[slot] == 0) {
                continue;
            }
            int sl = slot;
            if (slotRows.entrySet().stream().anyMatch(e -> e.getValue()[0] == sl && ancestors.contains(e.getKey()))) {
                open.add(slot);
            }
        }
        if (open.isEmpty() || open.size() > 12) {
            return;
        }
        List<Integer> skinned = new ArrayList<>(world.keySet());
        int identityRow = identityRow();
        int bestMask = 0;
        double bestErr = Double.MAX_VALUE;
        for (int mask = 0; mask < 1 << open.size(); mask++) {
            applySlotChoice(open, mask, base, identityRow);
            double err = chainError(skinned, world);
            if (err < bestErr - 1e-6) {
                bestErr = err;
                bestMask = mask;
            }
        }
        applySlotChoice(open, bestMask, base, identityRow);
    }

    /** Bit i of {@code mask} set: slot open[i] uses identity locals, else its guessed rows. */
    private void applySlotChoice(List<Integer> open, int mask, int[] base, int identityRow) {
        for (Map.Entry<Integer, int[]> e : slotRows.entrySet()) {
            int i = open.indexOf(e.getValue()[0]);
            if (i >= 0) {
                localIndex.put(e.getKey(), (mask >> i & 1) != 0 ? identityRow : base[e.getValue()[0]] + e.getValue()[1]);
            }
        }
    }

    private int identityRowIndex = -1;

    /** Index of an identity transform appended to the table (created on first use). */
    private int identityRow() {
        if (identityRowIndex < 0) {
            identityRowIndex = transformCount();
            float[] t = java.util.Arrays.copyOf(transforms, transforms.length + 7);
            t[transforms.length] = 1;
            transforms = t;
        }
        return identityRowIndex;
    }

    /** Summed translation error of the chained bind poses of {@code joints} against their known world poses. */
    private double chainError(List<Integer> joints, Map<Integer, double[]> world) {
        double err = 0;
        Map<Integer, double[]> memo = new HashMap<>();
        for (int j : joints) {
            double[] w = chainOnly(j, memo, 0);
            double[] k = world.get(j);
            if (w == null) {
                err += 10;
                continue;
            }
            err += Math.abs(w[3] - k[3]) + Math.abs(w[7] - k[7]) + Math.abs(w[11] - k[11]);
        }
        return err;
    }

    /** World bind pose from locals alone (roots and joints without a local = identity). */
    private double[] chainOnly(int j, Map<Integer, double[]> memo, int depth) {
        double[] m = memo.get(j);
        if (m != null) {
            return m;
        }
        int p = parentOf(j);
        double[] parentWorld = p >= 0 && p != j && depth < 256 ? chainOnly(p, memo, depth + 1) : identity();
        m = mul(parentWorld, local(j));
        memo.put(j, m);
        return m;
    }

    /** Type-04 records' {slot, row}, turned into transform indices once every slot's size is known. */
    private final Map<Integer, int[]> slotRows = new HashMap<>();

    /** A record within 48 bytes whose index is just past {@code next} and that the following record continues. */
    private int resync(int q, int next) {
        for (int p = q; p < q + 48 && p + 18 < d.length; p++) {
            if (!isRec(p)) {
                continue;
            }
            int a = LE.u16(d, p + 1);
            if (a % 6 != 0 || a / 6 <= next || a / 6 > next + 16 || LE.u16(d, p + 3) % 6 != 0) {
                continue;
            }
            int n = p + ((d[p] & 0xFF) == 7 ? 9 : 7);
            if (isRec(n) && LE.u16(d, n + 1) == a + 6) {
                return p;
            }
        }
        return -1;
    }

    private int run(int o, int i) {
        int n = 0;
        while (n < 40) {
            int hit = -1;
            for (int k = 0; k < 4; k++) {
                int p = o + k;
                if (p + 3 <= d.length && isRec(p) && LE.u16(d, p + 1) == 6 * (i + n)) {
                    hit = p;
                    break;
                }
            }
            if (hit < 0) {
                break;
            }
            o = hit + ((d[hit] & 0xFF) == 7 ? 9 : 7);
            n++;
        }
        return n;
    }

    private void readTransforms() {
        int bestN = 0, bestO = 0;
        int o = strEnd;
        while (o < d.length - 28 * 8) {
            int n = 0;
            while (o + 28 * (n + 1) <= d.length && validTransform(o + 28 * n)) {
                n++;
            }
            if (n > bestN) {
                bestN = n;
                bestO = o;
            }
            o += n >= 8 ? 28 * n : 1;
        }
        // Big rigs (the player's: 294 joints) continue the table after a short break -- a few bytes
        // that aren't a transform -- so runs starting within one record of the last one's end join it.
        List<float[]> rows = new ArrayList<>();
        int end = bestO;
        for (int i = 0; i < bestN; i++) {
            rows.add(row(bestO + 28 * i));
        }
        end = bestO + 28 * bestN;
        while (true) {
            int next = -1;
            for (int q = end; q < end + 28 && q + 28 * 8 <= d.length; q++) {
                int n = 0;
                while (n < 8 && validTransform(q + 28 * n)) {
                    n++;
                }
                if (n == 8) {
                    next = q;
                    break;
                }
            }
            if (next < 0) {
                break;
            }
            int n = 0;
            while (next + 28 * (n + 1) <= d.length && validTransform(next + 28 * n)) {
                rows.add(row(next + 28 * n));
                n++;
            }
            end = next + 28 * n;
        }
        transforms = new float[rows.size() * 7];
        for (int i = 0; i < rows.size(); i++) {
            System.arraycopy(rows.get(i), 0, transforms, i * 7, 7);
        }
    }

    private float[] row(int p) {
        float[] r = new float[7];
        for (int k = 0; k < 7; k++) {
            r[k] = LE.f32(d, p + 4 * k);
        }
        return r;
    }

    private boolean validTransform(int p) {
        double qq = 0, tmax = 0;
        for (int k = 0; k < 7; k++) {
            float f = LE.f32(d, p + 4 * k);
            if (!Float.isFinite(f)) {
                return false;
            }
            if (k < 4) {
                qq += (double) f * f;
            } else {
                tmax = Math.max(tmax, Math.abs(f));
            }
        }
        return Math.abs(qq - 1) <= 2e-3 && tmax <= 1e3;
    }

    private void readSkins() {
        for (int c = Math.max(strEnd, 1); c < d.length - 64; c++) {
            if (d[c - 1] != 1 || d[c] == 0 || d[c + 1] != 0 || d[c + 5] != 0 || d[c + 6] != 0
                || d[c + 8] != 0 || d[c + 9] != 0 || d[c + 10] != 0) {
                continue;
            }
            int n = d[c] & 0xFF;
            if (c + 16 + 2 * n + 8 > d.length) {
                continue;
            }
            int[] pal = new int[n];
            int bank = 0;
            boolean ok = true;
            for (int i = 0; i < n; i++) {
                int x = bits(c, 103 + 16 * i, 16);
                if ((x & 511) % 3 != 0) {
                    ok = false;
                    break;
                }
                pal[i] = BANK * bank + (x & 511) / 3;
                bank = x >> 9;
            }
            if (!ok) {
                continue;
            }
            int maxJ = 0;
            for (int j : pal) {
                maxJ = Math.max(maxJ, j);
            }
            if (maxJ >= Math.max(joints.size(), 1)) {
                continue;
            }
            List<double[]> ibm = ibmRun(c + 13 + 2 * n);
            if (ibm == null || ibm.size() < n) {
                continue;
            }
            skins.add(new Skin(c, pal, ibm));
        }
    }

    /** {@code count} bits starting {@code bit} bits into the little-endian bit stream at {@code base}. */
    private int bits(int base, int bit, int count) {
        int v = 0;
        for (int k = 0; k < count; k++) {
            int b = bit + k;
            if ((d[base + (b >> 3)] >> (b & 7) & 1) != 0) {
                v |= 1 << k;
            }
        }
        return v;
    }

    private List<double[]> ibmRun(int o) {
        for (int s = o; s < o + 64; s++) {
            List<double[]> mats = new ArrayList<>();
            int p = s;
            while (p + 48 <= d.length) {
                double[] m = identity();
                for (int k = 0; k < 12; k++) {
                    float f = LE.f32(d, p + 4 * k);
                    if (!Float.isFinite(f)) {
                        m = null;
                        break;
                    }
                    m[k] = f;
                }
                if (m == null || !orthonormal(m)) {
                    break;
                }
                mats.add(m);
                p += 48;
            }
            if (mats.size() >= 2) {
                return mats;
            }
        }
        return null;
    }

    private static boolean orthonormal(double[] m) {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                double dot = m[r * 4] * m[c * 4] + m[r * 4 + 1] * m[c * 4 + 1] + m[r * 4 + 2] * m[c * 4 + 2];
                if (Math.abs(dot - (r == c ? 1 : 0)) > 2e-3) {
                    return false;
                }
            }
        }
        return true;
    }

    private int indexOf(byte[] needle, int from) {
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

    // ---- poses -----------------------------------------------------------------

    /** The joint's local bind transform as {qw, qx, qy, qz, tx, ty, tz}, or null if unknown. */
    public float[] localTRS(int joint) {
        Integer r = localIndex.get(joint);
        if (r == null || r < 0 || r >= transformCount()) {
            return null;
        }
        float[] out = new float[7];
        System.arraycopy(transforms, r * 7, out, 0, 7);
        return out;
    }

    /** The joint's local bind transform (parent space), or identity if unknown. */
    public double[] local(int joint) {
        Integer r = localIndex.get(joint);
        if (r == null || r < 0 || r >= transformCount()) {
            return identity();
        }
        int o = r * 7;
        return quatMat(transforms[o], transforms[o + 1], transforms[o + 2], transforms[o + 3],
            transforms[o + 4], transforms[o + 5], transforms[o + 6]);
    }

    /**
     * World bind matrix per joint: skinned joints straight from their inverse
     * bind matrices (exact), the rest chained from their parent and local
     * transform when the hierarchy reproduces the skinned joints, else their
     * parent's pose (mirrors mp4x's {@code skeleton_worlds}).
     */
    public double[][] bindWorld(Skin skin) {
        int n = joints.size();
        double[][] known = new double[n][];
        if (skin != null) {
            for (int slot = 0; slot < skin.palette().length; slot++) {
                int j = skin.palette()[slot];
                if (j < n && known[j] == null) {
                    known[j] = invertAffine(skin.inverseBind().get(slot));
                }
            }
        }
        double[][] chained = new double[n][];
        for (int j = 0; j < n; j++) {
            chain(j, known, chained, 0);
        }
        int ok = 0, bad = 0;
        for (int j = 0; j < n; j++) {
            if (known[j] != null) {
                if (maxDiff(known[j], chained[j]) < 1e-3) ok++; else bad++;
            }
        }
        boolean trustChain = ok > 0 && bad <= 0.05 * (ok + bad);
        double[][] out = new double[n][];
        for (int j = 0; j < n; j++) {
            resolve(j, known, chained, trustChain, out, 0);
        }
        return out;
    }

    private double[] chain(int j, double[][] known, double[][] w, int depth) {
        if (w[j] != null) {
            return w[j];
        }
        int p = parentOf(j);
        if (p < 0 || p >= joints.size() || p == j || depth > 512) {
            // A root: solve it from a skinned child's bind pose when there is one.
            for (Map.Entry<Integer, Integer> e : parent.entrySet()) {
                int c = e.getKey();
                if (e.getValue() == j && c < known.length && known[c] != null) {
                    return w[j] = mul(known[c], invertAffine(local(c)));
                }
            }
            return w[j] = known[j] != null ? known[j] : identity();
        }
        return w[j] = mul(chain(p, known, w, depth + 1), local(j));
    }

    private double[] resolve(int j, double[][] known, double[][] chained, boolean trust, double[][] out, int depth) {
        if (out[j] != null) {
            return out[j];
        }
        if (known[j] != null) {
            return out[j] = known[j];
        }
        if (trust) {
            return out[j] = chained[j];
        }
        int p = parentOf(j);
        return out[j] = p >= 0 && p < joints.size() && p != j && depth < 512
            ? resolve(p, known, chained, trust, out, depth + 1) : identity();
    }

    // ---- matrix helpers (row-major 4x4) ----------------------------------------

    public static double[] identity() {
        return new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    public static double[] quatMat(double w, double x, double y, double z, double tx, double ty, double tz) {
        return new double[] {
            1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w), tx,
            2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w), ty,
            2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y), tz,
            0, 0, 0, 1};
    }

    public static double[] mul(double[] a, double[] b) {
        double[] r = new double[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double s = 0;
                for (int k = 0; k < 4; k++) {
                    s += a[i * 4 + k] * b[k * 4 + j];
                }
                r[i * 4 + j] = s;
            }
        }
        return r;
    }

    /** Inverse of an affine matrix whose 3x3 part is a rotation (possibly scaled). */
    public static double[] invertAffine(double[] m) {
        double a = m[0], b = m[1], c = m[2], dd = m[4], e = m[5], f = m[6], g = m[8], h = m[9], i = m[10];
        double det = a * (e * i - f * h) - b * (dd * i - f * g) + c * (dd * h - e * g);
        if (Math.abs(det) < 1e-12) {
            return identity();
        }
        double[] r = new double[16];
        r[0] = (e * i - f * h) / det; r[1] = (c * h - b * i) / det; r[2] = (b * f - c * e) / det;
        r[4] = (f * g - dd * i) / det; r[5] = (a * i - c * g) / det; r[6] = (c * dd - a * f) / det;
        r[8] = (dd * h - e * g) / det; r[9] = (b * g - a * h) / det; r[10] = (a * e - b * dd) / det;
        for (int row = 0; row < 3; row++) {
            r[row * 4 + 3] = -(r[row * 4] * m[3] + r[row * 4 + 1] * m[7] + r[row * 4 + 2] * m[11]);
        }
        r[15] = 1;
        return r;
    }

    private static double maxDiff(double[] a, double[] b) {
        double m = 0;
        for (int k = 0; k < 16; k++) {
            m = Math.max(m, Math.abs(a[k] - b[k]));
        }
        return m;
    }
}
