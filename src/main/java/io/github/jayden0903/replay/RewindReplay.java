package io.github.jayden0903.replay;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientVehicleMove;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemArmorTrim;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * RewindReplay: always-on per-player ring buffers of the last minute of movement and aim (from packets, netty threads,
 * O(1) per packet, fixed memory), poses, equipment and nearby entities, frozen to disk on a mark, and replayed in game to
 * one staff viewer with packet-only fake entities over the blocks as they were (CoreProtect history).
 */
public final class RewindReplay extends JavaPlugin implements Listener, PacketListener, ClipService.Host {
    private final Map<UUID, Recorder> online = new ConcurrentHashMap<>();
    private final Map<Integer, Recorder> byEntity = new ConcurrentHashMap<>();
    private final Map<Integer, Recorder> recent = new ConcurrentHashMap<>();     // rid -> recorder of players who left
    private final Map<UUID, ReplaySession> sessions = new ConcurrentHashMap<>();
    private volatile Settings settings = new Settings();
    private ClipService service;
    private PacketListenerCommon registered, hider;
    private Path restoreDir;
    // statistics (updated on netty threads; approximate is fine)
    private final java.util.concurrent.atomic.LongAdder packets = new java.util.concurrent.atomic.LongAdder(), nanos = new java.util.concurrent.atomic.LongAdder();
    private volatile long statsSince = System.nanoTime();
    private final java.util.concurrent.atomic.LongAdder losNanos = new java.util.concurrent.atomic.LongAdder(), losSteps = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.AtomicLong losMax = new java.util.concurrent.atomic.AtomicLong();
    // entity-thread sampler (pose every tick, items every 10 ticks); nanos measured around the sampling code only
    private final java.util.concurrent.atomic.LongAdder poseNanos = new java.util.concurrent.atomic.LongAdder(), poseRuns = new java.util.concurrent.atomic.LongAdder(),
        itemNanos = new java.util.concurrent.atomic.LongAdder(), itemRuns = new java.util.concurrent.atomic.LongAdder(),
        hotbarNanos = new java.util.concurrent.atomic.LongAdder(),   // the part 1.0.x already did (hotbar every 10 ticks)
        entityNanos = new java.util.concurrent.atomic.LongAdder(), entityRuns = new java.util.concurrent.atomic.LongAdder();   // 1.2.0 nearby entities

    record SavedState(String world, double x, double y, double z, float yaw, float pitch, String gameMode, boolean allowFlight, boolean flying) { }

    // ------------------------------------------------------------------ lifecycle

    @Override public void onEnable() {
        saveDefaultConfig();
        settings = Settings.load(getConfig());
        restoreDir = getDataFolder().toPath().resolve("restore");
        try { Files.createDirectories(restoreDir); } catch (IOException e) { getLogger().warning("cannot create " + restoreDir); }
        service = new ClipService(this, getLogger(), getDataFolder().toPath(), settings);
        getServer().getPluginManager().registerEvents(this, this);
        registered = PacketEvents.getAPI().getEventManager().registerListener(this, PacketListenerPriority.MONITOR);
        // 1.2.0: a replay viewer does not get spawn packets of today's mobs/players (MONITOR must not cancel, so a separate listener)
        hider = PacketEvents.getAPI().getEventManager().registerListener(new PacketListener() {
            @Override public void onPacketSend(PacketSendEvent e) {
                if (e.getPacketType() != PacketType.Play.Server.SPAWN_ENTITY || sessions.isEmpty()) return;
                UUID id = e.getUser().getUUID();
                ReplaySession s = id == null ? null : sessions.get(id);
                if (s == null || !s.hiding) return;
                try {
                    com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity w = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity(e);
                    if (hideableType(w.getEntityType())) { e.setCancelled(true); s.hiddenLive.add(w.getEntityId()); }
                } catch (RuntimeException ignored) { }
            }
        }, PacketListenerPriority.NORMAL);
        for (Player p : Bukkit.getOnlinePlayers()) p.getScheduler().run(this, t -> track(p), null);
        service.io.execute(() -> { try { long w0 = System.nanoTime(); ReplaySession.warmUp(); getLogger().info("viewer mappings warmed up in " + (System.nanoTime() - w0) / 1_000_000 + " ms (io thread); swing packet: "
                + (ReplaySession.Swing.SWING_ANIMATION_PACKET ? "SWING_ANIMATION (26.3+)" : "ANIMATE SWING_MAIN_ARM")); }
            catch (Throwable ex) { getLogger().warning("viewer warm-up failed: " + ex); } });
        getLogger().info("enabled: " + settings.bufferSeconds + " s + " + settings.afterSeconds + " s clips, "
            + settings.frameCap() + " frames/" + settings.eventCap + " events/" + settings.entityCap() + " entity rows per player ("
            + Recorder.bytesPerRecorder(settings.frameCap(), settings.eventCap, settings.entityCap()) / 1024 + " KiB); blocks from CoreProtect: "
            + (settings.worldHistory ? (getServer().getPluginManager().getPlugin("CoreProtect") != null ? "yes" : "no CoreProtect") : "off"));
    }

    @Override public void onDisable() {
        if (registered != null) PacketEvents.getAPI().getEventManager().unregisterListener(registered);
        if (hider != null) PacketEvents.getAPI().getEventManager().unregisterListener(hider);
        for (ReplaySession s : new ArrayList<>(sessions.values())) { s.ended = true; if (s.task != null) s.task.cancel(); s.cleanupPackets(); }
        sessions.clear();   // restore files stay; the viewer is restored on the next join
        if (service != null) service.shutdown();
        online.clear(); byEntity.clear(); recent.clear();
    }

    // ------------------------------------------------------------------ recorders (region threads)

