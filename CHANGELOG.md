# Changelog

## 1.2.0 (2026-10-16): the world as it was

- Nearby entities are recorded: mobs, dropped items, projectiles, boats and minecarts, primed TNT and falling blocks
  within 24 blocks of each player, 10 samples per second, nearest 32 per sample, sampled on the player's own entity
  thread. Players, armour stands, item frames, paintings and displays are never recorded as entities.
- Replays show those entities as packet-only fakes with their recorded position, rotation, fire, hurt flash and death
  animation. A mob seen by several players in the clip is merged into one entity.
- Blocks are shown as they were: when a clip is opened, CoreProtect's block history around the suspect's path is read
  and turned into a timeline (state at the clip start, then every change at its time). Seeking recomputes the state;
  stopping sends today's blocks back. Nothing extra is recorded for this; without CoreProtect, blocks show today's state.
- Today's mobs and players are hidden from the viewer while watching and shown again afterwards.
- The replay header reports how many entities are replayed and how many block positions were rewound.
- Clip format 3 (`rewind-replay/3`, adds `entities`). Formats 1 and 2 are still read.
- New config keys: `entities.*`, `world-history.*`, `hide-live-entities`.
- Memory per player grows from 132 KiB to 751 KiB (entity ring of 19,200 rows).

## 1.1.0 (2026-10-05): equipment and poses

- Per-frame server pose and state, sampled every tick on the player's entity thread: gliding (elytra), swimming,
  riptide, sleeping, crawling, using an item (main or off hand), blocking, allowed flight, riding, in water.
- Equipment (helmet, chestplate, leggings, boots, off hand) with enchantment glint and armour trims, stored only when it
  changes (checked every 10 ticks and at once when gliding starts or stops).
- Replays show armour, elytra, off-hand items, the elytra glide pose (with body roll from the replayed velocity),
  swimming, crawling, sleeping, riptide spin, eating and shield blocking. Pose-aware eye height and hitbox in the aim
  analysis and the look ray.
- Summary `flight` section and the review flag "airborne fast, not gliding": horizontal speed over 10 blocks/s for more
  than 1 s in the air with no glide, riptide, vehicle, allowed flight, water or recent knockback to explain it. Server
  teleports (anticheat setbacks) cannot hide a run.
- Control `at <seconds>` (jump to an exact time and pause). Fakes moved into chunks the viewer had not loaded yet are
  re-sent after a seek.
- Arm swings on 26.3+ servers (punch packet / swing animation packet) are recorded and replayed.
- Clip format 2 (adds `state`, `equip`, `trims`). Format 1 clips are still read.

## 1.0.0 (2026-09-27): ring buffers and aim analysis

- Always-on recording of every online player into fixed-size ring buffers (60 s at client tick rate, 116 KiB per
  player): position, exact yaw/pitch as received, ground state, sprint/sneak, movement keys, swings, attacks with the
  target's position, digging, block break/place, damage, held item, server teleports. Packets are read by a PacketEvents
  MONITOR listener and never cancelled or modified. No chat and no IP addresses are recorded.
- Flood guard: empty frames under 40 ms apart are dropped; above 30 frames/s (burst 60) frames are merged, so a timer
  cheat shortens the covered time instead of growing memory.
- Marks from staff (`/replay mark`) or the console and other plugins (`rewindreplay mark`) freeze 60 s before and 30 s
  after into a gzip JSON clip with every player who came within 32 blocks. Players who quit up to 2 minutes earlier can
  still be marked. Cooldown and hourly cap for automatic marks, retention by age and total size.
- Aim analysis summary: attack aim error, on-hitbox rate, reach, snaps into the attack and snap-backs (silent aura),
  attacks in the tick the crosshair entered the hitbox (triggerbot), time spent tracking a target hidden behind opaque
  blocks (ESP, line of sight checked on the owning region thread), rotation speed, clicks, movement speed.
- In-game viewer for one staff member: spectator mode at the scene, packet-only fake players with skins, a look ray
  from the exact float yaw/pitch, glowing aimed-at targets, boss bar and action bar with exact numbers, chat-button
  controls (pause, seek, single-tick steps, 0.25x to 4x, first person, summary). The viewer's position and game mode are
  restored on stop, quit or server restart.
