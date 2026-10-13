package com.mocktroid.model;

/**
 * Placeholder asset data so the GUI is fully demonstrable before the
 * PAK/format modules exist. {@code MocktroidApp} currently loads this on
 * startup -- once a real PAK is opened through the loader module, swap that
 * call for the real {@code AssetLibrary} and delete this class.
 */
public final class DemoData {
    private DemoData() {}

    public static AssetLibrary library() {
        AssetLibrary lib = new AssetLibrary();
        addDemo(lib, AssetType.VIDEO, "demo.pak",
            "fmv_intro_cinematic");
        addDemo(lib, AssetType.TEXTURE, "demo.pak",
            "hud_reticle_diff", "samus_suit_diff_01", "door_frame_normal", "energy_glow_mask");
        addDemo(lib, AssetType.SOUND, "demo.pak",
            "sfx_charge_beam_loop", "vo_computer_alert", "mus_title_theme");
        // CHPR (character/suit) demo entries filed under Models, matching AssetType.fromFourCc.
        addDemo(lib, AssetType.MODEL, "demo.pak",
            "cmdl_samus_varia", "cmdl_door_blast_shield", "chpr_samus_gravity", "chpr_samus_varia");
        addDemo(lib, AssetType.MATERIAL, "demo.pak",
            "mtrl_metal_scuffed", "mtrl_glass_energy");
        addDemo(lib, AssetType.ROOM, "demo.pak",
            "room_intro_ship");
        return lib;
    }

    private static void addDemo(AssetLibrary lib, AssetType type, String pak, String... names) {
        AssetCategory cat = lib.category(type);
        for (String name : names) {
            cat.entries().add(new AssetEntry(type.fourCc + ":" + name, name, type, pak, null));
        }
    }
}