    private void track(Player p) {
        Settings s = settings;
        Recorder r = new Recorder(p.getUniqueId(), p.getName(), p.getEntityId(), s.frameCap(), s.eventCap, s.entityCap());
        try {
            for (ProfileProperty pp : p.getPlayerProfile().getProperties())
                if (pp.getName().equals("textures")) { r.skinValue = pp.getValue(); r.skinSignature = pp.getSignature(); }
        } catch (Throwable ignored) { }
        User u = PacketEvents.getAPI().getPlayerManager().getUser(p);
        if (u != null && u.getClientVersion() != null) r.client = u.getClientVersion().getReleaseName();
        r.world = Names.id(p.getWorld().getName());
        r.spectator = p.getGameMode() == GameMode.SPECTATOR;
        org.bukkit.Location l = p.getLocation();
        r.cx = l.getX(); r.cy = l.getY(); r.cz = l.getZ(); r.cyaw = l.getYaw(); r.cpitch = l.getPitch(); r.slot = p.getInventory().getHeldItemSlot();
        r.state = state(p);
        hotbar(p, r); equipment(p, r);
        Recorder old = online.put(p.getUniqueId(), r);
        if (old != null) byEntity.remove(old.entityId, old);
        byEntity.put(r.entityId, r);
        // Runs on the player's own entity thread: pose/state every tick, hotbar + equipment every 10 ticks (and at once
        // when gliding starts or stops, so the elytra is never missing from the first glide frames).
        p.getScheduler().runAtFixedRate(this, t -> { Recorder cur = online.get(p.getUniqueId()); if (cur == r) sample(p, r); else t.cancel(); }, null, 1L, 1L);
    }

    private void sample(Player p, Recorder r) {
        long t0 = System.nanoTime();
        int old = r.state, st = state(p);
        r.state = st;
        long t1 = System.nanoTime();
        poseNanos.add(t1 - t0); poseRuns.increment();
        if (++r.sampleTick >= 10 || ((old ^ st) & Recorder.S_FALL_FLYING) != 0) {
            r.sampleTick = 0;
            hotbar(p, r);
            long t2 = System.nanoTime();
            equipment(p, r);
            hotbarNanos.add(t2 - t1); itemNanos.add(System.nanoTime() - t1); itemRuns.increment();
        }
        Settings s = settings;
        if (r.ncap > 0 && ++r.entityTick >= s.entityIntervalTicks) { r.entityTick = 0; sampleEntities(p, r, s); }
    }

    /** Scratch arrays for one entity sample (region threads run one task at a time, so one set per thread). */
    private static final class EntScratch {
        int[] id = new int[0], type, data; float[] x, y, z; byte[] yaw, pitch, flags;
        double[] d2 = new double[0]; Entity[] pick = new Entity[0];
        void ensure(int n) {
            if (id.length >= n) return;
            id = new int[n]; type = new int[n]; data = new int[n]; x = new float[n]; y = new float[n]; z = new float[n];
            yaw = new byte[n]; pitch = new byte[n]; flags = new byte[n];
        }
    }
    private static final ThreadLocal<EntScratch> SCRATCH = ThreadLocal.withInitial(EntScratch::new);

    /** Mobs, dropped items, projectiles, vehicles, TNT and falling blocks near the player (never players, armour stands,
     *  item frames, paintings or displays: those are either other tracks or part of the world). Player entity thread. */
    static boolean recordable(Entity e) {
        if (e instanceof Player || e instanceof org.bukkit.entity.ArmorStand) return false;
        return e instanceof org.bukkit.entity.LivingEntity || e instanceof org.bukkit.entity.Item || e instanceof Projectile
            || e instanceof org.bukkit.entity.Vehicle || e instanceof org.bukkit.entity.TNTPrimed || e instanceof org.bukkit.entity.FallingBlock;
    }

    /** What a replay viewer does not see of today's world: everything recordable, other players and experience orbs. */
    static boolean hideable(Entity e) { return recordable(e) || e instanceof Player || e instanceof org.bukkit.entity.ExperienceOrb; }

    private static final java.util.Set<String> HIDE_KEYS = java.util.Set.of("minecraft:item", "minecraft:experience_orb", "minecraft:tnt", "minecraft:falling_block",
        "minecraft:arrow", "minecraft:spectral_arrow", "minecraft:trident", "minecraft:snowball", "minecraft:egg", "minecraft:ender_pearl", "minecraft:potion",
        "minecraft:splash_potion", "minecraft:lingering_potion", "minecraft:experience_bottle", "minecraft:fireball", "minecraft:small_fireball",
        "minecraft:dragon_fireball", "minecraft:wither_skull", "minecraft:wind_charge", "minecraft:breeze_wind_charge", "minecraft:shulker_bullet",
        "minecraft:llama_spit", "minecraft:firework_rocket", "minecraft:fishing_bobber", "minecraft:eye_of_ender", "minecraft:evoker_fangs");
    private static final java.util.Map<com.github.retrooper.packetevents.protocol.entity.type.EntityType, Boolean> HIDE_CACHE = new ConcurrentHashMap<>();

    /** Same rule on the packet side (netty thread): living entities except armour stands, plus the keys above, boats and minecarts. */
    static boolean hideableType(com.github.retrooper.packetevents.protocol.entity.type.EntityType t) {
        if (t == null) return false;
        return HIDE_CACHE.computeIfAbsent(t, k -> {
            if (k == com.github.retrooper.packetevents.protocol.entity.type.EntityTypes.ARMOR_STAND) return false;
            if (k.isInstanceOf(com.github.retrooper.packetevents.protocol.entity.type.EntityTypes.LIVINGENTITY)) return true;
            String key = k.getName().toString();
            return HIDE_KEYS.contains(key) || key.endsWith("_boat") || key.endsWith("_raft") || key.contains("minecart")
                || k.isInstanceOf(com.github.retrooper.packetevents.protocol.entity.type.EntityTypes.ABSTRACT_ARROW)
                || k.isInstanceOf(com.github.retrooper.packetevents.protocol.entity.type.EntityTypes.PROJECTILE_ABSTRACT);
        });
    }

    private static final int[] ENTITY_TYPE_IDS = new int[org.bukkit.entity.EntityType.values().length];

