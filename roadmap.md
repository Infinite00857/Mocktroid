# Mocktroid Roadmap

All-in-one Metroid Prime 4: Beyond asset viewer. Browse a romfs dump's PAK
contents and preview textures, models, materials, rooms, sounds, and videos
without dumping/converting anything first. Named for "mocking" a Metroid --
syphoning the game's assets the way the creature syphons life force.

Reference material lives in `Mocktroid/reference/` (target UI mockups + the
in-game Logbook/Settings screens the original look was based on) and
`Mocktroid/assets/` (art assets). Format research references live in
`reference apps for mocktroid/` (cloned copies of retrotool and
MPR-Model-Dumper, used to reverse the binary layouts, not run directly).
**Per the user: don't use MPR-Model-Dumper (LogicFox et al.) going forward --
use mp4x (`E:\mp4x`, Python, made for the user's Prime 4 suit mod) as the
CHPR/model reference.** No Mocktroid code was ever derived from the dumper.

## Stack

Java 21+, JavaFX (UI), Maven. Chosen over Rust/retrotool-gui and .NET/Avalonia
for `jpackage`/`jlink`'s turnkey native `.app`/`.exe` packaging. See chat log
for the full comparison if revisiting this decision.

- **UI**: JavaFX, hand-styled via CSS (no Batik/AtlantaFX/etc.) -- see
  `AngledPanel` for the reusable cut-corner panel shape.
- **Icons**: simple flat SVGs parsed at runtime into `SVGPath` nodes
  (`SvgIconLoader`) -- no full SVG-rendering dependency.
- **Video (FMV0)**: VLCJ (drives system libVLC) -- broad codec support without
  relying on JavaFX's GStreamer-backed media stack.
- **Sound (CSMP)**: shell out to `vgmstream-cli`, which has native RFRM/CSMP
  support for MP4:Beyond's specific container version (confirmed via search).
- **Packaging**: `jlink` + `jpackage`, run per-target-OS (no cross-compiling --
  Windows build needs to happen on Windows or via CI).

## Format notes (confirmed from retrotool's mp4-branch source)

- Container is `RFRM` (32-byte header: magic/size/unk/id FourCC/reader+writer
  version) wrapping either raw data or a sequence of 24-byte-header `Chunk`s.
- `PACK` = RFRM("PACK") -> RFRM("TOCC") -> chunks `ADIR` (asset directory: type,
  UUID, versions, offset/size/decompressed_size), `META` (per-asset metadata
  blobs), `STRG` (asset name strings -- note: `kind` field's 4 bytes are
  byte-reversed relative to every other FourCC field in the format, a quirk in
  retrotool's own source that must be replicated exactly).
- All primitives little-endian. UUIDs stored as mixed-endian "GUID" bytes
  (`Uuid::from_bytes_le` semantics).
- Compression: custom LZSS with an MP4-specific Huffman variant (modes
  0-3 = plain LZSS at 3 granularities, 12-14 = Huffman-coded LZSS). Not
  zlib/zstd -- this is why nothing generic opens these files. Huffman tables
  (768 entries) mechanically ported from `lzss.rs` via a regex transform
  (`HuffmanTables.java` generation script), not hand-typed, to guarantee
  fidelity.
- `FMV0` = RFRM("FMV0") wrapping a raw MP4 byte stream directly -- the
  32-byte-strip approach in the user's original `strip_fmv0_headers.py` is
  exactly correct, just less robust than parsing the real header.
- `CHPR` (character/suit definitions) isn't handled by retrotool at all --
  mp4x (`E:\mp4x\FORMATS.md`, `mp4x/chpr.py`) documents its skeleton/skin
  encoding.

## UI direction (v2 -- supersedes the v1 Logbook-rail layout)

v1 (rail-left / grid-center / drill-down detail view) is retired in favor of
the mockups in `reference/how i want it to look *.png`. Key differences:

- **Custom undecorated window** -- no native title bar; own drag region +
  close/minimize/maximize buttons top-right, matching the mockups' chrome.
- **Top tab bar** for categories (Videos / Textures / Sounds / Models / ...)
  plus a centered settings tab (visor icon, filled variant on hover). Active
  category tab renders larger/highlighted.
- **Persistent split view**, not drill-down: asset list/grid on the left,
  preview pane on the right, both visible at once. List and grid are two
  interchangeable presentations of the same category contents, switchable
  in Settings (chosen once at first run, changeable after).
  - **List mode**: rows of asset names/GUIDs.
  - **Grid mode**: square thumbnails, configurable columns (default 3).
    Thumbnail states: no cache yet -> generic per-type placeholder (Screw
    Attack S icon); actively generating -> spinning morph ball
    (`mball loading.png`, clockwise); cached -> real render.
- Custom-styled thick glowing scrollbar (not the default JavaFX one) on the
  list/grid panel.
- "Filter" control, bottom-left of the list/grid panel; activation mode
  (hover vs. click) is a setting.
- Preview pane: "Previewing: <name>" header, square viewport with
  targeting-reticle corner brackets, small fullscreen-toggle button,
  border drag-handles to resize (deferred until a real preview module exists
  to make resizing meaningful).
- Contextual bottom transport bar: hidden for Textures; "animation playback"
  (stop/play + frame-scrub, A/D = step one frame) for Models; a media-time
  play/pause/seek bar for Sounds/Videos. Stubbed as inert until the relevant
  preview module exists.
- `InfiniteLogo.png` appears as a small watermark in Settings.

Colors are Claude's call (user: "didn't put much effort into color in the
mockups"). Chrome (panels, tab shapes, corner decorations, backgrounds,
buttons) is CSS-drawn, matching the mockups' geometry -- per the user's
"try not to use my assets now that I've seen your CSS capabilities," the
`.png` chrome assets in `Mocktroid/assets/` (panels, corners, generic
buttons, both `mocktroidBG*.png`) are treated as *style reference only*, not
loaded by the app. The exceptions, kept because they're unique content a
stylesheet can't reproduce and are called out directly in the new mockups:
`visor.png` / `visor filled.png` (settings tab icon, default/hover),
`mball loading.png` (thumbnail loading spinner), `Screw Attack S.png`
(placeholder/empty preview + uncached thumbnail icon), `InfiniteLogo.png`
(settings watermark).

## First-run flow

On first launch (no saved romfs path):
1. **Romfs directory picker -- required.** Won't proceed without a valid path
   containing the expected pak layout.
2. **"Build model thumbnail cache now?"** Yes/No/Later -- warns this can take
   a long time. (Dev note: real romfs dump for testing lives at
   `/Users/infinite/projects/metroidprime4/MetroidPrime4RomfsDump/romfs` on
   this machine -- not to be hardcoded as an app default, just for our own
   testing once the PAK reader exists.)
3. **List or Grid** -- default browse mode, changeable later in Settings.

## Settings (current + planned)

- General: romfs path (with re-run-onboarding / re-picker option), default
  browse mode (list/grid), grid columns, filter activation (hover/click).
- Audio: mute, master volume.
- Preview: render resolution, **custom skybox for the model previewer**
  (planned -- image picker setting; wired up once the model preview module
  exists).
- Thumbnail cache: build/rebuild, clear.

Settings screen takes over the *entire* window (category tabs and list/grid
disappear while open), not just the center content area.

## Module status

| Module | Status |
|---|---|
| Format research (RFRM/Chunk/PACK/LZSS-Huffman/FMV0) | Done -- byte-for-byte verified against a real romfs dump |
| `HuffmanTables` (mechanically ported) | Done, wired into `Lzss`/`Compression` |
| PAK reader (container + LZSS/Huffman decompression) | Done -- `PackReader` (full-file), `PackCatalog` (lightweight scan + lazy extract, used for romfs-wide cataloging), `RomfsLibrary`. Verified against all 441 real paks (90,416 assets), both compressed and uncompressed samples, decompressed output round-trips correctly |
| GUI shell v1 (rail/grid/drill-down) | Superseded by v2 |
| GUI shell v2 (tab bar / split list-grid+preview / custom window chrome) | Done -- undecorated window w/ working drag-move, edge/corner resize, and manual maximize toggle |
| Onboarding wizard | Done |
| Settings v2 (full-window, General/romfs path, skybox) | Done (skybox field present, not wired to a previewer yet) |
| Sound (CSMP) preview | Done (also verified on Windows) -- extract from real pak, decode via vgmstream-cli, play/pause/seek through a real MediaPlayer, wide waveform view with playhead + green loop-start marker, next/previous navigation, autoplay setting, configurable loop count. "Use game UI sounds" setting added (inert, reserved) |
| Sound Waveform Cache | Done -- background Task (Settings > General), persisted per-sound min/max amplitude bins + loop/duration metadata, grid view renders real mini-waveforms once cached. Validated against real data (see notes below) |
| Pak filter | Done -- popup checklist above the browse list, scoped to paks present in the current category |
| Sort (alphabetical/duration/pak, asc/desc) | Done -- duration sort accuracy depends on the Sound Waveform Cache being built (falls back to 0 for uncached entries) |
| TXTR preview (+ BC1-7/ASTC decode) | Done -- `format/texture/*`, `ui/preview/TexturePreview`. All 13,173 real TXTRs parse, deswizzle and decode with zero failures (13,169 match the game's recorded swizzled size exactly, 4 carry extra trailing padding). Pixel output checked against `texture2ddecoder`: ASTC/BC1/BC3 within +-1 (rounding), BC4/BC5/BC7 bit-exact. Mip/layer/cube-face/3D-slice selection, channel isolation, HDR exposure, zoom/pan, PNG export |
| Texture Thumbnail Cache | Done -- lazy (generated as grid cards scroll into view, LIFO so on-screen cards win), persisted under `<app data>/texture-thumbnails/v2`; Settings > General shows count + Clear |
| CMDL/SMDL model preview (JavaFX 3D: SubScene/MeshView/PhongMaterial) | Done -- `format/model/*`, `library/ModelLoader`, `ui/preview/ModelPreview`. 19,801/19,803 CMDL + 524/524 SMDL parse (2 use the unknown compression mode 6). Orbit/pan/zoom, LOD 0 only, base-colour textures, translucent parts, wireframe, custom skybox setting now wired. Skinned SMDLs (Samus's suits) are in the Models tab |
| MTRL/ROOM preview | Not started |
| Model animation mode (double-click a model) | Shell done with real data: double-click -> browse list becomes the model's clips (from every CHPR that references it), transport gets play/stop/scrub/A-D stepping; no clips -> red outline blink. Playback **poses the model** (2026-10-12): `AnimationSupport.decode` finds the clip payload, `ModelPreview` samples it per frame, chains joint locals, CPU-skins the meshes (delta `posed * bind^-1`, so the rest pose is exact) and redraws the bones overlay. Verified on Samus (violasuit_ic) idle / landing / roll clips. Limits: clip constants assumed identity (pool not located); joints the heuristic hierarchy misses stay at bind; big multi-segment CHPRs (e.g. 7238eaad) don't parse in ChprSkeleton |
| Derived names (`library/NameIndex`) | Done -- background + cached (`<app data>/names-v2.bin`). All 518 SMDLs (CHPR skin names), 1,380/1,414 CHPRs, 2,960 CMDLs (LEGA material names), 2,221 TXTRs (`<model>_<slot>`). Paks' own STRG names only cover ~650 assets (all ROOM/MSBT/FONT/CMAP/DGRP/LDTA) |
| Custom names (`library/CustomNames`) | Done -- user renames stored in `<app data>/custom-names.tsv` (`GUID<TAB>name`, keyed by GUID so every pak copy follows). Rename via preview-header pencil, F2, or right-click menu (Rename / Restore / Copy name / Copy GUID). Priority: custom > derived > pak name > GUID; filter also matches the original name and GUID. Settings > General: import/export/clear |
| Duration sort (`library/SoundDurations`) | Done -- lengths read from each CSMP's DSP header (`format/audio/CsmpDuration`: DATA chunk body = per-channel DSP header; samples from nibble count like vgmstream), pak-by-pak in offset order (~14 s cold for 17k entries on HDD, was ~117 s unordered), cached in `<app data>/sound-durations.bin`. Duration options only shown on the Sounds tab; unknown lengths sort last |
| CHPR (character/suit) support | Partial -- `format/chpr/ChprInfo`: string table, skin names, animation clip list (names + frame counts). `format/chpr/ChprSkeleton` (ported from mp4x's layout notes): joints, hierarchy, local transforms, skin palettes + inverse bind matrices, bind pose (exact for skinned joints). Clip key data: **decoded** -- `format/chpr/AnimClip` (payload locate, stream table, descriptor bytecode, key init/step, sample ops 2/3/6/7), verified bit-exact against game captures and on all 1157 Samus clips. `ChprSkeleton.poseAddress(joint)` = hierarchy record's last field = where clips write the joint |
| Skeleton overlay (Bones toggle) | Done -- `library/SkeletonLoader` picks the CHPR skin whose palette size matches the SMDL's SKHD joint count; ModelPreview draws joints + parent links over the mesh. Verified on the drop pod (31 joints, door panels) and Samus `violasuit` (118 joints). SMDL vertex joints/weights (semantics 10/11) are now decoded into `VertexStream`, ready for CPU skinning |
| FMV0 video preview | **Done (2026-10-12)** -- `ui/preview/VideoPreview`: FMV0 = RFRM header + plain MP4 (H.264, no audio track); the MP4 from its `ftyp` box goes to a temp file played by JavaFX Media (no VLC needed). 16:9 viewport, prev/play/next, full-width seek, honours Autoplay videos |
| Video playback fixes (2026-10-12) | Intermittent `ERROR_MEDIA_INVALID`: Windows' H.264 decoder refuses to initialise while another JavaFX player is still setting up (verified: 12/12 play when players are disposed in turn, random failures when they overlap) -- `VideoPreview` retries up to 3 times. One FMV0 (`b8e9b2f6`) is a **WebM (VP9 + Vorbis)**; `format/video/FmvExtractor` converts it to H.264 with ffmpeg when ffmpeg is on PATH, otherwise explains why it can't play |
| Video grid icons | First frame (muted off-screen player, one at a time, retried), stored in the same icon cache as models; the preview's **Cache icon** button also works on videos (current frame) |
| Model camera (2026-10-12) | **Free camera** toggle (unlocks from the model: drag looks around in place), **Follow model** toggle (camera tracks the top skinned joint through root motion), hold **right-click** to fly (cursor hidden + re-centred with `javafx.scene.robot.Robot`, mouse look, WASD, Q/E down/up, Shift faster, wheel steps a frame while paused), **space** plays/pauses anywhere (scene-level filter), shift/middle-drag pans. Texture cap raised 1024 -> 2048 (Samus's main suit texture is 2048) |
| Model detail maps (2026-10-12) | Material slots found per material (`ModelLoader.LoadedModel.textureSlots`): **NMAP** tangent-space normal map -> JavaFX bump map (green inverted: the preview flips V), **METL** packs metal (R) / roughness (G) -> specular map (smoothness^2, stronger on metal), **ICAN** is a glow colour *ramp* (not UV-mapped); the glow mask is the base colour's alpha on opaque submeshes (lit strips/trims) -> self-illumination = mask x ICAN average colour. TCH0-4 (tiling detail layers) unused. "Detail maps" toggle; key DirectionalLight added. Picking a clip puts the camera on the posed body. Settings > Preview > **Show floor** (fading grid plane at the model's lowest bind point, default on) |
| Camera keys (replaces right-click fly) | Right-click fly removed (Robot re-centring still drifted on the user's display). With the viewport focused: **WASD** moves along the view, **Q/E** down/up, **Shift** = fast, **Shift+wheel** changes the fast multiplier (x1.25 per notch, 1.25-50, shown top-right), **Z/X** and **Ctrl+wheel** step next/previous frame (pauses, wraps), space plays/pauses |
| Preview controls (2026-10-12, later) | Pan is **Ctrl**+left-drag (Shift is fast movement now). Settings > Preview > **Animation Speed** slider, 1-120 fps (default 30; the clips' real rate isn't decoded), applied live via the timeline rate |
| Normal maps: ASTC two-channel | Most ASTC NMAPs store X in RGB and Y in alpha (the usual ASTC swizzle; detected as R=G=B with varying alpha). Reading them as RGB gave the arm cannon its blotchy "static z-fight" shading |
| Glow (ICAN), reworked | ICAN the size of the base colour = a glow texture in UV layout (visor HUD lines, face light, cinematic gun fire-shot panels), masked by base alpha on opaque submeshes, unmasked on blended ones (the visor now glows). Smaller ICAN = lookup table: across = base alpha (glow mask, also scales brightness), down = time -- tables that change down their length are played as 16 frames over 2 s (the arm cannon's lights; timing is a guess). Noisy tiling ICANs (legacysuit's blue energy smoke) fall back to their average colour. Material values seen in MATI: DIFC, ICMC (always 0,0,0,1), ICNC (glow multiplier), CCHn/BKLT -- not used yet |
| Hide visors setting | Settings > Preview > **Hide visors**: blended submeshes are hidden (disables the transparent-visor toggle). Transparent visor itself re-tested on violasuit_ic and legacysuit: works (couldn't reproduce the reported regression) |
| CHPR local transforms: slot-major | Type-04 hierarchy records' `c = slot \| row*5 << 6`; transforms are stored slot by slot, so index = slot base + row. mp4x's `(c >> 6) / 5` is the single-slot case and broke every multi-slot skeleton (Sylux + Lockjaw: slots 5-8 collided). Some rows belong to unparsed records, so each slot base is calibrated by vote from skinned joints whose exact local transform (from inverse binds) matches a table row; others continue from the slot below. Verified: sylux 78/80, psydroid_sylux 73/75, samus 54/58, cinematic gun 50/50 (remaining = ties between identical rows) |
| Cinematic gun | `samus_cinematic_gun` (SMDL e0358935) + its own CHPRs per cutscene (e.g. c385cdcb in fg_3040_baseCamp: FireShotAcquired shots 01-17). Materials: 0 base (same cyan BCLR / NMAP / METL as the suits' cannon), 2 orange pulsing ICAN, 6 fire-shot panels (UV ICAN), 4/5 others; submeshes 0/4/5 are 608-tri copies (beam/missile variants?). The cannon looks the same on every suit; beams and missiles change it. Base colour is still the cyan the BCLR stores (mostly metal per METL -- JavaFX has no reflections) |
| Suits: metal + visors (2026-10-12, later) | Test model: **Samus Varia suit** = SMDL 467c447c ("default" in universeRoom.pak); `psychic` 3bc5ed9a = Psychic suit, `psychic` 4f05325d = first-person viewmodel (fps_* joints). **Metal**: with Detail maps on, the base colour is darkened by METL metalness (x(1-0.55m)) and metal highlights are tinted by the base colour -- Varia reads bronze, the arm cannon dark metallic teal (in game it IS teal; the cyan BCLR is right). **Opaque visors** (Varia/Psychic draw the visor opaque, no blended submesh): small submeshes bound wholly to one joint, with their own material sharing the body BCLR and a full-size ICAN, count as visors -- Hide visors / Transparent visor (0.45 opacity) / unmasked visor glow apply to them |
| Glow, more | Tables with a black first column are lookup tables (mask 0 = dark); tall narrow ramps (8x256, the orange barrel light) sweep a lit band up and down (v = mask + 0.5 sin t), 20 frames / 3 s (game timing unknown). Varia cannon: material 5 = inner barrel (orange sweep works), material 4 = body; its BCLR-alpha mask only covers the muzzle grille + a lamp -- the **green side dots and orange side strips** seen in game need another mask source (vertex colours? not decoded) |
| Paint strips (Viola bikes) | Materials with a 1-px-tall TCH1 colour strip + a TCH2 grey map in UV layout: base colour x strip[TCH2] -- the bikes' iridescent purple/blue/red finishes. The bike body (512x2048 BCLR material) isn't painted yet |
| Browse + preview UI (2026-10-12, later) | Search box is a plain always-visible field ("Search...", one click focuses); leaving the animation viewer restores the search text and scroll position; preview buttons moved to a tool row above the viewport (Cache icon, model camera toggles, fullscreen); **fullscreen** works (hides browse pane + tab bar, window fullscreen, Esc/X leaves); settings icon 18 -> 24 px; animation speed default 60 fps (user-confirmed game rate) |
| Animated textures: survey | 2D arrays >= 8 layers: 113. Others: 499 arrays of 4 layers (material layer stacks), 115 3D textures with depth >= 8 (many 33^3 / 32^3 = colour LUTs / noise). UI textures are plain 2D -- UI animations are likely sprite sheets or per-frame textures driven by UI data; need an example from the user |
| CHPR constant pools (2026-10-12) | Values a clip holds for its whole length index a per-CHPR float pool: after `01 00 00 00 00 01 <n> 00 00 00`, n x (`u16 index, u16 count, f32[count]`), each starting 1,0,0,0 (found unaligned). Streams pick their pool by the stream table's pool field. Fixes held poses (Sylux's spikes, fingers) -- `AnimClip.findPools` |
| CHPR transforms: big rigs | The player rig (f1ed30ec, 294 joints: first-person arms + third person + bike) continues its transform table after a 4-byte break -- runs starting within one record of the end are joined. Slots with no skinned joint are checked against the skinned bind poses: every guessed-rows / identity combination over them is scored (only slots holding ancestors of skinned joints); the first-person root / arm-base slot (27) has no rows = identity. First-person clips (fp_*, in the player CHPR 7238eaad -- animation-only, no skeleton) now pose correctly. Cross-CHPR clips are remapped by joint name when the files' addresses differ (`AnimationSupport.addressRemap`) |
| First-person viewmodels | One per suit (listed in the player CHPR): default ca0e9110 (Varia), psychic 4f05325d, violasuit ccd5793d, violasuit_ic 8bb87307, legacysuit 8c1dc538. All carry an **ice shell** (material MATI 8c4c0e03, shader a9739d76: snow + icicles) -- Settings > Preview > Arm Cannon Frost (0-100% opacity, 0 hides). Cannon-shader (3425eca6) materials with white low-alpha base colour (fire-shot parts) are drawn gunmetal teal |
| Rendering fixes (2026-10-12, later) | Bilinear sampling when combining textures of different sizes (METL / masks); textures < 1024 px upscaled bilinearly (JavaFX samples nearest -> blocky faces, stair-stepped normals); glow tables: each lit pixel uses the most common table column of its 5x5 neighbourhood (orange specks on Viola IC trims); narrow ramps only sweep when their rows really change (Sylux's flat green no longer animates); cut-out alpha (mostly opaque + clear pixels, e.g. eyelash cards on the face texture) is kept and drawn after opaque meshes; visor glow at 60% |
| UI: window + browse (2026-10-12, later) | Title bar: double-click maximizes/restores, dragging a maximized window restores it under the cursor, releasing at the top edge maximizes and at left/right edges half-snaps (undecorated window can't use Windows' own snapping); same on the tab bar's empty areas. Preview corner brackets sit outside the viewport. Count label: "double-click a model to open its animations" |
| Autoplay next | Settings: play the next sound / video / animation when one finishes (moves to the next list item / clip and starts it -- `PlayChain`) |
| Game UI sounds | Settings > Audio > Use game UI sounds now works: cues mapped from the user's renamed sounds (hover 14086ba7, select 2178f2b5, tab 990af14f, back b215793d, cancel 5c9381c6, open list 41fe0575, refuse c30b0591, scroll 0cf9afd2, pause/resume 4a9ffa5d, confirm 18e2d757, open app 644dfb1b, close 8895227d, drag aa30af2c), decoded once with vgmstream into AudioClips; scene-wide hooks (buttons, card/row/tab hover, list scroll) + the old SoundManager routed to it |
| Update romfs | Settings > General > Update RomFS Path: an emulator dump of the update's romfs; its paks replace base paks at the same relative path (new ones added). update.nsp itself is encrypted -- not read directly |
| Export (2026-10-12) | Preview tool row **Export...**: models -> OBJ (+MTL) or glTF 2.0 (.gltf + .bin), bind pose, base-colour PNGs (shared textures written once); sounds -> WAV (vgmstream), FLAC / OGG / MP3 with ffmpeg; videos -> MP4 (WebM ones converted with ffmpeg). Animated textures: Export GIF (JDK encoder) / WebP (ffmpeg) at the flipbook's fps. Settings > About: Nintendo notice (+ footer line) |
| UI animations (frame sequences) | UI animations are one texture per frame (UI_HUD: five 31-frame 180x180 sets, e.g. 0aa26829). Frame order lives in UI data (the UI ROOM only lists dependencies) -- for now `TextureSequences` groups same-size textures in UI paks whose 16x16 thumbnails differ < 0.06 RMS, orders them by nearest-neighbour walk from one end, and lists them in Animated Textures (cached in texture-sequences.bin) |
| Video stutter | Icon generation (model renders + video first frames, both on the FX thread / decoder) pauses while a video plays |
| Quick changes (2026-10-12, last) | Scroll UI sound removed; app-open sound decoded first; drop-down menus play the open-list sound, opening the animation viewer plays 3527788e; waveform click/drag seeks; animation transport has previous / next animation buttons; **Lockjaw** toggle (submeshes mostly weighted to lockjaw_* joints); CHPRs no longer listed in Models (still indexed); `Run Mocktroid.sh` (Linux) and `Run Mocktroid.command` (macOS) -- chmod +x them after copying from Windows. Notes for the user: their discoveries write-up is `DISCOVERIES.txt` |
| **Open (user requests, 2026-10-12)** | (1) Arm cannon green side dots / orange side strips (mask source unknown). (2) Visor inner pattern + scanning look (scanning is a cutscene state -> cinematic viewer). (3) Hotkeys page. (4) Cinematic viewer (plan below) -- incl. game camera playback for clips. (5) Grapple clip: red pieces around the cannon and the hand pose need checking against the game. (6) Carvex flower model + the 'rectangle textures' model -- need names. (7) Arm cannon looking off in some animations (screenshot 7) -- need the clip. (8) UI animation frame order from the real UI data. (9) NEXT SESSION, from the user: viewmodel fingers still a bit off (grapple / pinDown_fromDeath_tele: fingers bent oddly); many viewmodel clips leave the arms still -- probably morph ball / Viola bike clips sharing the player rig (filter or label clips by which joints they move); many psydroid and Griever animations are broken (check their rigs with LocalMatch / RelAbs / ChainPose like Sylux and the viewmodel). |
| Root motion | Clips put the whole-body placement (yaw, rolls, falls) at index 0 of the top joint's slot and the actor's travel at index 0 of the next slot (Samus: 2/0 and 3/0; 2/5 = 2/0 + 3/0 + the cinematic's scene offset). Those addresses belong to root joints (`Skeleton_Root`, `*.anchorBlend`) whose hierarchy records use an undecoded record kind, so `ModelPreview.rootMotion` applies them above every joint without hierarchy data when no joint claims them |
| Categories | **All** tab (pinned right of settings: every video/texture/sound/model/room) and **Animated Textures** (2D arrays with >= 8 layers, found by a background HEAD-chunk scan cached in `texture-layers.bin`; 113 in the full romfs) |
| Preview render resolution | Real now: the model previewer renders at the chosen size and scales to fit -- Low 1280x720, Medium 1920x1080 (default), High 2560x1440, Ultra 3840x2160, or Custom (width x height boxes, digits only, 64-7680) |
| Model grid icons | **Done** -- `ui/preview/AssetIconCache`: rendered lazily as cards scroll into view (load + texture decode off-thread, one off-screen 3D snapshot), cached on disk; preview's top-left **Cache icon** button stores the live view instead; Settings row shows count + Clear |
| Texture flipbooks | **Done** -- 2D array textures (30-64 layers = animation frames, e.g. smoke/explosion sprites) get a flipbook row: play/pause, frame scrub, fps (10-60); frames decoded once, played from memory; autoplays (>= 8 frames) with Autoplay animations |
| UI changes (2026-10-12) | Filter bar moved to the top of the browse pane; model + video previews are 16:9; transport content stretches across the whole bar; Materials tab removed; Rooms (and other unsupported categories) show a "not implemented yet" screen instead of the browser; Settings > Preview: **Transparent visor** (off = opaque like in game; blended submeshes keep alpha only when on) and **Autoplay animations** |
| Model Thumbnail Cache | Not started -- needs the model preview module first |
| Packaging (jlink/jpackage, icon conversion, Windows build) | Not started |


## Plan: cinematic viewer (requested 2026-10-12)

Goal: a **Cinematics** tab where you pick a cutscene and watch it in the 3D previewer -- every actor
animated, the cutscene camera, the room around it, with suit (and beam) toggles.

**What's known already**
- Cutscene clips are named `<cutscene>_shotNN_<actor>`, spread over one CHPR per actor per cutscene,
  e.g. `fg_3040_Basecamp05_FireShotAcquired_shot12_` + `samus_cinematic`, `samus_cinematic_gun`,
  `engineer_trooper`, `fg_3040_terminal_radial`, and `CinematicCamera` (in a CHPR named "Camera").
- Clips decode (`AnimClip`), skeletons map (incl. multi-slot rigs), root motion places the actor --
  cinematic clips are in scene space (the gun sits at ~(11, -4, -33)), so actors that share an origin
  should line up without a separate placement.
- The cutscene's pak holds the rest: fg_3040_baseCamp.pak = 1 ROOM, 2 FSMC (state machines),
  24 DLOG, 880 CSMP/CAUD (sounds), 37 GENP (particles), 1 FMV0, 12 SMDL/36 CMDL, 73 CHPR.

**Phases**
1. *Cutscene index (no new RE).* Scan every CHPR's clip names, group by `<cutscene>` -> shots -> actors;
   resolve each actor's model through the CHPR -> model map. Cinematics tab lists cutscenes; a cutscene
   page lists its shots (frames) and actors.
2. *Multi-actor stage.* One SubScene with several skinned models, each posed from its own clip on a
   shared timeline; shots play back to back (shot order from the names; check against the game).
   Per-actor show/hide toggles. Reuses ModelPreview's loading/skinning (split it into a reusable
   "actor" + "stage").
3. *Cutscene camera.* Decode the `CinematicCamera` clips (camera joint transform + float channels --
   probably FOV/focus, the 0x60/0x61 nodes) and drive the PerspectiveCamera; "Free camera" detaches.
4. *Suit + beam toggles.* Samus's cinematic actor shares the suit skeleton (`samus_all_onlyskins`), so
   any suit SMDL (violasuit, legacysuit, ...) can be swapped in. The gun's beam/missile looks are
   material/submesh variants (fire-shot panels, orange lights) -- needs mapping which submesh sets go
   with which upgrade; then a beam dropdown.
5. *Room.* ROOM format RE (not documented anywhere yet, mp4x included): static world models and their
   placements, then lights. Until then: an option to show the pak's CMDLs at their origin.
6. *Timing + extras (later).* What sequences shots and fires sounds/dialog/particles -- likely FSMC or
   ROOM script data. Then audio (CSMP via vgmstream), subtitles (DLOG/MSBT), particles (GENP).

**Risks / unknowns**: real clip frame rate (30 fps assumed; the speed slider is the stopgap), shot
order and cuts, per-shot actor visibility, animated material values (ICNC/ICMC, beam glows),
camera FOV channel, ROOM/FSMC formats.

## TXTR format notes (MP4, confirmed against the full romfs)

- Every TXTR is RFRM("TXTR") v65/66 and **self-contained** (unlike Prime
  Remastered): `HEAD` chunk (kind, format, w, h, layers, 4 component bytes,
  mip count + sizes, sampler) then a `GPU ` chunk.
- How the GPU bytes become the swizzled buffer is in the asset's pak
  **TOCC/META** blob (`PackToc` now keeps META per asset in
  `PackAsset.meta`). Layout = retrotool mp4-branch `STextureMetaData`; the
  first/second buffer assembly is ported step for step in `TxtrReader`.
  Buffers are the usual LZSS/Huffman modes. Two 4x4 placeholder textures use
  an unknown **mode 6** for their padding-mip buffer only -- tolerated
  (mip 0 still decodes).
- Texel data is **Tegra X1 block-linear** swizzled (`TegraSwizzle`, written
  from the hardware layout; Eden's GPL source was only used to cross-check
  the math, not ported). 2D/cube/array: block height from mip-0 height,
  shrunk per mip, layers padded to a mip-0 block. **3D textures always use
  block height 1** (even at depth 1) with block depth from the depth.
- Rows are stored **bottom-up** (vertical flip only -- play-button triangles
  in UI art still point right). `TextureDecoder` flips to top-down.
- Format mix (13,173 textures): ASTC 4x4/5x4/5x5/6x6/8x5 (+sRGB) ~70%,
  BC6H ~23% (2,576 of them HDR cube maps), BC7, RGBA8, a few BC1-5/R8.
- Decoders: ASTC written from the Khronos spec (`AstcDecoder`, LDR profile;
  HDR endpoint modes -> magenta error color, none occur). BC1-7 incl. BC6H
  ported from bcdec (MIT/Unlicense); its partition tables and BC6H bit
  layouts are generated by `source/tools/gen_bcn.py`, not hand-typed.
  BC6H SFLOAT couldn't be cross-checked (reference lacks a signed mode);
  value ranges look sane (bright HDR, tiny negatives) -- use the exposure
  slider.
- Note: some textures genuinely contain pure magenta (#FF00FF) art, so
  "magenta pixels" alone doesn't mean an ASTC decode error.

## Model format notes (MP4, confirmed against the full romfs)

- CMDL is RFRM v163/172, SMDL (skinned) v177/181; same chunk set: HEAD/SKHD
  (u32 + AABB), MTRL, MESH, VBUF, IBUF, GPU. Layout = retrotool's mp4-branch
  `cmdl.rs` except where noted below.
- **MP4 is Y-up** (older Primes were Z-up). The viewer maps (x,y,z) ->
  JavaFX (x,-y,-z).
- META gives the GPU buffers; each buffer entry has MP4's extra trailing u32.
  **GPU sub-buffers always carry the 4-byte compression-mode prefix**, even
  when compressed size == output size (`Compression.decompressBuffer`);
  treating equal sizes as raw (the pak-entry rule) corrupted 22 models.
- MTRL comes in two formats:
  - `MSHP` (most models): u32 count, u32 unk, u32 n + u32[n] (n = 0 for static
    models, 44 for Samus's suits), then 38-byte entries {GUID MATI material
    instance, GUID MTRL shader, 6 bytes}. Texture slots live in the MATI
    asset's MDAT chunk, whose header varies by shader, so `ModelLoader` finds
    slots by scanning for tag + type byte 5 + a GUID the romfs index confirms
    is a TXTR.
  - `LEGA` (~3,000 models): materials embedded inline, exactly retrotool's
    CMaterialCache layout (slot FourCC, "TXTR", GUID, 20-byte usage, ...).
  - `BCLR` = base colour; also NMAP, METL (multi-channel mask), ICAN, REFL,
    TCH0-5 (layer masks). Many surfaces get their final colour from MP4's
    layered shader (masks x colour parameters), which isn't emulated, so some
    parts look flatter than in game.
- MESH LOD table has **6 ranges per LOD** (Prime Remastered: 5): r0 all
  visible, r1 opaque, r2 transparent, r3/r4 a low-detail shadow-caster set,
  r5 = r0. Drawing the union put Samus's shadow mesh (purple shoulders) over
  her real suit. The viewer draws r0 of LOD 0 and blends r2.
- Base-colour alpha is a mask on opaque surfaces (Samus's armour is ~95%
  "transparent" by it), so only the transparent range keeps texture alpha.

## CHPR animation notes (2026-10-11)

- Clips **do** live in CHPR (confirmed, and per the Metroid Prime modding
  Discord). The string table holds clip names, e.g.
  `closed_to_open_gf_pirate_dropPod`, `BC_nora_sings_junior_trooper`.
- Clip record: `u16 0, u16 clip index, 00 00 00, 01 01, u32 name (string
  index), ff ff ff ff, ..., f32 1.0 @+0x1d, u16 frame count @+0x21`, then the
  compressed keys. Small CHPRs store each clip as a header side blob; big ones
  also inline them in the body (found by that signature). Shared gameplay
  clips live in big shared projects (e.g. 759 clips in a 30 MB trooper CHPR);
  per-cutscene CHPRs hold only their shot clips.
- Static clips are tiny (97 bytes for a 1-joint crate); animated ones are
  dense bit-packed data with visibly repeated per-track runs.
- **Game code** (Ghidra project `E:\Mprime4mod\MP4Optimizer\re\macghidra`,
  run scripts with `re/gm.sh`):
  - `FUN_710089a83c` (12 KB, callers `FUN_710089a600`, `FUN_7100e54b8c`) is the
    animation-set deserializer. It reads a byte stream (`param_2`: +0x20 buffer,
    +8 offset, +0x38 endian flag) and allocates the codec structures:
  - `NAnimStream::SFragDecode` (read by `FUN_7100e5c924`): `u16 a, u16 n,
    u16[n]` -- a per-fragment decode recipe. Stored at object +0xa8.
  - `NAnimComp::SConstPool` (read by `FUN_7100ba6f18`): `u16 a, u16 n,
    u32[n]` -- constant values for non-moving tracks. Stored at +0xb0.
  - **Next step:** map the loader's stream reads onto the crate clip bytes
    (`gf_crate_med_with_pallet_01`, 1 joint) to get the full clip layout, then
    find the runtime sampler that consumes SFragDecode (search for code
    reading object +0xa8/+0xb0, or the callers of the loader's users).
- **2026-10-12 findings:**
  - `FUN_710089a83c` is the **character project loader** (allocs `SChannel`, `SAnchorInfo`, `SModelNode` x 0x70, `SAnimParamNode`), not a clip decoder. It reads the clip count (u16 at char+0x3c) and allocates the `CAnim *` table at char+0x48 **all null** -- clips are created lazily elsewhere (side blobs). `FUN_7100e53db4` looks clips up via `*(char+0x48)[i]` and calls `CAnim` vtable +0x30.
  - `FUN_7100e5d5ac` is anchor pose blending (quat slerp + translation lerp), not decoding. The `caseD_*` functions around 0x7100e5d5ac-0x7100e5f8xx are two jump tables (20 and 28 cases at 0x71017b4540 / 0x71017b45e8) inside a function Ghidra failed to define -- strong candidate for the per-track decoder dispatch; needs manual function boundaries (`scripts/DecompAt.java` creates one in memory).
  - Clip blob header refined: `+0x11 u32 payload size` (= blob size - 0x11); payload = blob+0x11 with `u16` section offsets at payload+4/+6/+8 (0x20/0x30/0x40 animated, 0x20/0x30/0x38 static), `f32 1.0` at payload+0xc, `u16 frames` at payload+0x10. Every clip ends with a small table starting `00 01 10 00 02 02 00 00`.
  - Whole binary decompiled: `re/decomp/all/*.c` (62,159 functions) and concatenated at `F:\aiwork\all_decomp.c`; `F:\aiwork\fgrep.py` greps it per function (all regexes must match). New Ghidra scripts: `StrXrefs`, `DecompRange`, `DecompAt`, `DecompAll`, `DumpPtrs`.
- **2026-10-12, second pass (stopped here):**
  - **Clip data is an LSB-first bitstream** (bit autocorrelation is strong LSB-first, weak MSB-first). In the crate's animated clips the payload has two regions of fixed-length records: first **300-bit records**, each starting with a raw float32 (~0.01408, rising slowly from record to record -- a stored scale or channel value), then **157-bit records** (a constant field slides by the record length mod 8, visible as `03 1a` / `06 34` / `0c 68` / `18 d0` ...). Record counts (~30 + ~34) don't match the 163 frames, so records are probably keys or fragments (cf. `SFragDecode`), not frames.
  - **Clip trailer** (after the bitstream, also the whole body of static clips) holds **big-endian u16s**: a per-track descriptor list of 6-byte entries `(u16 1, u16 1, u16 offset)` whose offsets step by 4 (0x3c, 0x40, 0x44, ... with 0x3c reused, i.e. indices into a u32 constant pool, probably `SConstPool`), followed by a structure that is **identical across clips of one character** (`0004 0001 0001 0000 0001 0008 000b ...`). It looks like a rig/pose program for the bytecode VMs below.
  - **Two bytecode interpreters** live at 0x7100e5dab0.. (opcode table 0x71017b4540, 20 ops: parameter decision trees -- compare ctx bytes/floats, branch) and 0x7100e5e0c0.. (table 0x71017b45e0, 28 ops, some in 0x7100baa...: transform/matrix ops that read u16 operands `slot & 0x3f`, `index >> 6` into pose tables at ctx+0x10 -> +0x48). They are tail-call threaded (`ldrb op,[x0],#1; ldr x3,[table, op, lsl 3]; br x3`), which is why Ghidra splits them into broken `caseD_*` functions. The keyframe decoder is probably reached from one of these ops; not found yet.
  - **`CAnim` virtuals seen at call sites:** +0x30 track info (`u16 count @+4`, 8-byte entries @+8), +0x70 duration in seconds (callers multiply by 60), +0x78 `sample(anim, outTransform, characterCtx, 0)` returning flags (bit 0 rotation, bit 2 translation, same convention as the pose blender `FUN_7100e5d5ac`). The vtable itself wasn't identified (clip objects are created outside the character loader; no store into char+0x48 is visible in the decompile).
  - **Ruled out:** `FUN_710089a83c` (rig loader), `FUN_7100e5d5ac` (pose blend), `FUN_7100e628bc` (2D blend-space triangle lookup), `FUN_7100e4a2e0` (matrix compose), `FUN_7100e8fac4` (camera).
  - **Next idea:** dynamic tracing would settle it fast: run the game in an emulator with a GDB stub (`re/gdbd.py` exists), break on reads of a loaded clip's bitstream, and the decoder's PC falls out. Statically, follow the 0x71017b45e0 VM ops that read clip-related operands.
- **2026-10-12, live session (Citron test build + gdbstub, `re/animtrace.py`, `re/animwatch.py`):**
  - Clip table confirmed live: `char+0x3c` u16 count (0x1ac = 428 for Samus), `char+0x48` -> `CAnim*[]`. A breakpoint on the getter `FUN_710067f168` (x0 = character, x1 = index) or on a clip method gives live objects.
  - **All clip classes** = vtables with slot 4 == `0x71005a4afc` (`scripts/VtSlot.java`): `0x71017b2f68` **leaf**, `0x71017b3000` abstract base, `0x71017b3090` null clip, `0x71017b31a0` composite (children are 8-byte entries `{u8, u8 type7, u16 clip index, ...}` dispatched through per-type tables at 0x71017b3228-0x71017b3268; children just forward to other clips), `0x71017b4aa8` blend (child weights 0.5/0.25/1.0 visible in memory). Slots: +0x28 time/duration helper, +0x70 duration, +0x78 sample (flags bit0 rot / bit2 trans), +0x68 update.
  - **Leaf object** (0x71017b2f68): +0x20 f32 rate (1.0 seen), +0x24 u16 stream count, +0x26 u16 frames (duration = (frames-1)/rate, `FUN_7100e51d0c`), +0x28 -> stream pointers. Stream: `u8 ?, u8 4, u16 frames-1, u16 0, u16 n, u32 a, u32 b, u64 data` (e.g. a=0x128 b=0x2778). `data` points at a pool that starts with **float32 quaternions/translations with the low 16 bits zero** (`00 00 80 3f`, `df ee 7e 3f 00 10 b6 3d ...` -> w~0.996 first) -- i.e. f32 values truncated to 16-bit precision. Leaf +0x78 (`FUN_7100e519c0`) only looks up 0x24-byte records `{short key, short flags, ..., 7 floats @+8}` by joint key -- it returns already-decoded/constant transforms.
  - Read watchpoints work on ranges (one fired on the record lookup), but **none fired on a playing clip's stream data** during gameplay over 20-40 s, so clips appear to be decoded once (on play start) into a cache, not streamed per frame. Trying to catch a cutscene start froze Citron at 0 fps (gdbstub + cutscene load); emulator closed, config restored.
  - Gotcha: Citron's stub can keep a software breakpoint gdb forgot (every continue traps at the same PC); remove with `maint packet z0,<addr>,4`.
  - **Next:** set the read watch on a clip *before* it starts (e.g. watch a cutscene clip's `data` region from a save just before the cutscene trigger, or break on the leaf's update slot +0x68 `FUN_7100e51a58` / clip creation), so the one-time decode is caught.
- **2026-10-12, third live session (Volt Forge hallway loading screen):**
  - Correction: the earlier "clips are decoded once and cached" conclusion was wrong -- that watch covered the character's **skeleton transform table** (a leaf stream's `data` pointer points into the CHPR transform table, verified byte-for-byte in a space pirate CHPR), not clip data. The clip **payload is inline in the leaf object**, right after its stream entries (`u32 size, u16 0x20, u16 0x30, u16 off3, ...`); bitstream = payload + off3. `re/animwatch2.py` finds it automatically.
  - During the hallway the leaf sample method `0x7100e519c0` is not called; the leaf duration/update methods are. A watch on a 2160-frame clip's bitstream fired only in the generic **resource decompressor** (`FUN_71000d3b90`, mode 14 via `FUN_71000d3888`) -- the clip memory had been freed and reused while the next area loaded. Not the decoder.
  - Character in those sessions with 428 clip slots was a **space pirate** (`c_4e21973f` family), not Samus. Samus's main CHPR is `7238eaad` in the **update pak** `Patch/Z_198_NXPatch.pak.patch` (12.8 MB, ~600+ clips) -- **Mocktroid's scanner skips `.pak.patch` files**, so update-overridden assets are missing from the app (TODO).
  - **Next:** in steady gameplay (not a load), catch a leaf via its duration/update hooks, verify it's playing, and watch its inline bitstream with `animwatch2.py`.
- **2026-10-12, fourth live session (Volt Forge gameplay) -- decoder FOUND:**
  - Leaf header correction: `+0x50 u32` decoded-state size, `+0x54 u32` offset (from leaf) of the channel-descriptor bytecode, `+0x58 u32` offset of stream 0's bitstream, `+0x5c u32` payload offset, `+0x60 u32` payload size. Payload header is `u32 size, u16 0x20, u16 off2, u16 off3` (off2 is 0x30 or 0x50 -- not fixed).
  - **Stream entry** (pointed to from `leaf+0x28[]`): `u8 groupCount, u8 flags, u16 frames-1 (or key frame), u16 ?, u8 [6] = bits of the per-frame "channels updating now" count, u8 [7], u32 [8] = bitstream offset from the stream entry, u32 ?, u64 ptr (skeleton transforms)`, then `groupCount` 6-byte group entries at +0x18: `u16 (count & 0x3ff) | type<<10 (3 bits) | bit13 (=constant/no per-frame keys?) | ...`, `u16`, `u16 offset into decoded state`.
  - **Clip start:** `FUN_710024c14c(state, ctx, leaf)` builds the playback state (one 0x20-byte block per stream at state+0x18: `ptr stream, u16 frame, u16 flags, u32 bitpos, ...`), runs the descriptor bytecode at `leaf+[0x54]` through table `0x71017b2ee8` (4-bit opcodes, per-entry fields of 5/5/1/3 bits; `FUN_71008a6c80` writes per-channel `{u8 bits, u8 (sign - bits + 0x1f)}`), then calls **`FUN_7100061b0c(streamState)`** per stream = initial key decode. It sizes per-channel outputs with the 3-byte-per-type table at `0x71013cf1c7` (`{base, perChannel, ?}`: t0-t2 0x1c, t3 4+2n (+extra), t4 0xc, t5 8, t6 0, t7 1).
  - **Per frame:** **`FUN_71000616e0(streamState)`** = frame step. frame++; read `stream[6]` bits = number of channels with a new key now; for each such channel read a delta-to-next-key (width = 4-bit header field), store `nextKey += delta` and `1.0/delta` (linear interpolation rate); then decode the new key values by group type through **`0x71017b3a28`** handlers: t0 `0x7100e584a0`, t1 `0x7100e58750`, t2 `0x7100e588d0`, t3 `0x7100e58b60`, t4 `0x7100e58a50`, t5 `0x71008af1f0`, t6 `0x7100e56cb0`, t7 `0x71000615d0`. Bit reader = LSB-first 64-bit refill (`FUN_71008a6d44` / inline; refill `FUN_71008a6dec`). A read watch caught `0x710006175c` (inside the frame step) reading a playing clip's bitstream; another caught the initial decode at `0x7100e58608` (a handler) called from `0x7100061c9c`.
  - Captures for offline verification: `F:\aiwork\clipcap2` (241-frame clip, payload 0x33c0) and `clipcap3`/`clipcap4` (17-frame, wide clip): `leaf.bin` (leaf object + inline payload), `state.bin` (playback state right after setup = initial keys decoded), `info.txt`. Scripts: `re/capclip.py`, `re/watchsetup.py`, `re/watchleaf.py`.
  - Handler calling convention: `x0` = reader `{+0 streamState, +8 bitstream base, +0x10 u64 bit buffer, +0x18 bits left, +0x1c next refill byte offset, +0x24 limit}`, `x1` = the group's channel records (`streamState + [0x1c] + groupEntry.u16[2]`), `x2` = `{u16 n, n x u16 channel-index deltas}` (first delta 0; record = previous + delta*stride). Disassembly in `F:\aiwork\handlers.asm`.
  - **Type 0 (0x1c-byte channel record):** `+0 u8 bits`, `+1..+3` 24-bit field (`bits 11-15` = 32-bits for sign extension, `bits 16-21` = packed 2-bit component indices, read while value > 3, `bit 7` / `bit 23` = 2 flag bits read per key, `bit 22` = previous `bit 23`), `+4..+0xf` previous key (3 x i32), `+0x10..+0x1b` current key (3 x i32). Per key: copy current to previous, zero current, read 2 flag bits, then for each listed component read `bits` bits, sign-extend, and store as i32. Values are quantized ints; the float scale/offset and quaternion rebuild happen at sample time (not traced yet).
  - **Next (offline, no game needed):** disassemble the other type handlers + `FUN_7100061de8`, write a Java decoder, check it against `state.bin`, and check that stepping frames-1 times ends exactly at the payload end.
- **2026-10-12, offline decoder + playback (done):**
  - Payload: `u32 size, u16 0x20, u16 descriptorOff, u32 bitstreamOff, f32 rate, u16 frames, u48 slot mask, u16 decodedSize`; stored after `01 01 <u32 payload id> ff ff ff ff`. Payload id = ChprInfo clip index + a per-file offset (852 for Samus 7238eaad, 21 for the camera CHPR); `AnimationSupport` picks the offset that matches the most frame counts.
  - Section A at +0x20 (leaf ctor `FUN_71003cec08`, LSB-first): 4 streams, 10 total groups, 4 const-record count; per stream 21 bitstream off, 21 bytecode off (payload-relative), 4 group count, 1 has-const, 1 flag, 4 k (sample-rate multiplier; last frame = (frames-1)*k), 4 count-field bits, 4 pool index; per group 10 count, 3 type, 1 hold (bit13), +6 extra bits for type 3; per const record 16 key, 32 raw, optional 4x f32 quat, optional 3x f32 translation.
  - Descriptor ops (4-bit): 0 select stream, 1 end, 2/3 rot/vector channels (5 bits, 5 fraction bits, 1 sign, 3 xyz mask), 4 scalar (5,5,1), 5 bit-flag table, 6 raw int (5,1). Value records: t0-2 0x1c `{u8 bits, u24 field (0-4 p, 7 key flag, 8-9 comp count, 11-15 sign shift, 16-21 packed comp list, 22/23 prev/cur w sign), i32 prev[3], i32 cur[3]}`, t4 0xc `{bits, shift, p, prev, cur}`, t5 8 `{bits, shift, value}`.
  - Sample bytecode (per stream at bytecode off, table 0x71017b3a58): `01 u16 len` then ops: 2 = n x {rot, scale, trans} refs -> 10 floats per joint (quat w-first, scale (stored +1), translation); 3 = n x {rot, trans} -> 7 floats; 4 = bit flags (2+2n); 5 = const records (no operands); 6 = floats (scalar lerp `prev/2^p + u*(cur-prev)/2^p`); 7 = raw ints; 0 = end. Ref with bit15 = animated (bits 10-13 group, 0-9 channel), else constant pool index; rotation lerp is nlerp, or (key flag set) a two-step nlerp through the midpoint (`0x710021e5c0`).
  - Outputs are addressed `slot | index << 6`, the same as the CHPR hierarchy record's last u16 (`c`): joint -> (slot, index/5) for 10-float transforms. 60/64 (space pirate) and 130/134 (Samus) clip addresses match named joints.
  - **Clip transforms are relative to the bind pose:** local rotation = clip * bind (parent-space), translation = bind + clip offset, scale absolute. Checked by leg symmetry in a standing idle (bind * clip swings a leg out; absolute is wildly off) and by the user's eye on finger poses. Constant refs seen so far point at identity / zero.
  - Open: the clip **constant pool** (per-character float table, `stream+0x10` pointer at runtime; not found as one array in the file -- a live read of that pointer would settle it); 7-float (op 3) joints use the c/7 addressing of type 05/07 hierarchy records (untested); playback fps assumed 30.
  - Hierarchy parse fix: other record kinds (e.g. `0d ...`) can interrupt a run of 04/05/07 records; `ChprSkeleton.resync` resumes at the next record continuing the numbering. Fixed Samus's `knee_guard_R0_0_jnt` (right knee armour stayed at bind) and raised clip-address matches (space pirate 61/64, Samus 132/134).
  - Tools: `F:\aiwork\clipdec.py` (Python reference decoder), `clipscan.py`; scratch Java ClipVerify / PoseDiag / SymDiag; UI harness steps `frame=`, `focus=joint,dist,yaw`.
- MTRL bodies are **zstd**-compressed (`28 b5 2f fd`); shader names need a
  zstd decoder (e.g. aircompressor) -- not done.

## UI deviation from the mockups

The roadmap said the bottom transport bar is hidden for Textures. It now
hosts the texture controls (mip / layer / slice / channels / exposure /
export) since there was nowhere better for them -- easy to move if unwanted.
Texture previews also use the largest square that fits rather than the
fixed 380px viewport.

## Windows dev setup (this machine)

- Real romfs dump: `E:\mdreadmod\metroid prime 4 game\normal dump\romfs`
  (441 paks, 24 GB; this dump has 13,173 TXTR / 17,006 CSMP / 19,803 CMDL --
  more than the Mac-era counts below, likely a newer game version).
- Reference apps: `E:\Mprime4mod\mac_project\metroidprime4\reference apps for mocktroid`.
- Portable Maven: `F:\aiwork\tools\apache-maven-3.9.16\bin\mvn`;
  vgmstream (Windows): `F:\aiwork\tools\vgmstream\vgmstream-cli.exe` --
  set it in Settings > Audio > vgmstream-cli (new setting; also auto-detects
  PATH and `./tools/vgmstream`).
- VLC is installed at `C:\Program Files\VideoLAN\VLC` (for the FMV0 module).
- `mvn javafx:run` now works on Windows: the macOS `-Xdock` flags moved to an
  OS-activated `mac` profile (other JVMs refuse to start with them).

## Bugs found and fixed (2026-10-05)

- **Sort menu "didn't work"**: the dropdown's light option text sat on
  Modena's default white popup, so every option was invisible. Combo popups
  and spinner fields now have dark styling.
- **Grid sometimes needed a double-click**: cards selected on mouse *click*,
  which is lost if the card is rebuilt between press and release (the old
  selection code rebuilt every visible card). Grid and list now select on
  press and restyle the highlight in place instead of rebuilding.

## Bugs found and fixed (Windows session)

- **Waveform loop marker / playhead always drawn at the center**: the
  `Line`s were managed `StackPane` children, so layout re-centred them and
  ignored their x coordinates. Now unmanaged.
- **Waveform only ~700px wide**: canvas kept its construction width when the
  decode finished after layout. Now sized in `draw()`.
- **LoadingSpinner animations never stopped**: a running `Animation` is held
  by the master timer, so every spinner ever created kept ticking after
  leaving the scene. Now starts/stops with scene membership.
- **Grid horizontal scrollbar**: fixed 130px cards overflowed the pane;
  cards now size to fill the row at the configured column count.
- **Preview reticle brackets** sat at the corners of the whole preview area
  instead of the viewport (mockup has them on the viewport).
- **VgmstreamClient** only looked on PATH; now configurable + Windows hint.

## Bugs found and fixed this session (grid/list selection, seek bar, tab centering)

- **Grid/list selection needing repeated clicks**: `select()` called the
  heavy `ListView.refresh()` (tears down and rebuilds every visible cell)
  synchronously from inside the just-clicked card's own click handler --
  rebuilding a node while its own event is still mid-dispatch is a known
  JavaFX hazard. Fixed by deferring `refresh()` to the next pulse via
  `Platform.runLater`.
- **Seek bar not filling its row**: added explicit `setMaxWidth(Double.MAX_VALUE)`
  alongside `HBox.setHgrow(..., ALWAYS)` -- HGrow alone doesn't override a
  control's own default max-width cap.
- **Settings tab not staying centered**: the old approach centered a
  tightly-packed cluster (tabs + settings tab), which drifts off-true-center
  whenever the left tab group is wider than the lone active tab on the
  right. Fixed by binding the left/right flanking regions to always be
  exactly equal width `(barWidth - settingsTabWidth) / 2`, with each
  region's content anchored toward the settings tab.
- **macOS Dock icon showing generic/folder icon**: expected in dev-mode --
  `mvn javafx:run` launches a bare JVM with no `.app` bundle for macOS to
  associate an icon with. Added `-Xdock:name`/`-Xdock:icon` JVM args as a
  best-effort dev-mode attempt (support on modern JDKs is genuinely
  uncertain and unverified here -- no way to screenshot and confirm). The
  only guaranteed fix is proper `jpackage` `.app` packaging, which embeds a
  real `.icns` via `Info.plist` -- still a planned, not-yet-done step.

## Sound Waveform Cache validation

Tested the real background-task code path (not just the underlying logic)
by starting a minimal JavaFX toolkit (`Platform.startup`) and running
`SoundThumbnailCacheBuilder` on a `Thread` the same way `SettingsView` does,
against 6 real sounds including the Volt Forge first-visit theme
(`ae6e4e87-a078-436f-9f35-26225294fc29`). All 6 cached correctly; the Volt
Forge entry's cached loop start (2.667s) and natural duration (240.0s)
exactly matched the values independently confirmed via `vgmstream-cli -m`
earlier. 6 sounds (including one 4-minute track) took 517ms -- most of the
full ~9,500-sound library should be short SFX that decode quickly, but this
is still a genuinely long background task overall, hence the progress
bar/cancel button rather than pretending it's instant.


## Sound module notes

- vgmstream has native "Retro Studios RFRM header" support covering this
  game's CSMP container directly -- extracted bytes are handed to
  `vgmstream-cli` as-is, no manual header stripping needed.
- Decoded via `-l <configured loop count> -F` for the live preview (`-F`
  skips the default 10s post-loop fade, so duration scales cleanly and
  linearly with loop count -- confirmed against the Volt Forge track: 1
  loop = 240.0s, 2 loops = 477.3s, matching intro + N*loop-body exactly).
  Sound Waveform Cache generation uses loop count 1 regardless of the live
  setting, purely to keep cache-building as fast as possible.
- Playback uses plain `javafx.scene.media.MediaPlayer` on the decoded WAV
  (not VLCJ) -- WAV is basic enough that JavaFX's own GStreamer-backed media
  stack is reliable for it, unlike compressed video codecs (see FMV0 notes
  above on why VLCJ is reserved for that).
- `vgmstream` was installed via `brew install vgmstream`.

## Two real correctness bugs caught during sound module development

Both found by testing against the real romfs dump rather than synthetic
data -- worth keeping in mind for future modules:

1. **Compression dispatch**: an asset is only mode-prefixed when
   `size != decompressedSize`; when they're equal it's stored fully raw with
   no prefix at all. Treating every asset as mode-prefixed corrupted the
   first 4 bytes of any uncompressed asset (caught because it turned a real
   asset's leading "RFRM" magic into a bogus giant "compression mode").
2. **Integer overflow on large paks**: two real paks (`zone_solValley.pak`,
   `DuplicateData.pak`) run 3.5+ GB, exceeding `Integer.MAX_VALUE` bytes.
   `FormDescriptor` was eagerly truncating the outer PACK form's body size to
   `int` even though nothing actually needed to allocate an array that big --
   `PackCatalog` only ever needs an int-sized buffer for the (small) TOCC
   region. Fixed by keeping `FormDescriptor` in `long` throughout and only
   truncating at the specific point an array actually gets allocated.

## Performance note: virtualized rendering is required at real scale

A full romfs has ~90,000 assets across all types (CSMP alone: 9,472; CMDL:
17,565; TXTR: 9,725). The original `AssetListView`/`AssetGridView` eagerly
built one heavy `AngledPanel` node tree per entry on category selection --
fine for `DemoData`'s handful of placeholder entries, but hung the app for
well over a minute against real data (thousands of complex Node trees built
synchronously on the FX thread). Rewrote both as JavaFX `ListView`s (list:
one row per cell; grid: each "item" is a full row's worth of entries,
rendered as an HBox of cards) -- ListView only ever builds the handful of
cells actually on screen and recycles them on scroll, which is JavaFX's
standard virtualization mechanism and the only native way to get a
virtualized grid short of a custom control. Confirmed fix: idle CPU and
~300MB memory with the full 90k-asset library loaded, versus pegged CPU and
2.3GB+ climbing before.


## Open questions / deferred detail

- **Preview-pane corner drag-to-resize -- shelved.** Prototyped once; the
  handle's outer StackPane had no size cap so it silently stretched to
  cover the entire viewport, and all four handles ended up overlapping --
  that's what caused both the "crosshair cursor everywhere" and "resizing
  doesn't work well" symptoms. Removed rather than shipped half-working.
  Worth revisiting later with a properly capped hit-area (fixed pixel
  size, not just relying on StackPane alignment) once there's real preview
  content that benefits from resizing. Viewport is square-by-default
  (380x380) with no interactive resize for now.
- Exact frame-step (A/D) and playback semantics: noted for the model/media
  preview modules, not implemented until those exist.
- Grid "3 rows" vs "3 columns" in the mockup annotation: interpreted as 3
  columns (matches the drawn 3x2 example), configurable in Settings.
- Filter-box hover-vs-click activation setting was removed per request --
  filter is click-to-expand only now, no setting for it.
