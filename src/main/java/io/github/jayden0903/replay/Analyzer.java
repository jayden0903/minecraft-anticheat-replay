package io.github.jayden0903.replay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-clip summary for staff (stored in the clip file, usable by any dashboard). Pure computation on a {@link Clip}; the only world access is the
 * optional line-of-sight step, which the plugin runs on the owning region thread through {@link #losQueries}.
 *
 * Definitions (all from the suspect's frames, other players at their latest known position at that time):
 *  - error: angle between the suspect's look vector and the vector from the eye to the target hitbox centre
 *  - onBox: the look ray hits the target hitbox (0.6 x 1.8, sneaking 1.5) within the nearby radius
 *  - hidden: the eye cannot see the target's head, chest or feet point through occluding (full opaque) blocks
 */
final class Analyzer {
    static final double EYE = 1.62, EYE_SNEAK = 1.27;
    final Clip clip; final Clip.Track s; final int n; final double radius;
    final int[] best; final float[] err; final boolean[] onBox; final float[] dist;
    // line of sight (per suspect frame, for the best target): 0 unknown/not checked, 1 visible, 2 hidden
    final byte[] los;
    int losChecked, losUnknown;
    String losNote = "not run";

    Analyzer(Clip clip, double radius) {
        this.clip = clip; this.s = clip.tracks.get(0); this.n = s.n; this.radius = radius;
        best = new int[n]; err = new float[n]; onBox = new boolean[n]; dist = new float[n];
        los = s.los != null && s.los.length == n ? s.los : new byte[n];      // saved clips carry the LOS result
        Arrays.fill(best, -1);
        for (int i = 0; i < n; i++) {
            double bestErr = 1e9, bestD = 0; int bj = -1; boolean bHit = false;
            for (int j = 1; j < clip.tracks.size(); j++) {
                Aim a = aim(i, j);
                if (a == null) continue;
                // prefer a target the ray actually hits (nearest), otherwise the smallest angular error
                boolean better = bj < 0 || (a.hit && !bHit) || (a.hit == bHit && (a.hit ? a.dist < bestD : a.err < bestErr));
                if (better) { bj = j; bestErr = a.err; bestD = a.dist; bHit = a.hit; }
            }
            best[i] = bj; err[i] = (float) bestErr; onBox[i] = bHit; dist[i] = (float) bestD;
        }
    }

    record Aim(double err, boolean hit, double dist, double yawErr, double pitchErr, double ex, double ey, double ez, double tx, double ty, double tz, double h) { }

    static double eye(int flags) { return (flags & Recorder.F_SNEAK) != 0 ? EYE_SNEAK : EYE; }

    /** Eye height with the recorded server pose (format 2): gliding, swimming/crawling and riptide put the eye at 0.4,
     *  sleeping at 0.2; otherwise (and for format 1 clips, state 0) the sneak flag decides as before. */
    static double eye(int flags, int state) {
        return switch (state & Recorder.S_POSE_MASK) {
            case Recorder.P_FALL_FLYING, Recorder.P_SWIMMING, Recorder.P_SPIN_ATTACK -> 0.4;
            case Recorder.P_SLEEPING -> 0.2;
            default -> eye(flags);
        };
    }

    /** Hitbox height with the recorded pose (0.6 lying/gliding/swimming, 0.2 sleeping, 1.5 sneaking, 1.8 standing). */
    static double height(int flags, int state) {
        return switch (state & Recorder.S_POSE_MASK) {
            case Recorder.P_FALL_FLYING, Recorder.P_SWIMMING, Recorder.P_SPIN_ATTACK -> 0.6;
            case Recorder.P_SLEEPING -> 0.2;
            default -> (flags & Recorder.F_SNEAK) != 0 ? 1.5 : 1.8;
        };
    }

    /** Aim of suspect frame i at track j, or null when j is absent/elsewhere/too far. */
    Aim aim(int i, int j) {
        Clip.Track o = clip.tracks.get(j);
        int k = o.indexAt(s.t[i]);
        if (k < 0 || s.t[i] - o.t[k] > 1000 || o.world[k] != s.world[i] || (o.flags[k] & Recorder.F_SPECTATOR) != 0) return null;
        double ex = s.x[i], ey = s.y[i] + eye(s.flags[i], s.state(i)), ez = s.z[i];
        double h = height(o.flags[k], o.state(k));
        double tx = o.x[k], ty = o.y[k], tz = o.z[k];
        double vx = tx - ex, vy = ty + h / 2 - ey, vz = tz - ez;
        double d = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (d > radius || d < 1e-6) return null;
        double[] dir = dir(s.yaw[i], s.pitch[i]);
        double cos = (dir[0] * vx + dir[1] * vy + dir[2] * vz) / d;
        double e = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
        double hit = rayBox(ex, ey, ez, dir, tx - 0.3, ty, tz - 0.3, tx + 0.3, ty + h, tz + 0.3);
        double wantYaw = Math.toDegrees(Math.atan2(-vx, vz)), wantPitch = -Math.toDegrees(Math.atan2(vy, Math.sqrt(vx * vx + vz * vz)));
        return new Aim(e, hit >= 0 && hit <= radius, hit >= 0 ? hit : d, wrap(s.yaw[i] - wantYaw), s.pitch[i] - wantPitch, ex, ey, ez, tx, ty, tz, h);
    }

    static double[] dir(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch), cp = Math.cos(p);
        return new double[] {-Math.sin(y) * cp, -Math.sin(p), Math.cos(y) * cp};
    }

    static double wrap(double a) { a %= 360; if (a >= 180) a -= 360; if (a < -180) a += 360; return a; }

    /** Distance along the ray to the box, or -1. */
    static double rayBox(double ox, double oy, double oz, double[] d, double x0, double y0, double z0, double x1, double y1, double z1) {
        double tmin = 0, tmax = 1e9;
        double[] o = {ox, oy, oz}, lo = {x0, y0, z0}, hi = {x1, y1, z1};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-12) { if (o[a] < lo[a] || o[a] > hi[a]) return -1; continue; }
            double t1 = (lo[a] - o[a]) / d[a], t2 = (hi[a] - o[a]) / d[a];
            if (t1 > t2) { double t = t1; t1 = t2; t2 = t; }
            tmin = Math.max(tmin, t1); tmax = Math.min(tmax, t2);
            if (tmin > tmax) return -1;
        }
        return tmin;
    }

    static double angDist(float y1, float p1, float y2, float p2) {
        double dy = wrap(y2 - y1), dp = p2 - p1; return Math.sqrt(dy * dy + dp * dp);
    }

    // ------------------------------------------------------------------ line of sight

    /** One LOS query: eye -> three points on the target (head, chest, feet). */
    record LosQuery(int frame, int world, double ex, double ey, double ez, double[] targets) { }

    List<LosQuery> losQueries(int cap) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < n; i++) if (best[i] > 0 && (err[i] <= 10 || onBox[i])) idx.add(i);
        // attack frames always
        java.util.Set<Integer> must = new java.util.HashSet<>();
        for (Clip.Ev e : s.events) if (e.type.equals("attack") && e.frame >= 0 && e.frame < n && best[e.frame] > 0) must.add(e.frame);
        List<LosQuery> out = new ArrayList<>();
        int step = idx.size() > cap && cap > 0 ? (int) Math.ceil(idx.size() / (double) cap) : 1;
        for (int k = 0; k < idx.size(); k++) {
            int i = idx.get(k);
            if (k % step != 0 && !must.contains(i)) continue;
            Aim a = aim(i, best[i]);
            if (a == null) continue;
            out.add(new LosQuery(i, s.world[i], a.ex, a.ey, a.ez, new double[] {
                a.tx, a.ty + a.h - 0.12, a.tz,  a.tx, a.ty + a.h * 0.55, a.tz,  a.tx, a.ty + 0.2, a.tz}));
            if (cap > 0 && out.size() >= cap + must.size()) break;
        }
        return out;
    }

    void applyLos(List<LosQuery> q, byte[] result, String note) {
        losNote = note;
        s.los = los;
        for (int k = 0; k < q.size(); k++) {
            byte r = result[k];
            los[q.get(k).frame] = r;
            if (r == 0) losUnknown++; else losChecked++;
        }
    }

    // ------------------------------------------------------------------ summary

    JsonObject summary() {
        JsonObject o = new JsonObject();
        o.addProperty("version", 2);
        o.addProperty("frames", n);
        o.addProperty("durationSec", r3(n > 0 ? (s.t[n - 1] - s.t[0]) / 1000.0 : 0));
        int legacy = 0; for (int i = 0; i < n; i++) if ((s.flags[i] & Recorder.F_LEGACY) != 0) legacy++;
        o.addProperty("framesWithoutTickEnd", legacy);
        JsonArray nearby = new JsonArray();
        for (int j = 1; j < clip.tracks.size(); j++) nearby.add(clip.tracks.get(j).name);
        o.add("nearbyPlayers", nearby);

        // ---- aim over time
        int withT = 0, onT = 0; double errSum = 0;
        for (int i = 0; i < n; i++) if (best[i] > 0) { withT++; errSum += Math.min(err[i], 180); if (onBox[i]) onT++; }
        JsonObject aim = new JsonObject();
        aim.addProperty("framesWithTarget", withT);
        aim.addProperty("framesOnTarget", onT);
        aim.addProperty("onTargetRate", r3(withT > 0 ? onT / (double) withT : 0));
        aim.addProperty("meanErrDeg", r3(withT > 0 ? errSum / withT : 0));
        o.add("aim", aim);

        // ---- rotation speed
        List<Double> deltas = new ArrayList<>();
        int snaps40 = 0; double maxDelta = 0;
        for (int i = 1; i < n; i++) {
            if (((s.flags[i] | s.flags[i - 1]) & Recorder.F_TELEPORT) != 0 || s.t[i] - s.t[i - 1] > 150) continue;
            double d = angDist(s.yaw[i - 1], s.pitch[i - 1], s.yaw[i], s.pitch[i]);
            if ((s.flags[i] & Recorder.F_ROT) == 0) continue;
            deltas.add(d); maxDelta = Math.max(maxDelta, d); if (d > 40) snaps40++;
        }
        JsonObject rot = new JsonObject();
        rot.addProperty("rotatingFrames", deltas.size());
        rot.addProperty("maxDeltaDegPerTick", r3(maxDelta));
        rot.addProperty("p95DeltaDegPerTick", r3(pct(deltas, 0.95)));
        rot.addProperty("snapsOver40", snaps40);
        o.add("rotation", rot);

        // ---- attacks
        JsonObject at = new JsonObject();
        int total = 0, onPlayers = 0, hitBox = 0, snapBefore = 0, snapBack = 0;
        List<Double> errs = new ArrayList<>(), reach = new ArrayList<>(), intervals = new ArrayList<>();
        double yawErrAbs = 0, pitchErrAbs = 0;
        Map<String, Integer> onBoxBefore = new TreeMap<>();
        for (String k : new String[] {"0", "1", "2", "3", "4-9", "10+", "offBox"}) onBoxBefore.put(k, 0);
        int lastAttackT = Integer.MIN_VALUE;
        Map<String, Integer> perTarget = new TreeMap<>();
        for (Clip.Ev e : s.events) {
            if (!e.type.equals("attack")) continue;
            total++;
            if (lastAttackT != Integer.MIN_VALUE) intervals.add((double) (e.t - lastAttackT));
            lastAttackT = e.t;
            int i = Math.max(0, Math.min(n - 1, e.frame));
            if (e.targetTrack <= 0 || n == 0) { perTarget.merge(e.target.isEmpty() ? "entity" : e.target, 1, Integer::sum); continue; }
            Aim a = aim(i, e.targetTrack);
            if (a == null) continue;
            onPlayers++;
            perTarget.merge(clip.tracks.get(e.targetTrack).name, 1, Integer::sum);
            errs.add(a.err); yawErrAbs += Math.abs(a.yawErr); pitchErrAbs += Math.abs(a.pitchErr);
            if (a.hit) { hitBox++; reach.add(a.dist); }
            // ticks the crosshair was already on this target before the attack (0 = entered in the attack tick)
            if (!a.hit) onBoxBefore.merge("offBox", 1, Integer::sum);
            else {
                int k = i, c = 0;
                while (k - 1 >= 0 && c < 10) { Aim p = aim(k - 1, e.targetTrack); if (p == null || !p.hit) break; k--; c++; }
                String key = c <= 3 ? String.valueOf(c) : c < 10 ? "4-9" : "10+";
                onBoxBefore.merge(key, 1, Integer::sum);
            }
            // snap into the attack
            double maxIn = 0;
            for (int k = Math.max(1, i - 2); k <= i; k++) maxIn = Math.max(maxIn, angDist(s.yaw[k - 1], s.pitch[k - 1], s.yaw[k], s.pitch[k]));
            if (maxIn >= 30) snapBefore++;
            // silent-aura style: out to the target in the attack tick, straight back afterwards
            if (i >= 1 && i + 2 < n) {
                double exc = angDist(s.yaw[i - 1], s.pitch[i - 1], s.yaw[i], s.pitch[i]);
                double ret = Math.max(angDist(s.yaw[i], s.pitch[i], s.yaw[i + 1], s.pitch[i + 1]), angDist(s.yaw[i], s.pitch[i], s.yaw[i + 2], s.pitch[i + 2]));
                double net = Math.min(angDist(s.yaw[i - 1], s.pitch[i - 1], s.yaw[i + 1], s.pitch[i + 1]), angDist(s.yaw[i - 1], s.pitch[i - 1], s.yaw[i + 2], s.pitch[i + 2]));
                if (exc >= 30 && ret >= 0.75 * exc && net <= 0.35 * exc) snapBack++;
            }
        }
        at.addProperty("total", total);
        at.addProperty("onTrackedPlayers", onPlayers);
        JsonObject pt = new JsonObject(); perTarget.forEach(pt::addProperty); at.add("perTarget", pt);
        at.addProperty("meanErrDeg", r3(mean(errs)));
        at.addProperty("medianErrDeg", r3(pct(errs, 0.5)));
        at.addProperty("p90ErrDeg", r3(pct(errs, 0.9)));
        at.addProperty("maxErrDeg", r3(errs.isEmpty() ? 0 : errs.stream().mapToDouble(Double::doubleValue).max().orElse(0)));
        at.addProperty("meanAbsYawErrDeg", r3(onPlayers > 0 ? yawErrAbs / onPlayers : 0));
        at.addProperty("meanAbsPitchErrDeg", r3(onPlayers > 0 ? pitchErrAbs / onPlayers : 0));
        at.addProperty("onBoxRate", r3(onPlayers > 0 ? hitBox / (double) onPlayers : 0));
        at.addProperty("meanReach", r3(mean(reach)));
        at.addProperty("maxReach", r3(reach.isEmpty() ? 0 : reach.stream().mapToDouble(Double::doubleValue).max().orElse(0)));
        at.addProperty("snapBefore30", snapBefore);
        at.addProperty("snapBack", snapBack);
        JsonObject ob = new JsonObject(); onBoxBefore.forEach(ob::addProperty); at.add("ticksOnBoxBeforeAttack", ob);
        at.addProperty("zeroTickRate", r3(hitBox > 0 ? onBoxBefore.get("0") / (double) hitBox : 0));
        at.addProperty("medianIntervalMs", r3(pct(intervals, 0.5)));
        o.add("attacks", at);

        // ---- clicks
        int swings = 0, maxCps = 0;
        List<Integer> swingT = new ArrayList<>();
        for (int i = 0; i < n; i++) if ((s.flags[i] & Recorder.F_SWING) != 0) { swings++; swingT.add(s.t[i]); }
        for (int a = 0, b = 0; b < swingT.size(); b++) { while (swingT.get(b) - swingT.get(a) >= 1000) a++; maxCps = Math.max(maxCps, b - a + 1); }
        JsonObject cl = new JsonObject();
        cl.addProperty("swingTicks", swings);
        cl.addProperty("maxSwingTicksPer1s", maxCps);
        o.add("clicks", cl);

        // ---- hidden target (ESP-guided aim)
        JsonObject hid = new JsonObject();
        int onHidden = 0, nearHidden = 0, onVisible = 0; double hiddenTrackMs = 0, longest = 0, streak = 0; int prevI = -1, prevJ = -1;
        for (int i = 0; i < n; i++) {
            if (los[i] == 0) { streak = 0; prevI = -1; continue; }
            boolean hidden = los[i] == 2;
            if (onBox[i]) { if (hidden) onHidden++; else onVisible++; }
            boolean near = err[i] <= 5 || onBox[i];
            if (near && hidden) {
                nearHidden++;
                double dt = prevI >= 0 && prevJ == best[i] && s.t[i] - s.t[prevI] <= 150 ? s.t[i] - s.t[prevI] : 0;
                streak = dt > 0 ? streak + dt : 0; hiddenTrackMs += dt; longest = Math.max(longest, streak);
                prevI = i; prevJ = best[i];
            } else { streak = 0; prevI = -1; }
        }
        hid.addProperty("checkedFrames", losChecked);
        hid.addProperty("uncheckedFrames", losUnknown);
        hid.addProperty("framesOnTargetHidden", onHidden);
        hid.addProperty("framesOnTargetVisible", onVisible);
        hid.addProperty("framesWithin5DegHidden", nearHidden);
        hid.addProperty("hiddenTrackingSec", r3(hiddenTrackMs / 1000.0));
        hid.addProperty("longestHiddenTrackingSec", r3(longest / 1000.0));
        hid.addProperty("note", losNote);
        o.add("hidden", hid);

        // ---- movement
        double maxSpeed = 0; int fastGround = 0;
        for (int i = 1; i < n; i++) {
            int dt = s.t[i] - s.t[i - 1];
            if (dt <= 0 || dt > 150 || ((s.flags[i] | s.flags[i - 1]) & (Recorder.F_TELEPORT | Recorder.F_VEHICLE)) != 0 || s.world[i] != s.world[i - 1]) continue;
            double dx = s.x[i] - s.x[i - 1], dz = s.z[i] - s.z[i - 1];
            double v = Math.sqrt(dx * dx + dz * dz) / Math.max(dt, 50) * 1000.0;
            maxSpeed = Math.max(maxSpeed, v);
            if ((s.flags[i] & Recorder.F_GROUND) != 0 && v > 8) fastGround++;
        }
        JsonObject mv = new JsonObject();
        mv.addProperty("maxHorizontalBlocksPerSec", r3(maxSpeed));
        mv.addProperty("groundFramesOver8BlocksPerSec", fastGround);
        o.add("movement", mv);

        // ---- flight (format 2 clips: pose/state recorded on the server)
        Flight fl = flight();
        o.add("flight", fl.json);
        JsonArray flags = new JsonArray();
        if (fl.flagged) flags.add(AIR_FAST_FLAG);
        o.add("flags", flags);

        // ---- blocks, damage
        Map<String, Integer> broken = new TreeMap<>(); int placed = 0, damaged = 0, hits = 0;
        for (Clip.Ev e : s.events) {
            switch (e.type) {
                case "blockBreak" -> broken.merge(e.info, 1, Integer::sum);
                case "blockPlace" -> placed++;
                case "damaged" -> damaged++;
                case "hit" -> hits++;
                default -> { }
            }
        }
        JsonObject bl = new JsonObject(); JsonObject br = new JsonObject(); broken.forEach(br::addProperty);
        bl.add("broken", br); bl.addProperty("placed", placed); o.add("blocks", bl);
        JsonObject dm = new JsonObject(); dm.addProperty("taken", damaged); dm.addProperty("hitsConfirmed", hits); o.add("damage", dm);

        o.addProperty("headline", "공격 " + total + "회(플레이어 " + onPlayers + ") · 평균 오차 " + r1(mean(errs)) + "° · 즉시 공격 "
            + Math.round(100 * (hitBox > 0 ? onBoxBefore.get("0") / (double) hitBox : 0)) + "% · 스냅 " + snapBefore + "/" + snapBack
            + " · 가려진 대상 조준 " + r1(hiddenTrackMs / 1000.0) + "초"
            + (fl.glideSec > 0 ? " · 활공 " + r1(fl.glideSec) + "초" : "")
            + (fl.flagged ? " · 주의: " + AIR_FAST_FLAG + " " + r1(fl.longestSec) + "초" : ""));
        return o;
    }

    static final String AIR_FAST_FLAG = "공중 고속 이동(활공 아님)";
    static final double AIR_FAST_BPS = 10.0;          // horizontal blocks per second
    static final int AIR_FAST_MIN_MS = 1000, GRACE_MS = 1000, TP_GRACE_MS = 300, MAX_GAP_MS = 150;

    record Flight(JsonObject json, double glideSec, boolean flagged, double longestSec) { }

    /**
     * Elytra/flight review numbers. Horizontal speed per frame pair (as in "movement"). A frame counts as "airborne fast,
     * not gliding" when the speed is over 10 b/s while the client says it is in the air and the server says it is not
     * gliding, not riptiding, not riding/driving, not allowed to fly (creative, /fly) and not in water, and none of
     * those (nor damage knockback) happened in the previous second, nor a server teleport in the previous 0.3 s (only
     * long enough to skip the teleport jump itself: anticheat setbacks of a flying cheater must not hide the flight).
     * A run of such frames longer than
     * 1 s raises {@link #AIR_FAST_FLAG}. Clips without pose data (format 1) report poseData=false and never flag.
     */
    Flight flight() {
        JsonObject j = new JsonObject();
        boolean pose = s.state != null;
        j.addProperty("poseData", pose);
        if (!pose) { j.addProperty("note", "clip recorded before 1.1.0: no pose/equipment data"); return new Flight(j, 0, false, 0); }
        long glideMs = 0, swimMs = 0, riptideMs = 0, crawlMs = 0;
        double maxGlide = 0, maxNonGlide = 0, maxAirNonGlide = 0;
        int lastExcuse = Integer.MIN_VALUE / 2;   // t of the last frame that explains high speed (glide, riptide, vehicle, ...)
        int lastTp = Integer.MIN_VALUE / 2;       // t of the last server teleport (the client reaches the new position a tick or two later)
        int runStart = -1, runEnd = -1, runs = 0; long longest = 0, total = 0;
        for (int i = 0; i < n; i++) {
            int st = s.state[i], f = s.flags[i];
            boolean glide = (st & Recorder.S_FALL_FLYING) != 0;
            boolean excuse = glide || (st & (Recorder.S_RIPTIDE | Recorder.S_RIDING | Recorder.S_ABILITY_FLYING | Recorder.S_IN_WATER | Recorder.S_SLEEPING)) != 0
                || (f & (Recorder.F_VEHICLE | Recorder.F_DAMAGED | Recorder.F_SPECTATOR)) != 0;
            if ((f & Recorder.F_TELEPORT) != 0) lastTp = s.t[i];
            if (i == 0) { if (excuse) lastExcuse = s.t[0]; continue; }
            int dt = s.t[i] - s.t[i - 1];
            boolean neutral = s.t[i] - lastTp <= TP_GRACE_MS;   // the teleport/setback jump itself: neither extends nor ends a run
            boolean pairOk = dt > 0 && dt <= MAX_GAP_MS && s.world[i] == s.world[i - 1] && !neutral;
            if (pairOk) {
                if (glide) glideMs += dt;
                if ((st & Recorder.S_SWIMMING) != 0) swimMs += dt;
                if ((st & Recorder.S_RIPTIDE) != 0) riptideMs += dt;
                if ((st & Recorder.S_CRAWLING) != 0) crawlMs += dt;
            }
            double v = 0;
            if (pairOk) { double dx = s.x[i] - s.x[i - 1], dz = s.z[i] - s.z[i - 1]; v = Math.sqrt(dx * dx + dz * dz) / Math.max(dt, 50) * 1000.0; }
            if (pairOk && glide) maxGlide = Math.max(maxGlide, v);
            if (excuse) lastExcuse = s.t[i];
            // "not gliding" maxima count only unexplained frames (same rule as the flag: no glide/riptide/riding/allowed
            // flight/water/sleep/knockback in the last second), so momentum after a glide or riptide does not show up here
            boolean unexplained = pairOk && !excuse && s.t[i] - lastExcuse > GRACE_MS;
            if (unexplained) { maxNonGlide = Math.max(maxNonGlide, v); if ((f & Recorder.F_GROUND) == 0) maxAirNonGlide = Math.max(maxAirNonGlide, v); }
            boolean fast = unexplained && (f & Recorder.F_GROUND) == 0 && v > AIR_FAST_BPS;
            if (fast) {
                if (runStart < 0) runStart = i - 1;
                runEnd = i;
            }
            if ((!fast && !neutral || i == n - 1) && runStart >= 0) {
                long len = s.t[runEnd] - s.t[runStart];
                if (len > AIR_FAST_MIN_MS) { runs++; total += len; longest = Math.max(longest, len); }
                runStart = -1;
            }
        }
        j.addProperty("fallFlyingSec", r3(glideMs / 1000.0));
        j.addProperty("maxGlideHorizontalBlocksPerSec", r3(maxGlide));
        j.addProperty("maxNonGlideHorizontalBlocksPerSec", r3(maxNonGlide));
        j.addProperty("maxAirborneNonGlideHorizontalBlocksPerSec", r3(maxAirNonGlide));
        j.addProperty("swimmingSec", r3(swimMs / 1000.0));
        j.addProperty("crawlingSec", r3(crawlMs / 1000.0));
        j.addProperty("riptideSec", r3(riptideMs / 1000.0));
        j.addProperty("airFastNotGlidingRuns", runs);
        j.addProperty("airFastNotGlidingSec", r3(total / 1000.0));
        j.addProperty("airFastNotGlidingLongestSec", r3(longest / 1000.0));
        j.addProperty("rule", "horizontal > 10 b/s for > 1 s in the air, not gliding/riptiding/riding/allowed flight/in water/sleeping; 1 s grace after those and damage, 0.3 s after a server teleport");
        return new Flight(j, glideMs / 1000.0, runs > 0, longest / 1000.0);
    }

    static double mean(List<Double> l) { return l.isEmpty() ? 0 : l.stream().mapToDouble(Double::doubleValue).average().orElse(0); }
    static double pct(List<Double> l, double p) {
        if (l.isEmpty()) return 0;
        double[] a = l.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return a[Math.min(a.length - 1, (int) Math.floor(p * (a.length - 1) + 0.5))];
    }
    static double r3(double v) { return Math.round(v * 1000) / 1000.0; }
    static String r1(double v) { return String.valueOf(Math.round(v * 10) / 10.0); }

}
