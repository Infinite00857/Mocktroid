package com.mocktroid.format.texture;

/**
 * A parsed, deswizzled TXTR: header plus linear (but still block-compressed)
 * element data per {@code [layer][mip]}. Decode a slice to pixels with
 * {@link TextureDecoder}.
 */
public record Texture(TextureHeader header, byte[][][] surfaces) {

    public byte[] surface(int layer, int mip) {
        return surfaces[layer][mip];
    }
}
