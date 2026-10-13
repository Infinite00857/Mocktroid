package com.mocktroid.format.chpr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mocktroid.format.LE;

/**
 * One compressed animation clip from a CHPR, decoded the way the game does it
 * (Metroid Prime 4 NX 1.0 + update; reversed from GameFramework.nss).
 * <p>
 * <b>Payload</b> (stored right after a {@code 01 01 <u32 clip id> ff ff ff ff}
 * record in the CHPR body): {@code u32 size, u16 0x20, u16 descriptorOffset,
 * u32 bitstreamOffset, f32 rate, u16 frames, u48 output slot mask,
 * u16 decodedSize}, then an LSB-first bit-packed stream table at +0x20
 * (leaf constructor {@code FUN_71003cec08}), a descriptor bytecode at
 * {@code descriptorOffset} (handlers at {@code 0x71017b2ee8}) that sets each
 * channel's bit width / sign / fraction bits, and one key bitstream per
 * stream.
 * <p>
 * <b>Keys</b> are a forward-only stream ({@code FUN_7100061b0c} init,
 * {@code FUN_71000616e0} per frame). Every channel holds a previous and a
 * current key and the frame of its next key; each frame reads how many
 * channels hit a key, their delta to the following key, then the new values
 * through one handler per group type ({@code 0x71017b3a28}):
 * 0 rotation (3 ints + 2 flag bits, w rebuilt), 1/2 vector, 3 bit flags,
 * 4 scalar, 5 raw int.
 * <p>
 * <b>Sampling</b> runs a per-stream bytecode ({@code 0x71017b3a58}): op 2 writes
 * joint transforms (quat, scale - 1, translation), op 3 quat + translation,
 * op 6 floats, op 7 raw ints. Outputs are addressed {@code slot | index << 6},
 * the same packing as the CHPR hierarchy record's pose field
 * ({@link ChprSkeleton#poseAddress}). Joint transforms are relative to the
 * bind pose (local rotation = clip * bind, translation = bind + clip). Constants
 * (values a clip holds for its whole length) index a float pool of the character
 * ({@link #findPools}; the stream table names which pool): rotations are 4 floats
 * w-first, translations / scales 3, and pool[0..3] = 1, 0, 0, 0 (identity / zero).
 * Without pools they come back as {@code NaN}.
 */
public final class AnimClip {

    private static final int[] BASE = {0, 0, 0, 4, 0, 0, 0, 0};
    private static final int[] PER = {0x1C, 0x1C, 0x1C, 2, 0xC, 8, 0, 1};

    /** A clip payload found in a CHPR body. */
    public record Located(int id, int offset, int size) {}

    public record Group(int count, int type, boolean hold, int chanBase, int valOff, int extra) {}

    public record ConstRecord(int key, int raw, float[] rotation, float[] translation) {}

    public static final class Stream {
        int bitOff, codeOff, k, lastFrame, cntBits, pool, nchan, valSize;
        boolean hasConst, flag0;
        final List<Group> groups = new ArrayList<>();
        final List<ConstRecord> consts = new ArrayList<>();

        public List<Group> groups() { return groups; }
        public int lastFrame() { return lastFrame; }
        public int channels() { return nchan; }
    }

    private final byte[] p;
    private final int descriptorOff;
    private final float rate;
    private final int frames;
    private final long mask;
    private final List<Stream> streams = new ArrayList<>();

