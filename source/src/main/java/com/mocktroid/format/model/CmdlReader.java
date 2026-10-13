package com.mocktroid.format.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mocktroid.format.ChunkDescriptor;
import com.mocktroid.format.FormDescriptor;
import com.mocktroid.format.FourCC;
import com.mocktroid.format.GuidUtil;
import com.mocktroid.format.LE;
import com.mocktroid.format.MocktroidFormatException;
import com.mocktroid.format.compress.Compression;

/**
 * Parses MP4 cooked models (RFRM "CMDL" v163/172, plus the SMDL/WMDL
 * variants). Chunk layouts follow retrotool's mp4-branch {@code cmdl.rs}
 * where it covers MP4, confirmed against real assets:
 * <pre>
 *   HEAD/WDHD       u32 unk, AABB (6 x f32), ...
 *   SKHD            u32 0, u32 skin joint count, u32 skin hash, ... (SMDL)
 *   MTRL            "MSHP", u32 count, u32 unk, u32 n + u32[n] (skinned models only),
 *                   then count x 38-byte entries:
 *                   GUID materialInstance (MATI), GUID shader (MTRL), 6 bytes flags
 *                   (MP4-specific -- retrotool only knows the older "LEGA" layout)
 *   MESH            u32 count, count x {u16 material, u8 vtxBuf, u8 idxBuf,
 *                   u32 indexStart, u32 indexCount, u16 flags, u16 unk},
 *                   then a LOD table (see {@link #readLod0})
 *   VBUF            u32 count, per stream: u32 n, n x {u32 buffer, offset,
 *                   stride, format, component}, u32 unk, u8 bufferCount
 *   IBUF            u32 count, count x u32 index type (0 u8, 1 u16, 2 u32)
 *   GPU             compressed vertex/index buffers
 * </pre>
 * The GPU buffers are located via the asset's pak META blob:
 * {@code u32 unk, gpuOffset, readCount, {u32 size, offset}[readCount],
 * vtxCount, {u32 readIndex, offset, size, destSize, unk}[vtxCount], idxCount,
 * {same}[idxCount]} -- MP4 added the trailing {@code unk} to each buffer entry.
 */
public final class CmdlReader {

    private static final FourCC HEAD = new FourCC("HEAD");
    private static final FourCC SKHD = new FourCC("SKHD");
    private static final FourCC WDHD = new FourCC("WDHD");
    private static final FourCC MTRL = new FourCC("MTRL");
    private static final FourCC MESH = new FourCC("MESH");
    private static final FourCC VBUF = new FourCC("VBUF");
    private static final FourCC IBUF = new FourCC("IBUF");
    private static final FourCC MSHP = new FourCC("MSHP");
    private static final FourCC LEGA = new FourCC("LEGA");

    private static final int COMPONENT_POSITION = 0;
    private static final int COMPONENT_NORMAL = 1;
    private static final int COMPONENT_TEXCOORD0 = 5;
    private static final int COMPONENT_JOINTS = 10;
    private static final int COMPONENT_WEIGHTS = 11;

    private CmdlReader() {}

    private record VertexComponent(int buffer, int offset, int stride, VertexFormat format, int component) {}

    private record StreamLayout(List<VertexComponent> components, int bufferCount) {}

