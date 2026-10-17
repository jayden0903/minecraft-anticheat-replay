package io.github.jayden0903.replay;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemSwingAnimation;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSwingAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemArmorTrim;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-game replay for one staff viewer. Everything the viewer sees is packet-only: fake players (tab-unlisted),
 * text labels and a block-display look ray, sent to this viewer's connection alone. No server entity exists and the
 * world is not touched. All methods run on the viewer's region thread (entity scheduler); commands only enqueue actions.
 *
 * 1.2.0: the past world too. Recorded mobs/items/projectiles/vehicles are packet-only fake entities; blocks are shown as they
 * were (BlockTimeline from CoreProtect: the state at the clip start, then every change at its time), re-sent when the viewer's
 * client loads chunks, and today's blocks are sent back when the replay ends. Today's mobs and players are hidden from the
 * viewer while watching (spawn packets are dropped by the plugin; the ones already visible are destroyed) and shown again at the end.
 */
final class ReplaySession {
    static final AtomicInteger NEXT_ID = new AtomicInteger(0x7A000000);   // far above vanilla entity ids
    private static final AtomicInteger SESSIONS = new AtomicInteger();
    static final TextColor ACCENT = TextColor.color(0xA88770), SOFT = TextColor.color(0xD8CBB8);

    final RewindReplay plugin; final Player viewer; final User user; final Clip clip; final Analyzer an;
    final BlockTimeline blocks;                      // null: no CoreProtect / disabled / failed (blocks = today's world)
    final String blockNote;
    final java.util.Set<Integer> hiddenLive = java.util.concurrent.ConcurrentHashMap.newKeySet();   // today's entities hidden from the viewer
    volatile boolean hiding;
    final RewindReplay.SavedState saved;
    final int sid = SESSIONS.incrementAndGet();
    final ConcurrentLinkedQueue<String> actions = new ConcurrentLinkedQueue<>();
    final Fake[] fakes;
    final int beamId = NEXT_ID.incrementAndGet();
    final String teamSuspect = "rr" + sid + "s", teamOther = "rr" + sid + "o";
    ScheduledTask task; BossBar bar, eventBar; boolean eventShown;
    double cursor; double speed = 1; boolean paused, pov, ended, beamSpawned;
    int prevCursor = -1, tick, flashTicks, lastBeamBlock = -1, lastSuspectIdx = -1;
    int resyncUntil;   // after a seek: re-send positions a few times, so fakes moved into chunks the viewer had not loaded yet appear
    String flashText = ""; int flashUntilTick;
    long tickNanos, maxTickNanos, firstTickNanos; int measuredTicks;   // viewer region-thread cost (first tick kept apart)

    /** One replayed player: fake player + riding text label. */
    static final class Fake {
        final int eid = NEXT_ID.incrementAndGet(), labelId = NEXT_ID.incrementAndGet();
        final UUID uuid = UUID.randomUUID();
        final String profileName;
        boolean spawned; int lastIdx = -1, lastVisual = -1, lastItem = -1, lastEq = -2, evPtr; boolean glow, moving;
        Fake(String profileName) { this.profileName = profileName; }
    }

    // recorded entities (format 3)
    final int[] entEid; final UUID[] entUuid; final boolean[] entSpawned, entDeadShown; final int[] entLast, entLastFlags;
    final EntityType[] entType;
    // blocks: state currently shown per timeline position, next change to apply
    int[] shown; int chPtr; boolean blocksSent; int blockResyncAt = -1, blockResyncAt2 = -1; double lastResyncX = Double.NaN, lastResyncZ;
    int blocksSentTotal;

    ReplaySession(RewindReplay plugin, Player viewer, Clip clip, Analyzer an, RewindReplay.SavedState saved) { this(plugin, viewer, clip, an, saved, null, ""); }