    private AnimClip(byte[] payload) {
        p = payload;
        int hdr = LE.u16(p, 4);
        descriptorOff = LE.u16(p, 6);
        rate = LE.f32(p, 0xC);
        frames = LE.u16(p, 0x10);
        long m = 0;
        for (int i = 0; i < 6; i++) {
            m |= (long) (p[0x12 + i] & 0xFF) << (8 * i);
        }
        mask = m;
        Bits r = new Bits(p, hdr, 0);
        int ns = (int) r.read(4);
        r.read(10);
        r.read(4);
        for (int s = 0; s < ns; s++) {
            Stream st = new Stream();
            st.bitOff = (int) r.read(21);
            st.codeOff = (int) r.read(21);
            int gc = (int) r.read(4);
            st.hasConst = r.read(1) != 0;
            st.flag0 = r.read(1) != 0;
            st.k = (int) r.read(4);
            st.lastFrame = (frames - 1) * st.k;
            st.cntBits = (int) r.read(4);
            st.pool = (int) r.read(4);
            int chans = 0, voff = 0;
            for (int g = 0; g < gc; g++) {
                int cnt = (int) r.read(10);
                int type = (int) r.read(3);
                boolean hold = r.read(1) != 0;
                int extra = type == 3 ? (int) r.read(6) : 0;
                st.groups.add(new Group(cnt, type, hold, chans, voff, extra));
                chans += cnt;
                voff += BASE[type] + PER[type] * cnt + (type == 3 ? extra * 8 : 0);
            }
            st.nchan = chans;
            st.valSize = voff;
            if (st.hasConst) {
                int n = (int) r.read(4);
                for (int i = 0; i < n; i++) {
                    int key = (int) r.read(16);
                    int raw = (int) r.read(32);
                    float[] q = null, t = null;
                    if (r.read(1) != 0) {
                        q = new float[4];
                        for (int c = 0; c < 4; c++) q[c] = Float.intBitsToFloat((int) r.read(32));
                    }
                    if (r.read(1) != 0) {
                        t = new float[3];
                        for (int c = 0; c < 3; c++) t[c] = Float.intBitsToFloat((int) r.read(32));
                    }
                    st.consts.add(new ConstRecord(key, raw, q, t));
                }
            }
            streams.add(st);
        }
    }

    /** The character's constant pools (index = a stream's pool number), or empty. */
    private List<float[]> pools = List.of();

    public void setPools(List<float[]> pools) {
        this.pools = pools == null ? List.of() : pools;
    }

    /**
     * The CHPR's constant float pools: after the bytes {@code 01 00 00 00 00 01 <n> 00 00 00},
     * {@code n} x ({@code u16 index, u16 count, count f32}); each pool starts 1, 0, 0, 0. Found
     * unaligned (the body is packed), so by that signature.
     */
    public static List<float[]> findPools(byte[] d) {
        for (int i = 0; i + 26 <= d.length; i++) {
            if (d[i] != 1 || d[i + 1] != 0 || d[i + 2] != 0 || d[i + 3] != 0 || d[i + 4] != 0 || d[i + 5] != 1
                || d[i + 7] != 0 || d[i + 8] != 0 || d[i + 9] != 0 || d[i + 10] != 0 || d[i + 11] != 0) {
                continue;
            }
            int n = d[i + 6] & 0xFF;
            int o = i + 10;
            List<float[]> out = new ArrayList<>();
            boolean ok = n > 0 && n <= 16;
            for (int k = 0; k < n && ok; k++) {
                if (o + 4 > d.length || LE.u16(d, o) != k) {
                    ok = false;
                    break;
                }
                int count = LE.u16(d, o + 2);
                o += 4;
                if (count < 4 || o + 4L * count > d.length || LE.f32(d, o) != 1f || LE.f32(d, o + 4) != 0f) {
                    ok = false;
                    break;
                }
                float[] pool = new float[count];
                for (int j = 0; j < count; j++) {
                    pool[j] = LE.f32(d, o + 4 * j);
                }
                out.add(pool);
                o += 4 * count;
            }
            if (ok) {
                return out;
            }
        }
        return List.of();
    }

    /** Reads the clip payload starting at {@code offset}. */
    public static AnimClip read(byte[] data, int offset) {
        int size = LE.i32(data, offset);
        byte[] payload = new byte[size];
        System.arraycopy(data, offset, payload, 0, size);
        return new AnimClip(payload);
    }

