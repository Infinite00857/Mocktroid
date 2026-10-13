package com.mocktroid.export;

import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mocktroid.format.image.PngWriter;
import com.mocktroid.format.model.CmdlModel;
import com.mocktroid.format.pack.PackCatalog;
import com.mocktroid.format.texture.Texture;
import com.mocktroid.format.texture.TextureDecoder;
import com.mocktroid.format.texture.TxtrReader;
import com.mocktroid.library.ModelLoader;
import com.mocktroid.library.PakAssetRef;

/**
 * Writes a model (LOD 0, bind pose) as Wavefront OBJ (+ MTL) or glTF 2.0 (.gltf + .bin), with
 * each material's base colour as a PNG next to it. Positions stay in the game's Y-up space.
 * Texture V: the game's UVs are bottom-left based against the decoded (top-down) image, which is
 * what OBJ expects; glTF is top-left based, so V is flipped there.
 */
public final class ModelExporter {

    public enum Format {
        OBJ("Wavefront OBJ (*.obj)", "obj"),
        GLTF("glTF 2.0 (*.gltf)", "gltf");

        public final String label;
        public final String extension;

        Format(String label, String extension) {
            this.label = label;
            this.extension = extension;
        }
    }

    private ModelExporter() {}

    /** One drawn submesh, re-indexed into its own vertex range. */
    private record Part(int material, float[] positions, float[] normals, float[] uvs, int[] indices) {}

    public static void export(ModelLoader.LoadedModel loaded, Path target, Format format) throws IOException {
        List<Part> parts = parts(loaded.model());
        String stem = stem(target);
        Path dir = target.toAbsolutePath().getParent();
        Map<Integer, String> textures = writeTextures(loaded, parts, dir, stem);
        if (format == Format.OBJ) {
            writeObj(parts, textures, target, stem);
        } else {
            writeGltf(parts, textures, target, stem);
        }
    }

