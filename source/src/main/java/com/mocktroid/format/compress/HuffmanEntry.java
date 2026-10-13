package com.mocktroid.format.compress;

/**
 * One row of a Huffman decode table. `threshold` is `long` only so the
 * table-generation script (a straight regex transform off retrotool's
 * lzss.rs) didn't need to reformat any literal -- every real value fits
 * comfortably in an int (max observed is 0x0003ffff).
 */
record HuffmanEntry(int bitCount, int nextOffset, int symbol, long threshold) {}
