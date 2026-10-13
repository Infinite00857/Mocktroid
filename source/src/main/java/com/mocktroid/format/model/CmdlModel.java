package com.mocktroid.format.model;

import java.util.List;
import java.util.UUID;

/**
 * A parsed CMDL/SMDL/WMDL: decoded vertex streams, index buffers, the
 * submeshes that draw ranges of them, and each submesh's material reference.
 *
 * @param lod0Meshes        indices of the submeshes making up the highest-detail
 *                          LOD, or null if the model has no LOD table (draw all)
 * @param transparentMeshes the subset of {@code lod0Meshes} drawn with blending
 * @param skinJointCount    SMDL only: size of the CHPR skin palette the vertex
 *                          joint indices refer to (SKHD), else 0
 */
public record CmdlModel(String form, float[] boundsMin, float[] boundsMax, List<Submesh> submeshes,
                        List<VertexStream> vertexStreams, List<int[]> indexBuffers,
                        List<MaterialRef> materials, int[] lod0Meshes, int[] transparentMeshes,
                        int skinJointCount) {

    /** One draw: {@code indexCount} indices (a triangle list) starting at
     *  {@code indexStart} in index buffer {@code indexBuffer}, indexing into
     *  vertex stream {@code vertexStream}. */
    public record Submesh(int materialIndex, int vertexStream, int indexBuffer,
                          int indexStart, int indexCount, int flags) {}

    /** Per-vertex attributes the viewer uses; {@code normals}/{@code uv0} may be null.
     *  Skinned models also carry 4 joint indices (into the CHPR skin palette)
     *  and 4 weights per vertex; both null for static models. */
    public record VertexStream(int vertexCount, float[] positions, float[] normals, float[] uv0,
                               float[] joints, float[] weights) {}

    /** One material. MSHP-format models reference a MATI material instance
     *  (which holds the texture slots) plus the MTRL shader it instantiates;
     *  older LEGA-format models embed the slots directly, in which case
     *  {@code materialInstance} is null and {@code inlineTextures} maps slot
     *  tag (e.g. "BCLR" base colour, "NMAP" normal map) to TXTR id. */
    public record MaterialRef(String name, UUID materialInstance, UUID shader,
                              java.util.Map<String, UUID> inlineTextures) {}

    public int triangleCount() {
        int tris = 0;
        for (int i : drawnSubmeshes()) {
            tris += submeshes.get(i).indexCount() / 3;
        }
        return tris;
    }

    public int vertexCount() {
        int n = 0;
        for (VertexStream s : vertexStreams) {
            n += s.vertexCount();
        }
        return n;
    }

    /** Submesh indices to draw: LOD 0 when known, else everything. */
    public int[] drawnSubmeshes() {
        if (lod0Meshes != null) {
            return lod0Meshes;
        }
        int[] all = new int[submeshes.size()];
        for (int i = 0; i < all.length; i++) {
            all[i] = i;
        }
        return all;
    }
}
