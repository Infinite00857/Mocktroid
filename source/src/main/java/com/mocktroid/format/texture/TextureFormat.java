package com.mocktroid.format.texture;

/**
 * TXTR pixel formats, numbered exactly as retrotool's {@code ETextureFormat}
 * (the u32 at HEAD+4). {@code blockBytes} is bytes per pixel for
 * uncompressed formats and bytes per block for block-compressed ones -- the
 * unit the Tegra swizzle works in.
 * <p>
 * Formats observed across the full MP4 romfs (13,173 textures): ASTC
 * 4x4/5x4/5x5/6x6/8x5 (+sRGB) make up the bulk, then BC6H (HDR cube maps),
 * BC7, RGBA8, and a handful of BC1-5/R8. Everything else is listed for
 * completeness and reported as unsupported if it ever turns up.
 */
public enum TextureFormat {
    R8_UNORM(0, 1, 1, 1, "R8 UNORM"),
    R8_SNORM(1, 1, 1, 1, "R8 SNORM"),
    R8_UINT(2, 1, 1, 1, "R8 UINT"),
    R8_SINT(3, 1, 1, 1, "R8 SINT"),
    R16_UNORM(4, 1, 1, 2, "R16 UNORM"),
    R16_SNORM(5, 1, 1, 2, "R16 SNORM"),
    R16_UINT(6, 1, 1, 2, "R16 UINT"),
    R16_SINT(7, 1, 1, 2, "R16 SINT"),
    R16_FLOAT(8, 1, 1, 2, "R16 FLOAT"),
    R32_UINT(9, 1, 1, 4, "R32 UINT"),
    R32_SINT(10, 1, 1, 4, "R32 SINT"),
    RGB8_UNORM(11, 1, 1, 3, "RGB8 UNORM"),
    RGBA8_UNORM(12, 1, 1, 4, "RGBA8 UNORM"),
    RGBA8_SRGB(13, 1, 1, 4, "RGBA8 sRGB"),
    RGBA16_FLOAT(14, 1, 1, 8, "RGBA16 FLOAT"),
    RGBA32_FLOAT(15, 1, 1, 16, "RGBA32 FLOAT"),
    D16_UNORM(16, 1, 1, 2, "D16 UNORM"),
    D16_UNORM_2(17, 1, 1, 2, "D16 UNORM"),
    D24S8_UNORM(18, 1, 1, 4, "D24S8 UNORM"),
    D32_FLOAT(19, 1, 1, 4, "D32 FLOAT"),
    BC1_UNORM(20, 4, 4, 8, "BC1 UNORM"),
    BC1_SRGB(21, 4, 4, 8, "BC1 sRGB"),
    BC2_UNORM(22, 4, 4, 16, "BC2 UNORM"),
    BC2_SRGB(23, 4, 4, 16, "BC2 sRGB"),
    BC3_UNORM(24, 4, 4, 16, "BC3 UNORM"),
    BC3_SRGB(25, 4, 4, 16, "BC3 sRGB"),
    BC4_UNORM(26, 4, 4, 8, "BC4 UNORM"),
    BC4_SNORM(27, 4, 4, 8, "BC4 SNORM"),
    BC5_UNORM(28, 4, 4, 16, "BC5 UNORM"),
    BC5_SNORM(29, 4, 4, 16, "BC5 SNORM"),
    RG11B10_FLOAT(30, 1, 1, 4, "RG11B10 FLOAT"),
    R32_FLOAT(31, 1, 1, 4, "R32 FLOAT"),
    RG8_UNORM(32, 1, 1, 2, "RG8 UNORM"),
    RG8_SNORM(33, 1, 1, 2, "RG8 SNORM"),
    RG8_UINT(34, 1, 1, 2, "RG8 UINT"),
    RG8_SINT(35, 1, 1, 2, "RG8 SINT"),
    RG16_FLOAT(36, 1, 1, 4, "RG16 FLOAT"),
    RG16_UNORM(37, 1, 1, 4, "RG16 UNORM"),
    RG16_SNORM(38, 1, 1, 4, "RG16 SNORM"),
    RG16_UINT(39, 1, 1, 4, "RG16 UINT"),
    RG16_SINT(40, 1, 1, 4, "RG16 SINT"),
    RGB10A2_UNORM(41, 1, 1, 4, "RGB10A2 UNORM"),
    RGB10A2_UINT(42, 1, 1, 4, "RGB10A2 UINT"),
    RG32_UINT(43, 1, 1, 8, "RG32 UINT"),
    RG32_SINT(44, 1, 1, 8, "RG32 SINT"),
    RG32_FLOAT(45, 1, 1, 8, "RG32 FLOAT"),
    RGBA16_UNORM(46, 1, 1, 8, "RGBA16 UNORM"),
    RGBA16_SNORM(47, 1, 1, 8, "RGBA16 SNORM"),
    RGBA16_UINT(48, 1, 1, 8, "RGBA16 UINT"),
    RGBA16_SINT(49, 1, 1, 8, "RGBA16 SINT"),
    RGBA32_UINT(50, 1, 1, 16, "RGBA32 UINT"),
    RGBA32_SINT(51, 1, 1, 16, "RGBA32 SINT"),
    NONE(52, 1, 1, 0, "None"),
    ASTC_4X4(53, 4, 4, 16, "ASTC 4x4"),
    ASTC_5X4(54, 5, 4, 16, "ASTC 5x4"),
    ASTC_5X5(55, 5, 5, 16, "ASTC 5x5"),
    ASTC_6X5(56, 6, 5, 16, "ASTC 6x5"),
    ASTC_6X6(57, 6, 6, 16, "ASTC 6x6"),
    ASTC_8X5(58, 8, 5, 16, "ASTC 8x5"),
    ASTC_8X6(59, 8, 6, 16, "ASTC 8x6"),
    ASTC_8X8(60, 8, 8, 16, "ASTC 8x8"),
    ASTC_10X5(61, 10, 5, 16, "ASTC 10x5"),
    ASTC_10X6(62, 10, 6, 16, "ASTC 10x6"),
    ASTC_10X8(63, 10, 8, 16, "ASTC 10x8"),
    ASTC_10X10(64, 10, 10, 16, "ASTC 10x10"),
    ASTC_12X10(65, 12, 10, 16, "ASTC 12x10"),
    ASTC_12X12(66, 12, 12, 16, "ASTC 12x12"),
    ASTC_4X4_SRGB(67, 4, 4, 16, "ASTC 4x4 sRGB"),
    ASTC_5X4_SRGB(68, 5, 4, 16, "ASTC 5x4 sRGB"),
    ASTC_5X5_SRGB(69, 5, 5, 16, "ASTC 5x5 sRGB"),
    ASTC_6X5_SRGB(70, 6, 5, 16, "ASTC 6x5 sRGB"),
    ASTC_6X6_SRGB(71, 6, 6, 16, "ASTC 6x6 sRGB"),
    ASTC_8X5_SRGB(72, 8, 5, 16, "ASTC 8x5 sRGB"),
    ASTC_8X6_SRGB(73, 8, 6, 16, "ASTC 8x6 sRGB"),
    ASTC_8X8_SRGB(74, 8, 8, 16, "ASTC 8x8 sRGB"),
    ASTC_10X5_SRGB(75, 10, 5, 16, "ASTC 10x5 sRGB"),
    ASTC_10X6_SRGB(76, 10, 6, 16, "ASTC 10x6 sRGB"),
    ASTC_10X8_SRGB(77, 10, 8, 16, "ASTC 10x8 sRGB"),
    ASTC_10X10_SRGB(78, 10, 10, 16, "ASTC 10x10 sRGB"),
    ASTC_12X10_SRGB(79, 12, 10, 16, "ASTC 12x10 sRGB"),
    ASTC_12X12_SRGB(80, 12, 12, 16, "ASTC 12x12 sRGB"),
    BC6H_UFLOAT(81, 4, 4, 16, "BC6H UFLOAT"),
    BC6H_SFLOAT(82, 4, 4, 16, "BC6H SFLOAT"),
    BC7_UNORM(83, 4, 4, 16, "BC7 UNORM"),
    BC7_SRGB(84, 4, 4, 16, "BC7 sRGB");

