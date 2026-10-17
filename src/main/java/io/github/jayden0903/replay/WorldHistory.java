package io.github.jayden0903.replay;

import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Blocks as they were during a clip (1.2.0). Clips do not store blocks; when a clip is opened, CoreProtect's history around the
 * suspect's path (block breaks and places by players, explosions, fire, fluids, growth, pistons: whatever CoreProtect logs) from
 * the clip start until now is read on the io thread, and for every changed position:
 *   state at clip start = the "before" side of the first change after the start (a break logged the block that was there,
 *                         a place means there was no block: air),
 *   changes during the clip = breaks -> air, places -> the placed block, at the logged second (refined to the exact ms when a
 *                         clip player's own break/place event matches the position).
 * Later changes do not matter: the viewer is shown the reconstructed past, never today's blocks. CoreProtect times are whole
 * seconds; door/trapdoor toggles and container contents are not logged as block states and stay as they are now.
 * This class touches CoreProtect classes; it is only loaded when the CoreProtect plugin is enabled.
 */
final class WorldHistory {
    static boolean available() {
        Plugin pl = Bukkit.getPluginManager().getPlugin("CoreProtect");
        if (!(pl instanceof CoreProtect cp) || !pl.isEnabled()) return false;
        try { CoreProtectAPI api = cp.getAPI(); return api != null && api.isEnabled() && api.APIVersion() >= 9; }
        catch (Throwable t) { return false; }
    }

    private record Row(int x, int y, int z, int timeSec, int action, BlockData data, int order) { }

    /** io thread. Never touches the world; only CoreProtect's database and block-data parsing. */
    static BlockTimeline build(Clip clip, Settings s, Logger log) {
        long t0 = System.currentTimeMillis();
        BlockTimeline tl = new BlockTimeline();
        Clip.Track sus = clip.tracks.get(0);
        if (sus.n == 0) { tl.note = "용의자 기록 없음"; return tl; }
        // the world the suspect spent the most frames in
        Map<Integer, Integer> wc = new HashMap<>();
        for (int i = 0; i < sus.n; i++) wc.merge(sus.world[i], 1, Integer::sum);
        int wi = wc.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
        tl.world = clip.worlds.get(wi);
        World w = Bukkit.getWorld(tl.world);
        if (w == null) { tl.note = "월드 없음: " + tl.world; return tl; }
        // lookup centres along the path, one per radius of travel (capped)
        int rad = s.worldHistoryRadius;
        List<double[]> centres = new ArrayList<>();
        for (int i = 0; i < sus.n; i++) {
            if (sus.world[i] != wi) continue;
            double[] last = centres.isEmpty() ? null : centres.get(centres.size() - 1);
            if (last == null || Math.hypot(sus.x[i] - last[0], sus.z[i] - last[2]) > rad * 0.75) centres.add(new double[] {sus.x[i], sus.y[i], sus.z[i]});
        }
        if (centres.size() > 24) {   // very long flights: keep 24 evenly spaced centres
            List<double[]> k = new ArrayList<>();
            for (int i = 0; i < 24; i++) k.add(centres.get(i * (centres.size() - 1) / 23));
            centres = k;
        }
        CoreProtectAPI api = ((CoreProtect) Bukkit.getPluginManager().getPlugin("CoreProtect")).getAPI();
        long now = System.currentTimeMillis();
        int secondsBack = (int) Math.min(Integer.MAX_VALUE, (now - clip.startWallMs) / 1000L + 2);
        long startSec = clip.startWallMs / 1000L, endSec = (clip.startWallMs + clip.durationMs) / 1000L;
        Set<String> seen = new HashSet<>();
        List<Row> rows = new ArrayList<>();
        int order = 0;
        for (double[] c : centres) {
            List<String[]> res;
            try {
                res = api.performLookup(secondsBack, null, null, null, null, Arrays.asList(0, 1), rad, new Location(w, c[0], c[1], c[2]));
            } catch (Throwable ex) { tl.note = "CoreProtect 조회 실패: " + ex.getClass().getSimpleName(); log.warning("CoreProtect lookup failed: " + ex); break; }
            tl.lookups++;
            if (res == null) continue;
            // CoreProtect returns newest first; walk backwards so `order` grows with time inside one second
            for (int k = res.size() - 1; k >= 0; k--) {
                String[] r = res.get(k);
                CoreProtectAPI.ParseResult pr;
                try { pr = api.parseResult(r); } catch (Throwable ex) { continue; }
                if (pr.getActionId() != 0 && pr.getActionId() != 1) continue;
                String key = pr.getX() + "," + pr.getY() + "," + pr.getZ() + "," + pr.getTime() + "," + pr.getActionId() + "," + String.join("|", r);
                if (!seen.add(key)) continue;
                BlockData bd;
                try { bd = pr.getBlockData(); } catch (Throwable ex) { bd = null; }
                if (bd == null) { Material m = pr.getType(); bd = m != null && m.isBlock() ? m.createBlockData() : null; }
                if (bd == null) continue;
                rows.add(new Row(pr.getX(), pr.getY(), pr.getZ(), pr.getTime(), pr.getActionId(), bd, order++));
            }
        }
        tl.rows = rows.size();
        // group by position
        Map<Long, List<Row>> by = new HashMap<>();
        for (Row r : rows) by.computeIfAbsent(BlockTimeline.pack(r.x(), r.y(), r.z()), x -> new ArrayList<>()).add(r);
        // clip players' own breaks/places for exact times: position -> list of clip-ms times
        Map<Long, List<Integer>> exact = new HashMap<>();
        for (Clip.Track tr : clip.tracks) for (Clip.Ev e : tr.events)
            if (e.type.equals("blockBreak") || e.type.equals("blockPlace")) exact.computeIfAbsent(BlockTimeline.pack(e.bx, e.by, e.bz), x -> new ArrayList<>()).add(e.t);
        Map<String, Integer> ids = new HashMap<>();
        int air = stateId(Material.AIR.createBlockData(), ids);
        List<long[]> positions = new ArrayList<>();          // [pos, initialState]
        List<int[]> changes = new ArrayList<>();             // [t, posIndex, state]
        // keep the positions nearest the path when there are too many
        List<Map.Entry<Long, List<Row>>> entries = new ArrayList<>(by.entrySet());
        if (entries.size() > s.worldHistoryMaxBlocks) {
            final List<double[]> cs = centres;
            entries.sort((a, b) -> Double.compare(near(a.getKey(), cs), near(b.getKey(), cs)));
            entries = entries.subList(0, s.worldHistoryMaxBlocks);
            tl.note = "블록 " + by.size() + "곳 중 가까운 " + s.worldHistoryMaxBlocks + "곳만";
        }
        for (Map.Entry<Long, List<Row>> en : entries) {
            List<Row> l = en.getValue();
            l.sort((a, b) -> a.timeSec() != b.timeSec() ? Integer.compare(a.timeSec(), b.timeSec()) : Integer.compare(a.order(), b.order()));
            Row first = null;
            for (Row r : l) if (r.timeSec() >= startSec) { first = r; break; }
            if (first == null) continue;                     // nothing changed since the clip started
            int initial = first.action() == 0 ? stateId(first.data(), ids) : air;
            int pi = positions.size();
            positions.add(new long[] {en.getKey(), initial});
            List<Integer> ex = exact.get(en.getKey());
            for (Row r : l) {
                if (r.timeSec() < startSec || r.timeSec() > endSec) continue;
                int t = (int) Math.max(0, Math.min(clip.durationMs, r.timeSec() * 1000L - clip.startWallMs));
                if (ex != null) {   // the matching break/place of a clip player within 2 s gives the exact ms
                    int best = -1, bd = 2001;
                    for (int et : ex) { int d = Math.abs(et - t); if (d < bd) { bd = d; best = et; } }
                    if (best >= 0) t = best;
                }
                changes.add(new int[] {t, pi, r.action() == 0 ? air : stateId(r.data(), ids)});
            }
        }
        changes.sort((a, b) -> Integer.compare(a[0], b[0]));
        tl.pos = new long[positions.size()]; tl.initial = new int[positions.size()];
        for (int i = 0; i < positions.size(); i++) { tl.pos[i] = positions.get(i)[0]; tl.initial[i] = (int) positions.get(i)[1]; }
        tl.chT = new int[changes.size()]; tl.chPos = new int[changes.size()]; tl.chState = new int[changes.size()];
        for (int i = 0; i < changes.size(); i++) { int[] c = changes.get(i); tl.chT[i] = c[0]; tl.chPos[i] = c[1]; tl.chState[i] = c[2]; }
        tl.ms = System.currentTimeMillis() - t0;
        log.info("world history " + clip.id + ": " + tl.lookups + " lookups, " + tl.rows + " rows, " + tl.pos.length + " positions, "
            + tl.chT.length + " changes during the clip, " + tl.ms + " ms");
        return tl;
    }

    private static double near(long p, List<double[]> cs) {
        double best = Double.MAX_VALUE; int x = BlockTimeline.unpackX(p), z = BlockTimeline.unpackZ(p);
        for (double[] c : cs) best = Math.min(best, Math.hypot(x - c[0], z - c[2]));
        return best;
    }

    private static int stateId(BlockData bd, Map<String, Integer> cache) {
        return cache.computeIfAbsent(bd.getAsString(), k -> SpigotConversionUtil.fromBukkitBlockData(bd).getGlobalId());
    }
}
