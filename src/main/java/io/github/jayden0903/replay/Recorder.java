package io.github.jayden0903.replay;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-size ring buffers for one player: one frame per client tick (committed on CLIENT_TICK_END) plus a smaller
 * event ring (attacks, digs, hits, damage...). Every array is allocated once; recording never allocates.
 *
 * Threads: the "current" frame fields are only touched by the player's netty event loop. Commits, event appends and
 * copies are synchronized on this recorder (uncontended except while a clip is being copied, which takes microseconds).
 * Bukkit threads only append events, OR bits into {@link #pendingFlags} and set volatile fields.
 *
 * Pose/state and equipment (1.1.0) are sampled on the player's own entity thread (the same scheduler task that already
 * refreshed the hotbar): {@link #state} is a volatile word copied into every committed frame, and equipment is a small
 * ring of snapshots that is appended to only when something changed; each frame keeps the sequence of the snapshot that
 * was current (so a clip stores equipment changes only, never a copy per frame).
 */
final class Recorder {
    // ---- frame flag bits (also documented in the clip file "flagsLegend")
    static final int F_GROUND = 1, F_POS = 1 << 1, F_ROT = 1 << 2, F_SPRINT = 1 << 3, F_SNEAK = 1 << 4, F_SWING = 1 << 5,
        F_SWING_OFF = 1 << 6, F_ATTACK = 1 << 7, F_USE = 1 << 8, F_DIG = 1 << 9, F_FWD = 1 << 10, F_BACK = 1 << 11,
        F_LEFT = 1 << 12, F_RIGHT = 1 << 13, F_JUMP = 1 << 14, F_TELEPORT = 1 << 15, F_LEGACY = 1 << 16, F_DAMAGED = 1 << 17,
        F_VEHICLE = 1 << 18, F_GUI = 1 << 19, F_SPECTATOR = 1 << 20;
    static final int SLOT_SHIFT = 24;                 // bits 24..27: selected hotbar slot
    static final int TRANSIENT = F_POS | F_ROT | F_SWING | F_SWING_OFF | F_ATTACK | F_USE | F_DIG | F_TELEPORT | F_LEGACY | F_DAMAGED;
    static final String[] FLAG_NAMES = {"ground", "pos", "rot", "sprint", "sneak", "swing", "swingOff", "attack", "use", "dig",
        "fwd", "back", "left", "right", "jump", "serverTeleport", "noTickEnd", "damaged", "vehicle", "gui", "spectator"};

    // ---- per-frame state word (1.1.0, clip format 2): bits 0..3 = server pose code (POSE_NAMES), then flags.
    // Sampled every server tick on the player's entity thread from the Bukkit getters (isGliding, isSwimming, ...).
    static final int S_POSE_MASK = 15;
    static final int S_FALL_FLYING = 1 << 4, S_SWIMMING = 1 << 5, S_RIPTIDE = 1 << 6, S_SLEEPING = 1 << 7, S_CRAWLING = 1 << 8,
        S_USING = 1 << 9, S_USING_OFFHAND = 1 << 10, S_BLOCKING = 1 << 11, S_ABILITY_FLYING = 1 << 12, S_RIDING = 1 << 13,
        S_MAIN_GLINT = 1 << 14, S_IN_WATER = 1 << 15;
    static final String[] STATE_NAMES = {"fallFlying", "swimming", "riptide", "sleeping", "crawling", "usingItem", "usingOffhand",
        "blocking", "abilityFlying", "riding", "mainHandGlint", "inWater"};   // bit 4 upwards
    static final int P_STANDING = 0, P_FALL_FLYING = 1, P_SLEEPING = 2, P_SWIMMING = 3, P_SPIN_ATTACK = 4, P_CROUCHING = 5, P_DYING = 6, P_OTHER = 7;
    static final String[] POSE_NAMES = {"standing", "fall_flying", "sleeping", "swimming", "spin_attack", "crouching", "dying", "other"};

    // ---- equipment snapshot: head, chest, legs, feet, offhand (Names index | GLINT), then trims of head..feet (Names index)
    static final int EQ_SLOTS = 5, EQ_INTS = 9, EQ_CAP = 128;
    static final int GLINT = 1 << 30, ID_MASK = (1 << 24) - 1;

    // ---- event types
    static final byte E_ATTACK = 1, E_INTERACT = 2, E_DIG = 3, E_USE_ON_BLOCK = 4, E_BREAK = 5, E_PLACE = 6, E_DAMAGED = 7,
        E_HIT = 8, E_TELEPORT = 9, E_DEATH = 10, E_WORLD = 11, E_RESPAWN = 12;
    static final String[] EVENT_NAMES = {"?", "attack", "interact", "dig", "useOnBlock", "blockBreak", "blockPlace", "damaged",
        "hit", "serverTeleport", "death", "world", "respawn"};

    // ---- entity sample flags (1.2.0, clip format 3 "entityFlagsLegend")
    static final int N_FIRE = 1, N_BABY = 2, N_DEAD = 4, N_HURT = 8;
    static final String[] ENTITY_FLAG_NAMES = {"onFire", "baby", "dead", "hurt"};

    private static final AtomicInteger IDS = new AtomicInteger();

    final int rid = IDS.incrementAndGet();
    final UUID uuid;
    final String name;
    final long nanoBase = System.nanoTime();
    final long wallBase = System.currentTimeMillis();
    final int cap, ecap, ncap;
    volatile int entityId;
    volatile String client = "";
    volatile String skinValue, skinSignature;
    volatile int world;                                   // Names index of the current world
    volatile long quitNano;                               // 0 while online
    volatile boolean spectator, gui;                      // set from Bukkit events
    final int[] hotbar = new int[9];                      // Names index (| GLINT) of the hotbar items, refreshed by the entity task
    volatile int state;                                   // S_* bits, written by the entity task every tick
    int sampleTick, entityTick; final int[] eqScratch = new int[EQ_INTS];  // entity task only
    final AtomicInteger pendingFlags = new AtomicInteger(); // bits set from Bukkit threads for the next frame

    // ---- frames (ring)
    private final int[] t; private final double[] x, y, z; private final float[] yaw, pitch; private final int[] flags;
    private final int[] item; private final short[] worldIdx;
    private final int[] st, eqRef;                        // state word, equipment snapshot sequence (-1 = none yet)
    private long seq;                                     // frames committed so far
    // ---- equipment snapshots (ring, appended on change only)
    private final int[] eqRing = new int[EQ_CAP * EQ_INTS];
    private int eqSeq;                                    // snapshots appended so far
    private final int[] eqLast = new int[EQ_INTS];        // entity thread only: last sampled snapshot
    // ---- nearby entities (ring, 1.2.0): one row per entity per sample, written by the player's entity thread
    private final int[] nt, nid, ntype, ndata; private final float[] nx, ny, nz; private final byte[] nyaw, npitch, nflags; private final short[] nworld;
    private long nseq;
    long entitySamples, entityRows;
    // ---- events (ring)
    private final int[] et, eseqRel; private final byte[] etype; private final int[] ea, eb, ec, ed; private final double[] ex, ey, ez;
    private long eseq;

    // ---- current frame (netty thread only)
    double cx, cy, cz; float cyaw, cpitch; int cflags; boolean hasPos; int slot;
    long lastTickEndNano, lastCommitNano;
    // latest committed position, read by other threads (attack target positions, nearby checks)
    volatile double lx, ly, lz; volatile boolean lvalid;
    // statistics
    long frames, events, merged;
    private double tokens = 60.0;

    Recorder(UUID uuid, String name, int entityId, int frameCap, int eventCap) { this(uuid, name, entityId, frameCap, eventCap, 0); }

    Recorder(UUID uuid, String name, int entityId, int frameCap, int eventCap, int entityCap) {
        this.uuid = uuid; this.name = name; this.entityId = entityId; this.cap = frameCap; this.ecap = eventCap; this.ncap = entityCap;
        nt = new int[ncap]; nid = new int[ncap]; ntype = new int[ncap]; ndata = new int[ncap]; nx = new float[ncap]; ny = new float[ncap]; nz = new float[ncap];
        nyaw = new byte[ncap]; npitch = new byte[ncap]; nflags = new byte[ncap]; nworld = new short[ncap];
        t = new int[cap]; x = new double[cap]; y = new double[cap]; z = new double[cap]; yaw = new float[cap]; pitch = new float[cap];
        flags = new int[cap]; item = new int[cap]; worldIdx = new short[cap]; st = new int[cap]; eqRef = new int[cap];
        et = new int[ecap]; eseqRel = new int[ecap]; etype = new byte[ecap]; ea = new int[ecap]; eb = new int[ecap]; ec = new int[ecap];
        ed = new int[ecap]; ex = new double[ecap]; ey = new double[ecap]; ez = new double[ecap];
    }

    /** Bytes held by the ring arrays (object headers ignored). */
    static long bytesPerRecorder(int frameCap, int eventCap) { return bytesPerRecorder(frameCap, eventCap, 0); }

    static long bytesPerRecorder(int frameCap, int eventCap, int entityCap) {
        return (long) frameCap * (4 + 8 * 3 + 4 * 2 + 4 + 4 + 2 + 4 + 4) + (long) eventCap * (4 + 4 + 1 + 4 * 4 + 8 * 3) + EQ_CAP * EQ_INTS * 4L
            + (long) entityCap * (4 * 4 + 4 * 3 + 3 + 2);
    }

    /** Entity thread: append one sample (n rows from the scratch arrays) under one lock. */
    void entities(int tms, int n, int[] id, int[] type, int[] data, float[] px, float[] py, float[] pz, byte[] pyaw, byte[] ppitch, byte[] pflags) {
        if (ncap == 0) return;
        short w = (short) world;
        synchronized (this) {
            for (int k = 0; k < n; k++) {
                int i = (int) (nseq % ncap);
                nt[i] = tms; nid[i] = id[k]; ntype[i] = type[k]; ndata[i] = data[k]; nx[i] = px[k]; ny[i] = py[k]; nz[i] = pz[k];
                nyaw[i] = pyaw[k]; npitch[i] = ppitch[k]; nflags[i] = pflags[k]; nworld[i] = w;
                nseq++;
            }
            entitySamples++; entityRows += n;
        }
    }

    int msNow(long nano) { return (int) ((nano - nanoBase) / 1_000_000L); }

    // ------------------------------------------------------------------ netty-thread input

    void move(boolean pos, double px, double py, double pz, boolean rot, float pyaw, float ppitch, boolean ground) {
        if (pos) { cx = px; cy = py; cz = pz; hasPos = true; cflags |= F_POS; }
        if (rot) { cyaw = pyaw; cpitch = ppitch; cflags |= F_ROT; }
        cflags = ground ? cflags | F_GROUND : cflags & ~F_GROUND;
    }

    void flag(int bit) { cflags |= bit; }
    void setFlag(int bit, boolean on) { cflags = on ? cflags | bit : cflags & ~bit; }

    /** Commit the frame built since the last commit (client tick end, or each movement packet for clients without tick end). */
    synchronized void commit(long nano, boolean legacy) {
        if (!hasPos) return;                             // nothing to show before the first position packet
        int f = cflags | pendingFlags.getAndSet(0) | (legacy ? F_LEGACY : 0) | (spectator ? F_SPECTATOR : 0) | (gui ? F_GUI : 0);
        // Flood guard: frames faster than real time must not flush the history. Empty frames under 40 ms apart are
        // dropped; beyond a budget of 30 frames/s (burst 60, e.g. after a lag spike) a frame is merged into the previous one.
        long dtn = nano - lastCommitNano;
        tokens = Math.min(60.0, tokens + Math.max(0, dtn) * 30e-9);
        if (seq > 0 && (f & TRANSIENT) == 0 && dtn < 40_000_000L) { return; }
        lastCommitNano = nano;
        boolean merge = seq > 0 && tokens < 1.0;
        if (merge) { merged++; seq--; f |= flags[(int) (seq % cap)] & TRANSIENT; } else tokens -= 1.0;
        int i = (int) (seq % cap);
        t[i] = msNow(nano); x[i] = cx; y[i] = cy; z[i] = cz; yaw[i] = cyaw; pitch[i] = cpitch;
        int s = slot & 15;
        flags[i] = (f & ~(15 << SLOT_SHIFT)) | (s << SLOT_SHIFT);
        int h = s < 9 ? hotbar[s] : 0;
        item[i] = h & ID_MASK; worldIdx[i] = (short) world;
        st[i] = state | ((h & GLINT) != 0 ? S_MAIN_GLINT : 0); eqRef[i] = eqSeq - 1;
        seq++; if (!merge) frames++;
        cflags &= ~TRANSIENT;
        if (hasPos) { lx = cx; ly = cy; lz = cz; lvalid = true; }
    }

    /** Append an event to the frame currently being built. Any thread. */
    synchronized void event(byte type, int a, int b, int c, int d, double px, double py, double pz) {
        int i = (int) (eseq % ecap);
        et[i] = msNow(System.nanoTime()); eseqRel[i] = (int) seq; etype[i] = type; ea[i] = a; eb[i] = b; ec[i] = c; ed[i] = d;
        ex[i] = px; ey[i] = py; ez[i] = pz;
        eseq++; events++;
    }

    synchronized long frameSeq() { return seq; }

    /** Entity thread: record the sampled equipment (EQ_INTS values) if it differs from the last sample. */
    void equipment(int[] snap) {
        if (eqSeq > 0 && java.util.Arrays.equals(snap, 0, EQ_INTS, eqLast, 0, EQ_INTS)) return;
        System.arraycopy(snap, 0, eqLast, 0, EQ_INTS);
        synchronized (this) {
            System.arraycopy(snap, 0, eqRing, (eqSeq % EQ_CAP) * EQ_INTS, EQ_INTS);
            eqSeq++;
        }
    }

    // ------------------------------------------------------------------ copies (any thread)

    /** Copy of the frames with sequence >= fromSeq that are still in the ring, and of the events with sequence >= fromEventSeq. */
    synchronized Copy copy(long fromSeq, long fromEventSeq) { return copy(fromSeq, fromEventSeq, 0); }

    synchronized Copy copy(long fromSeq, long fromEventSeq, long fromEntitySeq) {
        long lo = Math.max(fromSeq, seq - cap);
        int n = (int) Math.max(0, seq - lo);
        Copy c = new Copy(n);
        c.firstSeq = lo; c.endSeq = seq;
        for (int k = 0; k < n; k++) {
            int i = (int) ((lo + k) % cap);
            c.t[k] = t[i]; c.x[k] = x[i]; c.y[k] = y[i]; c.z[k] = z[i]; c.yaw[k] = yaw[i]; c.pitch[k] = pitch[i];
            c.flags[k] = flags[i]; c.item[k] = item[i]; c.world[k] = worldIdx[i]; c.state[k] = st[i]; c.eqRef[k] = eqRef[i];
        }
        // the equipment snapshots the copied frames refer to (still in the ring); older ones fall back to the oldest kept
        int lo2 = Integer.MAX_VALUE, hi2 = -1;
        for (int k = 0; k < n; k++) { int r = c.eqRef[k]; if (r >= 0) { lo2 = Math.min(lo2, r); hi2 = Math.max(hi2, r); } }
        if (hi2 >= 0) {
            lo2 = Math.max(lo2, Math.max(0, eqSeq - EQ_CAP));
            if (hi2 >= lo2) {
                c.eqLo = lo2; c.eqData = new int[(hi2 - lo2 + 1) * EQ_INTS];
                for (int q = lo2; q <= hi2; q++) System.arraycopy(eqRing, (q % EQ_CAP) * EQ_INTS, c.eqData, (q - lo2) * EQ_INTS, EQ_INTS);
            }
        }
        long elo = Math.max(fromEventSeq, eseq - ecap);
        int m = (int) Math.max(0, eseq - elo);
        c.initEvents(m);
        c.endEventSeq = eseq;
        for (int k = 0; k < m; k++) {
            int i = (int) ((elo + k) % ecap);
            c.et[k] = et[i]; c.efseq[k] = eseqRel[i]; c.etype[k] = etype[i]; c.ea[k] = ea[i]; c.eb[k] = eb[i]; c.ec[k] = ec[i]; c.ed[k] = ed[i];
            c.ex[k] = ex[i]; c.ey[k] = ey[i]; c.ez[k] = ez[i];
        }
        long nlo = Math.max(fromEntitySeq, nseq - ncap);
        int q = (int) Math.max(0, nseq - nlo);
        c.initEntities(q);
        c.endEntitySeq = nseq;
        for (int k = 0; k < q; k++) {
            int i = (int) ((nlo + k) % ncap);
            c.nt[k] = nt[i]; c.nid[k] = nid[i]; c.ntype[k] = ntype[i]; c.ndata[k] = ndata[i]; c.nx[k] = nx[i]; c.ny[k] = ny[i]; c.nz[k] = nz[i];
            c.nyaw[k] = nyaw[i]; c.npitch[k] = npitch[i]; c.nflags[k] = nflags[i]; c.nworld[k] = nworld[i];
        }
        return c;
    }

    /** Plain arrays copied out of a recorder. Times are ms relative to the recorder's nanoBase. */
    static final class Copy {
        long firstSeq, endSeq, endEventSeq, endEntitySeq;
        int nq; int[] nt = new int[0], nid, ntype, ndata; float[] nx, ny, nz; byte[] nyaw, npitch, nflags; short[] nworld;
        final int n; final int[] t; final double[] x, y, z; final float[] yaw, pitch; final int[] flags, item, state, eqRef; final short[] world;
        int eqLo; int[] eqData = new int[0];             // snapshots eqLo .. eqLo + eqData.length / EQ_INTS - 1
        int m; int[] et, efseq, ea, eb, ec, ed; byte[] etype; double[] ex, ey, ez;
        Copy(int n) {
            this.n = n; t = new int[n]; x = new double[n]; y = new double[n]; z = new double[n]; yaw = new float[n]; pitch = new float[n];
            flags = new int[n]; item = new int[n]; world = new short[n]; state = new int[n]; eqRef = new int[n];
        }
        /** Equipment snapshot seq (EQ_INTS values written to out), false when the frame has none. */
        boolean snapshot(int seq, int[] out) {
            int cnt = eqData.length / EQ_INTS;
            if (seq < 0 || cnt == 0) return false;
            int q = Math.max(0, Math.min(cnt - 1, seq - eqLo));
            System.arraycopy(eqData, q * EQ_INTS, out, 0, EQ_INTS);
            return true;
        }
        void initEntities(int q) {
            nq = q; nt = new int[q]; nid = new int[q]; ntype = new int[q]; ndata = new int[q]; nx = new float[q]; ny = new float[q]; nz = new float[q];
            nyaw = new byte[q]; npitch = new byte[q]; nflags = new byte[q]; nworld = new short[q];
        }
        void initEvents(int m) {
            this.m = m; et = new int[m]; efseq = new int[m]; ea = new int[m]; eb = new int[m]; ec = new int[m]; ed = new int[m];
            etype = new byte[m]; ex = new double[m]; ey = new double[m]; ez = new double[m];
        }
    }
}
