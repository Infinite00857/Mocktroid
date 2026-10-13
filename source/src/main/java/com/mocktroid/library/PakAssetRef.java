package com.mocktroid.library;

import java.nio.file.Path;

import com.mocktroid.format.pack.PackAsset;

/** Points an {@code AssetEntry} back at the real pak + asset directory entry
 *  it came from, so a preview module can lazily extract its bytes on
 *  demand. This is what {@code AssetEntry.sourceRef()} holds for anything
 *  loaded from a real romfs (as opposed to {@code DemoData}, which uses
 *  {@code null}). */
public record PakAssetRef(Path pakFile, PackAsset asset) {}