    /** Finds every clip payload in a (decompressed) CHPR. */
    public static List<Located> locate(byte[] d) {
        List<Located> out = new ArrayList<>();
        for (int i = 6; i + 4 + 0x20 < d.length; i++) {
            if (d[i] != -1 || d[i + 1] != -1 || d[i + 2] != -1 || d[i + 3] != -1) continue;
            if (d[i - 6] != 1 || d[i - 5] != 1) continue;
            int o = i + 4;
            int size = LE.i32(d, o), hdr = LE.u16(d, o + 4), off2 = LE.u16(d, o + 6), off3 = LE.i32(d, o + 8);
            if (hdr == 0x20 && off2 > 0x20 && off3 > off2 && size > off3 && o + (long) size <= d.length) {
                out.add(new Located(LE.i32(d, i - 4), o, size));
                i = o + size - 1;
            }
        }
        return out;
    }

    public int frames() { return frames; }
    public float rate() { return rate; }
    public long slotMask() { return mask; }
    public List<Stream> streams() { return streams; }

    public Player player() {
        return new Player();
    }

    /** Sampled outputs, keyed by pose address {@code slot | index << 6}. */
    public static final class Pose {
        /** op 2: {qw, qx, qy, qz, sx, sy, sz, tx, ty, tz}; op 3: {qw, qx, qy, qz, tx, ty, tz}. */
        public final Map<Integer, float[]> transforms = new HashMap<>();
        public final Map<Integer, Float> floats = new HashMap<>();
        public final Map<Integer, Integer> ints = new HashMap<>();
    }

    /** Playback state (one per playing instance). */
    public final class Player {
        private final int[] frame = new int[streams.size()];
        private final long[] bitpos = new long[streams.size()];
        private final int[][] last = new int[streams.size()][];
        private final int[][] next = new int[streams.size()][];
        private final float[][] inv = new float[streams.size()][];
        private final byte[][] vals = new byte[streams.size()][];
        private final Map<Long, Integer> flags = new HashMap<>();
        private boolean started;

        private Player() {
            for (int s = 0; s < streams.size(); s++) {
                Stream st = streams.get(s);
                last[s] = new int[st.nchan];
                next[s] = new int[st.nchan];
                inv[s] = new float[st.nchan];
                vals[s] = new byte[st.valSize];
            }
            runDescriptors();
        }

        public int frame(int stream) { return frame[stream]; }
        public long bitPosition(int stream) { return bitpos[stream]; }

        /** Decodes up to {@code f} (in stream frames); restarts when going backwards. */
        public void seek(int stream, int f) {
            if (!started || f < frame[stream]) {
                init(stream);
            }
            Stream st = streams.get(stream);
            f = Math.min(f, st.lastFrame);
            while (frame[stream] < f) {
                step(stream);
            }
        }

        /** Samples all streams at {@code time} (clip frames, 0 .. frames - 1). */
        public Pose sample(float time, float[] pool) {
            Pose out = new Pose();
            for (int s = 0; s < streams.size(); s++) {
                Stream st = streams.get(s);
                float t = Math.max(0, Math.min(time * st.k, st.lastFrame));
                if (!started) {
                    for (int i = 0; i < streams.size(); i++) init(i);
                    started = true;
                }
                seek(s, (int) Math.floor(t));
                float[] pl = pool != null ? pool : st.pool < pools.size() ? pools.get(st.pool) : null;
                runSample(s, t, pl, out);
            }
            return out;
        }

