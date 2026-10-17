package io.github.jayden0903.replay;

import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * Line of sight on the owning region thread: voxel walk (Amanatides-Woo) from the eye to each target point, blocked by
 * occluding (full opaque) blocks only. Chunks that are unloaded or owned by another region give "unknown" and are
 * never loaded.
 */
final class Los {
    static final byte UNKNOWN = 0, VISIBLE = 1, HIDDEN = 2;
    private static final int CLEAR = 0, BLOCKED = 1, UNSURE = 2;

    static byte check(World w, Analyzer.LosQuery q) {
        boolean unsure = false;
        for (int p = 0; p < 3; p++) {
            int r = ray(w, q.ex(), q.ey(), q.ez(), q.targets()[p * 3], q.targets()[p * 3 + 1], q.targets()[p * 3 + 2]);
            if (r == CLEAR) return VISIBLE;
            if (r == UNSURE) unsure = true;
        }
        return unsure ? UNKNOWN : HIDDEN;
    }

    @SuppressWarnings("deprecation")
    static int ray(World w, double x0, double y0, double z0, double x1, double y1, double z1) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        int x = floor(x0), y = floor(y0), z = floor(z0), ex = floor(x1), ey = floor(y1), ez = floor(z1);
        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0, sy = dy > 0 ? 1 : dy < 0 ? -1 : 0, sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tdx = sx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx), tdy = sy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy), tdz = sz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double tmx = sx == 0 ? Double.MAX_VALUE : (sx > 0 ? (x + 1 - x0) : (x0 - x)) * tdx;
        double tmy = sy == 0 ? Double.MAX_VALUE : (sy > 0 ? (y + 1 - y0) : (y0 - y)) * tdy;
        double tmz = sz == 0 ? Double.MAX_VALUE : (sz > 0 ? (z + 1 - z0) : (z0 - z)) * tdz;
        int lcx = Integer.MIN_VALUE, lcz = Integer.MIN_VALUE;
        for (int steps = 0; steps < 256; steps++) {
            if (x == ex && y == ey && z == ez) return CLEAR;          // reached the target's own voxel
            if (tmx < tmy && tmx < tmz) { x += sx; tmx += tdx; } else if (tmy < tmz) { y += sy; tmy += tdy; } else { z += sz; tmz += tdz; }
            if (x == ex && y == ey && z == ez) return CLEAR;
            int cx = x >> 4, cz = z >> 4;
            if (cx != lcx || cz != lcz) {
                if (!w.isChunkLoaded(cx, cz) || !Bukkit.isOwnedByCurrentRegion(w, cx, cz)) return UNSURE;
                lcx = cx; lcz = cz;
            }
            if (y < w.getMinHeight() || y >= w.getMaxHeight()) continue;
            if (w.getType(x, y, z).isOccluding()) return BLOCKED;
        }
        return UNSURE;
    }

    private static int floor(double v) { int i = (int) v; return v < i ? i - 1 : i; }
}