    ReplaySession(RewindReplay plugin, Player viewer, Clip clip, Analyzer an, RewindReplay.SavedState saved, BlockTimeline blocks, String blockNote) {
        this.plugin = plugin; this.viewer = viewer; this.clip = clip; this.an = an; this.saved = saved;
        this.blocks = blocks == null || blocks.size() == 0 ? null : blocks; this.blockNote = blockNote == null ? "" : blockNote;
        int ne = clip.entities.size();
        entEid = new int[ne]; entUuid = new UUID[ne]; entSpawned = new boolean[ne]; entDeadShown = new boolean[ne]; entLast = new int[ne]; entLastFlags = new int[ne];
        entType = new EntityType[ne];
        for (int i = 0; i < ne; i++) { entEid[i] = NEXT_ID.incrementAndGet(); entUuid[i] = UUID.randomUUID(); entLast[i] = -1; entType[i] = peType(clip.entities.get(i).type); }
        if (this.blocks != null) shown = this.blocks.initial.clone();
        this.user = PacketEvents.getAPI().getPlayerManager().getUser(viewer);
        fakes = new Fake[clip.tracks.size()];
        for (int i = 0; i < fakes.length; i++) fakes[i] = new Fake("rr" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000000, 0x7FFFFFFF)));
    }

    // ------------------------------------------------------------------ lifecycle (viewer region thread)

    void begin() {
        send(new WrapperPlayServerTeams(teamSuspect, WrapperPlayServerTeams.TeamMode.CREATE, new WrapperPlayServerTeams.ScoreBoardTeamInfo(
            Component.text("replay"), Component.empty(), Component.empty(), WrapperPlayServerTeams.NameTagVisibility.NEVER,
            WrapperPlayServerTeams.CollisionRule.NEVER, NamedTextColor.RED, WrapperPlayServerTeams.OptionData.NONE), fakes[0].profileName));
        List<String> others = new ArrayList<>();
        for (int i = 1; i < fakes.length; i++) others.add(fakes[i].profileName);
        send(new WrapperPlayServerTeams(teamOther, WrapperPlayServerTeams.TeamMode.CREATE, new WrapperPlayServerTeams.ScoreBoardTeamInfo(
            Component.text("replay"), Component.empty(), Component.empty(), WrapperPlayServerTeams.NameTagVisibility.NEVER,
            WrapperPlayServerTeams.CollisionRule.NEVER, NamedTextColor.GOLD, WrapperPlayServerTeams.OptionData.NONE), others));
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> infos = new ArrayList<>();
        for (int i = 0; i < fakes.length; i++) {
            Clip.Track tr = clip.tracks.get(i);
            List<TextureProperty> props = new ArrayList<>();
            if (tr.skinValue != null && !tr.skinValue.isEmpty()) props.add(new TextureProperty("textures", tr.skinValue, tr.skinSignature == null || tr.skinSignature.isEmpty() ? null : tr.skinSignature));
            infos.add(new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(new UserProfile(fakes[i].uuid, fakes[i].profileName, props), false, 0, GameMode.SURVIVAL, null, null));
        }
        send(new WrapperPlayServerPlayerInfoUpdate(EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER, WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED), infos));
        bar = BossBar.bossBar(Component.text("리플레이"), 0f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_20);
        viewer.showBossBar(bar);
        eventBar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
        cursor = 0;
        seekPointers();
        if (plugin.settings().hideLiveEntities) hideLive();
        task = viewer.getScheduler().runAtFixedRate(plugin, t -> { long t0 = System.nanoTime(); try { tick(); } catch (Throwable ex) { plugin.getLogger().warning("replay tick failed: " + ex); end(true, "오류로 리플레이를 종료했습니다: " + ex.getClass().getSimpleName()); }
            long dt = System.nanoTime() - t0; if (firstTickNanos == 0) firstTickNanos = dt; else { tickNanos += dt; measuredTicks++; if (dt > maxTickNanos) maxTickNanos = dt; } }, () -> end(false, null), 1L, 1L);
        viewer.sendMessage(header());
        viewer.sendMessage(controls());
    }

    /** Stop: remove every fake, restore the viewer. restore=false when the viewer is gone (quit/disable). */
    void end(boolean restore, String msg) {
        if (ended) return;
        ended = true;
        if (task != null) task.cancel();
        cleanupPackets();
        hiding = false;
        if (restore) restoreBlocks();
        plugin.sessionEnded(this, restore, msg);
    }

    /** Destroy all fake entities for the viewer. Safe from any thread (packets only). */
    void cleanupPackets() {
        try {
            if (pov) send(new WrapperPlayServerCamera(viewer.getEntityId()));
            List<Integer> ids = new ArrayList<>();
            for (Fake f : fakes) if (f.spawned) { ids.add(f.eid); ids.add(f.labelId); f.spawned = false; }
            for (int i = 0; i < entEid.length; i++) if (entSpawned[i]) { ids.add(entEid[i]); entSpawned[i] = false; }
            if (beamSpawned) { ids.add(beamId); beamSpawned = false; }
            if (!ids.isEmpty()) send(new WrapperPlayServerDestroyEntities(ids.stream().mapToInt(Integer::intValue).toArray()));
            List<UUID> uuids = new ArrayList<>(); for (Fake f : fakes) uuids.add(f.uuid);
            send(new WrapperPlayServerPlayerInfoRemove(uuids));
            send(new WrapperPlayServerTeams(teamSuspect, WrapperPlayServerTeams.TeamMode.REMOVE, (WrapperPlayServerTeams.ScoreBoardTeamInfo) null, List.<String>of()));
            send(new WrapperPlayServerTeams(teamOther, WrapperPlayServerTeams.TeamMode.REMOVE, (WrapperPlayServerTeams.ScoreBoardTeamInfo) null, List.<String>of()));
        } catch (Throwable ignored) { }
        try { if (bar != null) viewer.hideBossBar(bar); if (eventBar != null) viewer.hideBossBar(eventBar); } catch (Throwable ignored) { }
    }

    private boolean batching;
    private void send(PacketWrapper<?> w) { if (user == null) return; if (batching) user.writePacketSilently(w); else user.sendPacketSilently(w); }
    private int spawnsThisTick;
    static final int MAX_SPAWNS_PER_TICK = 6;

    // ------------------------------------------------------------------ playback

    void tick() {
        batching = true; spawnsThisTick = 0;
        try { tick0(); } finally { batching = false; if (user != null) user.flushPackets(); }
    }

    private void tick0() {
        tick++;
        for (String a; (a = actions.poll()) != null; ) action(a);
        if (!viewer.isOnline() || ended) return;
        int dur = clip.durationMs;
        if (!paused) {
            cursor += 50 * speed;
            if (cursor >= dur) { cursor = dur; paused = true; viewer.sendMessage(Component.text("[리플레이] 끝까지 재생했습니다. ", SOFT).append(button("처음부터", "restart", "처음부터 다시 재생"))); }
        }
        int cur = (int) cursor;
        boolean jump = prevCursor < 0 || cur < prevCursor || cur - prevCursor > 1000;
        String viewerWorld = viewer.getWorld().getName();
        for (int i = 0; i < fakes.length; i++) updateTrack(i, cur, jump, viewerWorld);
        updateEntities(cur, viewerWorld);
        updateBlocks(cur, jump, viewerWorld);
        updateBeam(cur, viewerWorld);
        prevCursor = cur;
        if (tick % 2 == 0) actionBar(cur);
        if (tick % 5 == 0) bossBar(cur);
        labels(cur);
        if (tick % 20 == 0) follow(cur);
    }

    private void updateTrack(int i, int cur, boolean jump, String viewerWorld) {
        Clip.Track tr = clip.tracks.get(i); Fake f = fakes[i];
        int idx = tr.indexAt(cur);
        boolean visible = idx >= 0 && cur - tr.t[idx] <= 1000 && clip.worlds.get(tr.world[idx]).equals(viewerWorld)
            && (i == 0 || (tr.flags[idx] & Recorder.F_SPECTATOR) == 0);
        if (!visible && i == 0 && pov && f.spawned) visible = true;   // keep the camera entity while in first person
        if (!visible) {
            if (f.spawned) { send(new WrapperPlayServerDestroyEntities(f.eid, f.labelId)); f.spawned = false; f.lastIdx = -1; }
            return;
        }
        if (idx < 0) return;
        if (!f.spawned) { if (spawnsThisTick >= MAX_SPAWNS_PER_TICK) return; spawnsThisTick++; spawn(i, idx); f.lastIdx = idx; }
        else if (idx == f.lastIdx && tick <= resyncUntil && tick % 10 == 0) {
            send(new WrapperPlayServerEntityTeleport(f.eid, new Vector3d(tr.x[idx], tr.y[idx], tr.z[idx]), tr.yaw[idx], tr.pitch[idx], (tr.flags[idx] & Recorder.F_GROUND) != 0));
            send(new WrapperPlayServerEntityHeadLook(f.eid, tr.yaw[idx]));
            send(new WrapperPlayServerSetPassengers(f.eid, new int[] {f.labelId}));
            if (i == 0) lastSuspectIdx = -2;   // the look ray re-sends its position too
        }
        else if (idx != f.lastIdx) {
            send(new WrapperPlayServerEntityTeleport(f.eid, new Vector3d(tr.x[idx], tr.y[idx], tr.z[idx]), tr.yaw[idx], tr.pitch[idx], (tr.flags[idx] & Recorder.F_GROUND) != 0));
            send(new WrapperPlayServerEntityHeadLook(f.eid, tr.yaw[idx]));
            // gliding / riptide: the client rolls and tilts the body along the entity's motion, so send it like vanilla does
            boolean fly = (tr.state(idx) & (Recorder.S_FALL_FLYING | Recorder.S_RIPTIDE)) != 0;
            if (fly && !jump && f.lastIdx >= 0 && idx > f.lastIdx && tr.t[idx] > tr.t[f.lastIdx]) {
                double ticks = (tr.t[idx] - tr.t[f.lastIdx]) / 50.0;
                send(new WrapperPlayServerEntityVelocity(f.eid, new Vector3d((tr.x[idx] - tr.x[f.lastIdx]) / ticks, (tr.y[idx] - tr.y[f.lastIdx]) / ticks, (tr.z[idx] - tr.z[f.lastIdx]) / ticks)));
                f.moving = true;
            } else if (f.moving && !fly) { send(new WrapperPlayServerEntityVelocity(f.eid, new Vector3d(0, 0, 0))); f.moving = false; }
            if (!jump && f.lastIdx >= 0 && idx > f.lastIdx) {
                boolean swung = false;
                for (int k = f.lastIdx + 1; k <= idx && k < tr.n; k++) if ((tr.flags[k] & (Recorder.F_SWING | Recorder.F_ATTACK)) != 0) swung = true;
                if (swung) send(Swing.mainHand(f.eid));
            }
            f.lastIdx = idx;
        }
        visual(i, idx);
        // events in (prevCursor, cur]
        if (jump) { f.evPtr = firstEventAfter(tr, cur); return; }
        while (f.evPtr < tr.events.size() && tr.events.get(f.evPtr).t <= cur) { effect(i, tr.events.get(f.evPtr)); f.evPtr++; }
    }

    private void spawn(int i, int idx) {
        Clip.Track tr = clip.tracks.get(i); Fake f = fakes[i];
        send(new WrapperPlayServerSpawnEntity(f.eid, Optional.of(f.uuid), EntityTypes.PLAYER, new Vector3d(tr.x[idx], tr.y[idx], tr.z[idx]),
            tr.pitch[idx], tr.yaw[idx], tr.yaw[idx], 0, Optional.empty()));
        List<EntityData<?>> md = new ArrayList<>();
        md.add(new EntityData<>(16, EntityDataTypes.BYTE, (byte) 0x7F));       // all skin layers
        send(new WrapperPlayServerEntityMetadata(f.eid, md));
        send(new WrapperPlayServerSpawnEntity(f.labelId, Optional.of(UUID.randomUUID()), EntityTypes.TEXT_DISPLAY,
            new Vector3d(tr.x[idx], tr.y[idx] + 2.0, tr.z[idx]), 0f, 0f, 0f, 0, Optional.empty()));
        List<EntityData<?>> lm = new ArrayList<>();
        lm.add(new EntityData<>(11, EntityDataTypes.VECTOR3F, new Vector3f(0f, 0.25f, 0f)));   // translation
        lm.add(new EntityData<>(15, EntityDataTypes.BYTE, (byte) 3));                          // billboard centre
        lm.add(new EntityData<>(23, EntityDataTypes.ADV_COMPONENT, label(i, idx, (int) cursor)));
        lm.add(new EntityData<>(24, EntityDataTypes.INT, 240));
        lm.add(new EntityData<>(25, EntityDataTypes.INT, 0x60000000));                       // background
        lm.add(new EntityData<>(27, EntityDataTypes.BYTE, (byte) 0x03));                      // shadow + see through
        send(new WrapperPlayServerEntityMetadata(f.labelId, lm));
        send(new WrapperPlayServerSetPassengers(f.eid, new int[] {f.labelId}));
        f.spawned = true; f.lastVisual = -1; f.lastItem = -1; f.lastEq = -2; f.moving = false;
        if (i == 0 && pov) send(new WrapperPlayServerCamera(f.eid));
    }

    // Entity metadata indices of the 26.2 server protocol (ViaVersion rewrites them for older viewers). Checked against
    // the 26.2 server classes (javap): Entity 0 shared flags (0x02 shift, 0x08 sprint, 0x10 swimming, 0x40 glowing,
    // 0x80 fall flying), 6 pose; LivingEntity 8 living flags (0x01 using item, 0x02 off hand, 0x04 spin attack);
    // Avatar 16 skin layers.
    static final int MD_SHARED = 0, MD_POSE = 6, MD_LIVING = 8, MD_SKIN = 16;
    private static final EntityPose[] POSES = {EntityPose.STANDING, EntityPose.FALL_FLYING, EntityPose.SLEEPING, EntityPose.SWIMMING,
        EntityPose.SPIN_ATTACK, EntityPose.CROUCHING, EntityPose.DYING, EntityPose.STANDING};

    /** Equipment (all slots, when anything changed), then pose/flags metadata: sneak, sprint, swimming, elytra glide,
     *  riptide, crawl, sleep, item use/blocking, glow (the suspect's crosshair is on this player). Clips from before 1.1.0
     *  have no state/equipment: only the held item, sneak and sprint, as before. */
    private void visual(int i, int idx) {
        Clip.Track tr = clip.tracks.get(i); Fake f = fakes[i];
        int st = tr.state(idx);
        // equipment first: a "using item" flag makes the client use whatever is in that hand at that moment
        int item = tr.item[idx] | ((st & Recorder.S_MAIN_GLINT) != 0 ? Recorder.GLINT : 0);
        int eq = tr.equipAt(idx);
        if (item != f.lastItem || eq != f.lastEq) {
            f.lastItem = item; f.lastEq = eq;
            int[] e = eq >= 0 ? tr.equip.get(eq) : null;
            List<Equipment> l = new ArrayList<>(6);
            l.add(new Equipment(EquipmentSlot.MAIN_HAND, peItem(tr.item[idx], (st & Recorder.S_MAIN_GLINT) != 0, 0)));
            EquipmentSlot[] slots = {EquipmentSlot.HELMET, EquipmentSlot.CHEST_PLATE, EquipmentSlot.LEGGINGS, EquipmentSlot.BOOTS, EquipmentSlot.OFF_HAND};
            for (int k = 0; k < 5; k++)
                l.add(new Equipment(slots[k], e == null ? peItem(-1, false, 0) : peItem(e[1 + k], (e[6] & (1 << k)) != 0, k < 4 ? e[7 + k] : 0)));
            try { send(new WrapperPlayServerEntityEquipment(f.eid, l)); } catch (Throwable ignored) { }
        }
        boolean sneak = (tr.flags[idx] & Recorder.F_SNEAK) != 0, sprint = (tr.flags[idx] & Recorder.F_SPRINT) != 0;
        boolean glow = false;
        if (i > 0) { int si = suspectIdx(); glow = si >= 0 && an.best[si] == i && an.onBox[si]; }
        EntityPose pose;
        int shared = (sneak ? 0x02 : 0) | (sprint ? 0x08 : 0) | (glow ? 0x40 : 0), living = 0;
        if (tr.state == null) pose = sneak ? EntityPose.CROUCHING : EntityPose.STANDING;
        else {
            pose = POSES[st & 7];
            if ((st & Recorder.S_SWIMMING) != 0) shared |= 0x10;
            if ((st & Recorder.S_FALL_FLYING) != 0) { shared |= 0x80; pose = EntityPose.FALL_FLYING; }
            if ((st & Recorder.S_SLEEPING) != 0) pose = EntityPose.SLEEPING;
            if ((st & Recorder.S_USING) != 0) living |= 0x01 | ((st & Recorder.S_USING_OFFHAND) != 0 ? 0x02 : 0);
            if ((st & Recorder.S_RIPTIDE) != 0) living |= 0x04;
        }
        int v = shared | pose.ordinal() << 8 | living << 16;
        if (v != f.lastVisual) {
            List<EntityData<?>> md = new ArrayList<>();
            md.add(new EntityData<>(MD_SHARED, EntityDataTypes.BYTE, (byte) shared));
            md.add(new EntityData<>(MD_POSE, EntityDataTypes.ENTITY_POSE, pose));
            md.add(new EntityData<>(MD_LIVING, EntityDataTypes.BYTE, (byte) living));
            send(new WrapperPlayServerEntityMetadata(f.eid, md));
            f.lastVisual = v;
        }
    }

    /** PacketEvents item for a clip item index (+ glint, + trim index), cached per session (conversion is not free). */
    private final java.util.Map<Long, com.github.retrooper.packetevents.protocol.item.ItemStack> itemCache = new java.util.HashMap<>();
    private com.github.retrooper.packetevents.protocol.item.ItemStack peItem(int item, boolean glint, int trim) {
        long key = ((long) item << 32) ^ ((long) trim << 1) ^ (glint ? 1 : 0);
        com.github.retrooper.packetevents.protocol.item.ItemStack c = itemCache.get(key);
        if (c == null) {
            c = SpigotConversionUtil.fromBukkitItemStack(bukkitItem(clip, item, glint, trim));
            itemCache.put(key, c);
        }
        return c;
    }

    static ItemStack bukkitItem(Clip clip, int item, boolean glint, int trim) {
        Material m = item >= 0 && item < clip.items.size() && !clip.items.get(item).isEmpty() ? Material.matchMaterial(clip.items.get(item)) : null;
        if (m == null || !m.isItem() || m.isAir()) return new ItemStack(Material.AIR);
        ItemStack st = new ItemStack(m);
        try {
            if (glint) st.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
            if (trim > 0 && trim < clip.trims.size()) {
                String[] pm = clip.trims.get(trim).split(" ");
                NamespacedKey pk = pm.length == 2 ? NamespacedKey.fromString(pm[0]) : null, mk = pm.length == 2 ? NamespacedKey.fromString(pm[1]) : null;
                TrimPattern pat = pk == null ? null : RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_PATTERN).get(pk);
                TrimMaterial mat = mk == null ? null : RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_MATERIAL).get(mk);
                if (pat != null && mat != null) st.setData(DataComponentTypes.TRIM, ItemArmorTrim.itemArmorTrim(new ArmorTrim(mat, pat)));
            }
        } catch (RuntimeException ignored) { }   // cosmetic only
        return st;
    }

    int suspectIdx() {
        Clip.Track s = clip.tracks.get(0);
        int idx = s.indexAt((int) cursor);
        return idx >= 0 && cursor - s.t[idx] <= 1000 ? idx : -1;
    }

    /** Exact look ray from the suspect's eye (float yaw/pitch as received). Red = on a player's hitbox, white = attack. */
    private void updateBeam(int cur, String viewerWorld) {
        Clip.Track s = clip.tracks.get(0);
        int idx = suspectIdx();
        if (idx < 0 || !clip.worlds.get(s.world[idx]).equals(viewerWorld)) {
            if (beamSpawned) { send(new WrapperPlayServerDestroyEntities(beamId)); beamSpawned = false; }
            return;
        }
        boolean attack = false;
        if (idx != lastSuspectIdx && lastSuspectIdx >= 0 && idx > lastSuspectIdx && idx - lastSuspectIdx < 40)
            for (int k = lastSuspectIdx + 1; k <= idx; k++) if ((s.flags[k] & Recorder.F_ATTACK) != 0) attack = true;
        if (attack) flashTicks = 4;
        boolean on = an.onBox[idx];
        Material m = flashTicks > 0 ? Material.WHITE_CONCRETE : on ? (an.los[idx] == 2 ? Material.MAGENTA_CONCRETE : Material.RED_CONCRETE) : Material.YELLOW_CONCRETE;
        if (flashTicks > 0) flashTicks--;
        double ex = s.x[idx], ey = s.y[idx] + Analyzer.eye(s.flags[idx], s.state(idx)), ez = s.z[idx];
        float len = on ? Math.max(0.3f, an.dist[idx]) : 6f, w = 0.035f;
        if (!beamSpawned) {
            send(new WrapperPlayServerSpawnEntity(beamId, Optional.of(UUID.randomUUID()), EntityTypes.BLOCK_DISPLAY, new Vector3d(ex, ey, ez), 0f, 0f, 0f, 0, Optional.empty()));
            beamSpawned = true; lastBeamBlock = -1; lastSuspectIdx = -1;
        } else if (idx != lastSuspectIdx) {
            send(new WrapperPlayServerEntityTeleport(beamId, new Vector3d(ex, ey, ez), 0f, 0f, false));
        }
        if (idx != lastSuspectIdx || lastBeamBlock != m.ordinal()) {
            float[] q = quat(s.yaw[idx], s.pitch[idx]);
            float[] off = rotate(q, -w / 2, -w / 2, 0);
            List<EntityData<?>> md = new ArrayList<>();
            md.add(new EntityData<>(8, EntityDataTypes.INT, 0));
            md.add(new EntityData<>(9, EntityDataTypes.INT, 0));
            md.add(new EntityData<>(10, EntityDataTypes.INT, 0));
            md.add(new EntityData<>(11, EntityDataTypes.VECTOR3F, new Vector3f(off[0], off[1], off[2])));
            md.add(new EntityData<>(12, EntityDataTypes.VECTOR3F, new Vector3f(w, w, len)));
            md.add(new EntityData<>(13, EntityDataTypes.QUATERNION, new Quaternion4f(q[0], q[1], q[2], q[3])));
            md.add(new EntityData<>(16, EntityDataTypes.INT, (15 << 4) | (15 << 20)));
            md.add(new EntityData<>(17, EntityDataTypes.FLOAT, 2f));
            if (lastBeamBlock != m.ordinal()) {
                md.add(new EntityData<>(23, EntityDataTypes.BLOCK_STATE, stateId(m)));
                lastBeamBlock = m.ordinal();
            }
            send(new WrapperPlayServerEntityMetadata(beamId, md));
        }
        lastSuspectIdx = idx;
    }

    private static final java.util.Map<Material, Integer> STATE_IDS = new java.util.concurrent.ConcurrentHashMap<>();
    static int stateId(Material m) { return STATE_IDS.computeIfAbsent(m, k -> SpigotConversionUtil.fromBukkitBlockData(k.createBlockData()).getGlobalId()); }

    /** Load PacketEvents' block/item mappings off the region threads (the first conversion takes ~0.3 s). */
    static void warmUp() {
        for (Material m : new Material[] {Material.WHITE_CONCRETE, Material.RED_CONCRETE, Material.MAGENTA_CONCRETE, Material.YELLOW_CONCRETE}) stateId(m);
        SpigotConversionUtil.fromBukkitItemStack(new ItemStack(Material.DIAMOND_SWORD));
        ItemStack el = new ItemStack(Material.ELYTRA); el.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        SpigotConversionUtil.fromBukkitItemStack(el);
        new WrapperPlayServerEntityVelocity(1, new Vector3d(0, 0, 0));
        // class initialisation of the wrappers used per tick (no packet is sent)
        new WrapperPlayServerSpawnEntity(1, Optional.of(UUID.randomUUID()), EntityTypes.PLAYER, new Vector3d(0, 0, 0), 0f, 0f, 0f, 0, Optional.empty());
        new WrapperPlayServerEntityTeleport(1, new Vector3d(0, 0, 0), 0f, 0f, false);
        new WrapperPlayServerEntityHeadLook(1, 0f);
        new WrapperPlayServerEntityMetadata(1, List.of(new EntityData<>(23, EntityDataTypes.ADV_COMPONENT, Component.text("warm")),
            new EntityData<>(13, EntityDataTypes.QUATERNION, new Quaternion4f(0, 0, 0, 1)), new EntityData<>(6, EntityDataTypes.ENTITY_POSE, EntityPose.STANDING)));
        Swing.mainHand(1);
        new WrapperPlayServerSetPassengers(1, new int[] {2});
        new WrapperPlayServerDestroyEntities(1, 2);
        String.format("%.3f %s", 1.0, "warm");
        // 1.2.0 wrappers and lookups
        peType("minecraft:zombie"); peType("minecraft:item"); stateId(Material.STONE); stateId(Material.SAND);
        new WrapperPlayServerBlockChange(new Vector3i(0, 0, 0), 0);
        new WrapperPlayServerMultiBlockChange(new Vector3i(0, 0, 0), null, new WrapperPlayServerMultiBlockChange.EncodedBlock[] {new WrapperPlayServerMultiBlockChange.EncodedBlock(0, 1, 2, 3)});
        new WrapperPlayServerEntityStatus(1, 3);
        new WrapperPlayServerHurtAnimation(1, 0f);
        SpigotConversionUtil.fromBukkitItemStack(new ItemStack(Material.DIAMOND));
    }

    /** Main-hand arm swing of a fake player. Up to 26.2 it is ClientboundAnimatePacket SWING_MAIN_ARM; 26.3 moved swings to
     *  ClientboundSwingAnimationPacket(entity, hand, SwingAnimation) and ANIMATE no longer has a swing id. Detected once from
     *  PacketEvents' own id table for the running server version (no protocol numbers); ViaBackwards turns the 26.3 packet
     *  back into ANIMATE for older viewers. Lazy holder: resolved on first use, after PacketEvents knows the server version. */
    static final class Swing {
        static final boolean SWING_ANIMATION_PACKET = detect();
        /** vanilla SwingAnimation.DEFAULT (whack, 6 ticks): what an empty hand / sword swing looks like */
        private static final ItemSwingAnimation DEFAULT = new ItemSwingAnimation(ItemSwingAnimation.Type.WHACK, 6);
        private static boolean detect() {
            ServerVersion v = PacketEvents.getAPI().getServerManager().getVersion();
            try { return WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM.getId(v) < 0; }
            catch (IllegalStateException noId) { return true; }
        }
        static PacketWrapper<?> mainHand(int eid) {
            return SWING_ANIMATION_PACKET ? new WrapperPlayServerSwingAnimation(eid, InteractionHand.MAIN_HAND, DEFAULT)
                : new WrapperPlayServerEntityAnimation(eid, WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM);
        }
    }

    /** Quaternion (x,y,z,w) rotating +Z onto the Minecraft look vector: Ry(-yaw) * Rx(pitch). */
    static float[] quat(float yaw, float pitch) {
        double a = Math.toRadians(-yaw) / 2, b = Math.toRadians(pitch) / 2;
        double sy = Math.sin(a), cy = Math.cos(a), sx = Math.sin(b), cx = Math.cos(b);
        return new float[] {(float) (cy * sx), (float) (sy * cx), (float) (-sy * sx), (float) (cy * cx)};
    }

    static float[] rotate(float[] q, float vx, float vy, float vz) {
        // v' = v + 2w(u x v) + 2(u x (u x v)), u = (x,y,z)
        double ux = q[0], uy = q[1], uz = q[2], w = q[3];
        double cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
        double ccx = uy * cz - uz * cy, ccy = uz * cx - ux * cz, ccz = ux * cy - uy * cx;
        return new float[] {(float) (vx + 2 * w * cx + 2 * ccx), (float) (vy + 2 * w * cy + 2 * ccy), (float) (vz + 2 * w * cz + 2 * ccz)};
    }

    private int firstEventAfter(Clip.Track tr, int cur) {
        int k = 0; while (k < tr.events.size() && tr.events.get(k).t <= cur) k++; return k;
    }

    private void seekPointers() { for (int i = 0; i < fakes.length; i++) fakes[i].evPtr = firstEventAfter(clip.tracks.get(i), (int) cursor); prevCursor = -1; resyncUntil = tick + 61; }

    private void effect(int i, Clip.Ev e) {
        World w = viewer.getWorld();
        Clip.Track tr = clip.tracks.get(i);
        switch (e.type) {
            case "attack" -> {
                double x, y, z;
                if (e.targetTrack >= 0) {
                    Clip.Track tt = clip.tracks.get(e.targetTrack); int k = tt.indexAt(e.t);
                    if (k < 0) return; x = tt.x[k]; y = tt.y[k]; z = tt.z[k];
                } else if (!Double.isNaN(e.x)) { x = e.x; y = e.y; z = e.z; } else return;
                viewer.spawnParticle(Particle.CRIT, new Location(w, x, y + 1.1, z), 12, 0.25, 0.4, 0.25, 0.05);
                if (i == 0) {
                    String t = e.targetTrack > 0 ? clip.tracks.get(e.targetTrack).name : e.target.isEmpty() ? "엔티티" : e.target;
                    String extra = "";
                    int fi = Math.max(0, Math.min(tr.n - 1, e.frame));
                    if (e.targetTrack > 0) { Analyzer.Aim a = an.aim(fi, e.targetTrack); if (a != null) extra = String.format(" · 오차 %.2f° · 거리 %.2f · %s", a.err(), a.dist(), a.hit() ? "히트박스 안" : "히트박스 밖"); }
                    flash("공격 → " + t + extra);
                }
            }
            case "hit" -> {
                if (e.targetTrack >= 0 && fakes[e.targetTrack].spawned) send(new WrapperPlayServerHurtAnimation(fakes[e.targetTrack].eid, 0f));
                else if (!Double.isNaN(e.x)) viewer.spawnParticle(Particle.DAMAGE_INDICATOR, new Location(w, e.x, e.y + 1, e.z), 4, 0.2, 0.3, 0.2, 0.01);
            }
            case "damaged" -> { if (fakes[i].spawned) send(new WrapperPlayServerHurtAnimation(fakes[i].eid, 0f)); }
            case "blockBreak", "blockPlace" -> viewer.spawnParticle(Particle.WAX_OFF, new Location(w, e.bx + 0.5, e.by + 0.5, e.bz + 0.5), 6, 0.3, 0.3, 0.3, 0);
            case "death" -> { if (i == 0) flash(tr.name + " 사망"); }
            default -> { }
        }
    }

    private void flash(String text) { flashText = text; flashUntilTick = tick + 30; }

    // ------------------------------------------------------------------ 1.2.0: recorded entities

    private static final java.util.Map<String, EntityType> PE_TYPES = new java.util.concurrent.ConcurrentHashMap<>();
    static EntityType peType(String key) {
        if (key == null || key.isEmpty()) return null;
        EntityType t = PE_TYPES.get(key);
        if (t == null) { try { t = EntityTypes.getByName(key); } catch (RuntimeException ex) { t = null; } if (t != null) PE_TYPES.put(key, t); }
        return t;
    }
    static final int MAX_ENTITY_SPAWNS_PER_TICK = 24;

    /** Show each recorded entity while it has a sample within 350 ms of the cursor (players move away = gone). */
    private void updateEntities(int cur, String viewerWorld) {
        if (entEid.length == 0) return;
        int spawned = 0; List<Integer> gone = null;
        for (int i = 0; i < entEid.length; i++) {
            Clip.Ent e = clip.entities.get(i);
            int idx = e.indexAt(cur);
            boolean visible = entType[i] != null && idx >= 0 && cur - e.t[idx] <= 350 && e.world < clip.worlds.size() && clip.worlds.get(e.world).equals(viewerWorld);
            if (!visible) {
                if (entSpawned[i]) { if (gone == null) gone = new ArrayList<>(); gone.add(entEid[i]); entSpawned[i] = false; entLast[i] = -1; }
                continue;
            }
            int fl = e.flags[idx];
            if (!entSpawned[i]) {
                if (spawned >= MAX_ENTITY_SPAWNS_PER_TICK) continue;
                spawned++; spawnEntity(i, idx); continue;
            }
            if (idx != entLast[i]) {
                float yaw = e.yaw[idx] * 360f / 256f, pitch = e.pitch[idx] * 360f / 256f;
                send(new WrapperPlayServerEntityTeleport(entEid[i], new Vector3d(e.x[idx], e.y[idx], e.z[idx]), yaw, pitch, false));
                if (entType[i].isInstanceOf(EntityTypes.LIVINGENTITY)) send(new WrapperPlayServerEntityHeadLook(entEid[i], yaw));
                entLast[i] = idx;
            }
            if (fl != entLastFlags[i]) {
                int was = entLastFlags[i];
                if ((fl & Recorder.N_FIRE) != (was & Recorder.N_FIRE))
                    send(new WrapperPlayServerEntityMetadata(entEid[i], List.of(new EntityData<>(MD_SHARED, EntityDataTypes.BYTE, (byte) ((fl & Recorder.N_FIRE) != 0 ? 0x01 : 0)))));
                if ((fl & Recorder.N_HURT) != 0 && (was & Recorder.N_HURT) == 0 && entType[i].isInstanceOf(EntityTypes.LIVINGENTITY))
                    send(new WrapperPlayServerHurtAnimation(entEid[i], 0f));
                if ((fl & Recorder.N_DEAD) != 0 && !entDeadShown[i] && entType[i].isInstanceOf(EntityTypes.LIVINGENTITY)) {
                    send(new WrapperPlayServerEntityStatus(entEid[i], 3));   // vanilla death animation
                    entDeadShown[i] = true;
                }
                if ((fl & Recorder.N_DEAD) == 0) entDeadShown[i] = false;
                entLastFlags[i] = fl;
            }
        }
        if (gone != null) send(new WrapperPlayServerDestroyEntities(gone.stream().mapToInt(Integer::intValue).toArray()));
    }

    // Entity metadata used for recorded entities: only indices every entity has (0 shared flags, 5 no gravity) plus the item
    // entity's stack (8); per-type fields (baby, colours, held items) are not sent, so a wrong index can never kick the viewer.
    static final int MD_NO_GRAVITY = 5, MD_ITEM = 8;

    private void spawnEntity(int i, int idx) {
        Clip.Ent e = clip.entities.get(i); EntityType t = entType[i];
        float yaw = e.yaw[idx] * 360f / 256f, pitch = e.pitch[idx] * 360f / 256f;
        int data = 0;
        Material m = e.data.isEmpty() ? null : Material.matchMaterial(e.data);
        if (t == EntityTypes.FALLING_BLOCK) data = m != null && m.isBlock() ? stateId(m) : stateId(Material.SAND);
        send(new WrapperPlayServerSpawnEntity(entEid[i], Optional.of(entUuid[i]), t, new Vector3d(e.x[idx], e.y[idx], e.z[idx]), pitch, yaw, yaw, data, Optional.empty()));
        List<EntityData<?>> md = new ArrayList<>(3);
        md.add(new EntityData<>(MD_SHARED, EntityDataTypes.BYTE, (byte) ((e.flags[idx] & Recorder.N_FIRE) != 0 ? 0x01 : 0)));
        md.add(new EntityData<>(MD_NO_GRAVITY, EntityDataTypes.BOOLEAN, true));   // positions come from the recording, the client must not fall them
        if (t == EntityTypes.ITEM && m != null && m.isItem() && !m.isAir()) md.add(new EntityData<>(MD_ITEM, EntityDataTypes.ITEMSTACK, peMaterial(m)));
        send(new WrapperPlayServerEntityMetadata(entEid[i], md));
        entSpawned[i] = true; entLast[i] = idx; entLastFlags[i] = e.flags[idx]; entDeadShown[i] = false;
    }

    private final java.util.Map<Material, com.github.retrooper.packetevents.protocol.item.ItemStack> materialCache = new java.util.HashMap<>();
    private com.github.retrooper.packetevents.protocol.item.ItemStack peMaterial(Material m) {
        return materialCache.computeIfAbsent(m, k -> SpigotConversionUtil.fromBukkitItemStack(new ItemStack(k)));
    }

    // ------------------------------------------------------------------ 1.2.0: today's entities hidden while watching

    /** Today's mobs/items/players the viewer can see right now are destroyed client-side; the plugin drops new spawns. */
    private void hideLive() {
        hiding = true;
        List<Integer> ids = new ArrayList<>();
        try {
            for (org.bukkit.entity.Entity e : viewer.getWorld().getNearbyEntities(viewer.getLocation(), 160, 400, 160))
                if (e != viewer && RewindReplay.hideable(e)) { ids.add(e.getEntityId()); hiddenLive.add(e.getEntityId()); }
        } catch (RuntimeException ignored) { }   // entities of another region: their spawn packets are dropped when they come
        if (!ids.isEmpty()) send(new WrapperPlayServerDestroyEntities(ids.stream().mapToInt(Integer::intValue).toArray()));
    }

    // ------------------------------------------------------------------ 1.2.0: blocks as they were

    private void updateBlocks(int cur, boolean jump, String viewerWorld) {
        if (blocks == null || !blocks.world.equals(viewerWorld)) return;
        if (!blocksSent || jump) {
            // state at the cursor: the clip-start state, then every change up to the cursor; send what differs (everything the first time)
            int[] target = blocks.initial.clone();
            int k = 0;
            while (k < blocks.chT.length && blocks.chT[k] <= cur) { target[blocks.chPos[k]] = blocks.chState[k]; k++; }
            chPtr = k;
            List<Integer> diff = new ArrayList<>();
            for (int i = 0; i < target.length; i++) if (!blocksSent || target[i] != shown[i]) diff.add(i);
            shown = target;
            sendBlocks(diff);
            blocksSent = true;
            scheduleResync();
            return;
        }
        if (chPtr < blocks.chT.length && blocks.chT[chPtr] <= cur) {
            List<Integer> l = new ArrayList<>();
            while (chPtr < blocks.chT.length && blocks.chT[chPtr] <= cur) { int pi = blocks.chPos[chPtr]; shown[pi] = blocks.chState[chPtr]; l.add(pi); chPtr++; }
            sendBlocks(l);
        }
        // the client drops our changes when it (re)loads a chunk: re-send around the viewer after moving and every 2 s
        double vx = viewer.getLocation().getX(), vz = viewer.getLocation().getZ();
        if (!Double.isNaN(lastResyncX) && Math.hypot(vx - lastResyncX, vz - lastResyncZ) > 24) scheduleResync();
        if (tick == blockResyncAt || tick == blockResyncAt2 || tick % 40 == 0) resyncNear(vx, vz);
    }

    private void scheduleResync() { blockResyncAt = tick + 10; blockResyncAt2 = tick + 40; lastResyncX = viewer.getLocation().getX(); lastResyncZ = viewer.getLocation().getZ(); }

    private void resyncNear(double vx, double vz) {
        lastResyncX = vx; lastResyncZ = vz;
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < blocks.pos.length; i++) {
            long p = blocks.pos[i];
            if (Math.abs(BlockTimeline.unpackX(p) - vx) <= 112 && Math.abs(BlockTimeline.unpackZ(p) - vz) <= 112) l.add(i);
        }
        sendBlocks(l);
    }

    /** One BLOCK_UPDATE for a single block, else SECTION_BLOCKS_UPDATE per 16x16x16 section. */
    private void sendBlocks(List<Integer> idx) {
        if (idx.isEmpty()) return;
        blocksSentTotal += idx.size();
        if (idx.size() == 1) {
            long p = blocks.pos[idx.get(0)];
            send(new WrapperPlayServerBlockChange(new Vector3i(BlockTimeline.unpackX(p), BlockTimeline.unpackY(p), BlockTimeline.unpackZ(p)), shown[idx.get(0)]));
            return;
        }
        java.util.Map<Long, List<WrapperPlayServerMultiBlockChange.EncodedBlock>> bySection = new java.util.HashMap<>();
        for (int i : idx) {
            long p = blocks.pos[i]; int x = BlockTimeline.unpackX(p), y = BlockTimeline.unpackY(p), z = BlockTimeline.unpackZ(p);
            long key = BlockTimeline.pack(x >> 4, y >> 4, z >> 4);
            bySection.computeIfAbsent(key, k -> new ArrayList<>()).add(new WrapperPlayServerMultiBlockChange.EncodedBlock(shown[i], x & 15, y & 15, z & 15));
        }
        for (java.util.Map.Entry<Long, List<WrapperPlayServerMultiBlockChange.EncodedBlock>> en : bySection.entrySet()) {
            long k = en.getKey();
            send(new WrapperPlayServerMultiBlockChange(new Vector3i(BlockTimeline.unpackX(k), BlockTimeline.unpackY(k), BlockTimeline.unpackZ(k)), null,
                en.getValue().toArray(new WrapperPlayServerMultiBlockChange.EncodedBlock[0])));
        }
    }

    /** After the replay: send today's real blocks for every position we changed (read on each chunk's own region thread). */
    void restoreBlocks() {
        if (blocks == null || !blocksSent) return;
        World w = Bukkit.getWorld(blocks.world);
        if (w == null) return;
        java.util.Map<Long, List<long[]>> byChunk = new java.util.HashMap<>();
        for (long p : blocks.pos) {
            int x = BlockTimeline.unpackX(p), z = BlockTimeline.unpackZ(p);
            byChunk.computeIfAbsent(((long) (x >> 4) << 32) ^ ((z >> 4) & 0xFFFFFFFFL), k -> new ArrayList<>()).add(new long[] {p});
        }
        for (java.util.Map.Entry<Long, List<long[]>> en : byChunk.entrySet()) {
            int cx = (int) (en.getKey() >> 32), cz = (int) (long) en.getKey();
            List<long[]> list = en.getValue();
            try {
                Bukkit.getRegionScheduler().execute(plugin, w, cx, cz, () -> {
                    if (!viewer.isOnline()) return;
                    for (long[] q : list) {
                        long p = q[0]; int x = BlockTimeline.unpackX(p), y = BlockTimeline.unpackY(p), z = BlockTimeline.unpackZ(p);
                        try { viewer.sendBlockChange(new Location(w, x, y, z), w.getBlockAt(x, y, z).getBlockData()); } catch (RuntimeException ignored) { }
                    }
                });
            } catch (RuntimeException ignored) { }
        }
    }

    // ------------------------------------------------------------------ UI

    private void actionBar(int cur) {
        Clip.Track s = clip.tracks.get(0);
        int idx = suspectIdx();
        if (idx < 0) { viewer.sendActionBar(Component.text("이 시점에는 " + s.name + " 기록이 없습니다", NamedTextColor.GRAY)); return; }
        int f = s.flags[idx];
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("yaw %.3f pitch %.3f", s.yaw[idx], s.pitch[idx]));
        if (idx > 0 && s.t[idx] - s.t[idx - 1] <= 150) sb.append(String.format(" · %.1f°/틱", Analyzer.angDist(s.yaw[idx - 1], s.pitch[idx - 1], s.yaw[idx], s.pitch[idx])));
        sb.append((f & Recorder.F_GROUND) != 0 ? " · 지면" : " · 공중");
        if ((f & Recorder.F_SPRINT) != 0) sb.append(" · 달리기");
        if ((f & Recorder.F_SNEAK) != 0) sb.append(" · 웅크림");
        int st = s.state(idx);
        if ((st & Recorder.S_FALL_FLYING) != 0) sb.append(" · 활공");
        if ((st & Recorder.S_RIPTIDE) != 0) sb.append(" · 급류");
        if ((st & Recorder.S_SWIMMING) != 0) sb.append(" · 수영");
        if ((st & Recorder.S_CRAWLING) != 0) sb.append(" · 기어가기");
        if ((st & Recorder.S_SLEEPING) != 0) sb.append(" · 잠");
        if ((st & Recorder.S_BLOCKING) != 0) sb.append(" · 방패");
        else if ((st & Recorder.S_USING) != 0) sb.append(" · 아이템 사용");
        if ((st & Recorder.S_RIDING) != 0) sb.append(" · 탑승");
        if ((st & Recorder.S_ABILITY_FLYING) != 0) sb.append(" · 비행 허용");
        int eqi = s.equipAt(idx);
        if (eqi >= 0) { int ch = s.equip.get(eqi)[2]; if (ch > 0 && ch < clip.items.size() && clip.items.get(ch).equals("minecraft:elytra")) sb.append(" · 겉날개"); }
        int it = s.item[idx];
        if (it >= 0 && it < clip.items.size() && !clip.items.get(it).isEmpty()) sb.append(" · ").append(clip.items.get(it).replace("minecraft:", ""));
        boolean hidden = an.los[idx] == 2;
        if (an.best[idx] > 0) sb.append(String.format(" · %s %.1f칸 %.1f°", clip.tracks.get(an.best[idx]).name, an.dist[idx], an.err[idx]))
            .append(an.onBox[idx] ? (hidden ? " 벽 너머 조준" : " 조준") : hidden ? " 벽 뒤" : "");
        Component c = Component.text(sb.toString(), an.onBox[idx] ? (hidden ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.RED) : NamedTextColor.WHITE);
        viewer.sendActionBar(c);
        // last event (attack/death) on a second boss bar row, so the action bar keeps the exact numbers
        boolean show = tick < flashUntilTick;
        if (show) { eventBar.name(Component.text(flashText, NamedTextColor.GOLD)); eventBar.progress(Math.max(0f, (flashUntilTick - tick) / 30f)); }
        if (show != eventShown) { if (show) viewer.showBossBar(eventBar); else viewer.hideBossBar(eventBar); eventShown = show; }
    }

    private void bossBar(int cur) {
        float p = clip.durationMs > 0 ? Math.max(0f, Math.min(1f, cur / (float) clip.durationMs)) : 0f;
        double rel = (cur - clip.markOffsetMs) / 1000.0;
        String state = paused ? "일시정지" : (speed == 1 ? "1" : speed < 1 ? String.valueOf(speed) : String.valueOf((int) speed)) + "배속";
        bar.name(Component.text(String.format("리플레이 %s · %.1f / %.1f초 · 기록 지점 %s%.1f초 · %s%s", clip.suspectName, cur / 1000.0, clip.durationMs / 1000.0,
            rel >= 0 ? "+" : "", rel, state, pov ? " · 1인칭" : "")));
        bar.progress(p);
        bar.color(rel < 0 ? BossBar.Color.YELLOW : BossBar.Color.RED);
    }

    /** Each label is refreshed every 10 ticks, spread over the ticks so a big clip never updates all at once. */
    private void labels(int cur) {
        for (int i = tick % 10; i < fakes.length; i += 10) {
            Fake f = fakes[i]; if (!f.spawned) continue;
            Clip.Track tr = clip.tracks.get(i); int idx = tr.indexAt(cur); if (idx < 0) continue;
            send(new WrapperPlayServerEntityMetadata(f.labelId, List.of(new EntityData<>(23, EntityDataTypes.ADV_COMPONENT, label(i, idx, cur)))));
        }
    }

    private Component label(int i, int idx, int cur) {
        Clip.Track tr = clip.tracks.get(i);
        if (i == 0) return Component.text("용의자 ", NamedTextColor.RED).append(Component.text(tr.name, NamedTextColor.WHITE));
        Clip.Track s = clip.tracks.get(0); int si = s.indexAt(cur);
        TextComponent.Builder b = Component.text().append(Component.text(tr.name, NamedTextColor.GOLD));
        if (si >= 0 && s.world[si] == tr.world[idx]) {
            double dx = tr.x[idx] - s.x[si], dy = tr.y[idx] - s.y[si], dz = tr.z[idx] - s.z[si];
            b.append(Component.text(String.format(" %.1f칸", Math.sqrt(dx * dx + dy * dy + dz * dz)), NamedTextColor.GRAY));
        }
        return b.build();
    }

    private void follow(int cur) {
        int idx = suspectIdx(); if (idx < 0) return;
        Clip.Track s = clip.tracks.get(0);
        World w = Bukkit.getWorld(clip.worlds.get(s.world[idx]));
        if (w == null) return;
        Location v = viewer.getLocation();
        double dx = v.getX() - s.x[idx], dz = v.getZ() - s.z[idx];
        double d = v.getWorld().equals(w) ? Math.sqrt(dx * dx + dz * dz) : 1e9;
        if (pov ? d > 12 : d > 48) viewer.teleportAsync(pov ? new Location(w, s.x[idx], s.y[idx] + 1, s.z[idx], v.getYaw(), v.getPitch()) : viewPoint(w, idx));
    }

    Location viewPoint(World w, int idx) {
        Clip.Track s = clip.tracks.get(0);
        double yaw = Math.toRadians(s.yaw[idx]);
        double bx = s.x[idx] + Math.sin(yaw) * 4, bz = s.z[idx] - Math.cos(yaw) * 4, by = s.y[idx] + 2.2;
        double vx = s.x[idx] - bx, vy = s.y[idx] + 1.2 - by, vz = s.z[idx] - bz;
        float lookYaw = (float) Math.toDegrees(Math.atan2(-vx, vz)), lookPitch = (float) -Math.toDegrees(Math.atan2(vy, Math.sqrt(vx * vx + vz * vz)));
        return new Location(w, bx, by, bz, lookYaw, lookPitch);
    }

    // ------------------------------------------------------------------ controls

    void action(String a) {
        switch (a) {
            case "pause" -> paused = !paused;
            case "back10" -> seek(cursor - 10000);
            case "back1" -> seek(cursor - 1000);
            case "fwd1" -> seek(cursor + 1000);
            case "fwd10" -> seek(cursor + 10000);
            case "prev" -> { paused = true; stepFrame(-1); }
            case "next" -> { paused = true; stepFrame(1); }
            case "mark" -> seek(clip.markOffsetMs - 5000);
            case "restart" -> { seek(0); paused = false; }
            case "s025" -> speed = 0.25;
            case "s05" -> speed = 0.5;
            case "s1" -> speed = 1;
            case "s2" -> speed = 2;
            case "s4" -> speed = 4;
            case "pov" -> {
                pov = !pov;
                if (pov && fakes[0].spawned) send(new WrapperPlayServerCamera(fakes[0].eid));
                if (!pov) send(new WrapperPlayServerCamera(viewer.getEntityId()));
                viewer.sendMessage(Component.text(pov ? "[리플레이] 1인칭 시점: 용의자 눈으로 봅니다(머리 방향은 1.4° 단위로 표시됨, 정확한 값은 빛줄기와 아래 숫자)." : "[리플레이] 자유 시점으로 돌아왔습니다.", SOFT));
            }
            case "goto" -> { int idx = suspectIdx(); if (idx >= 0) { World w = Bukkit.getWorld(clip.worlds.get(clip.tracks.get(0).world[idx])); if (w != null) viewer.teleportAsync(viewPoint(w, idx)); } }
            case "summary" -> plugin.printSummary(viewer, clip);
            case "controls" -> viewer.sendMessage(controls());
            case "stop" -> end(true, null);
            default -> { if (a.startsWith("at:")) { paused = true; seek(Double.parseDouble(a.substring(3)) * 1000); } }
        }
    }

    private void seek(double ms) { cursor = Math.max(0, Math.min(clip.durationMs, ms)); seekPointers(); }

    private void stepFrame(int dir) {
        Clip.Track s = clip.tracks.get(0);
        int idx = s.indexAt((int) cursor);
        int ni = Math.max(0, Math.min(s.n - 1, idx + dir));
        if (ni >= 0 && ni < s.n) { cursor = s.t[ni]; if (dir < 0) seekPointers(); }
    }

    Component header() {
        TextComponent.Builder b = Component.text();
        b.append(Component.text("[리플레이] ", ACCENT)).append(Component.text(clip.suspectName, NamedTextColor.WHITE))
            .append(Component.text(" · 클립 " + clip.id + " · 주변 " + (clip.tracks.size() - 1) + "명", SOFT));
        StringBuilder why = new StringBuilder();
        for (Clip.Reason r : clip.reasons) { if (why.length() > 0) why.append(" / "); why.append(r.reason()); }
        b.append(Component.newline()).append(Component.text("사유: " + why, SOFT));
        if (clip.summary.has("headline")) b.append(Component.newline()).append(Component.text(clip.summary.get("headline").getAsString(), NamedTextColor.GRAY));
        b.append(Component.newline()).append(Component.text("빛줄기: 노랑 = 시선, 빨강 = 히트박스 조준, 보라 = 벽 너머 조준, 흰색 = 공격. 조준당한 플레이어는 윤곽선이 켜집니다. 이 화면은 본인에게만 보입니다.", NamedTextColor.GRAY));
        if (clip.format < 2) b.append(Component.newline()).append(Component.text("1.1.0 이전 클립이라 갑옷·겉날개·활공 자세 기록이 없습니다(든 아이템과 웅크리기만 보입니다).", NamedTextColor.GRAY));
        b.append(Component.newline()).append(Component.text(clip.format >= 3 ? "주변 몹·아이템·화살 " + clip.entities.size() + "개를 당시 움직임대로 보여 줍니다." : "1.2.0 이전 클립이라 몹·아이템 기록이 없습니다.", NamedTextColor.GRAY));
        b.append(Component.newline()).append(Component.text(blocks != null ? "블록 " + blocks.size() + "곳을 당시 상태로 되돌리고, 클립 동안 바뀐 " + blocks.chT.length + "곳은 그 시각에 바뀝니다(CoreProtect 기록, 초 단위)."
            : "블록은 지금 상태입니다" + (blockNote.isEmpty() ? "." : ": " + blockNote), NamedTextColor.GRAY));
        if (plugin.settings().hideLiveEntities) b.append(Component.newline()).append(Component.text("지금 주변의 실제 몹·플레이어는 리플레이 동안 숨깁니다.", NamedTextColor.GRAY));
        return b.build();
    }

    Component controls() {
        TextComponent.Builder b = Component.text();
        b.append(button("재생/일시정지", "pause", "재생하거나 멈춥니다")).append(Component.space())
            .append(button("-10초", "back10", "10초 뒤로")).append(Component.space())
            .append(button("-1초", "back1", "1초 뒤로")).append(Component.space())
            .append(button("-1틱", "prev", "한 틱 뒤 (일시정지)")).append(Component.space())
            .append(button("+1틱", "next", "한 틱 앞 (일시정지)")).append(Component.space())
            .append(button("+1초", "fwd1", "1초 앞으로")).append(Component.space())
            .append(button("+10초", "fwd10", "10초 앞으로"))
            .append(Component.newline())
            .append(button("0.25배", "s025", "0.25배속")).append(Component.space())
            .append(button("0.5배", "s05", "0.5배속")).append(Component.space())
            .append(button("1배", "s1", "1배속")).append(Component.space())
            .append(button("2배", "s2", "2배속")).append(Component.space())
            .append(button("4배", "s4", "4배속")).append(Component.space())
            .append(button("기록 지점", "mark", "기록 지점 5초 전으로")).append(Component.space())
            .append(button("1인칭", "pov", "용의자 시점 켜기/끄기")).append(Component.space())
            .append(button("가까이", "goto", "용의자 뒤로 이동")).append(Component.space())
            .append(button("요약", "summary", "클립 요약 수치")).append(Component.space())
            .append(Component.text("[종료]", NamedTextColor.RED).clickEvent(ClickEvent.runCommand(ReplayCmd.CLICK + "control stop")).hoverEvent(HoverEvent.showText(Component.text("리플레이를 끝내고 원래 자리로 돌아갑니다"))));
        return b.build();
    }

    static Component button(String label, String act, String hover) {
        return Component.text("[" + label + "]", TextColor.color(0xE8C07D)).clickEvent(ClickEvent.runCommand(ReplayCmd.CLICK + "control " + act))
            .hoverEvent(HoverEvent.showText(Component.text(hover)));
    }
}