        private void runDescriptors() {
            Bits r = new Bits(p, descriptorOff, 0);
            int cur = 0;
            while (true) {
                int op = (int) r.read(4);
                switch (op) {
                    case 0 -> cur = (int) r.read(4);
                    case 1 -> { return; }
                    case 2, 3 -> {
                        Group g = streams.get(cur).groups.get((int) r.read(4));
                        byte[] v = vals[cur];
                        for (int c = 0; c < g.count; c++) {
                            int o = g.valOff + c * 0x1C;
                            int b = (int) r.read(5), pp = (int) r.read(5), sg = (int) r.read(1), m = (int) r.read(3);
                            int lst = 0, n = 0;
                            for (int comp = 0; comp < 3; comp++) {
                                if ((m >> comp & 1) != 0) {
                                    lst |= comp << (2 * n);
                                    n++;
                                }
                            }
                            int f = field(v, o);
                            f = (f & (op == 2 ? 0xFFC00080 : 0xFFC00000)) | pp;
                            f |= n << 8 | ((sg - b - 1) & 31) << 11 | lst << 16;
                            v[o] = (byte) b;
                            setField(v, o, f);
                        }
                    }
                    case 4 -> {
                        Group g = streams.get(cur).groups.get((int) r.read(4));
                        byte[] v = vals[cur];
                        for (int c = 0; c < g.count; c++) {
                            int o = g.valOff + c * 0xC;
                            int b = (int) r.read(5), pp = (int) r.read(5), sg = (int) r.read(1);
                            v[o] = (byte) b;
                            v[o + 1] = (byte) (sg - b - 1);
                            v[o + 2] = (byte) pp;
                        }
                    }
                    case 5 -> {
                        r.read(4);
                        int n = (int) r.read(12);
                        for (int i = 0; i < n; i++) {
                            r.read(1);
                            r.read(16);
                        }
                    }
                    case 6 -> {
                        Group g = streams.get(cur).groups.get((int) r.read(4));
                        byte[] v = vals[cur];
                        for (int c = 0; c < g.count; c++) {
                            int o = g.valOff + c * 8;
                            int b = (int) r.read(5), sg = (int) r.read(1);
                            v[o] = (byte) b;
                            v[o + 1] = (byte) (sg - b - 1);
                        }
                    }
                    default -> throw new IllegalStateException("descriptor op " + op);
                }
            }
        }

        private void init(int s) {
            started = true;
            Stream st = streams.get(s);
            frame[s] = 0;
            java.util.Arrays.fill(last[s], 0);
            java.util.Arrays.fill(next[s], 0);
            Bits r = new Bits(p, st.bitOff, 0);
            List<int[]> lists = deltas(s, r, 0);
            handlers(s, r, lists);
            List<int[]> second = new ArrayList<>();
            for (int g = 0; g < st.groups.size(); g++) {
                Group gr = st.groups.get(g);
                if (!gr.hold && gr.count > 0) second.add(all(g, gr.count));
            }
            handlers(s, r, second);
            bitpos[s] = r.pos;
        }

        private void step(int s) {
            Stream st = streams.get(s);
            frame[s]++;
            Bits r = new Bits(p, st.bitOff, bitpos[s]);
            int n = (int) r.read(st.cntBits);
            if (n != 0) {
                List<int[]> lists;
                if (frame[s] == st.lastFrame) {
                    lists = new ArrayList<>();
                    for (int g = 0; g < st.groups.size(); g++) {
                        Group gr = st.groups.get(g);
                        if (gr.hold && gr.count > 0) lists.add(all(g, gr.count));
                    }
                } else {
                    lists = deltas(s, r, frame[s]);
                }
                handlers(s, r, lists);
            }
            bitpos[s] = r.pos;
        }

        private int[] all(int g, int count) {
            int[] a = new int[count + 1];
            a[0] = g;
            for (int i = 0; i < count; i++) a[i + 1] = i;
            return a;
        }