    public static CmdlModel read(byte[] asset, byte[] meta) {
        if (meta == null) {
            throw new MocktroidFormatException("Model has no META blob (GPU buffer layout)");
        }
        FormDescriptor form = FormDescriptor.read(asset, 0);
        String formId = form.id().toString();
        if (!formId.equals("CMDL") && !formId.equals("SMDL") && !formId.equals("WMDL")) {
            throw new MocktroidFormatException("Not a model form: " + formId);
        }

        // ---- GPU buffers from META ----
        int m = 8;
        int readCount = u32(meta, m);
        m += 4;
        int[] readSize = new int[readCount];
        int[] readOffset = new int[readCount];
        for (int i = 0; i < readCount; i++, m += 8) {
            readSize[i] = u32(meta, m);
            readOffset[i] = u32(meta, m + 4);
        }
        List<byte[]> vtxBuffers = new ArrayList<>();
        m = readBuffers(asset, meta, m, readOffset, vtxBuffers);
        List<byte[]> idxBuffers = new ArrayList<>();
        readBuffers(asset, meta, m, readOffset, idxBuffers);

        // ---- chunks ----
        float[] min = new float[3];
        float[] max = new float[3];
        List<CmdlModel.MaterialRef> materials = new ArrayList<>();
        List<CmdlModel.Submesh> submeshes = new ArrayList<>();
        LodSets lod0 = null;
        List<StreamLayout> layouts = new ArrayList<>();
        List<Integer> indexTypes = new ArrayList<>();
        int skinJoints = 0;

        int pos = FormDescriptor.HEADER_SIZE;
        while (pos < form.nextOffset()) {
            ChunkDescriptor c = ChunkDescriptor.read(asset, pos);
            int b = c.bodyStart();
            if (c.id().equals(SKHD)) {
                // SKHD: u32 0, u32 skin joint count, u32 skin hash, ... (no bounds; HEAD follows).
                skinJoints = u32(asset, b + 4);
            } else if (c.id().equals(HEAD) || c.id().equals(WDHD)) {
                for (int i = 0; i < 3; i++) {
                    min[i] = f32(asset, b + 4 + i * 4);
                    max[i] = f32(asset, b + 16 + i * 4);
                }
            } else if (c.id().equals(MTRL)) {
                readMaterials(asset, c, materials);
            } else if (c.id().equals(MESH)) {
                int count = u32(asset, b);
                for (int i = 0; i < count; i++) {
                    int o = b + 4 + i * 16;
                    submeshes.add(new CmdlModel.Submesh(LE.u16(asset, o), asset[o + 2] & 0xFF, asset[o + 3] & 0xFF,
                        u32(asset, o + 4), u32(asset, o + 8), LE.u16(asset, o + 12)));
                }
                lod0 = readLod0(asset, b + 4 + count * 16, b + c.bodyLen(), count);
            } else if (c.id().equals(VBUF)) {
                int count = u32(asset, b);
                int o = b + 4;
                for (int i = 0; i < count; i++) {
                    int n = u32(asset, o);
                    o += 4;
                    List<VertexComponent> comps = new ArrayList<>();
                    for (int k = 0; k < n; k++, o += 20) {
                        comps.add(new VertexComponent(u32(asset, o), u32(asset, o + 4), u32(asset, o + 8),
                            VertexFormat.fromId(LE.u32(asset, o + 12)), u32(asset, o + 16)));
                    }
                    o += 4; // unk
                    int bufferCount = asset[o] & 0xFF;
                    o += 1;
                    layouts.add(new StreamLayout(comps, bufferCount));
                }
            } else if (c.id().equals(IBUF)) {
                int count = u32(asset, b);
                for (int i = 0; i < count; i++) {
                    indexTypes.add(u32(asset, b + 4 + i * 4));
                }
            }
            // GPU (consumed via META) and any other chunks: skip.
            pos = c.nextOffset();
        }

        List<CmdlModel.VertexStream> streams = new ArrayList<>();
        int bufferBase = 0;
        for (StreamLayout layout : layouts) {
            streams.add(decodeStream(layout, vtxBuffers, bufferBase));
            bufferBase += layout.bufferCount();
        }

        List<int[]> indexBuffers = new ArrayList<>();
        for (int i = 0; i < idxBuffers.size(); i++) {
            int type = i < indexTypes.size() ? indexTypes.get(i) : 1;
            indexBuffers.add(decodeIndices(idxBuffers.get(i), type));
        }

        return new CmdlModel(formId, min, max, submeshes, streams, indexBuffers, materials,
            lod0 != null ? lod0.visible() : null, lod0 != null ? lod0.transparent() : new int[0], skinJoints);
    }

    private static int readBuffers(byte[] asset, byte[] meta, int m, int[] readOffset, List<byte[]> out) {
        int count = u32(meta, m);
        m += 4;
        for (int i = 0; i < count; i++, m += 20) {
            int readIndex = u32(meta, m);
            int offset = u32(meta, m + 4);
            int size = u32(meta, m + 8);
            int destSize = u32(meta, m + 12);
            out.add(Compression.decompressBuffer(asset, readOffset[readIndex] + offset, size, destSize));
        }
        return m;
    }

