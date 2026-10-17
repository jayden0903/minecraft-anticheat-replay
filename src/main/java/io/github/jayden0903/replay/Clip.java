package io.github.jayden0903.replay;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * One saved clip: the suspect's track plus the tracks of players who came near, all on one clip timeline
 * (ms from the first frame). Written as gzip JSON with column arrays (plugins/RewindReplay/clips/<date>/<id>.json.gz).
 *
 * Format 2 (1.1.0) adds per track a "state" column (pose code + pose/state bits, see stateLegend) and an "equip" list of
 * equipment changes [frame, head, chest, legs, feet, offhand, glintMask, trimHead, trimChest, trimLegs, trimFeet]
 * (item indices into "items", trim indices into "trims"). Format 3 (1.2.0) adds a top-level "entities" list (nearby mobs,
 * items, projectiles, vehicles: type, data, column arrays t/x/y/z/yaw/pitch/flags; see entityFlagsLegend).
 * Format 1 and 2 clips are still read; they simply have no state (null), no equipment changes or no entities.
 */
final class Clip {
    static final String FORMAT = "rewind-replay/3", FORMAT_V2 = "rewind-replay/2", FORMAT_V1 = "rewind-replay/1";
    /** One equipment change: frame, 5 item indices, glint mask (bit k = slot k), 4 trim indices. */
    static final int EQ_LEN = 11;
    static final String EQUIP_LEGEND = "[frame, head, chest, legs, feet, offhand, glintMask(bit0=head..bit4=offhand), trimHead, trimChest, trimLegs, trimFeet]; items -> items[], trims -> trims[] (\"pattern material\")";

    int format = 3;

    String id = "";
    long createdAtMs, startWallMs, markWallMs;
    int markOffsetMs, durationMs;
    String suspectName = "", suspectUuid = "";
    final List<Reason> reasons = new ArrayList<>();
    final List<String> worlds = new ArrayList<>(), items = new ArrayList<>(), trims = new ArrayList<>();
    final List<Track> tracks = new ArrayList<>();       // index 0 = suspect
    final List<Ent> entities = new ArrayList<>();       // format 3: nearby non-player entities
    JsonObject summary = new JsonObject();

    record Reason(long atMs, String source, String reason) { }

    static final class Track {
        String role = "nearby", name = "", uuid = "", client = "", skinValue, skinSignature;
        int n; int[] t; double[] x, y, z; float[] yaw, pitch; int[] flags, item, world;
        int[] state;                                    // format 2: Recorder.S_* per frame; null for format 1 clips
        final List<int[]> equip = new ArrayList<>();    // format 2: equipment changes (EQ_LEN ints each), frame ascending
        byte[] los;                                     // suspect only: 0 unknown, 1 visible, 2 hidden (best target per frame)
        final List<Ev> events = new ArrayList<>();

        void alloc(int n) {
            this.n = n; t = new int[n]; x = new double[n]; y = new double[n]; z = new double[n]; yaw = new float[n]; pitch = new float[n];
            flags = new int[n]; item = new int[n]; world = new int[n];
        }

        int state(int i) { return state == null || i < 0 || i >= state.length ? 0 : state[i]; }

        /** Index into {@link #equip} of the change in effect at frame i, or -1. */
        int equipAt(int i) {
            int lo = 0, hi = equip.size() - 1, r = -1;
            while (lo <= hi) { int mid = (lo + hi) >>> 1; if (equip.get(mid)[0] <= i) { r = mid; lo = mid + 1; } else hi = mid - 1; }
            return r;
        }

        /** Index of the last frame with t <= ms, or -1. */
        int indexAt(int ms) {
            int lo = 0, hi = n - 1, r = -1;
            while (lo <= hi) { int mid = (lo + hi) >>> 1; if (t[mid] <= ms) { r = mid; lo = mid + 1; } else hi = mid - 1; }
            return r;
        }
    }

    /** One recorded entity (format 3). Times on the clip timeline; yaw/pitch in 1/256 turns. */
    static final class Ent {
        int id; String type = "", data = ""; int world; int n; int[] t; float[] x, y, z; byte[] yaw, pitch, flags;
        void alloc(int n) { this.n = n; t = new int[n]; x = new float[n]; y = new float[n]; z = new float[n]; yaw = new byte[n]; pitch = new byte[n]; flags = new byte[n]; }
        /** Index of the last sample with t <= ms, or -1. */
        int indexAt(int ms) {
            int lo = 0, hi = n - 1, r = -1;
            while (lo <= hi) { int mid = (lo + hi) >>> 1; if (t[mid] <= ms) { r = mid; lo = mid + 1; } else hi = mid - 1; }
            return r;
        }
    }