        private List<int[]> deltas(int s, Bits r, int f) {
            Stream st = streams.get(s);
            int w = (int) r.read(4);
            List<int[]> lists = new ArrayList<>();
            for (int g = 0; g < st.groups.size(); g++) {
                Group gr = st.groups.get(g);
                int[] tmp = new int[gr.count + 1];
                int n = 0;
                for (int c = 0; c < gr.count; c++) {
                    int ch = gr.chanBase + c;
                    if (next[s][ch] == f) {
                        last[s][ch] = next[s][ch];
                        int d = (int) r.read(w) & 0xFFFF;
                        next[s][ch] = (next[s][ch] + d) & 0xFFFF;
                        inv[s][ch] = 1.0f / d;
                        tmp[++n] = c;
                    }
                }
                if (n > 0) {
                    tmp[0] = g;
                    lists.add(java.util.Arrays.copyOf(tmp, n + 1));
                }
            }
            return lists;
        }

        private void handlers(int s, Bits r, List<int[]> lists) {
            Stream st = streams.get(s);
            byte[] v = vals[s];
            for (int[] l : lists) {
                Group g = st.groups.get(l[0]);
                if (g.type == 3) {
                    long bits = r.read(l.length - 1);
                    for (int k = 1; k < l.length; k++) {
                        flags.put(((long) s << 40) | ((long) l[0] << 20) | l[k], (int) (bits >> (k - 1) & 1));
                    }
                    continue;
                }
                for (int k = 1; k < l.length; k++) {
                    int c = l[k];
                    switch (g.type) {
                        case 0, 1, 2 -> {
                            int o = g.valOff + c * 0x1C;
                            System.arraycopy(v, o + 0x10, v, o + 4, 12);
                            java.util.Arrays.fill(v, o + 0x10, o + 0x1C, (byte) 0);
                            int f = field(v, o);
                            if (g.type == 0) {
                                f = (f & ~(1 << 22)) | ((f >> 23 & 1) << 22);
                                int fl = (int) r.read(2);
                                f = (f & ~0x800080) | (fl & 1) << 7 | (fl >> 1 & 1) << 23;
                                setField(v, o, f);
                            }
                            int lst = f >> 16 & 0x3F, sh = f >> 11 & 31, b = v[o] & 0xFF;
                            while (true) {
                                LE.putI32(v, o + 0x10 + 4 * (lst & 3), sext(r.read(b), sh));
                                if (lst <= 3) break;
                                lst >>= 2;
                            }
                        }
                        case 4 -> {
                            int o = g.valOff + c * 0xC;
                            int val = sext(r.read(v[o] & 0xFF), v[o + 1]);
                            System.arraycopy(v, o + 8, v, o + 4, 4);
                            LE.putI32(v, o + 8, val);
                        }
                        case 5 -> {
                            int o = g.valOff + c * 8;
                            LE.putI32(v, o + 4, sext(r.read(v[o] & 0xFF), v[o + 1]));
                        }
                        default -> throw new IllegalStateException("group type " + g.type);
                    }
                }
            }
        }

        // ---------------------------------------------------------------- sampling