    private static String stem(Path target) {
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static List<Part> parts(CmdlModel model) {
        List<Part> out = new ArrayList<>();
        for (int si : model.drawnSubmeshes()) {
            CmdlModel.Submesh s = model.submeshes().get(si);
            if (s.vertexStream() >= model.vertexStreams().size() || s.indexBuffer() >= model.indexBuffers().size()) {
                continue;
            }
            CmdlModel.VertexStream vs = model.vertexStreams().get(s.vertexStream());
            int[] ib = model.indexBuffers().get(s.indexBuffer());
            int start = s.indexStart();
            int count = Math.min(s.indexCount(), ib.length - start) / 3 * 3;
            if (count <= 0) {
                continue;
            }
            int min = Integer.MAX_VALUE, max = -1;
            for (int k = start; k < start + count; k++) {
                min = Math.min(min, ib[k]);
                max = Math.max(max, ib[k]);
            }
            if (max >= vs.vertexCount()) {
                continue;
            }
            int n = max - min + 1;
            float[] p = java.util.Arrays.copyOfRange(vs.positions(), min * 3, (min + n) * 3);
            float[] nm = vs.normals() == null ? null : java.util.Arrays.copyOfRange(vs.normals(), min * 3, (min + n) * 3);
            float[] uv = vs.uv0() == null ? null : java.util.Arrays.copyOfRange(vs.uv0(), min * 2, (min + n) * 2);
            int[] idx = new int[count];
            for (int k = 0; k < count; k++) {
                idx[k] = ib[start + k] - min;
            }
            out.add(new Part(s.materialIndex(), p, nm, uv, idx));
        }
        return out;
    }

    /** Material index -> PNG file name (only materials with a base colour). */
    private static Map<Integer, String> writeTextures(ModelLoader.LoadedModel loaded, List<Part> parts, Path dir, String stem) {
        Map<Integer, String> out = new LinkedHashMap<>();
        Map<java.util.UUID, String> byTexture = new java.util.HashMap<>();
        for (Part part : parts) {
            int m = part.material();
            if (out.containsKey(m) || m >= loaded.baseColorTextures().size() || loaded.baseColorTextures().get(m) == null) {
                continue;
            }
            PakAssetRef ref = loaded.baseColorTextures().get(m);
            String shared = byTexture.get(ref.asset().assetId());
            if (shared != null) {
                out.put(m, shared);
                continue;
            }
            try {
                Texture t = TxtrReader.read(PackCatalog.extractDecompressed(ref.pakFile(), ref.asset()), ref.asset().meta());
                TextureDecoder.Image img = TextureDecoder.decode(t, 0, 0, 0);
                String name = stem + "_mat" + m + ".png";
                PngWriter.write(dir.resolve(name), img.width(), img.height(), img.argb());
                out.put(m, name);
                byTexture.put(ref.asset().assetId(), name);
            } catch (Exception e) {
                // exported untextured
            }
        }
        return out;
    }

    private static void writeObj(List<Part> parts, Map<Integer, String> textures, Path target, String stem) throws IOException {
        Path mtl = target.resolveSibling(stem + ".mtl");
        try (Writer w = Files.newBufferedWriter(mtl, StandardCharsets.UTF_8)) {
            java.util.Set<Integer> mats = new java.util.TreeSet<>();
            parts.forEach(p -> mats.add(p.material()));
            for (int m : mats) {
                w.write("newmtl mat" + m + "\nKd 1 1 1\n");
                if (textures.containsKey(m)) {
                    w.write("map_Kd " + textures.get(m) + "\n");
                }
                w.write("\n");
            }
        }
        try (Writer w = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            w.write("# Exported by Mocktroid\nmtllib " + mtl.getFileName() + "\n");
            int base = 1;
            for (int i = 0; i < parts.size(); i++) {
                Part p = parts.get(i);
                int n = p.positions().length / 3;
                w.write("o submesh" + i + "\nusemtl mat" + p.material() + "\n");
                StringBuilder sb = new StringBuilder();
                for (int v = 0; v < n; v++) {
                    sb.append("v ").append(p.positions()[v * 3]).append(' ').append(p.positions()[v * 3 + 1]).append(' ').append(p.positions()[v * 3 + 2]).append('\n');
                }
                if (p.uvs() != null) {
                    for (int v = 0; v < n; v++) {
                        sb.append("vt ").append(p.uvs()[v * 2]).append(' ').append(p.uvs()[v * 2 + 1]).append('\n');
                    }
                }
                if (p.normals() != null) {
                    for (int v = 0; v < n; v++) {
                        sb.append("vn ").append(p.normals()[v * 3]).append(' ').append(p.normals()[v * 3 + 1]).append(' ').append(p.normals()[v * 3 + 2]).append('\n');
                    }
                }
                for (int k = 0; k < p.indices().length; k += 3) {
                    sb.append('f');
                    for (int c = 0; c < 3; c++) {
                        int v = p.indices()[k + c] + base;
                        sb.append(' ').append(v);
                        if (p.uvs() != null || p.normals() != null) {
                            sb.append('/').append(p.uvs() != null ? String.valueOf(v) : "");
                            if (p.normals() != null) {
                                sb.append('/').append(v);
                            }
                        }
                    }
                    sb.append('\n');
                }
                w.write(sb.toString());
                base += n;
            }
        }
    }

    private static void writeGltf(List<Part> parts, Map<Integer, String> textures, Path target, String stem) throws IOException {
        Path binPath = target.resolveSibling(stem + ".bin");
        java.io.ByteArrayOutputStream bin = new java.io.ByteArrayOutputStream();
        StringBuilder views = new StringBuilder(), accessors = new StringBuilder(), meshes = new StringBuilder();
        int[] counter = {0};
        java.util.function.BiFunction<byte[], Integer, Integer> addView = (bytes, targetKind) -> {
            int offset = bin.size();
            bin.writeBytes(bytes);
            while (bin.size() % 4 != 0) {
                bin.write(0);
            }
            if (views.length() > 0) {
                views.append(',');
            }
            views.append("{\"buffer\":0,\"byteOffset\":").append(offset).append(",\"byteLength\":").append(bytes.length)
                .append(",\"target\":").append(targetKind).append('}');
            return counter[0]++;
        };
        List<Integer> materialOrder = new ArrayList<>();
        int accessorCount = 0;
        for (int i = 0; i < parts.size(); i++) {
            Part p = parts.get(i);
            int n = p.positions().length / 3;
            float[] mn = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, mx = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (int v = 0; v < n; v++) {
                for (int c = 0; c < 3; c++) {
                    mn[c] = Math.min(mn[c], p.positions()[v * 3 + c]);
                    mx[c] = Math.max(mx[c], p.positions()[v * 3 + c]);
                }
            }
            StringBuilder attrs = new StringBuilder();
            int pv = addView.apply(floats(p.positions()), 34962);
            appendAccessor(accessors, pv, 5126, n, "VEC3", String.format(java.util.Locale.ROOT,
                ",\"min\":[%f,%f,%f],\"max\":[%f,%f,%f]", mn[0], mn[1], mn[2], mx[0], mx[1], mx[2]));
            attrs.append("\"POSITION\":").append(accessorCount++);
            if (p.normals() != null) {
                int nv = addView.apply(floats(p.normals()), 34962);
                appendAccessor(accessors, nv, 5126, n, "VEC3", "");
                attrs.append(",\"NORMAL\":").append(accessorCount++);
            }
            if (p.uvs() != null) {
                float[] uv = p.uvs().clone();
                for (int v = 0; v < n; v++) {
                    uv[v * 2 + 1] = 1f - uv[v * 2 + 1];
                }
                int tv = addView.apply(floats(uv), 34962);
                appendAccessor(accessors, tv, 5126, n, "VEC2", "");
                attrs.append(",\"TEXCOORD_0\":").append(accessorCount++);
            }
            ByteBuffer ib = ByteBuffer.allocate(p.indices().length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int x : p.indices()) {
                ib.putInt(x);
            }
            int iv = addView.apply(ib.array(), 34963);
            appendAccessor(accessors, iv, 5125, p.indices().length, "SCALAR", "");
            int indexAccessor = accessorCount++;
            int matSlot = materialOrder.indexOf(p.material());
            if (matSlot < 0) {
                materialOrder.add(p.material());
                matSlot = materialOrder.size() - 1;
            }
            if (meshes.length() > 0) {
                meshes.append(',');
            }
            meshes.append("{\"name\":\"submesh").append(i).append("\",\"primitives\":[{\"attributes\":{").append(attrs)
                .append("},\"indices\":").append(indexAccessor).append(",\"material\":").append(matSlot).append("}]}");
        }
        Files.write(binPath, bin.toByteArray());

        StringBuilder images = new StringBuilder(), texs = new StringBuilder(), mats = new StringBuilder();
        int texCount = 0;
        for (int m : materialOrder) {
            if (mats.length() > 0) {
                mats.append(',');
            }
            mats.append("{\"name\":\"mat").append(m).append("\",\"pbrMetallicRoughness\":{");
            if (textures.containsKey(m)) {
                if (images.length() > 0) {
                    images.append(',');
                    texs.append(',');
                }
                images.append("{\"uri\":\"").append(textures.get(m)).append("\"}");
                texs.append("{\"source\":").append(texCount).append('}');
                mats.append("\"baseColorTexture\":{\"index\":").append(texCount).append("},");
                texCount++;
            }
            mats.append("\"metallicFactor\":0,\"roughnessFactor\":0.8},\"doubleSided\":true}");
        }
        StringBuilder nodes = new StringBuilder(), nodeIdx = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                nodes.append(',');
                nodeIdx.append(',');
            }
            nodes.append("{\"mesh\":").append(i).append('}');
            nodeIdx.append(i);
        }
        String json = "{\"asset\":{\"version\":\"2.0\",\"generator\":\"Mocktroid\"},\"scene\":0,\"scenes\":[{\"nodes\":[" + nodeIdx + "]}],"
            + "\"nodes\":[" + nodes + "],\"meshes\":[" + meshes + "],\"materials\":[" + mats + "],"
            + (texCount > 0 ? "\"images\":[" + images + "],\"textures\":[" + texs + "]," : "")
            + "\"buffers\":[{\"uri\":\"" + binPath.getFileName() + "\",\"byteLength\":" + bin.size() + "}],"
            + "\"bufferViews\":[" + views + "],\"accessors\":[" + accessors + "]}";
        Files.writeString(target, json, StandardCharsets.UTF_8);
    }

    private static void appendAccessor(StringBuilder sb, int view, int componentType, int count, String type, String extra) {
        if (sb.length() > 0) {
            sb.append(',');
        }
        sb.append("{\"bufferView\":").append(view).append(",\"componentType\":").append(componentType)
            .append(",\"count\":").append(count).append(",\"type\":\"").append(type).append('"').append(extra).append('}');
    }

    private static byte[] floats(float[] f) {
        ByteBuffer b = ByteBuffer.allocate(f.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float x : f) {
            b.putFloat(x);
        }
        return b.array();
    }
}