    public final int id;
    public final int blockWidth;
    public final int blockHeight;
    public final int blockBytes;
    public final String label;

    TextureFormat(int id, int blockWidth, int blockHeight, int blockBytes, String label) {
        this.id = id;
        this.blockWidth = blockWidth;
        this.blockHeight = blockHeight;
        this.blockBytes = blockBytes;
        this.label = label;
    }

    private static final TextureFormat[] BY_ID = new TextureFormat[values().length];
    static {
        for (TextureFormat f : values()) {
            BY_ID[f.id] = f;
        }
    }

    public static TextureFormat fromId(long id) {
        if (id < 0 || id >= BY_ID.length) {
            throw new IllegalArgumentException("Unknown TXTR format id " + id);
        }
        return BY_ID[(int) id];
    }

    public boolean isAstc() {
        return id >= ASTC_4X4.id && id <= ASTC_12X12_SRGB.id;
    }

    public boolean isBlockCompressed() {
        return blockWidth > 1;
    }

    /** True for formats whose stored values are already sRGB-encoded, i.e.
     *  display-ready as-is (vs. linear data that would need a transfer
     *  function, which only matters for the HDR formats here). */
    public boolean isSrgb() {
        return switch (this) {
            case RGBA8_SRGB, BC1_SRGB, BC2_SRGB, BC3_SRGB, BC7_SRGB -> true;
            default -> id >= ASTC_4X4_SRGB.id && id <= ASTC_12X12_SRGB.id;
        };
    }

    @Override
    public String toString() { return label; }
}