        private void runSample(int s, float t, float[] pool, Pose out) {
            Stream st = streams.get(s);
            int q = st.codeOff;
            if ((p[q] & 0xFF) != 1) return;
            q += 3;
            while (q < p.length) {
                int op = p[q] & 0xFF;
                if (op == 0) return;
                int n = p[q + 1] & 0xFF;
                int dest = LE.u16(p, q + 2);
                switch (op) {
                    case 2 -> {
                        for (int i = 0; i < n; i++) {
                            int e = q + 4 + 6 * i;
                            float[] x = new float[10];
                            rotation(s, LE.u16(p, e), t, pool, x, 0);
                            int sc = LE.u16(p, e + 2);
                            if ((sc & 0x8000) == 0) {
                                constants(pool, (sc & 0x4000) != 0 ? sc & 0x3FFF : sc & 0x3FFF, (sc & 0x4000) != 0 ? 1 : 3, x, 4);
                                if ((sc & 0x4000) != 0) { x[5] = x[4]; x[6] = x[4]; }
                            } else if ((sc & 0x4000) != 0) {
                                x[4] = scalar(s, sc, t);
                                x[5] = x[4];
                                x[6] = x[4];
                            } else {
                                vector(s, sc, t, x, 4);
                            }
                            x[4] += 1;
                            x[5] += 1;
                            x[6] += 1;
                            int tr = LE.u16(p, e + 4);
                            if ((tr & 0x8000) == 0) constants(pool, tr, 3, x, 7);
                            else vector(s, tr, t, x, 7);
                            out.transforms.put(advance(dest, 5 * i), x);
                        }
                        q += 4 + 6 * n;
                    }
                    case 3 -> {
                        for (int i = 0; i < n; i++) {
                            int e = q + 4 + 4 * i;
                            float[] x = new float[7];
                            rotation(s, LE.u16(p, e), t, pool, x, 0);
                            int tr = LE.u16(p, e + 2);
                            if ((tr & 0x8000) == 0) constants(pool, tr, 3, x, 4);
                            else vector(s, tr, t, x, 4);
                            out.transforms.put(advance(dest, 7 * i), x);
                        }
                        q += 4 + 4 * n;
                    }
                    case 4 -> q += 3 + 2 * n;
                    case 5 -> q += 1;
                    case 6 -> {
                        for (int i = 0; i < n; i++) {
                            int e = LE.u16(p, q + 4 + 2 * i);
                            float f;
                            if ((e & 0x8000) == 0) {
                                f = pool != null && (e & 0x3FFF) < pool.length ? pool[e & 0x3FFF] : Float.NaN;
                            } else {
                                f = scalar(s, e, t);
                            }
                            out.floats.put(advance(dest, i), f);
                        }
                        q += 4 + 2 * n;
                    }
                    case 7 -> {
                        for (int i = 0; i < n; i++) {
                            int e = LE.u16(p, q + 4 + 2 * i);
                            if ((e & 0x8000) != 0) {
                                Group g = streams.get(s).groups.get(e >> 10 & 15);
                                out.ints.put(advance(dest, i), LE.i32(vals[s], g.valOff + (e & 0x3FF) * 8 + 4));
                            } else if (pool != null && (e & 0x3FFF) < pool.length) {
                                out.ints.put(advance(dest, i), Float.floatToRawIntBits(pool[e & 0x3FFF]));
                            }
                        }
                        q += 4 + 2 * n;
                    }
                    default -> { return; }
                }
            }
        }

        private int advance(int dest, int units) {
            return (dest & 0x3F) | ((dest >> 6) + units) << 6;
        }

        private void constants(float[] pool, int index, int n, float[] x, int at) {
            for (int i = 0; i < n; i++) {
                x[at + i] = pool != null && index + i < pool.length ? pool[index + i] : Float.NaN;
            }
        }

        /** Interpolation factor of channel {@code ch}, or -1 when exactly on its last key. */
        private float factor(int s, int ch, float t) {
            float l = last[s][ch];
            return t == l ? -1 : (t - l) * inv[s][ch];
        }

        private void rotation(int s, int ref, float t, float[] pool, float[] x, int at) {
            if ((ref & 0x8000) == 0) {
                constants(pool, ref & 0x3FFF, 4, x, at);
                return;
            }
            Group g = streams.get(s).groups.get(ref >> 10 & 15);
            int c = ref & 0x3FF;
            byte[] v = vals[s];
            int o = g.valOff + c * 0x1C;
            int f = field(v, o);
            float sc = 1f / (1 << (f & 31));
            float[] a = quat(v, o + 4, sc, (f >> 22 & 1) != 0);
            float u = factor(s, g.chanBase + c, t);
            if (u < 0) {
                System.arraycopy(a, 0, x, at, 4);
                return;
            }
            float[] b = quat(v, o + 0x10, sc, (f >> 23 & 1) != 0);
            float[] r = (f & 0x80) != 0 ? twoStep(a, b, u) : nlerp(a, b, u);
            System.arraycopy(r, 0, x, at, 4);
        }

