// Lab scenario behind the README screenshots (offline-mode test server only, never a public server).
//   npm install mineflayer vec3
//   HOST=127.0.0.1 PORT=25565 CONSOLE_FIFO=/path/to/console.fifo node scenario.js
// CONSOLE_FIFO is a named pipe whose lines are fed to the server console (e.g. `tail -f fifo | java -jar server.jar`).
// The bot needs a client version the server accepts (here 1.21.11 through ViaVersion/ViaBackwards); RewindReplay,
// PacketEvents and CoreProtect must be installed, with `after-seconds: 8` in RewindReplay's config.yml.
//
// "Sus" stands next to a stone-brick wall with pigs, a cow, a zombie and a dropped diamond around, breaks wall blocks one
// by one and places gold blocks, then a TNT blows a hole in a second wall. The clip is marked, and AFTER the clip the world
// is changed (more of the wall dug out, one more gold block, every mob killed), so a correct replay must show the past,
// not today's world. A staff client then runs /replay list and [보기] (view).
const mineflayer = require('mineflayer');
const { Vec3 } = require('vec3');
const fs = require('fs');
const HOST = process.env.HOST || '127.0.0.1', PORT = Number(process.env.PORT || 25565), FIFO = process.env.CONSOLE_FIFO;
if (!FIFO) { console.error('set CONSOLE_FIFO'); process.exit(1); }
const c = cmd => fs.appendFileSync(FIFO, cmd + '\n');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log(new Date().toISOString().slice(11, 23), ...a);

(async () => {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: 'Sus', version: '1.21.11', auth: 'offline' });
  bot.on('kicked', r => log('KICKED', JSON.stringify(r).slice(0, 200)));
  bot.on('error', e => log('ERR', e.message));
  await new Promise(r => bot.once('spawn', r));
  await sleep(2000);
  // stage (console changes are NOT in CoreProtect: they are "how the world was" before the clip)
  c('kill @e[type=!player]');
  c('fill -8 -60 -8 16 -50 16 minecraft:air');
  c('fill 4 -60 -2 4 -57 2 minecraft:stone_bricks');          // wall A (broken by hand)
  c('fill -6 -60 -2 -6 -57 2 minecraft:polished_andesite');   // wall B (TNT)
  c('gamemode survival Sus'); c('effect give Sus minecraft:resistance infinite 4 true');
  c('tp Sus 2.5 -60 0.5 -90 0');
  c('clear Sus'); c('give Sus minecraft:netherite_pickaxe');
  c('give Sus minecraft:gold_block 16');
  await sleep(3000);
  c('summon minecraft:pig 0 -60 4'); c('summon minecraft:pig 1 -60 -4'); c('summon minecraft:cow -2 -60 5');
  c('summon minecraft:zombie 0 -60 -6 {PersistenceRequired:1b}');
  c('summon minecraft:item 1 -60 2 {Item:{id:"minecraft:diamond",count:3},PickupDelay:32767s}');
  log('staged; recording starts');
  await sleep(6000);
  const pick = bot.inventory.items().find(i => i.name === 'netherite_pickaxe'); if (pick) await bot.equip(pick, 'hand');
  // break wall A blocks one by one (CoreProtect logs these breaks)
  for (const [y, z] of [[-60, 0], [-59, 0], [-60, 1], [-59, 1], [-58, 0], [-60, -1]]) {
    const b = bot.blockAt(new Vec3(4, y, z));
    if (b && b.name !== 'air') { try { await bot.lookAt(b.position.offset(0.5, 0.5, 0.5)); await bot.dig(b, true); log('dug', 4, y, z); } catch (e) { log('dig', e.message); } }
    await sleep(800);
  }
  // place gold blocks on the floor in front of the wall
  const gold = bot.inventory.items().find(i => i.name === 'gold_block'); if (gold) await bot.equip(gold, 'hand');
  for (const z of [-1, 0, 1]) {
    const ref = bot.blockAt(new Vec3(3, -61, z));
    try { await bot.lookAt(new Vec3(3.5, -60, z + 0.5)); await bot.placeBlock(ref, new Vec3(0, 1, 0)); log('placed gold', 3, -60, z); } catch (e) { log('place', e.message); }
    await sleep(800);
  }
  await sleep(2000);
  c('summon minecraft:tnt -5 -60 0 {fuse:20s}');             // blows a hole in wall B (CoreProtect logs #tnt)
  log('tnt');
  await sleep(5000);
  c('rewindreplay mark Sus lab-1.2.0');
  log('marked');
  await sleep(12000);                                       // 8 s after-seconds + save
  // the world AFTER the clip: must not show up in the replay
  // dig the top of wall A and put a gold block nearby (CoreProtect logs these too, after the clip)
  const pk2 = bot.inventory.items().find(i => i.name === 'netherite_pickaxe'); if (pk2) await bot.equip(pk2, 'hand');
  for (const [y, z] of [[-57, -2], [-57, -1], [-57, 0], [-57, 1], [-57, 2]]) {
    const b = bot.blockAt(new Vec3(4, y, z));
    if (b && b.name !== 'air') { try { await bot.lookAt(b.position.offset(0.5, 0.5, 0.5)); await bot.dig(b, true); log('after-clip dug', 4, y, z); } catch (e) { log('dig', e.message); } }
    await sleep(500);
  }
  const g2 = bot.inventory.items().find(i => i.name === 'gold_block'); if (g2) await bot.equip(g2, 'hand');
  try { await bot.placeBlock(bot.blockAt(new Vec3(2, -61, 2)), new Vec3(0, 1, 0)); log('after-clip gold', 2, -60, 2); } catch (e) { log('place', e.message); }
  c('kill @e[type=!player]');
  log('world changed after the clip');
  await sleep(1500);
  bot.quit();
  log('done');
})();