    /** One event. Positions are NaN when unknown. */
    static final class Ev {
        int t, frame; String type = ""; int entityId = -1, targetTrack = -1; String target = "", info = "";
        int bx, by, bz; double x = Double.NaN, y = Double.NaN, z = Double.NaN, amount = Double.NaN;
    }

    // ------------------------------------------------------------------ write

    void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)), 1 << 16), StandardCharsets.UTF_8);
             JsonWriter j = new JsonWriter(w)) {
            j.beginObject();
            j.name("format").value(FORMAT);
            j.name("id").value(id);
            j.name("createdAtMs").value(createdAtMs);
            j.name("startWallMs").value(startWallMs);
            j.name("markWallMs").value(markWallMs);
            j.name("markOffsetMs").value(markOffsetMs);
            j.name("durationMs").value(durationMs);
            j.name("suspect").beginObject().name("name").value(suspectName).name("uuid").value(suspectUuid).endObject();
            j.name("reasons").beginArray();
            for (Reason r : reasons) j.beginObject().name("atMs").value(r.atMs()).name("source").value(r.source()).name("reason").value(r.reason()).endObject();
            j.endArray();
            j.name("worlds"); strings(j, worlds);
            j.name("items"); strings(j, items);
            j.name("trims"); strings(j, trims);
            j.name("flagsLegend").beginObject();
            for (int b = 0; b < Recorder.FLAG_NAMES.length; b++) j.name(Recorder.FLAG_NAMES[b]).value(1L << b);
            j.name("slotShift").value(Recorder.SLOT_SHIFT);
            j.endObject();
            j.name("stateLegend").beginObject();
            j.name("poseMask").value(Recorder.S_POSE_MASK);
            j.name("poses"); strings(j, List.of(Recorder.POSE_NAMES));
            for (int b = 0; b < Recorder.STATE_NAMES.length; b++) j.name(Recorder.STATE_NAMES[b]).value(1L << (b + 4));
            j.endObject();
            j.name("equipLegend").value(EQUIP_LEGEND);
            j.name("summary"); new Gson().toJson(summary, j);
            j.name("tracks").beginArray();
            for (Track tr : tracks) {
                j.beginObject();
                j.name("role").value(tr.role).name("name").value(tr.name).name("uuid").value(tr.uuid).name("client").value(tr.client);
                if (tr.skinValue != null) j.name("skin").beginObject().name("value").value(tr.skinValue).name("signature").value(tr.skinSignature).endObject();
                j.name("n").value(tr.n);
                j.name("t").beginArray(); for (int i = 0; i < tr.n; i++) j.value(tr.t[i]); j.endArray();
                j.name("x").beginArray(); for (int i = 0; i < tr.n; i++) j.value(tr.x[i]); j.endArray();
                j.name("y").beginArray(); for (int i = 0; i < tr.n; i++) j.value(tr.y[i]); j.endArray();
                j.name("z").beginArray(); for (int i = 0; i < tr.n; i++) j.value(tr.z[i]); j.endArray();
                j.name("yaw").beginArray(); for (int i = 0; i < tr.n; i++) j.value((Number) Float.valueOf(tr.yaw[i])); j.endArray();
                j.name("pitch").beginArray(); for (int i = 0; i < tr.n; i++) j.value((Number) Float.valueOf(tr.pitch[i])); j.endArray();
                j.name("flags").beginArray(); for (int i = 0; i < tr.n; i++) j.value(tr.flags[i]); j.endArray();
                j.name("item").beginArray(); for (int i = 0; i < tr.n; i++) j.value(tr.item[i]); j.endArray();
                j.name("world").beginArray(); for (int i = 0; i < tr.n; i++) j.value(tr.world[i]); j.endArray();
                if (tr.state != null) { j.name("state").beginArray(); for (int i = 0; i < tr.n; i++) j.value(tr.state[i]); j.endArray(); }
                j.name("equip").beginArray();
                for (int[] e : tr.equip) { j.beginArray(); for (int v : e) j.value(v); j.endArray(); }
                j.endArray();
                if (tr.los != null) { j.name("los").beginArray(); for (int i = 0; i < tr.n; i++) j.value(tr.los[i]); j.endArray(); }
                j.name("events").beginArray();
                for (Ev e : tr.events) {
                    j.beginObject().name("t").value(e.t).name("frame").value(e.frame).name("type").value(e.type);
                    if (e.entityId >= 0) j.name("entityId").value(e.entityId);
                    if (e.targetTrack >= 0) j.name("targetTrack").value(e.targetTrack);
                    if (!e.target.isEmpty()) j.name("target").value(e.target);
                    if (!e.info.isEmpty()) j.name("info").value(e.info);
                    if (e.type.equals("dig") || e.type.equals("useOnBlock") || e.type.equals("blockBreak") || e.type.equals("blockPlace"))
                        j.name("block").beginArray().value(e.bx).value(e.by).value(e.bz).endArray();
                    if (!Double.isNaN(e.x)) j.name("pos").beginArray().value(e.x).value(e.y).value(e.z).endArray();
                    if (!Double.isNaN(e.amount)) j.name("amount").value(e.amount);
                    j.endObject();
                }
                j.endArray();
                j.endObject();
            }
            j.endArray();
            j.name("entityFlagsLegend").beginObject();
            for (int b = 0; b < Recorder.ENTITY_FLAG_NAMES.length; b++) j.name(Recorder.ENTITY_FLAG_NAMES[b]).value(1L << b);
            j.endObject();
            j.name("entities").beginArray();
            for (Ent en : entities) {
                j.beginObject().name("id").value(en.id).name("type").value(en.type).name("data").value(en.data).name("world").value(en.world).name("n").value(en.n);
                j.name("t").beginArray(); for (int i = 0; i < en.n; i++) j.value(en.t[i]); j.endArray();
                j.name("x").beginArray(); for (int i = 0; i < en.n; i++) j.value(Math.round(en.x[i] * 100.0) / 100.0); j.endArray();
                j.name("y").beginArray(); for (int i = 0; i < en.n; i++) j.value(Math.round(en.y[i] * 100.0) / 100.0); j.endArray();
                j.name("z").beginArray(); for (int i = 0; i < en.n; i++) j.value(Math.round(en.z[i] * 100.0) / 100.0); j.endArray();
                j.name("yaw").beginArray(); for (int i = 0; i < en.n; i++) j.value(en.yaw[i]); j.endArray();
                j.name("pitch").beginArray(); for (int i = 0; i < en.n; i++) j.value(en.pitch[i]); j.endArray();
                j.name("flags").beginArray(); for (int i = 0; i < en.n; i++) j.value(en.flags[i]); j.endArray();
                j.endObject();
            }
            j.endArray();
            j.endObject();
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void strings(JsonWriter j, List<String> l) throws IOException {
        j.beginArray(); for (String s : l) j.value(s); j.endArray();
    }

    // ------------------------------------------------------------------ read

    static Clip read(Path file) throws IOException {
        JsonObject o;
        try (Reader r = new InputStreamReader(new GZIPInputStream(Files.newInputStream(file), 1 << 16), StandardCharsets.UTF_8)) {
            o = JsonParser.parseReader(r).getAsJsonObject();
        }
        String fmt = str(o, "format");
        if (!FORMAT.equals(fmt) && !FORMAT_V2.equals(fmt) && !FORMAT_V1.equals(fmt)) throw new IOException("unknown clip format: " + fmt);
        Clip c = new Clip();
        c.format = FORMAT.equals(fmt) ? 3 : FORMAT_V2.equals(fmt) ? 2 : 1;
        c.id = str(o, "id");
        c.createdAtMs = o.get("createdAtMs").getAsLong();
        c.startWallMs = o.get("startWallMs").getAsLong();
        c.markWallMs = o.get("markWallMs").getAsLong();
        c.markOffsetMs = o.get("markOffsetMs").getAsInt();
        c.durationMs = o.get("durationMs").getAsInt();
        JsonObject s = o.getAsJsonObject("suspect");
        c.suspectName = str(s, "name"); c.suspectUuid = str(s, "uuid");
        for (JsonElement e : o.getAsJsonArray("reasons")) {
            JsonObject r = e.getAsJsonObject();
            c.reasons.add(new Reason(r.get("atMs").getAsLong(), str(r, "source"), str(r, "reason")));
        }
        for (JsonElement e : o.getAsJsonArray("worlds")) c.worlds.add(e.getAsString());
        for (JsonElement e : o.getAsJsonArray("items")) c.items.add(e.getAsString());
        if (o.has("trims")) for (JsonElement e : o.getAsJsonArray("trims")) c.trims.add(e.getAsString());
        if (o.has("summary") && o.get("summary").isJsonObject()) c.summary = o.getAsJsonObject("summary");
        for (JsonElement te : o.getAsJsonArray("tracks")) {
            JsonObject to = te.getAsJsonObject();
            Track tr = new Track();
            tr.role = str(to, "role"); tr.name = str(to, "name"); tr.uuid = str(to, "uuid"); tr.client = str(to, "client");
            if (to.has("skin")) { JsonObject sk = to.getAsJsonObject("skin"); tr.skinValue = str(sk, "value"); tr.skinSignature = str(sk, "signature"); }
            int n = to.get("n").getAsInt();
            tr.alloc(n);
            JsonArray t = to.getAsJsonArray("t"), x = to.getAsJsonArray("x"), y = to.getAsJsonArray("y"), z = to.getAsJsonArray("z"),
                yaw = to.getAsJsonArray("yaw"), pitch = to.getAsJsonArray("pitch"), fl = to.getAsJsonArray("flags"),
                it = to.getAsJsonArray("item"), w = to.getAsJsonArray("world");
            for (int i = 0; i < n; i++) {
                tr.t[i] = t.get(i).getAsInt(); tr.x[i] = x.get(i).getAsDouble(); tr.y[i] = y.get(i).getAsDouble(); tr.z[i] = z.get(i).getAsDouble();
                tr.yaw[i] = yaw.get(i).getAsFloat(); tr.pitch[i] = pitch.get(i).getAsFloat(); tr.flags[i] = fl.get(i).getAsInt();
                tr.item[i] = it.get(i).getAsInt(); tr.world[i] = w.get(i).getAsInt();
            }
            if (to.has("state")) { JsonArray sa = to.getAsJsonArray("state"); tr.state = new int[n]; for (int i = 0; i < n && i < sa.size(); i++) tr.state[i] = sa.get(i).getAsInt(); }
            if (to.has("equip")) for (JsonElement ee : to.getAsJsonArray("equip")) {
                JsonArray ea = ee.getAsJsonArray(); int[] v = new int[EQ_LEN];
                for (int k = 0; k < EQ_LEN && k < ea.size(); k++) v[k] = ea.get(k).getAsInt();
                tr.equip.add(v);
            }
            if (to.has("los")) { JsonArray l = to.getAsJsonArray("los"); tr.los = new byte[n]; for (int i = 0; i < n && i < l.size(); i++) tr.los[i] = l.get(i).getAsByte(); }
            for (JsonElement ee : to.getAsJsonArray("events")) {
                JsonObject eo = ee.getAsJsonObject();
                Ev e = new Ev();
                e.t = eo.get("t").getAsInt(); e.frame = eo.get("frame").getAsInt(); e.type = str(eo, "type");
                if (eo.has("entityId")) e.entityId = eo.get("entityId").getAsInt();
                if (eo.has("targetTrack")) e.targetTrack = eo.get("targetTrack").getAsInt();
                e.target = str(eo, "target"); e.info = str(eo, "info");
                if (eo.has("block")) { JsonArray b = eo.getAsJsonArray("block"); e.bx = b.get(0).getAsInt(); e.by = b.get(1).getAsInt(); e.bz = b.get(2).getAsInt(); }
                if (eo.has("pos")) { JsonArray p = eo.getAsJsonArray("pos"); e.x = p.get(0).getAsDouble(); e.y = p.get(1).getAsDouble(); e.z = p.get(2).getAsDouble(); }
                if (eo.has("amount")) e.amount = eo.get("amount").getAsDouble();
                tr.events.add(e);
            }
            c.tracks.add(tr);
        }
        if (o.has("entities")) for (JsonElement ee : o.getAsJsonArray("entities")) {
            JsonObject eo = ee.getAsJsonObject();
            Ent en = new Ent();
            en.id = eo.get("id").getAsInt(); en.type = str(eo, "type"); en.data = str(eo, "data"); en.world = eo.get("world").getAsInt();
            int n = eo.get("n").getAsInt();
            en.alloc(n);
            JsonArray t = eo.getAsJsonArray("t"), x = eo.getAsJsonArray("x"), y = eo.getAsJsonArray("y"), z = eo.getAsJsonArray("z"),
                yw = eo.getAsJsonArray("yaw"), pt = eo.getAsJsonArray("pitch"), fl = eo.getAsJsonArray("flags");
            for (int i = 0; i < n; i++) {
                en.t[i] = t.get(i).getAsInt(); en.x[i] = x.get(i).getAsFloat(); en.y[i] = y.get(i).getAsFloat(); en.z[i] = z.get(i).getAsFloat();
                en.yaw[i] = yw.get(i).getAsByte(); en.pitch[i] = pt.get(i).getAsByte(); en.flags[i] = fl.get(i).getAsByte();
            }
            c.entities.add(en);
        }
        return c;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }
}