    private static void readMaterials(byte[] d, ChunkDescriptor c, List<CmdlModel.MaterialRef> out) {
        int b = c.bodyStart();
        FourCC kind = LE.fourCC(d, b);
        if (kind.equals(MSHP)) {
            // "MSHP", u32 count, u32 unk, u32 tableLen, u32[tableLen], then the
            // 38-byte entries. tableLen is 0 for static models; skinned SMDLs
            // carry an extra table there (44 entries for Samus's suits).
            int count = u32(d, b + 4);
            int first = b + 16 + u32(d, b + 12) * 4;
            if (first + count * 38 > c.nextOffset()) {
                return;
            }
            for (int i = 0; i < count; i++) {
                int o = first + i * 38;
                out.add(new CmdlModel.MaterialRef(null, GuidUtil.fromBytesLe(d, o),
                    GuidUtil.fromBytesLe(d, o + 16), Map.of()));
            }
        } else if (kind.equals(LEGA)) {
            readLegacyMaterials(d, b + 4, c.nextOffset(), out);
        }
    }

    /**
     * LEGA: the embedded material layout (retrotool's SMaterialChunk /
     * CMaterialCache, unchanged in MP4 for the ~3,000 models that still use
     * it): u32 unk, u32 count, then per material: u32-length name, GUID
     * shader, GUID unk, u32 x2, u32 n + FourCC[n], u32 n + {FourCC, FourCC,
     * u8, u8}[n], u32 n + data entries {FourCC id, FourCC type, payload}.
     * Stops quietly at an unknown payload type, keeping what parsed.
     */
    private static void readLegacyMaterials(byte[] d, int o, int end, List<CmdlModel.MaterialRef> out) {
        try {
            o += 4;
            int count = u32(d, o);
            o += 4;
            for (int i = 0; i < count && o < end; i++) {
                int nameLen = u32(d, o);
                String name = new String(d, o + 4, nameLen, java.nio.charset.StandardCharsets.UTF_8);
                o += 4 + nameLen;
                UUID shader = GuidUtil.fromBytesLe(d, o);
                o += 32 + 8;
                o += 4 + u32(d, o) * 4;
                o += 4 + u32(d, o) * 10;
                int dataCount = u32(d, o);
                o += 4;
                Map<String, UUID> textures = new LinkedHashMap<>();
                for (int k = 0; k < dataCount; k++) {
                    String id = LE.fourCC(d, o).toString();
                    String type = LE.fourCC(d, o + 4).toString();
                    o += 8;
                    switch (type) {
                        case "TXTR" -> {
                            UUID tex = GuidUtil.fromBytesLe(d, o);
                            o += 16;
                            if (tex.getMostSignificantBits() != 0 || tex.getLeastSignificantBits() != 0) {
                                textures.putIfAbsent(id, tex);
                                o += 20; // usage: texcoord, filter, wrap x/y/z
                            }
                        }
                        case "COLR", "INT4" -> o += 16;
                        case "SCLR", "INT1" -> o += 4;
                        case "MAT4" -> o += 64;
                        case "CPLX" -> {
                            if (id.equals("BCRL") || id.equals("MTLL") || id.equals("NRML")) {
                                o += 4 + 48 + 1;
                                for (int t = 0; t < 3; t++) {
                                    UUID tex = GuidUtil.fromBytesLe(d, o);
                                    o += 16;
                                    if (tex.getMostSignificantBits() != 0 || tex.getLeastSignificantBits() != 0) {
                                        o += 20;
                                    }
                                }
                            } else if (id.equals("XCH0")) {
                                o += 56;
                            } else {
                                throw new MocktroidFormatException("unknown complex " + id);
                            }
                        }
                        default -> throw new MocktroidFormatException("unknown material data type " + type);
                    }
                }
                out.add(new CmdlModel.MaterialRef(name, null, shader, textures));
            }
        } catch (RuntimeException e) {
            // Keep the materials that parsed; the rest render untextured.
        }
    }

    /** LOD 0's "everything visible" and "transparent" submesh lists. */
    private record LodSets(int[] visible, int[] transparent) {}

