package io.github.jayden0903.replay;

import org.bukkit.configuration.file.FileConfiguration;

/** Immutable-after-load settings (config.yml). */
final class Settings {
    int bufferSeconds = 60;          // history kept per player
    int afterSeconds = 30;           // recording continues this long after a mark
    int frameSlackPercent = 25;      // extra frames for bursty clients (timer cheats shorten the history instead)
    int eventCap = 1024;             // events per player (attacks, digs, hits, damage...)
    double nearbyRadius = 32;        // other players included when they came this close to the suspect
    int recentQuitSeconds = 120;     // a player who left can still be marked this long after quitting
    int maxRecentQuit = 40;
    int consoleCooldownSeconds = 120; // console/plugin marks for the same player inside this window join the same clip
    int maxClipsPerHour = 60;        // global safety valve for automatic marks
    int maxPending = 12;
    int retentionDays = 30;
    long maxTotalMb = 2048;
    boolean los = true;              // line-of-sight check (region thread, bounded) for the hidden-target metrics
    int losMaxCandidates = 2400;
    int losPerTick = 150;
    boolean notifyStaffOnAutoMark = false;
    boolean excludeSpectators = true; // spectators/staff viewing replays are not recorded as nearby players
    // 1.2.0: the world around the players, so a replay shows the past and not today's world
    boolean entities = true;         // record mobs, dropped items, projectiles, boats/minecarts, TNT, falling blocks near each player
    double entityRadius = 24;        // blocks around each player
    int entityIntervalTicks = 2;     // one sample every N server ticks (2 = 10 per second)
    int entityMaxPerSample = 32;     // nearest N entities per sample
    boolean worldHistory = true;     // replay blocks as they were (CoreProtect history, looked up when a clip is opened)
    int worldHistoryRadius = 48;     // blocks around the suspect's path
    int worldHistoryMaxBlocks = 60000;
    boolean hideLiveEntities = true; // the viewer does not see today's mobs/players while watching the past

    int frameCap() { return Math.max(200, bufferSeconds * 20 * (100 + frameSlackPercent) / 100); }
    /** Entity rows per player: one buffer of samples at the densest allowed rate (denser areas shorten the entity history). */
    int entityCap() { return entities ? Math.max(256, bufferSeconds * (20 / entityIntervalTicks) * entityMaxPerSample) : 0; }

    static Settings load(FileConfiguration c) {
        Settings s = new Settings();
        s.bufferSeconds = clamp(c.getInt("buffer-seconds", s.bufferSeconds), 10, 180);
        s.afterSeconds = clamp(c.getInt("after-seconds", s.afterSeconds), 0, 120);
        s.frameSlackPercent = clamp(c.getInt("frame-slack-percent", s.frameSlackPercent), 0, 200);
        s.eventCap = clamp(c.getInt("event-cap", s.eventCap), 64, 8192);
        s.nearbyRadius = Math.max(4, Math.min(96, c.getDouble("nearby-radius", s.nearbyRadius)));
        s.recentQuitSeconds = clamp(c.getInt("recent-quit-seconds", s.recentQuitSeconds), 0, 600);
        s.maxRecentQuit = clamp(c.getInt("max-recent-quit", s.maxRecentQuit), 0, 200);
        s.consoleCooldownSeconds = clamp(c.getInt("console-cooldown-seconds", s.consoleCooldownSeconds), 0, 3600);
        s.maxClipsPerHour = clamp(c.getInt("max-clips-per-hour", s.maxClipsPerHour), 1, 1000);
        s.maxPending = clamp(c.getInt("max-pending", s.maxPending), 1, 64);
        s.retentionDays = clamp(c.getInt("retention-days", s.retentionDays), 1, 365);
        s.maxTotalMb = Math.max(16, c.getLong("max-total-mb", s.maxTotalMb));
        s.los = c.getBoolean("line-of-sight.enabled", s.los);
        s.losMaxCandidates = clamp(c.getInt("line-of-sight.max-candidates", s.losMaxCandidates), 0, 20000);
        s.losPerTick = clamp(c.getInt("line-of-sight.per-tick", s.losPerTick), 10, 2000);
        s.notifyStaffOnAutoMark = c.getBoolean("notify-staff-on-auto-mark", s.notifyStaffOnAutoMark);
        s.excludeSpectators = c.getBoolean("exclude-spectators", s.excludeSpectators);
        s.entities = c.getBoolean("entities.enabled", s.entities);
        s.entityRadius = Math.max(4, Math.min(64, c.getDouble("entities.radius", s.entityRadius)));
        s.entityIntervalTicks = clamp(c.getInt("entities.interval-ticks", s.entityIntervalTicks), 1, 10);
        s.entityMaxPerSample = clamp(c.getInt("entities.max-per-sample", s.entityMaxPerSample), 1, 128);
        s.worldHistory = c.getBoolean("world-history.enabled", s.worldHistory);
        s.worldHistoryRadius = clamp(c.getInt("world-history.radius", s.worldHistoryRadius), 8, 128);
        s.worldHistoryMaxBlocks = clamp(c.getInt("world-history.max-blocks", s.worldHistoryMaxBlocks), 100, 500000);
        s.hideLiveEntities = c.getBoolean("hide-live-entities", s.hideLiveEntities);
        return s;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