        private float[] quat(byte[] v, int o, float sc, boolean negW) {
            float qx = LE.i32(v, o) * sc, qy = LE.i32(v, o + 4) * sc, qz = LE.i32(v, o + 8) * sc;
            float w = (float) Math.sqrt(Math.max(0, 1 - (qx * qx + qy * qy + qz * qz)));
            return new float[] {negW ? -w : w, qx, qy, qz};
        }

        private void vector(int s, int ref, float t, float[] x, int at) {
            Group g = streams.get(s).groups.get(ref >> 10 & 15);
            int c = ref & 0x3FF;
            byte[] v = vals[s];
            int o = g.valOff + c * 0x1C;
            float sc = 1f / (1 << ((v[o + 1] & 0xFF) & 31));
            float u = factor(s, g.chanBase + c, t);
            for (int i = 0; i < 3; i++) {
                float a = LE.i32(v, o + 4 + 4 * i) * sc;
                x[at + i] = u < 0 ? a : a + u * (LE.i32(v, o + 0x10 + 4 * i) * sc - a);
            }
        }

        private float scalar(int s, int ref, float t) {
            Group g = streams.get(s).groups.get(ref >> 10 & 15);
            int c = ref & 0x3FF;
            byte[] v = vals[s];
            int o = g.valOff + c * 0xC;
            float sc = 1f / (1 << ((v[o + 2] & 0xFF) & 31));
            float a = LE.i32(v, o + 4) * sc;
            float u = factor(s, g.chanBase + c, t);
            return u < 0 ? a : a + u * (LE.i32(v, o + 8) * sc - a);
        }
    }

    private static float[] nlerp(float[] a, float[] b, float u) {
        float[] r = new float[4];
        float n = 0;
        for (int i = 0; i < 4; i++) {
            r[i] = a[i] + u * (b[i] - a[i]);
            n += r[i] * r[i];
        }
        n = (float) (1 / Math.sqrt(n));
        for (int i = 0; i < 4; i++) r[i] *= n;
        return r;
    }

    /** {@code 0x710021e5c0}: blend through the normalized midpoint (near side of b). */
    private static float[] twoStep(float[] a, float[] b, float u) {
        float dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        if (dot < -1f / 512) {
            b = new float[] {-b[0], -b[1], -b[2], -b[3]};
        }
        float[] mid = nlerp(a, b, 0.5f);
        if (u < 0.5f) return nlerp(a, mid, 2 * u);
        if (u > 0.5f) return nlerp(mid, b, 2 * (u - 0.5f));
        return mid;
    }

    private static int field(byte[] v, int o) {
        return (v[o + 1] & 0xFF) | (v[o + 2] & 0xFF) << 8 | (v[o + 3] & 0xFF) << 16;
    }

    private static void setField(byte[] v, int o, int f) {
        v[o + 1] = (byte) f;
        v[o + 2] = (byte) (f >> 8);
        v[o + 3] = (byte) (f >> 16);
    }

    private static int sext(long value, int shift) {
        shift &= 31;
        return ((int) value << shift) >> shift;
    }

    /** LSB-first bit reader; reads past the end as zeros. */
    static final class Bits {
        private final byte[] d;
        private final int start;
        long pos;

        Bits(byte[] d, int start, long bitPos) {
            this.d = d;
            this.start = start;
            this.pos = bitPos;
        }

        long read(int n) {
            if (n == 0) return 0;
            long v = 0;
            int got = 0;
            while (got < n) {
                long byteIndex = start + (pos >> 3);
                int bit = (int) (pos & 7);
                int take = Math.min(8 - bit, n - got);
                int b = byteIndex < d.length ? d[(int) byteIndex] & 0xFF : 0;
                v |= (long) ((b >> bit) & ((1 << take) - 1)) << got;
                got += take;
                pos += take;
            }
            return v;
        }
    }
}