    private void sampleEntities(Player p, Recorder r, Settings s) {
        long t0 = System.nanoTime();
        List<Entity> near;
        try { near = p.getNearbyEntities(s.entityRadius, s.entityRadius, s.entityRadius); }
        catch (RuntimeException ex) { return; }   // a box reaching into another region: skip this sample
        EntScratch sc = SCRATCH.get();
        int max = s.entityMaxPerSample;
        sc.ensure(max);
        org.bukkit.Location pl = p.getLocation();
        // nearest `max` recordable entities (partial selection; the list is small)
        int cand = 0;
        if (sc.pick.length < near.size()) { sc.pick = new Entity[near.size()]; sc.d2 = new double[near.size()]; }
        for (Entity e : near) {
            if (!recordable(e)) continue;
            org.bukkit.Location l = e.getLocation();
            double dx = l.getX() - pl.getX(), dy = l.getY() - pl.getY(), dz = l.getZ() - pl.getZ();
            sc.pick[cand] = e; sc.d2[cand] = dx * dx + dy * dy + dz * dz; cand++;
        }
        int n = Math.min(cand, max);
        for (int k = 0; k < n && cand > max; k++) {   // selection of the nearest only when there are too many
            int b = k; for (int j = k + 1; j < cand; j++) if (sc.d2[j] < sc.d2[b]) b = j;
            Entity te = sc.pick[k]; sc.pick[k] = sc.pick[b]; sc.pick[b] = te; double td = sc.d2[k]; sc.d2[k] = sc.d2[b]; sc.d2[b] = td;
        }
        for (int k = 0; k < n; k++) {
            Entity e = sc.pick[k];
            org.bukkit.Location l = e.getLocation();
            org.bukkit.entity.EntityType et = e.getType();
            int o = et.ordinal(), tid = ENTITY_TYPE_IDS[o];
            if (tid == 0) { tid = Names.id(et.getKey().toString()); ENTITY_TYPE_IDS[o] = tid; }
            int data = 0;
            if (e instanceof org.bukkit.entity.Item it) data = item(it.getItemStack()) & Recorder.ID_MASK;
            else if (e instanceof org.bukkit.entity.FallingBlock fb) data = Names.id(fb.getBlockData().getMaterial().getKey().toString());
            int f = 0;
            if (e.getFireTicks() > 0 || e.isVisualFire()) f |= Recorder.N_FIRE;
            if (e instanceof org.bukkit.entity.Ageable a && !a.isAdult()) f |= Recorder.N_BABY;
            if (e.isDead()) f |= Recorder.N_DEAD;
            if (e instanceof org.bukkit.entity.LivingEntity le) {
                if (le.getHealth() <= 0) f |= Recorder.N_DEAD;
                if (le.getNoDamageTicks() > le.getMaximumNoDamageTicks() / 2) f |= Recorder.N_HURT;
            }
            sc.id[k] = e.getEntityId(); sc.type[k] = tid; sc.data[k] = data;
            sc.x[k] = (float) l.getX(); sc.y[k] = (float) l.getY(); sc.z[k] = (float) l.getZ();
            sc.yaw[k] = (byte) Math.round(l.getYaw() * 256f / 360f); sc.pitch[k] = (byte) Math.round(l.getPitch() * 256f / 360f); sc.flags[k] = (byte) f;
            sc.pick[k] = null;
        }
        for (int k = n; k < cand; k++) sc.pick[k] = null;
        r.entities(r.msNow(System.nanoTime()), n, sc.id, sc.type, sc.data, sc.x, sc.y, sc.z, sc.yaw, sc.pitch, sc.flags);
        entityNanos.add(System.nanoTime() - t0); entityRuns.increment();
    }

    /** Pose code + state bits from the server's own view of the player (entity thread). */
    static int state(Player p) {
        Pose pose = p.getPose();
        int code = switch (pose) {
            case STANDING -> Recorder.P_STANDING; case FALL_FLYING -> Recorder.P_FALL_FLYING; case SLEEPING -> Recorder.P_SLEEPING;
            case SWIMMING -> Recorder.P_SWIMMING; case SPIN_ATTACK -> Recorder.P_SPIN_ATTACK; case SNEAKING -> Recorder.P_CROUCHING;
            case DYING -> Recorder.P_DYING; default -> Recorder.P_OTHER;
        };
        int s = code;
        if (p.isGliding()) s |= Recorder.S_FALL_FLYING;
        boolean swim = p.isSwimming();
        if (swim) s |= Recorder.S_SWIMMING;
        else if (pose == Pose.SWIMMING) s |= Recorder.S_CRAWLING;
        if (p.isRiptiding()) s |= Recorder.S_RIPTIDE;
        if (p.isSleeping()) s |= Recorder.S_SLEEPING;
        if (p.isHandRaised()) { s |= Recorder.S_USING; if (p.getHandRaised() == EquipmentSlot.OFF_HAND) s |= Recorder.S_USING_OFFHAND; }
        if (p.isBlocking()) s |= Recorder.S_BLOCKING;
        if (p.isFlying()) s |= Recorder.S_ABILITY_FLYING;
        if (p.isInsideVehicle()) s |= Recorder.S_RIDING;
        if (p.isInWater()) s |= Recorder.S_IN_WATER;
        return s;
    }