    /**
     * MESH trailer (retrotool's SMeshLoadInformation, with MP4's extra range):
     * ceil(n/4) + ceil(n/8) bytes of per-mesh flags, u32 shortCount + u16 mesh
     * indices, u8 lodCount, then per LOD <b>6</b> x {u32 offset, u32 count}
     * ranges into those indices (Prime Remastered had 5), then u32 hasRules +
     * f32 per LOD + 1 byte. Confirmed on real models, the six ranges are:
     * r0 everything visible, r1 its opaque part, r2 its transparent part
     * (e.g. Samus's visor -- also the submeshes without the opaque flag),
     * r3/r4 a separate low-detail set (shadow casters: drawing them too put a
     * second, purple-shouldered suit over Samus), r5 = r0 again.
     * Returns null if the table doesn't parse cleanly, so the caller falls
     * back to drawing everything.
     */
    private static LodSets readLod0(byte[] d, int o, int end, int meshCount) {
        try {
            o += (meshCount + 3) / 4 + (meshCount + 7) / 8;
            int shortCount = u32(d, o);
            o += 4;
            int shortsStart = o;
            o += shortCount * 2;
            int lodCount = d[o] & 0xFF;
            o += 1;
            if (lodCount == 0 || o + 48 > end) {
                return null;
            }
            int[] visible = range(d, o, shortsStart, shortCount, meshCount);
            int[] transparent = range(d, o + 16, shortsStart, shortCount, meshCount);
            if (visible == null || visible.length == 0) {
                return null;
            }
            return new LodSets(visible, transparent != null ? transparent : new int[0]);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int[] range(byte[] d, int o, int shortsStart, int shortCount, int meshCount) {
        int off = u32(d, o);
        int cnt = u32(d, o + 4);
        if (off < 0 || cnt < 0 || off + cnt > shortCount) {
            return null;
        }
        int[] out = new int[cnt];
        for (int k = 0; k < cnt; k++) {
            out[k] = LE.u16(d, shortsStart + (off + k) * 2);
            if (out[k] >= meshCount) {
                return null;
            }
        }
        return out;
    }

    private static CmdlModel.VertexStream decodeStream(StreamLayout layout, List<byte[]> buffers, int base) {
        float[] positions = null;
        float[] normals = null;
        float[] uv0 = null;
        float[] joints = null;
        float[] weights = null;
        int vertexCount = 0;
        for (VertexComponent comp : layout.components()) {
            if (comp.stride() <= 0 || base + comp.buffer() >= buffers.size()) {
                continue;
            }
            byte[] buf = buffers.get(base + comp.buffer());
            int n = buf.length / comp.stride();
            switch (comp.component()) {
                case COMPONENT_POSITION -> {
                    positions = decode(buf, comp, n, 3);
                    vertexCount = n;
                }
                case COMPONENT_NORMAL -> normals = decode(buf, comp, n, 3);
                case COMPONENT_TEXCOORD0 -> uv0 = decode(buf, comp, n, 2);
                case COMPONENT_JOINTS -> joints = decode(buf, comp, n, 4);
                case COMPONENT_WEIGHTS -> weights = decode(buf, comp, n, 4);
                default -> { }
            }
        }
        if (positions == null) {
            throw new MocktroidFormatException("Vertex stream has no positions");
        }
        if (normals != null && normals.length != vertexCount * 3) {
            normals = null;
        }
        if (uv0 != null && uv0.length != vertexCount * 2) {
            uv0 = null;
        }
        if (joints == null || weights == null || joints.length != vertexCount * 4 || weights.length != vertexCount * 4) {
            joints = null;
            weights = null;
        }
        return new CmdlModel.VertexStream(vertexCount, positions, normals, uv0, joints, weights);
    }

    private static float[] decode(byte[] buf, VertexComponent comp, int n, int want) {
        float[] out = new float[n * want];
        float[] tmp = new float[4];
        for (int v = 0; v < n; v++) {
            int o = v * comp.stride() + comp.offset();
            if (o + comp.format().byteSize > buf.length) {
                break;
            }
            comp.format().read(buf, o, tmp);
            for (int k = 0; k < want; k++) {
                out[v * want + k] = k < comp.format().components ? tmp[k] : 0f;
            }
        }
        return out;
    }

    private static int[] decodeIndices(byte[] buf, int type) {
        int size = type == 0 ? 1 : type == 2 ? 4 : 2;
        int[] out = new int[buf.length / size];
        for (int i = 0; i < out.length; i++) {
            out[i] = switch (size) {
                case 1 -> buf[i] & 0xFF;
                case 4 -> u32(buf, i * 4);
                default -> LE.u16(buf, i * 2);
            };
        }
        return out;
    }

    private static int u32(byte[] d, int o) {
        return (int) LE.u32(d, o);
    }

    private static float f32(byte[] d, int o) {
        return Float.intBitsToFloat((int) LE.u32(d, o));
    }
}
