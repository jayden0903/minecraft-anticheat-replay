package io.github.jayden0903.replay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Offline checks (no server): ring buffer + concurrent copy, clip JSON round trip, analyzer metrics on synthetic
 * aimbot / human / triggerbot / silent-aura / ESP (hidden tracking) suspects, equipment/pose ring, flight flag cases,
 * entity ring + merge (format 3), reading of older clip formats, and the 60-player cost bench.
 * Run: python3 build.py test
 * Optional system properties:
 *   -Drr.oldclips=dir[:dir]  also read real format 1 clips from these folders (skipped when unset)
 *   -Drr.labclips=dir[:dir]  re-analyze real format 2+ clips from these folders and print their flight summary
 *   -Drr.sampleOut=file      copy the synthetic format 3 clip written by entitiesV3() to this file
 */
public final class VerifyReplay {
    static int fails;
    static void check(boolean ok, String what) { System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        ring();
        concurrentCopy();
        roundTrip();
        analyzer();
        equipmentRing();
        assembleV2();
        entitiesV3();
        flightFlag();
        olderFormats();
        oldClips();
        labClips();
        bench();
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ ring buffer

    static void ring() {
        Recorder r = new Recorder(UUID.randomUUID(), "A", 1, 100, 16);
        long nano = System.nanoTime();
        for (int i = 0; i < 250; i++) {
            r.move(true, i, i, i, true, i * 0.5f, 0, true);
            if (i % 10 == 0) r.event(Recorder.E_ATTACK, i, -1, 0, 0, Double.NaN, Double.NaN, Double.NaN);
            r.commit(nano + i * 50_000_000L, false);
        }
        Recorder.Copy c = r.copy(0, 0);
        check(c.n == 100 && c.x[0] == 150 && c.x[99] == 249, "ring keeps the newest 100 frames (" + c.n + ", " + c.x[0] + ".." + c.x[99] + ")");
        check(c.m == 16 && c.ea[15] == 240 && c.ea[0] == 90, "event ring keeps the newest 16 events");
        Recorder.Copy d = r.copy(c.endSeq - 5, c.endEventSeq);
        check(d.n == 5 && d.x[4] == 249 && d.m == 0, "incremental copy from a sequence");
        // flood of empty tick ends is not recorded as history
        long before = r.frameSeq();
        for (int i = 0; i < 1000; i++) r.commit(nano + 250 * 50_000_000L + i * 100_000L, false);
        check(r.frameSeq() - before <= 3, "empty tick-end flood (0.1 ms apart) does not flush the ring (" + (r.frameSeq() - before) + " frames)");
        // flood of frames WITH data: 400 frames/s for 5 s -> at most 30/s + burst are stored
        Recorder f = new Recorder(UUID.randomUUID(), "F", 3, 1500, 16);
        long n0 = System.nanoTime();
        for (int i = 0; i < 2000; i++) { f.move(true, i, 0, 0, true, i, 0, true); f.commit(n0 + i * 2_500_000L, false); }
        Recorder.Copy fc = f.copy(0, 0);
        check(fc.n <= 60 + 5 * 30 + 2 && fc.x[fc.n - 1] == 1999, "400 frames/s flood is merged to <= 30 frames/s + burst (" + fc.n + " frames kept, last position kept)");
    }

    static void concurrentCopy() throws Exception {
        Recorder r = new Recorder(UUID.randomUUID(), "B", 2, 1500, 1024);
        AtomicBoolean stop = new AtomicBoolean();
        Thread w = new Thread(() -> {
            long i = 0, n0 = System.nanoTime();
            while (!stop.get()) { r.move(true, i, i, i, true, i, i, false); r.commit(n0 + i * 50_000_000L, false); i++; }
        });
        w.start();
        boolean ok = true; int copies = 0;
        long end = System.currentTimeMillis() + 1500;
        while (System.currentTimeMillis() < end) {
            Recorder.Copy c = r.copy(0, 0); copies++;
            for (int k = 0; k < c.n; k++) if (c.x[k] != c.y[k] || c.y[k] != c.z[k] || c.yaw[k] != (float) c.x[k] && c.x[k] < (1 << 24)) { ok = false; break; }
            for (int k = 1; k < c.n; k++) if (c.x[k] != c.x[k - 1] + 1) { ok = false; break; }
        }
        stop.set(true); w.join();
        check(ok, "copies taken while another thread records are consistent (" + copies + " copies)");
    }

    // ------------------------------------------------------------------ JSON

    static void roundTrip() throws Exception {
        Clip c = synthetic("aimbot", 7);
        c.summary = new Analyzer(c, 32).summary();
        Path f = Files.createTempDirectory("rr").resolve("x.json.gz");
        c.write(f);
        Clip d = Clip.read(f);
        boolean ok = d.tracks.size() == c.tracks.size();
        for (int j = 0; ok && j < c.tracks.size(); j++) {
            Clip.Track a = c.tracks.get(j), b = d.tracks.get(j);
            ok = a.n == b.n && a.events.size() == b.events.size();
            for (int i = 0; ok && i < a.n; i++)
                ok = a.x[i] == b.x[i] && a.y[i] == b.y[i] && a.z[i] == b.z[i] && a.yaw[i] == b.yaw[i] && a.pitch[i] == b.pitch[i] && a.flags[i] == b.flags[i] && a.t[i] == b.t[i];
        }
        check(ok, "clip file round trip is bit-exact for positions and float yaw/pitch (" + Files.size(f) / 1024 + " KiB gz)");
        check(d.summary.toString().equals(c.summary.toString()), "summary survives the round trip");
    }

    // ------------------------------------------------------------------ analyzer

    /** Suspect at the origin fighting a strafing target; kind selects the aim model. Timeline 90 s at 20 Hz. */
    static Clip synthetic(String kind, long seed) {
        Random rnd = new Random(seed);
        int n = 1800;
        Clip c = new Clip();
        c.id = "test-" + kind; c.suspectName = "Sus"; c.suspectUuid = UUID.randomUUID().toString(); c.durationMs = (n - 1) * 50; c.markOffsetMs = 60000;
        c.worlds.add("world"); c.items.add(""); c.items.add("minecraft:diamond_sword");
        Clip.Track s = new Clip.Track(), t = new Clip.Track();
        s.role = "suspect"; s.name = "Sus"; t.name = "Target";
        s.alloc(n); t.alloc(n);
        double yaw = 0, pitch = 0, lagYaw = 0, lagPitch = 0;
        boolean prevOn = false;
        int cooldown = 0;
        for (int i = 0; i < n; i++) {
            double ang = i * 0.03, rad = 3.0 + Math.sin(i * 0.011);
            double tx = Math.cos(ang) * rad, tz = Math.sin(ang) * rad, ty = 64;
            t.t[i] = i * 50; t.x[i] = tx; t.y[i] = ty; t.z[i] = tz; t.yaw[i] = 0; t.pitch[i] = 0; t.flags[i] = Recorder.F_GROUND | Recorder.F_POS;
            s.t[i] = i * 50; s.x[i] = 0; s.y[i] = 64; s.z[i] = 0;
            double vx = tx, vy = ty + 0.9 - (64 + 1.62), vz = tz;
            double wantYaw = Math.toDegrees(Math.atan2(-vx, vz)), wantPitch = -Math.toDegrees(Math.atan2(vy, Math.sqrt(vx * vx + vz * vz)));
            switch (kind) {
                case "aimbot" -> { yaw = wantYaw; pitch = wantPitch; }
                case "esp" -> { yaw = wantYaw + rnd.nextGaussian() * 0.8; pitch = wantPitch + rnd.nextGaussian() * 0.5; }
                case "silent" -> {
                    // looks around ~90 deg away from the target; the aura rotates only in the attack tick
                    lagYaw += rnd.nextGaussian() * 2.0; lagPitch = Math.max(-30, Math.min(30, lagPitch + rnd.nextGaussian()));
                    yaw = wantYaw + 90 + Analyzer.wrap(lagYaw) * 0.2; pitch = lagPitch;
                }
                case "human", "trigger" -> {
                    // human-like: follows the target with 150-250 ms lag and noise
                    lagYaw += Analyzer.wrap(wantYaw - lagYaw) * 0.25 + rnd.nextGaussian() * 2.0;
                    lagPitch += (wantPitch - lagPitch) * 0.25 + rnd.nextGaussian() * 1.0;
                    yaw = lagYaw; pitch = lagPitch;
                }
                default -> { }
            }
            s.yaw[i] = (float) yaw; s.pitch[i] = (float) pitch; s.flags[i] = Recorder.F_GROUND | Recorder.F_POS | Recorder.F_ROT;
            s.item[i] = 1;
            // attack decision
            boolean attack = false;
            if (cooldown > 0) cooldown--;
            double[] d = Analyzer.dir(s.yaw[i], s.pitch[i]);
            boolean on = Analyzer.rayBox(0, 65.62, 0, d, tx - 0.3, ty, tz - 0.3, tx + 0.3, ty + 1.8, tz + 0.3) >= 0;
            switch (kind) {
                case "aimbot", "human" -> { if (cooldown == 0 && rnd.nextDouble() < 0.4) { attack = true; cooldown = 10 + rnd.nextInt(4); } }
                case "trigger" -> { if (on && !prevOn && cooldown == 0) { attack = true; cooldown = 10; } }
                case "silent" -> { if (cooldown == 0 && rnd.nextDouble() < 0.4) { attack = true; cooldown = 12; s.yaw[i] = (float) wantYaw; s.pitch[i] = (float) wantPitch; } }
                default -> { }
            }
            prevOn = on;
            if (attack) {
                s.flags[i] |= Recorder.F_ATTACK | Recorder.F_SWING;
                Clip.Ev e = new Clip.Ev(); e.t = i * 50; e.frame = i; e.type = "attack"; e.entityId = 99; e.targetTrack = 1; e.target = "Target";
                s.events.add(e);
            }
        }
        if (kind.equals("silent")) for (int i = 1; i < n; i++) if ((s.flags[i - 1] & Recorder.F_ATTACK) != 0 && (s.flags[i] & Recorder.F_ATTACK) == 0) {
            // snap back next tick
            s.yaw[i] = s.yaw[Math.max(0, i - 2)]; s.pitch[i] = s.pitch[Math.max(0, i - 2)];
        }
        c.tracks.add(s); c.tracks.add(t);
        return c;
    }

    static void analyzer() {
        for (String kind : new String[] {"aimbot", "human", "trigger", "silent"}) {
            Clip c = synthetic(kind, 11);
            Analyzer an = new Analyzer(c, 32);
            var s = an.summary();
            var at = s.getAsJsonObject("attacks");
            System.out.println("  " + kind + ": " + s.get("headline").getAsString() + " | onTargetRate=" + s.getAsJsonObject("aim").get("onTargetRate")
                + " zeroTickRate=" + at.get("zeroTickRate") + " snapBack=" + at.get("snapBack") + " meanErr=" + at.get("meanErrDeg"));
        }
        var aim = new Analyzer(synthetic("aimbot", 11), 32).summary();
        var hum = new Analyzer(synthetic("human", 11), 32).summary();
        var trg = new Analyzer(synthetic("trigger", 11), 32).summary();
        var sil = new Analyzer(synthetic("silent", 11), 32).summary();
        check(aim.getAsJsonObject("attacks").get("meanErrDeg").getAsDouble() < 0.5, "aimbot: attack aim error ~0 deg");
        check(aim.getAsJsonObject("aim").get("onTargetRate").getAsDouble() > 0.95, "aimbot: crosshair on the hitbox > 95 % of the time");
        check(hum.getAsJsonObject("attacks").get("meanErrDeg").getAsDouble() > 2, "human model: attack aim error several degrees");
        check(trg.getAsJsonObject("attacks").get("zeroTickRate").getAsDouble() > 0.9, "triggerbot: attacks in the tick the crosshair enters the box");
        check(hum.getAsJsonObject("attacks").get("zeroTickRate").getAsDouble() < 0.5, "human model: most attacks not in the entry tick");
        check(sil.getAsJsonObject("attacks").get("snapBack").getAsInt() > 5 && hum.getAsJsonObject("attacks").get("snapBack").getAsInt() == 0, "silent aura: snap-backs counted, none for the human model");
        // hidden tracking: pretend a wall hides the target for frames 300..700
        Clip esp = synthetic("esp", 3);
        Analyzer an = new Analyzer(esp, 32);
        List<Analyzer.LosQuery> q = an.losQueries(2400);
        byte[] res = new byte[q.size()];
        for (int k = 0; k < q.size(); k++) res[k] = (byte) (q.get(k).frame() >= 300 && q.get(k).frame() < 700 ? 2 : 1);
        an.applyLos(q, res, "test");
        var h = an.summary().getAsJsonObject("hidden");
        System.out.println("  esp hidden: " + h);
        check(h.get("longestHiddenTrackingSec").getAsDouble() > 15, "ESP model: long tracking of a hidden target is measured (" + h.get("longestHiddenTrackingSec") + " s)");
    }

    // ------------------------------------------------------------------ 1.1.0: pose/state + equipment

    static int[] snap(int head, int chest, int legs, int feet, int off, int trimChest) {
        return new int[] {head, chest, legs, feet, off, 0, trimChest, 0, 0};
    }

    static void equipmentRing() {
        Recorder r = new Recorder(UUID.randomUUID(), "E", 1, 200, 16);
        long nano = System.nanoTime();
        int helmet = Names.id("minecraft:diamond_helmet"), elytra = Names.id("minecraft:elytra"), plate = Names.id("minecraft:diamond_chestplate");
        int trim = Names.id("minecraft:sentry minecraft:gold");
        int appended = 0;
        for (int i = 0; i < 300; i++) {
            if (i == 0) r.equipment(snap(helmet, 0, 0, 0, 0, 0));
            if (i >= 0 && i % 10 == 0) r.equipment(i < 150 ? (i < 60 ? snap(helmet, 0, 0, 0, 0, 0) : snap(helmet, elytra | Recorder.GLINT, 0, 0, 0, 0)) : snap(helmet, plate, 0, 0, 0, trim));
            r.state = i >= 80 && i < 140 ? (Recorder.P_FALL_FLYING | Recorder.S_FALL_FLYING) : 0;
            r.hotbar[0] = Names.id("minecraft:diamond_sword") | (i >= 200 ? Recorder.GLINT : 0);
            r.move(true, i, 100, 0, true, 0, 0, false);
            r.commit(nano + i * 50_000_000L, false);
        }
        Recorder.Copy c = r.copy(0, 0);
        int[] o = new int[Recorder.EQ_INTS];
        // ring keeps frames 100..299; snapshots: helmet (seq 0), +elytra (seq 1), plate+trim (seq 2): 3 appended only
        boolean ok = c.n == 200;
        ok &= c.snapshot(c.eqRef[0], o) && o[1] == (elytra | Recorder.GLINT);        // frame 100
        ok &= c.snapshot(c.eqRef[c.n - 1], o) && o[1] == plate && o[6] == trim;     // frame 299
        ok &= c.eqRef[c.n - 1] == 2;
        check(ok, "equipment ring: only changes are stored (3 snapshots for 300 frames), frames point at the right snapshot");
        check((c.state[0] & Recorder.S_FALL_FLYING) != 0 && (c.state[c.n - 1] & Recorder.S_FALL_FLYING) == 0 && (c.state[c.n - 1] & Recorder.S_MAIN_GLINT) != 0
            && (c.state[50] & Recorder.S_MAIN_GLINT) == 0 && c.item[0] == Names.id("minecraft:diamond_sword"),
            "state word per frame (glide bits, main-hand glint split from the item id)");
        // more changes than the ring holds: oldest frames fall back to the oldest kept snapshot, never crash
        Recorder w = new Recorder(UUID.randomUUID(), "W", 2, 400, 16);
        for (int i = 0; i < 400; i++) { w.equipment(snap(i + 1, 0, 0, 0, 0, 0)); w.move(true, i, 0, 0, true, 0, 0, true); w.commit(nano + i * 50_000_000L, false); }
        Recorder.Copy wc = w.copy(0, 0);
        check(wc.snapshot(wc.eqRef[0], o) && o[0] == 400 - Recorder.EQ_CAP + 1 && wc.snapshot(wc.eqRef[399], o) && o[0] == 400,
            "equipment ring overflow (400 changes > " + Recorder.EQ_CAP + "): newest exact, oldest frames use the oldest kept snapshot");
        System.out.printf("  memory per player with state+equipment: %d KiB (1.0.x: %d KiB)%n", Recorder.bytesPerRecorder(1500, 1024) / 1024,
            ((long) 1500 * (4 + 8 * 3 + 4 * 2 + 4 + 4 + 2) + (long) 1024 * (4 + 4 + 1 + 4 * 4 + 8 * 3)) / 1024);
    }

    static ClipService.Host host(List<Recorder> recs) {
        return new ClipService.Host() {
            public Collection<Recorder> recorders() { return recs; }
            public Recorder find(String name) { for (Recorder r : recs) if (r.name.equals(name)) return r; return null; }
            public void runLos(String world, double x, double z, List<Analyzer.LosQuery> q, Consumer<byte[]> done) { done.accept(new byte[q.size()]); }
            public void saved(ClipService.Pending p, ClipService.IndexEntry e) { }
        };
    }

    /** Recorder -> mark -> assembled clip (format 2) -> gzip -> read back; equipment changes and glide state survive. */
    static void assembleV2() throws Exception {
        Settings st = new Settings(); st.afterSeconds = 0;
        Recorder sus = new Recorder(UUID.randomUUID(), "Glider", 1, st.frameCap(), st.eventCap);
        int helmet = Names.id("minecraft:netherite_helmet"), elytra = Names.id("minecraft:elytra"), plate = Names.id("minecraft:netherite_chestplate");
        int trim = Names.id("minecraft:sentry minecraft:gold");
        long base = System.nanoTime() - 30_000_000_000L;   // 30 s of history ending now
        for (int i = 0; i < 600; i++) {
            if (i % 10 == 0) sus.equipment(i < 200 ? snap(helmet, plate, 0, 0, 0, trim) : snap(helmet, elytra | Recorder.GLINT, 0, 0, 0, 0));
            boolean glide = i >= 220 && i < 420;
            sus.state = glide ? Recorder.P_FALL_FLYING | Recorder.S_FALL_FLYING : 0;
            double x = glide ? (i - 220) * 1.5 : i < 220 ? 0 : 300;          // 30 b/s while gliding
            sus.move(true, x, glide ? 150 - (i - 220) * 0.1 : 70, 0, true, -90, 10, !glide);
            sus.commit(base + i * 50_000_000L, false);
        }
        List<Recorder> recs = new ArrayList<>(List.of(sus));
        Path dir = Files.createTempDirectory("rrv2");
        ClipService svc = new ClipService(host(recs), Logger.getLogger("v2"), dir, st);
        svc.mark("Glider", "test", "console", false);
        long m0 = System.nanoTime();
        while (svc.saved == 0 && svc.failed == 0 && System.nanoTime() - m0 < 20_000_000_000L) Thread.sleep(5);
        check(svc.saved == 1, "format 2 clip saved through ClipService");
        Path f;
        try (var w = Files.walk(dir.resolve("clips"))) { f = w.filter(x -> x.toString().endsWith(".json.gz")).findFirst().orElseThrow(); }
        Clip c = Clip.read(f);
        Clip.Track t = c.tracks.get(0);
        boolean eq = t.equip.size() == 2 && t.equip.get(0)[0] == 0 && c.items.get(t.equip.get(0)[2]).equals("minecraft:netherite_chestplate")
            && c.trims.get(t.equip.get(0)[8]).equals("minecraft:sentry minecraft:gold")
            && c.items.get(t.equip.get(1)[2]).equals("minecraft:elytra") && (t.equip.get(1)[6] & 2) != 0 && t.equip.get(1)[8] == 0
            && c.items.get(t.equip.get(1)[1]).equals("minecraft:netherite_helmet");
        check(c.format >= 2 && eq, "equipment stored as changes only (" + t.equip.size() + " entries for " + t.n + " frames): netherite+trim, then glinted elytra");
        int glideFrames = 0; for (int i = 0; i < t.n; i++) if ((t.state(i) & Recorder.S_FALL_FLYING) != 0) glideFrames++;
        check(glideFrames == 200 && (t.state(t.equip.get(1)[0]) & Recorder.S_FALL_FLYING) == 0, "glide state per frame survives the file (" + glideFrames + " frames)");
        var fl = c.summary.getAsJsonObject("flight");
        System.out.println("  flight: " + fl);
        check(Math.abs(fl.get("fallFlyingSec").getAsDouble() - 10.0) < 0.2 && fl.get("maxGlideHorizontalBlocksPerSec").getAsDouble() > 29
            && c.summary.getAsJsonArray("flags").isEmpty(), "summary: 10 s fall-flying at 30 b/s, not flagged");
        svc.shutdown();
        System.out.printf("  format 2 clip (1 track, %d frames): %d bytes gz%n", t.n, Files.size(f));
    }

    /** 1.2.0: entity ring overflow, two players seeing the same mob are merged into one entity, format 3 round trip, block positions. */
    static void entitiesV3() throws Exception {
        // ring keeps the newest rows
        Recorder r = new Recorder(UUID.randomUUID(), "R", 9, 200, 16, 256);
        int[] id = new int[4], ty = new int[4], da = new int[4]; float[] x = new float[4], y = new float[4], z = new float[4]; byte[] yw = new byte[4], pt = new byte[4], fl = new byte[4];
        for (int i = 0; i < 300; i++) { for (int k = 0; k < 4; k++) { id[k] = k; x[k] = i; } r.entities(i * 100, 4, id, ty, da, x, y, z, yw, pt, fl); }
        Recorder.Copy rc = r.copy(0, 0, 0);
        check(rc.nq == 256 && rc.nx[255] == 299 && rc.nx[0] == 236, "entity ring keeps the newest 256 rows (" + rc.nq + ", " + rc.nx[0] + ".." + rc.nx[255] + ")");
        Recorder.Copy rd = r.copy(rc.endSeq, rc.endEventSeq, rc.endEntitySeq - 8);
        check(rd.nq == 8 && rd.nx[7] == 299, "incremental entity copy");
        // two players, one zombie both see, one item only B sees
        Settings st = new Settings(); st.afterSeconds = 0;
        Recorder a = new Recorder(UUID.randomUUID(), "Sus", 1, st.frameCap(), st.eventCap, st.entityCap());
        Recorder b = new Recorder(UUID.randomUUID(), "Near", 2, st.frameCap(), st.eventCap, st.entityCap());
        a.world = b.world = Names.id("world");
        int zombie = Names.id("minecraft:zombie"), item = Names.id("minecraft:item"), dia = Names.id("minecraft:diamond");
        long base = System.nanoTime() - 20_000_000_000L;
        for (int i = 0; i < 400; i++) {
            long nano = base + i * 50_000_000L;
            a.move(true, i * 0.1, 64, 0, true, 0, 0, true); a.commit(nano, false);
            b.move(true, i * 0.1 + 3, 64, 0, true, 0, 0, true); b.commit(nano, false);
            if (i % 2 == 0) {
                int[] i1 = {500}; int[] t1 = {zombie}; int[] d1 = {0}; float[] x1 = {(float) (i * 0.1 + 5)}, y1 = {64f}, z1 = {2.5f};
                byte[] yaw1 = {(byte) 64}, p1 = {0}, f1 = {(byte) (i > 300 ? Recorder.N_FIRE : 0)};
                a.entities(a.msNow(nano), 1, i1, t1, d1, x1, y1, z1, yaw1, p1, f1);
                b.entities(b.msNow(nano + 3_000_000L), 1, i1, t1, d1, x1, y1, z1, yaw1, p1, f1);   // same mob, 3 ms later -> merged
                int[] i2 = {501}; int[] t2 = {item}; int[] d2 = {dia}; float[] x2 = {10f}, y2 = {63f}, z2 = {-1f};
                b.entities(b.msNow(nano), 1, i2, t2, d2, x2, y2, z2, new byte[1], new byte[1], new byte[1]);
            }
        }
        List<Recorder> recs = new ArrayList<>(List.of(a, b));
        Path dir = Files.createTempDirectory("rrv3");
        ClipService svc = new ClipService(host(recs), Logger.getLogger("v3"), dir, st);
        svc.mark("Sus", "test", "console", false);
        long m0 = System.nanoTime();
        while (svc.saved == 0 && svc.failed == 0 && System.nanoTime() - m0 < 20_000_000_000L) Thread.sleep(5);
        Path f;
        try (var w = Files.walk(dir.resolve("clips"))) { f = w.filter(q -> q.toString().endsWith(".json.gz")).findFirst().orElseThrow(); }
        Clip c = Clip.read(f);
        Clip.Ent ez = null, ei = null;
        for (Clip.Ent e : c.entities) { if (e.id == 500) ez = e; if (e.id == 501) ei = e; }
        check(c.format == 3 && c.tracks.size() == 2 && c.entities.size() == 2 && ez != null && ei != null, "format 3 clip: 2 tracks, 2 entities (" + c.entities.size() + ")");
        boolean mono = true; for (int i = 1; ez != null && i < ez.n; i++) if (ez.t[i] - ez.t[i - 1] < 40) mono = false;
        check(ez != null && ez.n >= 195 && ez.n <= 201 && mono && ez.type.equals("minecraft:zombie"), "the zombie both players saw is one entity, samples merged (" + (ez == null ? 0 : ez.n) + " samples, >= 40 ms apart)");
        check(ei != null && ei.data.equals("minecraft:diamond") && Math.abs(ei.x[0] - 10f) < 0.01 && Math.abs(ei.y[0] - 63f) < 0.01, "dropped item keeps its material and position");
        int last = ez == null ? -1 : ez.indexAt(c.durationMs);
        check(ez != null && last >= 0 && (ez.flags[last] & Recorder.N_FIRE) != 0 && (ez.flags[0] & Recorder.N_FIRE) == 0 && ez.yaw[0] == 64, "entity flags and rotation survive (on fire later, yaw 64/256)");
        svc.shutdown();
        String sample = System.getProperty("rr.sampleOut", "");
        if (!sample.isBlank()) { Files.copy(f, Path.of(sample), java.nio.file.StandardCopyOption.REPLACE_EXISTING); System.out.println("  sample clip written to " + sample); }
        System.out.printf("  format 3 clip with 2 entities: %d bytes gz; memory per player with entities: %d KiB%n", Files.size(f),
            Recorder.bytesPerRecorder(st.frameCap(), st.eventCap, st.entityCap()) / 1024);
        // block positions
        boolean pk = true;
        for (int[] v : new int[][] {{0, 0, 0}, {-12345, -60, 98765}, {29_999_999, 319, -29_999_999}, {-1, -64, -1}, {7, 2047, -8}}) {
            long p = BlockTimeline.pack(v[0], v[1], v[2]);
            pk &= BlockTimeline.unpackX(p) == v[0] && BlockTimeline.unpackY(p) == v[1] && BlockTimeline.unpackZ(p) == v[2];
        }
        check(pk, "block position packing round trip (negative, world border, build limits)");
    }

    /** Synthetic suspect: speeds in the air with different states; only the not-gliding one is flagged. */
    static void flightFlag() {
        record Case(String name, int state, int flags, double bps, int frames, boolean expect) { }
        Case[] cases = {
            new Case("air 15 b/s 2 s, not gliding", 0, 0, 15, 40, true),
            new Case("air 15 b/s 0.9 s, not gliding", 0, 0, 15, 18, false),
            new Case("air 15 b/s 2 s, gliding", Recorder.P_FALL_FLYING | Recorder.S_FALL_FLYING, 0, 15, 40, false),
            new Case("air 25 b/s 2 s, riptide", Recorder.P_SPIN_ATTACK | Recorder.S_RIPTIDE, 0, 25, 40, false),
            new Case("air 15 b/s 2 s, riding", Recorder.S_RIDING, 0, 15, 40, false),
            new Case("air 15 b/s 2 s, creative flight", Recorder.S_ABILITY_FLYING, 0, 15, 40, false),
            new Case("ground 15 b/s 2 s", 0, Recorder.F_GROUND, 15, 40, false),
            new Case("air 9 b/s 3 s", 0, 0, 9, 60, false),
        };
        for (Case k : cases) {
            Clip c = new Clip(); c.worlds.add("world"); c.items.add("");
            Clip.Track s = new Clip.Track(); s.alloc(200); s.state = new int[200];
            for (int i = 0; i < 200; i++) {
                boolean in = i >= 50 && i < 50 + k.frames;
                s.t[i] = i * 50; s.x[i] = i == 0 ? 0 : s.x[i - 1] + (in ? k.bps / 20 : 0.1); s.y[i] = 100;
                s.flags[i] = Recorder.F_POS | (in ? k.flags : Recorder.F_GROUND); s.state[i] = in ? k.state : 0;
            }
            c.tracks.add(s);
            var sum = new Analyzer(c, 32).summary();
            boolean flagged = !sum.getAsJsonArray("flags").isEmpty();
            check(flagged == k.expect, "fly flag: " + k.name + " -> " + (flagged ? "flagged" : "not flagged") + " (" + sum.getAsJsonObject("flight").get("airFastNotGlidingLongestSec") + " s)");
        }
        // anticheat setbacks (server teleports every 0.5 s) during an air speed hack must not hide it
        {
            Clip c0 = new Clip(); c0.worlds.add("world"); c0.items.add("");
            Clip.Track s0 = new Clip.Track(); s0.alloc(200); s0.state = new int[200];
            for (int i = 0; i < 200; i++) {
                boolean in = i >= 50 && i < 110;
                s0.t[i] = i * 50; s0.x[i] = i == 0 ? 0 : s0.x[i - 1] + (in ? 0.75 : 0.1); s0.y[i] = 100;
                s0.flags[i] = Recorder.F_POS | (in ? 0 : Recorder.F_GROUND) | (in && i % 10 == 0 ? Recorder.F_TELEPORT : 0);
            }
            c0.tracks.add(s0);
            var sum0 = new Analyzer(c0, 32).summary();
            check(!sum0.getAsJsonArray("flags").isEmpty(), "fly flag: air 15 b/s for 3 s with a server teleport (setback) every 0.5 s is still flagged ("
                + sum0.getAsJsonObject("flight").get("airFastNotGlidingLongestSec") + " s longest run)");
        }
        // momentum right after a glide ends (elytra broke mid-air): 0.8 s of fast air movement is within the grace
        Clip c = new Clip(); c.worlds.add("world"); c.items.add("");
        Clip.Track s = new Clip.Track(); s.alloc(200); s.state = new int[200];
        for (int i = 0; i < 200; i++) {
            boolean glide = i >= 50 && i < 100, coast = i >= 100 && i < 116;
            s.t[i] = i * 50; s.x[i] = i == 0 ? 0 : s.x[i - 1] + (glide || coast ? 1.5 : 0.1); s.y[i] = 100;
            s.flags[i] = Recorder.F_POS | (glide || coast ? 0 : Recorder.F_GROUND); s.state[i] = glide ? Recorder.P_FALL_FLYING | Recorder.S_FALL_FLYING : 0;
        }
        c.tracks.add(s);
        check(new Analyzer(c, 32).summary().getAsJsonArray("flags").isEmpty(), "fly flag: 0.8 s coasting after a glide ends is not flagged (1 s grace)");
    }

    /** A clip rewritten as format 1 (no state/equipment/entities) and format 2 (no entities) still loads and analyzes. */
    static void olderFormats() throws Exception {
        Clip c = synthetic("aimbot", 5);
        c.tracks.get(0).state = new int[c.tracks.get(0).n];
        c.tracks.get(0).equip.add(new int[] {0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        c.summary = new Analyzer(c, 32).summary();
        Path dir = Files.createTempDirectory("rrfmt"), f3 = dir.resolve("v3.json.gz");
        c.write(f3);
        com.google.gson.JsonObject o;
        try (var r = new java.io.InputStreamReader(new java.util.zip.GZIPInputStream(Files.newInputStream(f3)), java.nio.charset.StandardCharsets.UTF_8)) {
            o = com.google.gson.JsonParser.parseReader(r).getAsJsonObject();
        }
        for (int v = 1; v <= 2; v++) {
            com.google.gson.JsonObject x = o.deepCopy();
            x.addProperty("format", v == 1 ? Clip.FORMAT_V1 : Clip.FORMAT_V2);
            x.remove("entities"); x.remove("entityFlagsLegend");
            if (v == 1) {
                x.remove("trims"); x.remove("stateLegend"); x.remove("equipLegend");
                for (var te : x.getAsJsonArray("tracks")) { te.getAsJsonObject().remove("state"); te.getAsJsonObject().remove("equip"); }
            }
            Path fv = dir.resolve("v" + v + ".json.gz");
            try (var w = new java.io.OutputStreamWriter(new java.util.zip.GZIPOutputStream(Files.newOutputStream(fv)), java.nio.charset.StandardCharsets.UTF_8)) { w.write(x.toString()); }
            Clip d = Clip.read(fv);
            Analyzer an = new Analyzer(d, 32);
            boolean ok = d.format == v && d.entities.isEmpty() && d.tracks.size() == 2 && d.tracks.get(0).n == c.tracks.get(0).n;
            if (v == 1) ok &= d.tracks.stream().allMatch(t -> t.state == null && t.equip.isEmpty() && t.equipAt(0) == -1 && t.state(0) == 0)
                && !an.flight().json().get("poseData").getAsBoolean();
            else ok &= d.tracks.get(0).state != null && d.tracks.get(0).equip.size() == 1 && an.flight().json().get("poseData").getAsBoolean();
            ok &= an.summary().getAsJsonObject("attacks").get("total").getAsInt() == c.tracks.get(0).events.size();
            check(ok, "format " + v + " clip (synthetic) loads and analyzes: " + (v == 1 ? "no state, no equipment, poseData=false" : "state + equipment, no entities"));
        }
    }

    /** Real clips written by 1.0.x (format 1) still load: no state, no equipment, viewer/analyzer work. Dirs from -Drr.oldclips;
     *  skipped when the property is unset (the synthetic check in {@link #olderFormats()} always runs). */
    static void oldClips() throws Exception {
        String dirs = System.getProperty("rr.oldclips", "");
        if (dirs.isBlank()) { System.out.println("SKIP real format 1 clips (set -Drr.oldclips=dir to read them)"); return; }
        int read = 0, bad = 0;
        for (String d : dirs.split(":")) {
            if (d.isBlank() || !Files.isDirectory(Path.of(d))) continue;
            try (var w = Files.walk(Path.of(d))) {
                for (Path f : w.filter(x -> x.toString().endsWith(".json.gz")).sorted().toList()) {
                    try {
                        Clip c = Clip.read(f);
                        Analyzer an = new Analyzer(c, 32);
                        boolean ok = c.format == 1 && c.tracks.stream().allMatch(t -> t.state == null && t.equip.isEmpty() && t.equipAt(0) == -1 && t.state(0) == 0)
                            && !an.flight().json().get("poseData").getAsBoolean();
                        if (ok) read++; else bad++;
                        System.out.println("  old clip " + f.getFileName() + ": format " + c.format + ", " + c.tracks.size() + " tracks, " + c.tracks.get(0).n + " frames" + (ok ? "" : " UNEXPECTED"));
                    } catch (Exception ex) { bad++; System.out.println("  old clip " + f + " FAILED: " + ex); }
                }
            }
        }
        check(read > 0 && bad == 0, "format 1 clips from 1.0.x load (" + read + " read, " + bad + " failed)");
    }

    /** Re-analyze real format 2+ clips (dirs from -Drr.labclips) with this build's Analyzer and print the flight summary. */
    static void labClips() throws Exception {
        String dirs = System.getProperty("rr.labclips", "");
        if (dirs.isBlank()) { System.out.println("SKIP lab clip re-analysis (set -Drr.labclips=dir to run it)"); return; }
        for (String d : dirs.split(":")) {
            if (d.isBlank() || !Files.isDirectory(Path.of(d))) continue;
            try (var w = Files.walk(Path.of(d))) {
                for (Path f : w.filter(x -> x.toString().endsWith(".json.gz")).sorted().toList()) {
                    Clip c = Clip.read(f);
                    if (c.format < 2) continue;
                    var sum = new Analyzer(c, 32).summary();
                    var fl = sum.getAsJsonObject("flight"); fl.remove("rule");
                    System.out.println("  lab clip " + f.getFileName() + " (" + c.suspectName + "): flags=" + sum.getAsJsonArray("flags") + " flight=" + fl);
                }
            }
        }
    }

    // ------------------------------------------------------------------ bench (60 players)

    static void bench() throws Exception {
        Settings st = new Settings();
        int players = 60, fcap = st.frameCap(), ecap = st.eventCap;
        long per = Recorder.bytesPerRecorder(fcap, ecap);
        System.out.printf("  memory: %d frames + %d events per player = %d KiB; 60 players = %.2f MiB; +40 recent quits = %.2f MiB%n",
            fcap, ecap, per / 1024, players * per / 1048576.0, (players + 40) * per / 1048576.0);
        Runtime rt = Runtime.getRuntime();
        System.gc(); Thread.sleep(200); long used0 = rt.totalMemory() - rt.freeMemory();
        List<Recorder> recs = new ArrayList<>();
        for (int i = 0; i < players; i++) recs.add(new Recorder(UUID.randomUUID(), "P" + i, i + 1, fcap, ecap));
        System.gc(); Thread.sleep(200); long used1 = rt.totalMemory() - rt.freeMemory();
        System.out.printf("  measured heap for 60 recorders: %.2f MiB%n", (used1 - used0) / 1048576.0);
        // per-packet cost: 20 Hz x (move + tick end) + some swings/attacks, 4 netty threads
        int threads = 4, seconds = 90;
        long totalOps = 0; long t0 = System.nanoTime();
        CountDownLatch done = new CountDownLatch(threads);
        long[] ops = new long[threads];
        for (int th = 0; th < threads; th++) {
            final int tt = th;
            new Thread(() -> {
                Random rnd = new Random(tt);
                long base = System.nanoTime() - seconds * 1_000_000_000L;   // the simulated 90 s end now
                for (int tick = 0; tick < seconds * 20; tick++) {
                    long now = base + tick * 50_000_000L;
                    for (int p = tt; p < players; p += threads) {
                        Recorder r = recs.get(p);
                        r.move(true, rnd.nextDouble() * 100, 64, rnd.nextDouble() * 100, true, rnd.nextFloat() * 360, rnd.nextFloat() * 90, true); ops[tt]++;
                        if (rnd.nextInt(4) == 0) { r.flag(Recorder.F_SWING); ops[tt]++; }
                        if (rnd.nextInt(12) == 0) { r.flag(Recorder.F_ATTACK); r.event(Recorder.E_ATTACK, 5, -1, 0, 0, 1, 2, 3); ops[tt]++; }
                        r.commit(now, false); ops[tt]++;
                    }
                }
                done.countDown();
            }).start();
        }
        done.await();
        long el = System.nanoTime() - t0;
        for (long o : ops) totalOps += o;
        System.out.printf("  record: %d packet operations (60 players x 90 s of 20 Hz traffic) in %.1f ms on 4 threads = %.0f ns/op; "
            + "at real time that is %.4f %% of one core%n", totalOps, el / 1e6, el * threads / (double) totalOps, el * threads / (seconds * 1e9) * 100);
        // mark: copy all 60 + assemble + analyze + write (host with every recorder near the suspect's area)
        Path dir = Files.createTempDirectory("rrbench");
        ClipService.Host host = new ClipService.Host() {
            public Collection<Recorder> recorders() { return recs; }
            public Recorder find(String name) { for (Recorder r : recs) if (r.name.equals(name)) return r; return null; }
            public void runLos(String world, double x, double z, List<Analyzer.LosQuery> q, Consumer<byte[]> done) { done.accept(new byte[q.size()]); }
            public void saved(ClipService.Pending p, ClipService.IndexEntry e) { }
        };
        st.afterSeconds = 0;
        ClipService svc = new ClipService(host, Logger.getLogger("bench"), dir, st);
        long m0 = System.nanoTime();
        String msg = svc.mark("P0", "bench", "console", false);
        while (svc.saved == 0 && svc.failed == 0 && System.nanoTime() - m0 < 30_000_000_000L) Thread.sleep(5);
        System.out.printf("  mark -> saved clip: %d ms (finalize %d ms), %d KiB, 60 candidate tracks, message: %s%n",
            (System.nanoTime() - m0) / 1_000_000, svc.lastFinalizeMs, svc.lastWriteBytes / 1024, msg);
        check(svc.saved == 1, "bench clip saved");
        svc.shutdown();
    }
}