    private static void hotbar(Player p, Recorder r) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) r.hotbar[i] = item(inv.getItem(i));
    }

    /** Head, chest, legs, feet, offhand (+ armour trims) into the recorder's equipment ring when changed. */
    private static void equipment(Player p, Recorder r) {
        PlayerInventory inv = p.getInventory();
        int[] e = r.eqScratch;
        ItemStack h = inv.getHelmet(), c = inv.getChestplate(), l = inv.getLeggings(), f = inv.getBoots();
        e[0] = item(h); e[1] = item(c); e[2] = item(l); e[3] = item(f); e[4] = item(inv.getItemInOffHand());
        e[5] = trim(h); e[6] = trim(c); e[7] = trim(l); e[8] = trim(f);
        r.equipment(e);
    }

    /** Names index per material (filled lazily; benign races write the same value). */
    private static final int[] MATERIAL_IDS = new int[org.bukkit.Material.values().length];

    /** Names index of the material, | GLINT when enchanted (or glint forced). 0 = empty. */
    static int item(ItemStack it) {
        if (it == null) return 0;
        org.bukkit.Material m = it.getType();
        if (m.isAir()) return 0;
        int o = m.ordinal(), id = o < MATERIAL_IDS.length ? MATERIAL_IDS[o] : 0;
        if (id == 0) { id = Names.id(m.getKey().toString()); if (o < MATERIAL_IDS.length) MATERIAL_IDS[o] = id; }
        // only component-patch checks (ItemStack#hasNonDefault) on the hot path; hasItemMeta() would build an ItemMeta
        try {
            boolean glint;
            if (it.isDataOverridden(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE)) glint = Boolean.TRUE.equals(it.getData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE));
            else glint = it.isDataOverridden(DataComponentTypes.ENCHANTMENTS) && !it.getEnchantments().isEmpty()
                || it.isDataOverridden(DataComponentTypes.STORED_ENCHANTMENTS);
            if (glint) id |= Recorder.GLINT;
        } catch (RuntimeException ignored) { }
        return id;
    }

    private static volatile org.bukkit.Registry<org.bukkit.inventory.meta.trim.TrimPattern> trimPatterns;
    private static volatile org.bukkit.Registry<org.bukkit.inventory.meta.trim.TrimMaterial> trimMaterials;

    /** Names index of "pattern material" (e.g. "minecraft:sentry minecraft:gold") or 0. */
    static int trim(ItemStack it) {
        if (it == null || it.getType().isAir()) return 0;
        try {
            if (!it.isDataOverridden(DataComponentTypes.TRIM)) return 0;
            ItemArmorTrim t = it.getData(DataComponentTypes.TRIM);
            if (t == null) return 0;
            if (trimPatterns == null) { trimPatterns = RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_PATTERN); trimMaterials = RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_MATERIAL); }
            NamespacedKey pk = trimPatterns.getKey(t.armorTrim().getPattern()), mk = trimMaterials.getKey(t.armorTrim().getMaterial());
            return pk == null || mk == null ? 0 : Names.id(pk + " " + mk);
        } catch (RuntimeException ex) { return 0; }
    }

    @EventHandler(priority = EventPriority.MONITOR) public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        track(p);
        Path f = restoreDir.resolve(p.getUniqueId() + ".properties");
        if (Files.exists(f)) p.getScheduler().runDelayed(this, t -> restoreFromFile(p, f), null, 5L);
    }

    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        ReplaySession s = sessions.remove(p.getUniqueId());
        if (s != null) {
            s.ended = true; if (s.task != null) s.task.cancel();
            // gamemode now; the position is restored from the restore file on the next join
            try { p.setGameMode(GameMode.valueOf(s.saved.gameMode())); } catch (Throwable ignored) { }
        }
        Recorder r = online.remove(p.getUniqueId());
        if (r != null) {
            byEntity.remove(r.entityId, r);
            r.quitNano = System.nanoTime();
            if (settings.recentQuitSeconds > 0 && settings.maxRecentQuit > 0) recent.put(r.rid, r);
            pruneRecent();
        }
    }

    private void pruneRecent() {
        long now = System.nanoTime(), max = settings.recentQuitSeconds * 1_000_000_000L;
        recent.values().removeIf(r -> now - r.quitNano > max && service.pending.get(r.rid) == null);
        while (recent.size() > settings.maxRecentQuit) {
            Recorder oldest = null;
            for (Recorder r : recent.values()) if (service.pending.get(r.rid) == null && (oldest == null || r.quitNano < oldest.quitNano)) oldest = r;
            if (oldest == null) break;
            recent.remove(oldest.rid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void gamemode(PlayerGameModeChangeEvent e) {
        Recorder r = online.get(e.getPlayer().getUniqueId()); if (r != null) r.spectator = e.getNewGameMode() == GameMode.SPECTATOR;
    }

    @EventHandler(priority = EventPriority.MONITOR) public void world(PlayerChangedWorldEvent e) {
        Recorder r = online.get(e.getPlayer().getUniqueId()); if (r == null) return;
        r.world = Names.id(e.getPlayer().getWorld().getName());
        r.event(Recorder.E_WORLD, r.world, 0, 0, 0, Double.NaN, Double.NaN, Double.NaN);
    }

    @EventHandler(priority = EventPriority.MONITOR) public void respawn(PlayerRespawnEvent e) {
        Recorder r = online.get(e.getPlayer().getUniqueId()); if (r == null) return;
        r.world = Names.id(e.getRespawnLocation().getWorld().getName());
        org.bukkit.Location l = e.getRespawnLocation();
        r.event(Recorder.E_RESPAWN, 0, 0, 0, 0, l.getX(), l.getY(), l.getZ());
        r.pendingFlags.getAndUpdate(v -> v | Recorder.F_TELEPORT);
    }

    @EventHandler(priority = EventPriority.MONITOR) public void death(PlayerDeathEvent e) {
        Recorder r = online.get(e.getPlayer().getUniqueId()); if (r == null) return;
        org.bukkit.Location l = e.getPlayer().getLocation();
        r.event(Recorder.E_DEATH, 0, 0, 0, 0, l.getX(), l.getY(), l.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void damage(EntityDamageEvent e) {
        Entity victim = e.getEntity();
        Entity damager = e instanceof EntityDamageByEntityEvent be ? be.getDamager() : null;
        UUID attackerId = null;
        if (damager instanceof Player dp) attackerId = dp.getUniqueId();
        else if (damager instanceof Projectile pr && pr.getShooter() instanceof Player sp) attackerId = sp.getUniqueId();
        Recorder attacker = attackerId == null ? null : online.get(attackerId);
        org.bukkit.Location vl = victim.getLocation();
        int dmg = (int) Math.round(e.getFinalDamage() * 100);
        if (victim instanceof Player vp) {
            Recorder r = online.get(vp.getUniqueId());
            if (r != null) {
                r.pendingFlags.getAndUpdate(v -> v | Recorder.F_DAMAGED);
                double dx = Double.NaN, dy = Double.NaN, dz = Double.NaN;
                if (damager != null) { org.bukkit.Location dl = damager.getLocation(); dx = dl.getX(); dy = dl.getY(); dz = dl.getZ(); }
                r.event(Recorder.E_DAMAGED, Names.id(e.getCause().name()), attacker == null ? -1 : attacker.rid,
                    damager == null ? 0 : Names.id(damager.getType().getKey().toString()), dmg, dx, dy, dz);
            }
        }
        if (attacker != null) {
            Recorder vr = victim instanceof Player vp ? online.get(vp.getUniqueId()) : null;
            attacker.event(Recorder.E_HIT, victim.getEntityId(), vr == null ? -1 : vr.rid, Names.id(victim.getType().getKey().toString()), dmg, vl.getX(), vl.getY(), vl.getZ());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void breakBlock(BlockBreakEvent e) {
        Recorder r = online.get(e.getPlayer().getUniqueId()); if (r == null) return;
        var b = e.getBlock();
        r.event(Recorder.E_BREAK, b.getX(), b.getY(), b.getZ(), Names.id(b.getType().getKey().toString()), Double.NaN, Double.NaN, Double.NaN);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void placeBlock(BlockPlaceEvent e) {
        Recorder r = online.get(e.getPlayer().getUniqueId()); if (r == null) return;
        var b = e.getBlockPlaced();
        r.event(Recorder.E_PLACE, b.getX(), b.getY(), b.getZ(), Names.id(b.getType().getKey().toString()), Double.NaN, Double.NaN, Double.NaN);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void open(InventoryOpenEvent e) {
        Recorder r = online.get(e.getPlayer().getUniqueId()); if (r != null) r.gui = true;
    }

    @EventHandler(priority = EventPriority.MONITOR) public void close(InventoryCloseEvent e) {
        Recorder r = online.get(e.getPlayer().getUniqueId()); if (r != null) r.gui = false;
    }

    // ------------------------------------------------------------------ packets (netty threads, O(1), never cancel)

    @Override public void onPacketReceive(PacketReceiveEvent e) {
        PacketTypeCommon type = e.getPacketType();
        if (!(type instanceof PacketType.Play.Client)) return;
        UUID id = e.getUser().getUUID();
        if (id == null) return;
        Recorder r = online.get(id);
        if (r == null) return;
        long t0 = System.nanoTime();
        try {
            if (type == PacketType.Play.Client.CLIENT_TICK_END) {
                r.lastTickEndNano = t0;
                r.commit(t0, false);
            } else if (WrapperPlayClientPlayerFlying.isFlying(type)) {
                WrapperPlayClientPlayerFlying w = new WrapperPlayClientPlayerFlying(e);
                Location l = w.getLocation();
                r.move(w.hasPositionChanged(), l.getX(), l.getY(), l.getZ(), w.hasRotationChanged(), l.getYaw(), l.getPitch(), w.isOnGround());
                if (t0 - r.lastTickEndNano > 1_000_000_000L) r.commit(t0, true);   // client without tick end: one frame per movement packet
            } else if (type == PacketType.Play.Client.VEHICLE_MOVE) {
                Vector3d v = new WrapperPlayClientVehicleMove(e).getPosition();
                r.move(true, v.getX(), v.getY(), v.getZ(), false, 0, 0, false);
                r.flag(Recorder.F_VEHICLE);
            } else if (type == PacketType.Play.Client.ANIMATION) {
                r.flag(new WrapperPlayClientAnimation(e).getHand() == InteractionHand.MAIN_HAND ? Recorder.F_SWING : Recorder.F_SWING_OFF);
            } else if (type == PacketType.Play.Client.PUNCH) {
                // 26.3+: ServerboundPunchPacket replaced the swing packet; it is always a main-hand left-click swing
                // (a server knows only one of ANIMATION/PUNCH, so nothing is recorded twice)
                r.flag(Recorder.F_SWING);
            } else if (type == PacketType.Play.Client.ATTACK) {
                attack(r, new WrapperPlayClientAttack(e).getEntityId());
            } else if (type == PacketType.Play.Client.INTERACT_ENTITY) {
                WrapperPlayClientInteractEntity w = new WrapperPlayClientInteractEntity(e);
                if (w.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) attack(r, w.getEntityId());
                else if (w.getAction() == WrapperPlayClientInteractEntity.InteractAction.INTERACT) {
                    r.flag(Recorder.F_USE);
                    Recorder tgt = byEntity.get(w.getEntityId());
                    r.event(Recorder.E_INTERACT, w.getEntityId(), tgt == null ? -1 : tgt.rid, 0, 0, Double.NaN, Double.NaN, Double.NaN);
                }
            } else if (type == PacketType.Play.Client.PLAYER_DIGGING) {
                WrapperPlayClientPlayerDigging w = new WrapperPlayClientPlayerDigging(e);
                DiggingAction a = w.getAction();
                int code = a == DiggingAction.START_DIGGING ? 0 : a == DiggingAction.CANCELLED_DIGGING ? 1 : a == DiggingAction.FINISHED_DIGGING ? 2 : a == DiggingAction.STAB ? 3 : -1;
                if (code >= 0) {
                    Vector3i b = w.getBlockPosition();
                    r.flag(Recorder.F_DIG);
                    r.event(Recorder.E_DIG, b.getX(), b.getY(), b.getZ(), code, Double.NaN, Double.NaN, Double.NaN);
                } else if (a == DiggingAction.RELEASE_USE_ITEM) r.flag(Recorder.F_USE);
            } else if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
                WrapperPlayClientPlayerBlockPlacement w = new WrapperPlayClientPlayerBlockPlacement(e);
                Vector3i b = w.getBlockPosition();
                r.flag(Recorder.F_USE);
                r.event(Recorder.E_USE_ON_BLOCK, b.getX(), b.getY(), b.getZ(), w.getFaceId(), Double.NaN, Double.NaN, Double.NaN);
            } else if (type == PacketType.Play.Client.USE_ITEM) {
                r.flag(Recorder.F_USE);
            } else if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
                r.slot = new WrapperPlayClientHeldItemChange(e).getSlot();
            } else if (type == PacketType.Play.Client.PLAYER_INPUT) {
                WrapperPlayClientPlayerInput w = new WrapperPlayClientPlayerInput(e);
                r.setFlag(Recorder.F_FWD, w.isForward()); r.setFlag(Recorder.F_BACK, w.isBackward()); r.setFlag(Recorder.F_LEFT, w.isLeft());
                r.setFlag(Recorder.F_RIGHT, w.isRight()); r.setFlag(Recorder.F_JUMP, w.isJump()); r.setFlag(Recorder.F_SNEAK, w.isShift());
            } else if (type == PacketType.Play.Client.ENTITY_ACTION) {
                WrapperPlayClientEntityAction.Action a = new WrapperPlayClientEntityAction(e).getAction();
                if (a == WrapperPlayClientEntityAction.Action.START_SPRINTING) r.setFlag(Recorder.F_SPRINT, true);
                else if (a == WrapperPlayClientEntityAction.Action.STOP_SPRINTING) r.setFlag(Recorder.F_SPRINT, false);
                else if (a == WrapperPlayClientEntityAction.Action.START_SNEAKING) r.setFlag(Recorder.F_SNEAK, true);
                else if (a == WrapperPlayClientEntityAction.Action.STOP_SNEAKING) r.setFlag(Recorder.F_SNEAK, false);
            } else return;
        } catch (RuntimeException ex) {
            getLogger().fine("packet record error: " + ex);
        }
        packets.increment(); nanos.add(System.nanoTime() - t0);
    }

    private void attack(Recorder r, int eid) {
        r.flag(Recorder.F_ATTACK);
        Recorder tgt = byEntity.get(eid);
        if (tgt != null && tgt.lvalid) r.event(Recorder.E_ATTACK, eid, tgt.rid, 0, 0, tgt.lx, tgt.ly, tgt.lz);
        else r.event(Recorder.E_ATTACK, eid, -1, 0, 0, Double.NaN, Double.NaN, Double.NaN);
    }

    @Override public void onPacketSend(PacketSendEvent e) {
        if (e.getPacketType() != PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) return;
        UUID id = e.getUser().getUUID();
        if (id == null) return;
        Recorder r = online.get(id);
        if (r == null) return;
        r.pendingFlags.getAndUpdate(v -> v | Recorder.F_TELEPORT);
        r.event(Recorder.E_TELEPORT, 0, 0, 0, 0, Double.NaN, Double.NaN, Double.NaN);
    }

    // ------------------------------------------------------------------ ClipService.Host

    @Override public Collection<Recorder> recorders() {
        List<Recorder> l = new ArrayList<>(online.values()); l.addAll(recent.values()); return l;
    }

    @Override public Recorder find(String name) {
        for (Recorder r : online.values()) if (r.name.equalsIgnoreCase(name)) return r;
        Recorder best = null;
        for (Recorder r : recent.values()) if (r.name.equalsIgnoreCase(name) && (best == null || r.quitNano > best.quitNano)) best = r;
        return best;
    }

    @Override public void runLos(String worldName, double x, double z, List<Analyzer.LosQuery> q, Consumer<byte[]> done) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) { done.accept(new byte[q.size()]); return; }
        byte[] res = new byte[q.size()];
        int cx = (int) Math.floor(x) >> 4, cz = (int) Math.floor(z) >> 4;
        Bukkit.getRegionScheduler().execute(this, w, cx, cz, () -> losStep(w, cx, cz, q, res, 0, done));
    }

    private void losStep(World w, int cx, int cz, List<Analyzer.LosQuery> q, byte[] res, int from, Consumer<byte[]> done) {
        int to = Math.min(q.size(), from + settings.losPerTick);
        long t0 = System.nanoTime();
        for (int k = from; k < to; k++) res[k] = Los.check(w, q.get(k));
        long dt = System.nanoTime() - t0; losNanos.add(dt); losSteps.increment(); losMax.accumulateAndGet(dt, Math::max);
        if (to >= q.size() || !isEnabled()) { done.accept(res); return; }
        Bukkit.getRegionScheduler().runDelayed(this, w, cx, cz, t -> losStep(w, cx, cz, q, res, to, done), 1L);
    }

    @Override public void saved(ClipService.Pending p, ClipService.IndexEntry e) {
        Component msg = Component.text("[리플레이] ", ReplaySession.ACCENT).append(Component.text(e.suspect() + " 클립 저장됨 (" + e.id() + ") ", NamedTextColor.WHITE))
            .append(ReplayCmd.view(e.id())).append(Component.space()).append(ReplayCmd.sum(e.id()))
            .append(Component.newline()).append(Component.text(e.headline(), NamedTextColor.GRAY));
        List<String> to = new ArrayList<>();
        synchronized (p) { for (Clip.Reason r : p.reasons) if (r.source().startsWith("op:")) to.add(r.source().substring(3)); }
        for (Player pl : Bukkit.getOnlinePlayers()) {
            boolean staff = pl.hasPermission(ReplayCmd.PERM);
            if (!staff) continue;
            if (to.contains(pl.getName()) || (!p.manual && settings.notifyStaffOnAutoMark)) pl.getScheduler().run(this, t -> pl.sendMessage(msg), null);
        }
    }

    // ------------------------------------------------------------------ viewing

    void startView(Player viewer, String id) {
        ClipService.IndexEntry e = service.entry(id);
        if (e == null) { viewer.sendMessage(Component.text("클립을 찾을 수 없습니다: " + id, NamedTextColor.RED)); return; }
        viewer.sendMessage(Component.text("[리플레이] 클립을 불러오는 중입니다...", ReplaySession.SOFT));
        service.io.execute(() -> {
            Clip c; Analyzer an;
            try { c = Clip.read(service.file(e)); an = new Analyzer(c, settings.nearbyRadius); }
            catch (Throwable ex) {
                getLogger().log(Level.WARNING, "clip read failed " + id, ex);
                viewer.getScheduler().run(this, t -> viewer.sendMessage(Component.text("클립을 읽지 못했습니다: " + ex.getMessage(), NamedTextColor.RED)), null);
                return;
            }
            BlockTimeline bt = null; String note;
            if (!settings.worldHistory) note = "설정에서 꺼짐";
            else if (!coreProtect()) note = "CoreProtect 없음";
            else {
                viewer.getScheduler().run(this, t -> viewer.sendMessage(Component.text("[리플레이] 당시 블록 기록을 불러오는 중입니다...", ReplaySession.SOFT)), null);
                try { bt = WorldHistory.build(c, settings, getLogger()); note = bt.note; }
                catch (Throwable ex) { getLogger().log(Level.WARNING, "world history failed " + id, ex); note = "블록 기록 조회 실패"; }
            }
            final BlockTimeline fbt = bt; final String fnote = note;
            viewer.getScheduler().run(this, t -> openSession(viewer, c, an, fbt, fnote), null);
        });
    }

    /** CoreProtect present and enabled (checked without loading CoreProtect classes when it is missing). */
    private boolean coreProtect() {
        org.bukkit.plugin.Plugin pl = getServer().getPluginManager().getPlugin("CoreProtect");
        if (pl == null || !pl.isEnabled()) return false;
        try { return WorldHistory.available(); } catch (Throwable t) { return false; }
    }

    private void openSession(Player viewer, Clip c, Analyzer an, BlockTimeline bt, String note) {
        if (!viewer.isOnline()) return;
        World w = Bukkit.getWorld(c.worlds.isEmpty() ? "" : c.worlds.get(c.tracks.get(0).world[0]));
        if (w == null) { viewer.sendMessage(Component.text("클립의 월드를 찾을 수 없습니다.", NamedTextColor.RED)); return; }
        ReplaySession old = sessions.remove(viewer.getUniqueId());
        SavedState st;
        if (old != null) { old.ended = true; if (old.task != null) old.task.cancel(); old.cleanupPackets(); st = old.saved; }
        else {
            org.bukkit.Location l = viewer.getLocation();
            st = new SavedState(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), viewer.getGameMode().name(), viewer.getAllowFlight(), viewer.isFlying());
            writeRestore(viewer.getUniqueId(), st);
        }
        ReplaySession s = new ReplaySession(this, viewer, c, an, st, bt, note);
        sessions.put(viewer.getUniqueId(), s);
        if (viewer.getGameMode() != GameMode.SPECTATOR) viewer.setGameMode(GameMode.SPECTATOR);
        int first = 0;
        viewer.teleportAsync(s.viewPoint(w, first)).thenAccept(ok -> viewer.getScheduler().run(this, t -> {
            if (sessions.get(viewer.getUniqueId()) == s && !s.ended) s.begin();
        }, null));
    }

    ReplaySession session(Player p) { return sessions.get(p.getUniqueId()); }

    /** Called by the session on its viewer's thread (restore=true) or on quit/disable (restore=false). */
    void sessionEnded(ReplaySession s, boolean restore, String msg) {
        sessions.remove(s.viewer.getUniqueId(), s);
        if (!restore || !s.viewer.isOnline()) return;
        Player v = s.viewer;
        restore(v, s.saved, () -> {
            deleteRestore(v.getUniqueId());
            reshow(v, s);
            v.sendMessage(Component.text(msg != null ? msg : "[리플레이] 종료했습니다. 원래 자리와 게임 모드로 돌아왔습니다.", ReplaySession.SOFT));
        });
    }

    /** Today's entities hidden during the replay that are still in the viewer's tracking range were never re-sent by the
     *  server (it thinks the viewer has them): hide and show them through the API so the client gets them again. Others come
     *  back by themselves (the tracker sends them when they enter range again). Viewer thread, after the restore teleport. */
    private void reshow(Player v, ReplaySession s) {
        if (s.hiddenLive.isEmpty()) return;
        List<Entity> back = new ArrayList<>();
        try {
            for (Entity e : v.getWorld().getNearbyEntities(v.getLocation(), 160, 400, 160))
                if (e != v && s.hiddenLive.contains(e.getEntityId())) back.add(e);
        } catch (RuntimeException ignored) { }
        for (Entity e : back) { try { v.hideEntity(this, e); } catch (RuntimeException ignored) { } }
        v.getScheduler().runDelayed(this, t -> { for (Entity e : back) { try { v.showEntity(this, e); } catch (RuntimeException ignored) { } } }, null, 2L);
        s.hiddenLive.clear();
    }

    private void restore(Player v, SavedState st, Runnable after) {
        GameMode gm; try { gm = GameMode.valueOf(st.gameMode()); } catch (Throwable ex) { gm = GameMode.SURVIVAL; }
        World w = Bukkit.getWorld(st.world());
        org.bukkit.Location loc = w == null ? Bukkit.getWorlds().get(0).getSpawnLocation() : new org.bukkit.Location(w, st.x(), st.y(), st.z(), st.yaw(), st.pitch());
        final GameMode fgm = gm;
        v.teleportAsync(loc).thenAccept(ok -> v.getScheduler().run(this, t -> {
            v.setGameMode(fgm);
            if (st.allowFlight()) { v.setAllowFlight(true); v.setFlying(st.flying()); }
            after.run();
        }, null));
    }

    private void writeRestore(UUID id, SavedState st) {
        Properties p = new Properties();
        p.setProperty("world", st.world()); p.setProperty("x", Double.toString(st.x())); p.setProperty("y", Double.toString(st.y()));
        p.setProperty("z", Double.toString(st.z())); p.setProperty("yaw", Float.toString(st.yaw())); p.setProperty("pitch", Float.toString(st.pitch()));
        p.setProperty("gameMode", st.gameMode()); p.setProperty("allowFlight", Boolean.toString(st.allowFlight())); p.setProperty("flying", Boolean.toString(st.flying()));
        try (var w = Files.newBufferedWriter(restoreDir.resolve(id + ".properties"), StandardCharsets.UTF_8)) { p.store(w, "RewindReplay viewer state before a replay"); }
        catch (IOException ex) { getLogger().warning("restore file write failed: " + ex); }
    }

    private void deleteRestore(UUID id) { try { Files.deleteIfExists(restoreDir.resolve(id + ".properties")); } catch (IOException ignored) { } }

    private void restoreFromFile(Player p, Path f) {
        if (!p.isOnline() || sessions.containsKey(p.getUniqueId())) return;
        try (var r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            Properties pr = new Properties(); pr.load(r);
            SavedState st = new SavedState(pr.getProperty("world"), Double.parseDouble(pr.getProperty("x")), Double.parseDouble(pr.getProperty("y")),
                Double.parseDouble(pr.getProperty("z")), Float.parseFloat(pr.getProperty("yaw")), Float.parseFloat(pr.getProperty("pitch")),
                pr.getProperty("gameMode"), Boolean.parseBoolean(pr.getProperty("allowFlight")), Boolean.parseBoolean(pr.getProperty("flying")));
            restore(p, st, () -> { deleteRestore(p.getUniqueId()); p.sendMessage(Component.text("[리플레이] 지난 리플레이 전 위치와 게임 모드로 돌려놓았습니다.", ReplaySession.SOFT)); });
        } catch (Throwable ex) { getLogger().warning("restore failed for " + p.getName() + ": " + ex); }
    }

    // ------------------------------------------------------------------ summary / status

    void printSummary(CommandSender to, Clip c) {
        JsonObject s = c.summary;
        to.sendMessage(Component.text("[리플레이] " + c.id + " · " + c.suspectName + " 요약", ReplaySession.ACCENT));
        if (s.has("headline")) to.sendMessage(Component.text(s.get("headline").getAsString(), NamedTextColor.WHITE));
        for (String k : new String[] {"attacks", "aim", "rotation", "hidden", "clicks", "movement", "flight", "damage", "blocks"}) {
            if (!s.has(k) || !s.get(k).isJsonObject()) continue;
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, JsonElement> en : s.getAsJsonObject(k).entrySet()) {
                if (en.getKey().equals("rule")) continue;   // long definition text: kept in the file, not in chat
                if (sb.length() > 0) sb.append(", ");
                sb.append(en.getKey()).append('=').append(en.getValue().isJsonPrimitive() ? en.getValue().getAsString() : en.getValue().toString());
            }
            to.sendMessage(Component.text(label(k) + ": ", ReplaySession.SOFT).append(Component.text(sb.toString(), NamedTextColor.GRAY)));
        }
        // review flags last, so they are the lines staff see at the bottom of the chat
        if (s.has("flags") && s.get("flags").isJsonArray()) for (JsonElement f : s.getAsJsonArray("flags"))
            to.sendMessage(Component.text("주의: " + f.getAsString() + " (활공·급류·탑승·비행 허용·물속이 아닌데 공중에서 초속 10칸 넘게 1초 이상)", NamedTextColor.RED));
    }

    private static String label(String k) {
        return switch (k) {
            case "attacks" -> "공격"; case "aim" -> "조준"; case "rotation" -> "회전"; case "hidden" -> "가려진 대상";
            case "clicks" -> "클릭"; case "movement" -> "이동"; case "flight" -> "활공·자세"; case "damage" -> "피해"; case "blocks" -> "블록"; default -> k;
        };
    }

    List<String> status() {
        List<String> l = new ArrayList<>();
        Settings s = settings;
        long per = Recorder.bytesPerRecorder(s.frameCap(), s.eventCap, s.entityCap());
        double secs = (System.nanoTime() - statsSince) / 1e9;
        long pk = packets.sum(), ns = nanos.sum();
        l.add(String.format(Locale.ROOT, "기록 중 %d명 + 최근 퇴장 %d명 · 버퍼 %d KiB/명 (합계 %.1f MiB, 60명 기준 %.1f MiB)", online.size(), recent.size(), per / 1024,
            (online.size() + recent.size()) * per / 1048576.0, 60 * per / 1048576.0));
        l.add(String.format(Locale.ROOT, "패킷 %d개 · 평균 %.0f ns/패킷 · 넷티 CPU %.4f%% of one core (%.0f초 동안)", pk, pk > 0 ? ns / (double) pk : 0, secs > 0 ? ns / (secs * 1e9) * 100 : 0, secs));
        long pr = poseRuns.sum(), ir = itemRuns.sum();
        l.add(String.format(Locale.ROOT, "엔티티 스레드 표본: 자세 %d회 평균 %.2f µs · 핫바+장비 %d회 평균 %.2f µs (핫바만 %.2f µs = 1.0.x 작업) · 합계 %.4f%% of one core",
            pr, pr > 0 ? poseNanos.sum() / 1000.0 / pr : 0, ir, ir > 0 ? itemNanos.sum() / 1000.0 / ir : 0, ir > 0 ? hotbarNanos.sum() / 1000.0 / ir : 0,
            secs > 0 ? (poseNanos.sum() + itemNanos.sum()) / (secs * 1e9) * 100 : 0));
        long er = entityRuns.sum(); long rows = 0; for (Recorder r : online.values()) rows += r.entityRows;
        l.add(String.format(Locale.ROOT, "주변 엔티티 표본(1.2.0): %d회 평균 %.2f µs · %.4f%% of one core · 지금 기록 중인 행 누계 %d (반경 %.0f, %d틱마다, 최대 %d개)",
            er, er > 0 ? entityNanos.sum() / 1000.0 / er : 0, secs > 0 ? entityNanos.sum() / (secs * 1e9) * 100 : 0, rows, s.entityRadius, s.entityIntervalTicks, s.entityMaxPerSample));
        long ls = losSteps.sum();
        l.add(String.format(Locale.ROOT, "시야 검사(지역 스레드) %d틱 · 틱당 평균 %.0f µs, 최대 %.0f µs", ls, ls > 0 ? losNanos.sum() / 1000.0 / ls : 0, losMax.get() / 1000.0));
        l.add(String.format(Locale.ROOT, "저장 대기 %d · 저장 %d · 실패 %d · 마지막 저장 %d ms, %d KiB · 클립 %d개 %.1f MiB (보관 %d일, 최대 %d MiB)", service.pending.size(), service.saved, service.failed,
            service.lastFinalizeMs, service.lastWriteBytes / 1024, service.list(null, Integer.MAX_VALUE).size(), service.totalBytes() / 1048576.0, s.retentionDays, s.maxTotalMb));
        StringBuilder vs = new StringBuilder("리플레이 보는 중: " + sessions.size() + "명");
        for (ReplaySession rs : sessions.values()) if (rs.measuredTicks > 0)
            vs.append(String.format(Locale.ROOT, " · %s(%d명 표시) 틱당 평균 %.0f µs, 최대 %.0f µs, 첫 틱 %.0f µs (%d틱)", rs.viewer.getName(), rs.clip.tracks.size(),
                rs.tickNanos / 1000.0 / rs.measuredTicks, rs.maxTickNanos / 1000.0, rs.firstTickNanos / 1000.0, rs.measuredTicks));
        l.add(vs.toString());
        return l;
    }

    Settings settings() { return settings; }

    void resetStats() { entityNanos.reset(); entityRuns.reset(); packets.reset(); nanos.reset(); losNanos.reset(); losSteps.reset(); losMax.set(0); poseNanos.reset(); poseRuns.reset(); itemNanos.reset(); itemRuns.reset(); hotbarNanos.reset(); statsSince = System.nanoTime(); }

    // ------------------------------------------------------------------ commands

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return ReplayCmd.handle(this, sender, command.getName(), args);
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return ReplayCmd.complete(this, sender, alias, args);   // the alias decides Korean or English sub-commands
    }

    ClipService service() { return service; }
    Collection<Recorder> onlineRecorders() { return online.values(); }
    static String time(long ms) { return DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ClipService.ZONE).format(Instant.ofEpochMilli(ms)); }
}
