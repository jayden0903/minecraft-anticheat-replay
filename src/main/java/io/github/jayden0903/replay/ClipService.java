package io.github.jayden0903.replay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Marks, the 30 s continuation, clip assembly, analysis hand-off, gzip files, index and retention.
 * All heavy work runs on one daemon thread ("RewindReplay-io"); the only world access (line of sight) is delegated to
 * the host, which runs it on the owning region thread.
 */
final class ClipService {
    interface Host {
        Collection<Recorder> recorders();                  // online + recently quit
        Recorder find(String name);
        /** Run LOS queries on the region that owns (world, x, z); call done with 0/1/2 per query (any thread). */
        void runLos(String world, double x, double z, List<Analyzer.LosQuery> q, Consumer<byte[]> done);
        void saved(Pending p, IndexEntry e);                 // feedback to the marker / staff
    }

    static final ZoneId ZONE = ZoneId.systemDefault();
    static final DateTimeFormatter ID_FMT = DateTimeFormatter.ofPattern("yyMMdd-HHmmss").withZone(ZONE);
    static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZONE);

    final Host host; final Logger log; final Path dir, clips, indexFile;
    volatile Settings s;
    final ScheduledThreadPoolExecutor io;
    final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Long> lastClipByUuid = new ConcurrentHashMap<>();
    private final ArrayDeque<Long> autoMarks = new ArrayDeque<>();
    final List<IndexEntry> index = new ArrayList<>();       // guarded by itself
    volatile boolean shuttingDown;
    volatile long lastFinalizeMs, lastWriteBytes; volatile int saved, failed;

    static final class Pending {
        final String id; final Recorder suspect; final long markNano, markWall; final boolean manual;
        final List<Clip.Reason> reasons = new ArrayList<>();
        final Map<Integer, Recorder.Copy> first = new HashMap<>();
        final Map<Integer, Recorder> recs = new HashMap<>();
        ScheduledFuture<?> timer; boolean done; volatile Runnable force;
        Pending(String id, Recorder suspect, long markNano, long markWall, boolean manual) {
            this.id = id; this.suspect = suspect; this.markNano = markNano; this.markWall = markWall; this.manual = manual;
        }
    }

    record IndexEntry(String id, String file, String suspect, String uuid, long createdAtMs, String reasons, String sources,
                      long bytes, String headline) { }

    ClipService(Host host, Logger log, Path dataDir, Settings s) {
        this.host = host; this.log = log; this.dir = dataDir; this.clips = dataDir.resolve("clips"); this.indexFile = dataDir.resolve("index.jsonl");
        this.s = s;
        io = new ScheduledThreadPoolExecutor(1, r -> { Thread t = new Thread(r, "RewindReplay-io"); t.setDaemon(true); t.setPriority(Thread.NORM_PRIORITY - 1); return t; });
        io.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        io.setRemoveOnCancelPolicy(true);
        loadIndex();
        io.scheduleWithFixedDelay(this::sweep, 1, 60, TimeUnit.MINUTES);
    }

    // ------------------------------------------------------------------ marks

    /** Returns a message for the caller. Any thread. */
    synchronized String mark(String name, String reason, String source, boolean manual) {
        if (shuttingDown) return "서버가 종료 중이라 기록할 수 없습니다.";
        Recorder r = host.find(name);
        if (r == null) return "최근 기록이 없는 플레이어입니다: " + name;
        long now = System.currentTimeMillis();
        String why = reason == null || reason.isBlank() ? "(사유 없음)" : reason.strip();
        if (why.length() > 200) why = why.substring(0, 200);
        Pending p = pending.get(r.rid);
        if (p != null) {
            synchronized (p) { p.reasons.add(new Clip.Reason(now, source, why)); }
            return "이미 저장 중인 클립 " + p.id + "에 사유를 추가했습니다.";
        }
        if (!manual) {
            Long last = lastClipByUuid.get(r.uuid.toString());
            if (last != null && now - last < s.consoleCooldownSeconds * 1000L)
                return "최근 " + s.consoleCooldownSeconds + "초 안에 " + r.name + " 클립이 이미 저장되어 건너뜁니다.";
            while (!autoMarks.isEmpty() && now - autoMarks.peekFirst() > 3_600_000L) autoMarks.pollFirst();
            if (autoMarks.size() >= s.maxClipsPerHour) return "자동 기록 한도(시간당 " + s.maxClipsPerHour + "개)를 넘어 건너뜁니다.";
            autoMarks.addLast(now);
        }
        if (pending.size() >= s.maxPending) return "저장 대기 중인 클립이 너무 많습니다. 잠시 후 다시 시도하세요.";
        String id = ID_FMT.format(Instant.ofEpochMilli(now)) + "-" + Integer.toHexString(0x100 + ThreadLocalRandom.current().nextInt(0xF00)).substring(1);
        p = new Pending(id, r, System.nanoTime(), now, manual);
        p.reasons.add(new Clip.Reason(now, source, why));
        pending.put(r.rid, p);
        lastClipByUuid.put(r.uuid.toString(), now);
        final Pending fp = p;
        io.execute(() -> firstCopy(fp));
        int after = r.quitNano != 0 ? 0 : s.afterSeconds;
        p.timer = io.schedule(() -> finish(fp, true), after, TimeUnit.SECONDS);
        log.info("mark " + r.name + " id=" + id + " source=" + source + " reason=" + why);
        return r.name + " 기록을 표시했습니다. 앞 " + s.bufferSeconds + "초와 뒤 " + after + "초를 클립 " + id + "로 저장합니다.";
    }

    private void firstCopy(Pending p) {
        try {
            for (Recorder r : host.recorders()) { p.first.put(r.rid, r.copy(0, 0)); p.recs.put(r.rid, r); }
            if (!p.recs.containsKey(p.suspect.rid)) { p.first.put(p.suspect.rid, p.suspect.copy(0, 0)); p.recs.put(p.suspect.rid, p.suspect); }
        } catch (Throwable t) { log.log(Level.WARNING, "first copy failed", t); }
    }

    // ------------------------------------------------------------------ assembly

    /** Build the clip. los=false skips the line-of-sight step (shutdown). */
    private void finish(Pending p, boolean los) {
        synchronized (p) { if (p.done) return; p.done = true; }
        long t0 = System.nanoTime();
        Clip clip;
        Analyzer an;
        try {
            clip = assemble(p);
            an = new Analyzer(clip, s.nearbyRadius);
        } catch (Throwable t) {
            log.log(Level.WARNING, "clip " + p.id + " assembly failed", t);
            pending.remove(p.suspect.rid); failed++;
            return;
        }
        List<Analyzer.LosQuery> q = los && s.los && !shuttingDown && clip.tracks.size() > 1 ? an.losQueries(s.losMaxCandidates) : List.of();
        if (q.isEmpty()) { complete(p, clip, an, null, null, clip.tracks.size() > 1 ? (shuttingDown ? "skipped: shutdown" : s.los ? "no aim near a target" : "disabled") : "no nearby players", t0); return; }
        Clip.Track st = clip.tracks.get(0);
        int mid = q.get(q.size() / 2).frame();
        String world = clip.worlds.get(st.world[mid]);
        final Object lock = new Object(); final boolean[] finished = {false};
        p.force = () -> {
            synchronized (lock) { if (finished[0]) return; finished[0] = true; }
            complete(p, clip, an, q, new byte[q.size()], shuttingDown ? "skipped: shutdown" : "timeout", t0);
        };
        ScheduledFuture<?> timeout = io.schedule(p.force, 20, TimeUnit.SECONDS);
        host.runLos(world, st.x[mid], st.z[mid], q, res -> io.execute(() -> {
            synchronized (lock) { if (finished[0]) return; finished[0] = true; }
            timeout.cancel(false);
            complete(p, clip, an, q, res, "blocks as of save time; occluding blocks only (glass, leaves, slabs do not hide)", t0);
        }));
    }

    private void complete(Pending p, Clip clip, Analyzer an, List<Analyzer.LosQuery> q, byte[] res, String note, long t0) {
        try {
            if (q != null) an.applyLos(q, res, note); else an.losNote = note;
            if (q != null) clip.tracks.get(0).los = an.los;
            clip.summary = an.summary();
            synchronized (p) { clip.reasons.clear(); clip.reasons.addAll(p.reasons); }
            LocalDate day = LocalDate.ofInstant(Instant.ofEpochMilli(p.markWall), ZONE);
            Path file = clips.resolve(DAY_FMT.format(day)).resolve(p.id + ".json.gz");
            clip.write(file);
            long bytes = Files.size(file);
            StringBuilder why = new StringBuilder(), src = new StringBuilder();
            for (Clip.Reason r : clip.reasons) { if (why.length() > 0) { why.append(" / "); src.append(','); } why.append(r.reason()); src.append(r.source()); }
            JsonObject sum = clip.summary;
            IndexEntry e = new IndexEntry(p.id, clips.relativize(file).toString(), clip.suspectName, clip.suspectUuid, clip.createdAtMs,
                why.toString(), src.toString(), bytes, sum.has("headline") ? sum.get("headline").getAsString() : "");
            synchronized (index) { index.add(e); }
            appendIndex(e);
            saved++; lastWriteBytes = bytes; lastFinalizeMs = (System.nanoTime() - t0) / 1_000_000L;
            log.info("clip " + p.id + " saved (" + clip.suspectName + ", " + clip.tracks.size() + " tracks, " + clip.tracks.get(0).n + " frames, "
                + bytes / 1024 + " KiB, " + lastFinalizeMs + " ms): " + e.headline());
            try { host.saved(p, e); } catch (Throwable ignored) { }
        } catch (Throwable t) {
            failed++;
            log.log(Level.WARNING, "clip " + p.id + " write failed", t);
        } finally {
            pending.remove(p.suspect.rid);
        }
    }

    Clip assemble(Pending p) {
        long before = s.bufferSeconds * 1_000_000_000L, after = s.afterSeconds * 1_000_000_000L;
        long zero = p.markNano - before, end = p.markNano + after;
        Map<Integer, Recorder> recs = new LinkedHashMap<>(p.recs);
        for (Recorder r : host.recorders()) recs.putIfAbsent(r.rid, r);
        recs.putIfAbsent(p.suspect.rid, p.suspect);
        Map<Integer, String> ridNames = new HashMap<>();
        for (Recorder r : recs.values()) ridNames.put(r.rid, r.name);

        Clip c = new Clip();
        c.id = p.id; c.createdAtMs = System.currentTimeMillis(); c.markWallMs = p.markWall;
        c.suspectName = p.suspect.name; c.suspectUuid = p.suspect.uuid.toString();
        Map<Integer, Integer> worldMap = new HashMap<>(), itemMap = new HashMap<>(), trimMap = new HashMap<>();
        c.trims.add(""); trimMap.put(0, 0);            // trim index 0 = no trim
        Map<Integer, Clip.Track> built = new LinkedHashMap<>();
        Map<Integer, long[]> seqBase = new HashMap<>();
        Map<Integer, Recorder.Copy> second = new HashMap<>();
        for (Recorder r : recs.values()) {
            Recorder.Copy a = p.first.get(r.rid);
            Recorder.Copy b = a == null ? r.copy(0, 0, 0) : r.copy(a.endSeq, a.endEventSeq, a.endEntitySeq);
            second.put(r.rid, b);
            Clip.Track tr = track(r, a, b, zero, end, worldMap, itemMap, trimMap, c, seqBase);
            if (tr != null) built.put(r.rid, tr);
        }
        Clip.Track sus = built.get(p.suspect.rid);
        if (sus == null) throw new IllegalStateException("no frames for suspect " + p.suspect.name);
        sus.role = "suspect";
        c.tracks.add(sus);
        Map<Integer, Integer> trackOf = new HashMap<>();
        trackOf.put(p.suspect.rid, 0);
        for (Map.Entry<Integer, Clip.Track> en : built.entrySet()) {
            if (en.getKey() == p.suspect.rid) continue;
            if (near(sus, en.getValue(), s.nearbyRadius)) { trackOf.put(en.getKey(), c.tracks.size()); c.tracks.add(en.getValue()); }
        }
        // resolve event targets (rid -> track index / name)
        for (Clip.Track tr : c.tracks) for (Clip.Ev e : tr.events) {
            if (e.targetTrack > 0) {  // holds rid+1 during assembly
                int rid = e.targetTrack - 1;
                Integer ti = trackOf.get(rid);
                e.target = ridNames.getOrDefault(rid, e.target);
                e.targetTrack = ti == null ? -1 : ti;
            } else e.targetTrack = -1;
        }
        entities(c, p, recs, second, trackOf.keySet(), zero, end, worldMap);
        c.startWallMs = p.markWall - s.bufferSeconds * 1000L;
        c.markOffsetMs = (int) (before / 1_000_000L);
        int last = 0; for (Clip.Track tr : c.tracks) if (tr.n > 0) last = Math.max(last, tr.t[tr.n - 1]);
        c.durationMs = last;
        return c;
    }

    private static Clip.Track track(Recorder r, Recorder.Copy a, Recorder.Copy b, long zero, long end, Map<Integer, Integer> worldMap,
                                    Map<Integer, Integer> itemMap, Map<Integer, Integer> trimMap, Clip c, Map<Integer, long[]> seqBase) {
        long off = r.nanoBase - zero;          // add to (t ms * 1e6) to get nanos on the clip timeline
        int na = a == null ? 0 : a.n, nb = b.n;
        int count = 0;
        for (int k = 0; k < na + nb; k++) {
            long tn = off + (long) (k < na ? a.t[k] : b.t[k - na]) * 1_000_000L;
            if (tn >= 0 && tn <= end - zero) count++;
        }
        if (count == 0) return null;
        Clip.Track tr = new Clip.Track();
        tr.name = r.name; tr.uuid = r.uuid.toString(); tr.client = r.client; tr.skinValue = r.skinValue; tr.skinSignature = r.skinSignature;
        tr.alloc(count);
        tr.state = new int[count];
        int[] snap = new int[Recorder.EQ_INTS], prev = null;
        int i = 0; long firstSeq = -1;
        for (int k = 0; k < na + nb; k++) {
            Recorder.Copy src = k < na ? a : b; int q = k < na ? k : k - na;
            long tn = off + (long) src.t[q] * 1_000_000L;
            if (tn < 0 || tn > end - zero) continue;
            if (firstSeq < 0) firstSeq = src.firstSeq + q;
            tr.t[i] = (int) (tn / 1_000_000L); tr.x[i] = src.x[q]; tr.y[i] = src.y[q]; tr.z[i] = src.z[q];
            tr.yaw[i] = src.yaw[q]; tr.pitch[i] = src.pitch[q]; tr.flags[i] = src.flags[q];
            tr.world[i] = local(worldMap, c.worlds, src.world[q]);
            tr.item[i] = local(itemMap, c.items, src.item[q]);
            tr.state[i] = src.state[q];
            // equipment: one entry per change (and one at the first frame that has a snapshot)
            if (src.snapshot(src.eqRef[q], snap)) {
                int[] e = new int[Clip.EQ_LEN];
                e[0] = i;
                for (int sl = 0; sl < Recorder.EQ_SLOTS; sl++) {
                    e[1 + sl] = local(itemMap, c.items, snap[sl] & Recorder.ID_MASK);
                    if ((snap[sl] & Recorder.GLINT) != 0) e[6] |= 1 << sl;
                }
                for (int sl = 0; sl < 4; sl++) e[7 + sl] = snap[Recorder.EQ_SLOTS + sl] == 0 ? 0 : local(trimMap, c.trims, snap[Recorder.EQ_SLOTS + sl]);
                if (prev == null || !java.util.Arrays.equals(prev, 1, Clip.EQ_LEN, e, 1, Clip.EQ_LEN)) { tr.equip.add(e); prev = e; }
            }
            i++;
        }
        int ma = a == null ? 0 : a.m;
        for (int k = 0; k < ma + b.m; k++) {
            Recorder.Copy src = k < ma ? a : b; int q = k < ma ? k : k - ma;
            long tn = off + (long) src.et[q] * 1_000_000L;
            if (tn < 0 || tn > end - zero) continue;
            Clip.Ev e = new Clip.Ev();
            e.t = (int) (tn / 1_000_000L);
            e.frame = (int) Math.max(0, Math.min(count - 1, src.efseq[q] - firstSeq));
            byte ty = src.etype[q];
            e.type = ty > 0 && ty < Recorder.EVENT_NAMES.length ? Recorder.EVENT_NAMES[ty] : "?";
            int ea = src.ea[q], eb = src.eb[q], ec = src.ec[q], ed = src.ed[q];
            switch (ty) {
                case Recorder.E_ATTACK, Recorder.E_INTERACT -> { e.entityId = ea; e.targetTrack = eb >= 0 ? eb + 1 : 0; e.info = Names.name(ec); }
                case Recorder.E_HIT -> { e.entityId = ea; e.targetTrack = eb >= 0 ? eb + 1 : 0; e.info = Names.name(ec); e.amount = ed / 100.0; }
                case Recorder.E_DAMAGED -> { e.info = Names.name(ea); e.targetTrack = eb >= 0 ? eb + 1 : 0; if (ec > 0) e.target = Names.name(ec); e.amount = ed / 100.0; }
                case Recorder.E_DIG -> { e.bx = ea; e.by = eb; e.bz = ec; e.info = ed == 0 ? "start" : ed == 1 ? "cancel" : ed == 2 ? "finish" : "stab"; }
                case Recorder.E_USE_ON_BLOCK -> { e.bx = ea; e.by = eb; e.bz = ec; e.info = "face" + ed; }
                case Recorder.E_BREAK, Recorder.E_PLACE -> { e.bx = ea; e.by = eb; e.bz = ec; e.info = Names.name(ed); }
                case Recorder.E_WORLD -> e.info = Names.name(ea);
                default -> { }
            }
            if (!Double.isNaN(src.ex[q])) { e.x = src.ex[q]; e.y = src.ey[q]; e.z = src.ez[q]; }
            tr.events.add(e);
        }
        return tr;
    }

    /** One entity sample while merging the rings of the clip's players. */
    private record ERow(int t, float x, float y, float z, byte yaw, byte pitch, byte flags, int type, int data, int world) { }
    static final int MAX_ENTITIES = 600, MAX_ENTITY_ROWS = 250_000;

    /** Nearby entities from the rings of every player in the clip, merged per entity id (players near each other saw the
     *  same mob; samples closer than 40 ms are one), on the clip timeline. */
    static void entities(Clip c, Pending p, Map<Integer, Recorder> recs, Map<Integer, Recorder.Copy> second, java.util.Collection<Integer> rids,
                         long zero, long end, Map<Integer, Integer> worldMap) {
        Map<Integer, List<ERow>> by = new HashMap<>();
        int rows = 0;
        for (int rid : rids) {
            Recorder r = recs.get(rid); if (r == null) continue;
            long off = r.nanoBase - zero;
            for (Recorder.Copy src : new Recorder.Copy[] {p.first.get(rid), second.get(rid)}) {
                if (src == null) continue;
                for (int k = 0; k < src.nq && rows < MAX_ENTITY_ROWS; k++) {
                    long tn = off + (long) src.nt[k] * 1_000_000L;
                    if (tn < 0 || tn > end - zero) continue;
                    by.computeIfAbsent(src.nid[k], x -> new ArrayList<>()).add(new ERow((int) (tn / 1_000_000L), src.nx[k], src.ny[k], src.nz[k],
                        src.nyaw[k], src.npitch[k], src.nflags[k], src.ntype[k], src.ndata[k], src.nworld[k]));
                    rows++;
                }
            }
        }
        List<Map.Entry<Integer, List<ERow>>> list = new ArrayList<>(by.entrySet());
        list.sort((x, y) -> Integer.compare(y.getValue().size(), x.getValue().size()));   // longest-seen first when capped
        for (Map.Entry<Integer, List<ERow>> en : list) {
            if (c.entities.size() >= MAX_ENTITIES) break;
            List<ERow> l = en.getValue();
            l.sort(Comparator.comparingInt(ERow::t));
            List<ERow> kept = new ArrayList<>(l.size());
            for (ERow r : l) if (kept.isEmpty() || r.t() - kept.get(kept.size() - 1).t() >= 40) kept.add(r);
            Clip.Ent e = new Clip.Ent();
            e.id = en.getKey(); ERow f = kept.get(0);
            e.type = Names.name(f.type()); e.data = f.data() == 0 ? "" : Names.name(f.data()); e.world = local(worldMap, c.worlds, f.world());
            e.alloc(kept.size());
            for (int i = 0; i < kept.size(); i++) {
                ERow r = kept.get(i);
                e.t[i] = r.t(); e.x[i] = r.x(); e.y[i] = r.y(); e.z[i] = r.z(); e.yaw[i] = r.yaw(); e.pitch[i] = r.pitch(); e.flags[i] = r.flags();
            }
            c.entities.add(e);
        }
    }

    private static int local(Map<Integer, Integer> map, List<String> list, int global) {
        Integer v = map.get(global);
        if (v != null) return v;
        list.add(Names.name(global)); map.put(global, list.size() - 1);
        return list.size() - 1;
    }

    /** True when o came within radius of the suspect at any suspect frame (same world, o not spectating). */
    static boolean near(Clip.Track sus, Clip.Track o, double radius) {
        double r2 = radius * radius; int k = 0;
        for (int i = 0; i < sus.n; i++) {
            while (k + 1 < o.n && o.t[k + 1] <= sus.t[i]) k++;
            if (o.t[k] > sus.t[i] || sus.t[i] - o.t[k] > 1000 || o.world[k] != sus.world[i] || (o.flags[k] & Recorder.F_SPECTATOR) != 0) continue;
            double dx = o.x[k] - sus.x[i], dy = o.y[k] - sus.y[i], dz = o.z[k] - sus.z[i];
            if (dx * dx + dy * dy + dz * dz <= r2) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ files

    Path file(IndexEntry e) { return clips.resolve(e.file()); }

    IndexEntry entry(String id) {
        synchronized (index) { for (int i = index.size() - 1; i >= 0; i--) if (index.get(i).id().equalsIgnoreCase(id)) return index.get(i); }
        return null;
    }

    List<IndexEntry> list(String suspect, int limit) {
        List<IndexEntry> out = new ArrayList<>();
        synchronized (index) {
            for (int i = index.size() - 1; i >= 0 && out.size() < limit; i--) {
                IndexEntry e = index.get(i);
                if (suspect == null || e.suspect().equalsIgnoreCase(suspect)) out.add(e);
            }
        }
        return out;
    }

    private void loadIndex() {
        try {
            Files.createDirectories(clips);
            if (!Files.exists(indexFile)) return;
            for (String line : Files.readAllLines(indexFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                    IndexEntry e = new IndexEntry(o.get("id").getAsString(), o.get("file").getAsString(), o.get("suspect").getAsString(),
                        o.get("uuid").getAsString(), o.get("createdAtMs").getAsLong(), o.get("reasons").getAsString(), o.get("sources").getAsString(),
                        o.get("bytes").getAsLong(), o.get("headline").getAsString());
                    if (Files.exists(clips.resolve(e.file()))) index.add(e);
                } catch (RuntimeException ignored) { }
            }
            index.sort(Comparator.comparingLong(IndexEntry::createdAtMs));
        } catch (IOException ex) { log.log(Level.WARNING, "index load failed", ex); }
    }

    private static String json(IndexEntry e) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.id()); o.addProperty("file", e.file()); o.addProperty("suspect", e.suspect()); o.addProperty("uuid", e.uuid());
        o.addProperty("createdAtMs", e.createdAtMs()); o.addProperty("reasons", e.reasons()); o.addProperty("sources", e.sources());
        o.addProperty("bytes", e.bytes()); o.addProperty("headline", e.headline());
        return o.toString();
    }

    private void appendIndex(IndexEntry e) throws IOException {
        Files.writeString(indexFile, json(e) + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    /** Retention: drop clips older than retentionDays, then the oldest until under maxTotalMb. Runs on io. */
    void sweep() {
        try {
            long cutoff = System.currentTimeMillis() - s.retentionDays * 86_400_000L;
            List<IndexEntry> drop = new ArrayList<>();
            long total = 0;
            synchronized (index) {
                for (IndexEntry e : index) total += e.bytes();
                for (IndexEntry e : index) {
                    if (e.createdAtMs() < cutoff || total > s.maxTotalMb * 1024 * 1024) { drop.add(e); total -= e.bytes(); }
                }
                index.removeAll(drop);
            }
            for (IndexEntry e : drop) Files.deleteIfExists(file(e));
            // stray files (no index line) older than the retention also go; empty day folders are removed
            if (Files.isDirectory(clips)) try (DirectoryStream<Path> days = Files.newDirectoryStream(clips)) {
                for (Path d : days) {
                    if (!Files.isDirectory(d)) continue;
                    try (DirectoryStream<Path> fs = Files.newDirectoryStream(d)) {
                        for (Path f : fs) if (Files.getLastModifiedTime(f).toMillis() < cutoff) Files.deleteIfExists(f);
                    }
                    try (DirectoryStream<Path> fs = Files.newDirectoryStream(d)) { if (!fs.iterator().hasNext()) Files.deleteIfExists(d); }
                }
            }
            if (!drop.isEmpty()) {
                Path tmp = indexFile.resolveSibling("index.jsonl.tmp");
                StringBuilder sb = new StringBuilder();
                synchronized (index) { for (IndexEntry e : index) sb.append(json(e)).append('\n'); }
                Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
                Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                log.info("retention removed " + drop.size() + " clip(s)");
            }
        } catch (Throwable t) { log.log(Level.WARNING, "retention sweep failed", t); }
    }

    long totalBytes() { synchronized (index) { long b = 0; for (IndexEntry e : index) b += e.bytes(); return b; } }

    /** Save every pending clip now (without line of sight) and stop the io thread. */
    void shutdown() {
        shuttingDown = true;
        for (Pending p : pending.values()) {
            if (p.timer != null) p.timer.cancel(false);
            io.execute(() -> { Runnable f = p.force; if (f != null) f.run(); else finish(p, false); });
        }
        io.shutdown();
        try { if (!io.awaitTermination(20, TimeUnit.SECONDS)) log.warning("io thread did not finish in 20 s"); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
