package io.github.jayden0903.replay;

/** Reconstructed block timeline for one world (built by {@link WorldHistory}; kept free of CoreProtect classes so the viewer
 *  loads without CoreProtect). pos = packed x/y/z, states = global block-state ids for the viewer's protocol. */
final class BlockTimeline {
    String world = ""; long[] pos = new long[0]; int[] initial = new int[0];
    int[] chT = new int[0], chPos = new int[0], chState = new int[0];   // changes during the clip, time ascending
    int lookups, rows; long ms; String note = "";

    int size() { return pos.length; }

    static long pack(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF); }
    static int unpackX(long p) { return (int) (p >> 38); }
    static int unpackZ(long p) { return (int) (p << 26 >> 38); }
    static int unpackY(long p) { return (int) (p << 52 >> 52); }
}
