import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import com.mocktroid.format.*;
import com.mocktroid.format.chpr.*;

/** Cross-checks clip output addresses against the CHPR's hierarchy and typed-address tables. args: chprFile [clipChprFile] */
public class ChprTables {
    public static void main(String[] a) throws Exception {
        byte[] d = Files.readAllBytes(Path.of(a[0]));
        byte[] cd = a.length > 1 ? Files.readAllBytes(Path.of(a[1])) : d;
        ChprSkeleton s = ChprSkeleton.read(d);
        Set<Integer> jointAddr = new TreeSet<>();
        for (int j = 0; j < s.joints().size(); j++) if (s.poseAddress(j) >= 0) jointAddr.add(s.poseAddress(j));
        // typed tables: runs of (u16 addr, u8 kind in 0x60..0x6f, u8 0)
        Map<Integer, Integer> typed = new TreeMap<>(); // addr -> kind
        int bestRun = 0, bestAt = -1;
        for (int i = 0; i + 4 <= d.length; i++) {
            int k = 0;
            while (i + 4 * k + 4 <= d.length && (d[i + 4 * k + 2] & 0xF0) == 0x60 && (d[i + 4 * k + 2] & 0xFF) >= 0x63 && d[i + 4 * k + 3] == 0) k++;
            if (k > bestRun) { bestRun = k; bestAt = i; }
            if (k > 20) i += 4 * k;
        }
        Map<Integer, Integer> kindCount = new TreeMap<>();
        for (int q = 0; q < bestRun; q++) { int addr = LE.u16(d, bestAt + 4 * q), kind = d[bestAt + 4 * q + 2] & 0xFF; typed.put(addr, kind); kindCount.merge(kind, 1, Integer::sum); }
        System.out.printf("typed address table @%x: %d entries, kinds %s%n", bestAt, bestRun, kindCount);
        System.out.println("joint pose addresses: " + jointAddr.size());
        // clip outputs
        List<AnimClip.Located> locs = AnimClip.locate(cd);
        Field pf = AnimClip.class.getDeclaredField("p"); pf.setAccessible(true);
        Field co = AnimClip.Stream.class.getDeclaredField("codeOff"); co.setAccessible(true);
        for (AnimClip.Located l : locs.subList(0, Math.min(3, locs.size()))) {
            AnimClip c = AnimClip.read(cd, l.offset());
            byte[] p = (byte[]) pf.get(c);
            Map<String, List<Integer>> byOp = new TreeMap<>();
            for (AnimClip.Stream st : c.streams()) {
                int q = (int) co.get(st);
                if ((p[q] & 0xFF) != 1) continue;
                q += 3;
                while (q < p.length) {
                    int op = p[q] & 0xFF; if (op == 0) break;
                    int n = p[q + 1] & 0xFF, dest = LE.u16(p, q + 2);
                    int unit = op == 2 ? 5 : op == 3 ? 7 : 1;
                    if (op == 2 || op == 3 || op == 6 || op == 7) for (int i = 0; i < n; i++) byOp.computeIfAbsent("op" + op, k -> new ArrayList<>()).add((dest & 63) | ((dest >> 6) + unit * i) << 6);
                    if (op == 2) q += 4 + 6 * n; else if (op == 3) q += 4 + 4 * n; else if (op == 4) q += 3 + 2 * n; else if (op == 5) q += 1; else if (op == 6 || op == 7) q += 4 + 2 * n; else break;
                }
            }
            System.out.printf("clip id %d frames %d streams %d%n", l.id(), c.frames(), c.streams().size());
            for (var e : byOp.entrySet()) {
                List<Integer> addrs = e.getValue();
                int inJoints = 0, inTyped = 0; Map<Integer, Integer> typedKinds = new TreeMap<>(); Set<Integer> slots = new TreeSet<>();
                for (int ad : addrs) { slots.add(ad & 63); if (jointAddr.contains(ad)) inJoints++; Integer k = typed.get(ad); if (k != null) { inTyped++; typedKinds.merge(k, 1, Integer::sum); } }
                System.out.printf("   %s: %d outputs, slots %s, = joint addresses %d, in typed table %d %s%n", e.getKey(), addrs.size(), slots, inJoints, inTyped, typedKinds);
            }
        }
        // typed table addresses vs joints
        Map<Integer, int[]> perKind = new TreeMap<>();
        typed.forEach((ad, k) -> { int[] v = perKind.computeIfAbsent(k, x -> new int[2]); v[0]++; if (jointAddr.contains(ad)) v[1]++; });
        perKind.forEach((k, v) -> System.out.printf("typed kind %02x: %d addresses, %d are joint addresses%n", k, v[0], v[1]));
    }
}
