// Acceptance tests: bots on a fresh throwaway server, 127.0.0.1 only, with made-up worlds and groups.
// Every "blocked" result has a positive control next to it, so a test can't pass just because nothing works.
import { chmodSync, existsSync, readFileSync, readdirSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import path from 'node:path'
import { freshServer, writeConfig, Server, bot, sleep, check, summary, PAPER, STORAGE, results } from './harness.mjs'

console.log(`# Paper ${PAPER}, LuckPerms ${STORAGE} storage`)
const dir = freshServer('server')
writeConfig(dir)
let s = new Server(dir)
const SLOT = { young: 10, grown: 11, builders: 12, young_sky: 28, grown_sky: 29, CANCEL: 45, CONFIRM: 53 }
const WORLDS = ['young_explore', 'grown_explore', 'workshop', 'grown_sky']
const ACCESS = { young: ['young_explore'], grown: ['grown_explore'], builders: ['workshop'], grown_sky: ['grown_sky'], young_sky: [] }
const bots = []
const join = async name => { const b = await bot(name); bots.push(b); return b }
const quit = async b => { if (!b) return; b.quit(); await sleep(800) }
const changesLog = () => existsSync(path.join(dir, 'plugins/Stationmaster/changes.log'))
  ? readFileSync(path.join(dir, 'plugins/Stationmaster/changes.log'), 'utf8').trim().split('\n').filter(Boolean).map(l => { try { return JSON.parse(l) } catch { return { unreadable: l } } }) : []
const spy = (from, re) => s.log.slice(from).filter(l => /SPY /.test(l) && re.test(l))
const whitelist = () => JSON.parse(readFileSync(path.join(dir, 'whitelist.json'), 'utf8'))
const offlineUuid = name => {
  // The same as UUID.nameUUIDFromBytes("OfflinePlayer:" + name): MD5, version 3, IETF variant.
  const h = createHash('md5').update('OfflinePlayer:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30; h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex'); return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}

async function inWorld(name, world, ms = 6000) {
  const key = world === 'world' ? 'minecraft:overworld' : 'minecraft:' + world
  const end = Date.now() + ms
  while (Date.now() < end) {
    if ((await s.ask(`execute in ${key} if entity @a[name=${name},distance=0..]`, 250)).some(l => /Test passed/.test(l))) return true
    await sleep(250)
  }
  return false
}
/** The bot tries to walk into a world with Multiverse's own /mvtp, which enforces multiverse.access.<world>. */
async function canEnter(b, world) {
  b.chat('/mvtp ' + world)
  const ok = await inWorld(b.username, world, 3000)
  if (ok) { await s.ask(`mvtp ${b.username} world`, 1200) }
  return ok
}
async function openWorlds(b) {
  const open = []
  for (const w of WORLDS) if (await canEnter(b, w)) open.push(w)
  return open
}
const expected = profiles => WORLDS.filter(w => profiles.some(p => (ACCESS[p] ?? []).includes(w)))
const same = (a, b) => JSON.stringify([...a].sort()) === JSON.stringify([...b].sort())

function nextWindow(b, ms = 8000) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => { b.removeListener('windowOpen', on); reject(new Error('no window opened')) }, ms)
    const on = w => { clearTimeout(t); resolve(w) }
    b.once('windowOpen', on)
  })
}
async function openMenu(b, cmd) { const p = nextWindow(b); b.chat(cmd); const w = await p; await sleep(400); return w }
async function click(b, slot) { rawClick(b, slot, 0, 0); await sleep(400) }
/** A hand-made click packet, so the server sees exactly what a modified client could send. */
function rawClick(b, slot, mouseButton, mode, extra = {}) {
  const w = b.currentWindow
  b._client.write('window_click', { windowId: w ? w.id : 0, stateId: w?.stateId ?? 0, slot, mouseButton, mode, changedSlots: [], cursorItem: undefined, ...extra })
}
const itemAt = (b, slot) => b.currentWindow?.slots[slot]?.name
async function count(name, item) {
  const out = (await s.ask(`clear ${name} ${item} 0`, 500)).join(' ')
  const m = out.match(/Found (\d+) matching item/)
  return m ? Number(m[1]) : 0
}
async function profilesOf(nameOrUuid) {
  const r = await s.sm('show', nameOrUuid)
  return r.status === 'ok' ? r.profiles : ['<' + r.code + '>']
}

try {
  // ---------- Setup ----------
  console.log('\n# Setup: made-up worlds and groups')
  await s.start()
  check(`control: the server really is Minecraft ${PAPER}`, s.log.some(l => l.includes('Starting minecraft server version ' + PAPER)))
  const lpFiles = readdirSync(path.join(dir, 'plugins/LuckPerms'))
  check(`control: LuckPerms really uses ${STORAGE} storage`, STORAGE === 'h2'
    ? lpFiles.some(f => /\.mv\.db$/.test(f)) && !lpFiles.includes('yaml-storage')
    : lpFiles.includes('yaml-storage'), lpFiles.join(','))
  for (const w of WORLDS) await s.ask(`mv create ${w} normal --world-type flat --no-structures`, 6000)
  await s.ask('mv config enforce-access true', 800)
  for (const g of ['young', 'grown', 'builders', 'young_sky', 'grown_sky', 'admin', 'moderator', 'owner']) await s.ask('lp creategroup ' + g, 400)
  await s.ask('lp group owner parent add admin', 400)
  for (const [g, ws] of Object.entries(ACCESS)) for (const w of ws) await s.ask(`lp group ${g} permission set multiverse.access.${w} true`, 300)
  for (const perm of ['multiverse.access.world', 'multiverse.teleport.self.w', 'multiverse.teleport.self.*']) await s.ask('lp group default permission set ' + perm + ' true', 300)
  for (const a of ['AdminA', 'AdminB', 'Pleb']) await s.ask('whitelist add ' + a, 300)
  const reload = (await s.ask('stationmaster reload', 1200)).join(' ')
  check('setup: all five example profiles load once their groups exist', /Reloaded: 5 profiles/.test(reload), reload.slice(0, 200))

  const adminA = await join('AdminA'), adminB = await join('AdminB'), pleb = await join('Pleb')
  for (const a of ['AdminA', 'AdminB']) await s.ask(`lp user ${a} permission set stationmaster.approve true`, 500)
  await sleep(1000)

  // ---------- Console interface ----------
  console.log('\n# The console interface for Leaf Juice')
  const prof = await s.sm('profiles')
  check('profiles lists what can be offered, with circles and confirmations', prof.status === 'ok' && prof.profiles.length === 5
    && prof.profiles.find(p => p.id === 'grown_sky')?.confirm && prof.profiles.find(p => p.id === 'young')?.circle === 'young', JSON.stringify(prof).slice(0, 200))
  check('every request gets exactly one result line', prof.hits === 1)
  check('restricted groups are listed', same(prof.restricted, ['admin', 'owner', 'moderator']))
  const badReq = (await s.ask('stationmaster apply has!bad x y', 800)).join(' ')
  check('a bad request ID is answered with an error line, not silence', /stationmaster-result - .*bad_request/.test(badReq))

  // ---------- Strangers ----------
  console.log('\n# Strangers stay out, and are remembered')
  let from = s.log.length
  let kicked = await join('Stranger1').then(() => false, e => /whitelist|not white/i.test(e.message) || /kicked/.test(e.message))
  check('control: a stranger is turned away by the whitelist', kicked)
  await join('Stranger1').catch(() => {})
  const att = await s.sm('attempts')
  const st1 = att.attempts.find(a => a.name === 'Stranger1')
  check('the attempt is listed for Leaf Juice, with a count and no duplicate', st1 && st1.attempts === 2 && att.attempts.filter(a => a.name === 'Stranger1').length === 1, JSON.stringify(att.attempts))
  check('a JoinAttemptEvent fired once, on the main thread, despite two tries', spy(from, /SPY attempt name=Stranger1 .*main=true/).length === 1, spy(from, /attempt/).join(' | '))

  // ---------- Approving with each single profile, with several and with none ----------
  console.log('\n# Approving: each profile alone, several, and none')
  const cases = [['P_young', ['young']], ['P_grown', ['grown']], ['P_build', ['builders']], ['P_gsky', ['grown_sky']],
    ['P_several', ['young', 'builders', 'young_sky']], ['P_none', []]]
  for (const [name, profiles] of cases) {
    const uuid = offlineUuid(name)
    from = s.log.length
    const confirm = profiles.includes('grown_sky') ? ['confirm=confirm:grown_sky'] : []
    const r = await s.sm('apply', uuid, name, ...profiles.map(p => '+' + p), ...confirm, 'via=leaf-juice')
    check(`${name}: approved (${profiles.join('+') || 'no profile'})`, r.status === 'ok' && r.newlyWhitelisted === true, r.message)
    const b = await join(name)
    const open = await openWorlds(b)
    check(`${name}: exactly the right worlds open`, same(open, expected(profiles)), `open: ${open.join(',') || 'none'}; expected: ${expected(profiles).join(',') || 'none'}`)
    await quit(b)
  }
  const doneLines = changesLog().filter(e => e.event === 'done' && e.via === 'leaf-juice')
  check('Leaf Juice changes are logged as console actions from Leaf Juice', doneLines.length === cases.length && doneLines.every(e => e.actor === 'Console' && e.time), String(doneLines.length))
  check('a ProfilesChangedEvent fired for an approval, with who and what', spy(0, /SPY changed target=P_several .*actor=Console via=leaf-juice added=\[young, builders, young_sky\].*whitelisted=true main=true/).length === 1)
  const replay = await s.smAs('t1-replay', 'apply', offlineUuid('P_replay'), 'P_replay', '+builders')
  const replay2 = await s.smAs('t1-replay', 'apply', offlineUuid('P_replay'), 'P_replay', '+builders')
  check('a repeated request ID is answered again but not applied twice', replay.status === 'ok' && replay2.replayed === true
    && changesLog().filter(e => e.request === 't1-replay' && e.event === 'done').length === 1)

  // ---------- Removing a profile while they're inside ----------
  console.log('\n# Removing a profile while they are in that world')
  const rm = await join('P_young')
  await s.ask('give P_young minecraft:diamond 5', 500) // in the lobby
  rm.chat('/mvtp young_explore')
  check('control: P_young can go into young_explore', await inWorld('P_young', 'young_explore'))
  await s.ask('give P_young minecraft:emerald 7', 600)
  check('control: holds 7 emeralds and no diamonds there (Multiverse-Inventories keeps worlds apart)', await count('P_young', 'minecraft:emerald') === 7 && await count('P_young', 'minecraft:diamond') === 0)
  from = s.log.length
  const rr = await s.sm('apply', offlineUuid('P_young'), 'P_young', '-young', 'whitelist=no')
  check('removing young works', rr.status === 'ok' && rr.removed.includes('young'), rr.message)
  check('they land in the lobby', await inWorld('P_young', 'world', 8000))
  await sleep(500)
  check('lobby inventory is back (5 diamonds), and the emeralds stayed behind', await count('P_young', 'minecraft:diamond') === 5 && await count('P_young', 'minecraft:emerald') === 0)
  check('they were told kindly', rm.chat_log.some(m => /isn't open to you anymore/.test(m)), rm.chat_log.slice(-3).join(' | '))
  check('young_explore is now closed to them', !(await canEnter(rm, 'young_explore')))
  await s.sm('apply', offlineUuid('P_young'), 'P_young', '+young', 'whitelist=no')
  await sleep(500)
  rm.chat('/mvtp young_explore')
  check('given back, the world opens again', await inWorld('P_young', 'young_explore'))
  await sleep(600)
  check('and the 7 emeralds are still there', await count('P_young', 'minecraft:emerald') === 7)
  await s.ask('mvtp P_young world', 1000)
  await quit(rm)

  // ---------- Permissions ----------
  console.log('\n# Without the permission')
  pleb.chat_log.length = 0
  let opened = false
  const onOpen = () => { opened = true }
  pleb.on('windowOpen', onOpen)
  for (const c of ['/approve Someone', '/profile P_build', '/profile P_build edit', '/stationmaster apply x 00000000-0000-0000-0000-000000000000 Someone +young', '/stationmaster reload', '/smaster profiles x']) {
    pleb.chat(c); await sleep(700)
  }
  pleb.removeListener('windowOpen', onOpen)
  check('a player without the permission gets no menu', !opened)
  check('and every command is hidden from them, as if it didn\'t exist', pleb.chat_log.filter(m => /Unknown or incomplete command/.test(m)).length === 6, pleb.chat_log.join(' | '))
  check('and nothing about P_build changed', same(await profilesOf('P_build'), ['builders']))
  rawClick(pleb, 10, 0, 0)
  await sleep(500)
  check('a crafted click with no menu open does nothing', same(await profilesOf('P_build'), ['builders']) && s.errors().length === 0)
  check('control: the console can still see P_build', (await s.sm('show', 'P_build')).whitelisted === true)

  // ---------- Self ----------
  console.log('\n# Nobody approves or edits themselves')
  adminA.chat_log.length = 0
  opened = false
  adminA.on('windowOpen', onOpen)
  adminA.chat('/approve AdminA'); await sleep(1500)
  adminA.chat('/profile adminA edit'); await sleep(1500)
  adminA.removeListener('windowOpen', onOpen)
  check('self-approval and self-editing are refused, in any capitals', !opened && adminA.chat_log.filter(m => /can't approve yourself/.test(m)).length === 2, adminA.chat_log.join(' | '))
  const consoleSelf = await s.sm('apply', offlineUuid('AdminA'), 'AdminA', '+builders', 'whitelist=no')
  check('control: the console may change an admin', consoleSelf.status === 'ok')
  await s.sm('apply', offlineUuid('AdminA'), 'AdminA', '-builders', 'whitelist=no')
  adminA.chat_log.length = 0
  adminA.chat('/profile AdminA'); await sleep(1500)
  check('viewing your own profiles is fine', adminA.chat_log.some(m => /AdminA is approved/.test(m)), adminA.chat_log.join(' | '))

  // ---------- The menu ----------
  console.log('\n# The menu')
  let w = await openMenu(adminA, '/approve P_menu')
  check('/approve opens the menu for a name that has never joined', w && itemAt(adminA, SLOT.young) === 'oak_sapling' && itemAt(adminA, SLOT.CONFIRM) === 'lime_concrete')
  check('restricted groups never appear in it', !Object.values(w.slots.slice(0, 54)).some(i => i && /admin|moderator|owner/i.test(JSON.stringify(i.customName ?? ''))))
  await click(adminA, SLOT.young)
  await click(adminA, SLOT.builders)
  await click(adminA, SLOT.builders) // and off again
  from = s.log.length
  await click(adminA, SLOT.CONFIRM)
  await sleep(1500)
  check('choosing in the menu and pressing Approve applies exactly that', same(await profilesOf('P_menu'), ['young']) && whitelist().some(e => e.name === 'P_menu'))
  check('the admin gets a friendly line', adminA.chat_log.some(m => /P_menu is approved and can join now, with Young explorers/.test(m)), adminA.chat_log.slice(-2).join(' | '))
  check('it logs who did it from the menu', changesLog().some(e => e.event === 'done' && e.target === 'P_menu' && e.actor === 'AdminA' && e.via === 'menu'))

  // Closing mid-way.
  w = await openMenu(adminA, '/profile P_menu edit')
  await click(adminA, SLOT.builders)
  adminA.closeWindow(w); await sleep(800)
  check('closing the menu before saving changes nothing', same(await profilesOf('P_menu'), ['young']))

  // Abuse.
  console.log('\n# Menu abuse')
  w = await openMenu(adminA, '/profile P_menu edit')
  const before = { saplings: await count('AdminA', 'minecraft:oak_sapling'), concrete: await count('AdminA', 'minecraft:lime_concrete') }
  await s.ask('give AdminA minecraft:stick 3', 500); await sleep(300)
  const ownSlot = adminA.currentWindow.slots.findIndex((it, i) => i >= 54 && it?.name === 'stick')
  rawClick(adminA, SLOT.builders, 0, 1)           // shift-click a profile
  rawClick(adminA, SLOT.CONFIRM, 0, 1)            // shift-click confirm
  rawClick(adminA, SLOT.grown, 0, 2)              // number key 1 on a profile
  rawClick(adminA, SLOT.young, 40, 2)             // swap with offhand
  rawClick(adminA, SLOT.builders, 0, 4)           // drop key
  rawClick(adminA, SLOT.builders, 0, 3)           // middle click (creative copy)
  rawClick(adminA, SLOT.builders, 0, 6)           // double click collect
  rawClick(adminA, -999, 0, 5); rawClick(adminA, SLOT.builders, 1, 5); rawClick(adminA, SLOT.grown, 1, 5); rawClick(adminA, -999, 2, 5) // a drag
  if (ownSlot > 0) rawClick(adminA, ownSlot, 0, 1) // shift a stick from their own inventory into the menu
  rawClick(adminA, 200, 0, 0); rawClick(adminA, -5, 0, 0); rawClick(adminA, 60, 0, 0)  // out of range and own-inventory slots
  // A packet that claims the click took the item out.
  rawClick(adminA, SLOT.young, 0, 0, { changedSlots: [{ location: SLOT.young, item: undefined }] })
  await sleep(1200)
  adminA.closeWindow(adminA.currentWindow ?? w); await sleep(800)
  const after = { saplings: await count('AdminA', 'minecraft:oak_sapling'), concrete: await count('AdminA', 'minecraft:lime_concrete'), sticks: await count('AdminA', 'minecraft:stick') }
  const menuItems = ['oak_sapling', 'compass', 'bricks', 'blaze_rod', 'end_rod', 'barrier', 'book', 'lime_concrete', 'player_head']
  let stolen = 0
  for (const it of menuItems) stolen += await count('AdminA', 'minecraft:' + it)
  check('no menu item can be taken: shift, number keys, offhand, drop, middle, double-click, drag, crafted', stolen === 0 && after.saplings === before.saplings, `items held: ${stolen}`)
  check('their own items are untouched', after.sticks === 3, String(after.sticks))
  check('none of those clicks changed anything', same(await profilesOf('P_menu'), ['young']))
  const dropped = (await s.ask('execute if entity @e[type=minecraft:item]', 400)).join(' ')
  check('nothing was dropped on the ground', /Test failed/.test(dropped), dropped.slice(0, 80))

  // Double confirm.
  w = await openMenu(adminA, '/profile P_menu edit')
  await click(adminA, SLOT.builders)
  from = s.log.length
  const n0 = changesLog().filter(e => e.target === 'P_menu' && e.event === 'done').length
  rawClick(adminA, SLOT.CONFIRM, 0, 0); rawClick(adminA, SLOT.CONFIRM, 0, 0); rawClick(adminA, SLOT.CONFIRM, 0, 0)
  await sleep(2000)
  check('pressing Save three times applies once', changesLog().filter(e => e.target === 'P_menu' && e.event === 'done').length === n0 + 1
    && spy(from, /SPY changed target=P_menu /).length === 1 && same(await profilesOf('P_menu'), ['young', 'builders']))

  // ---------- Restricted groups ----------
  console.log('\n# Restricted groups')
  const cfg = path.join(dir, 'plugins/Stationmaster/config.yml')
  const goodCfg = readFileSync(cfg, 'utf8')
  await s.ask('lp creategroup helpers', 400); await s.ask('lp group helpers parent add moderator', 400)
  await s.ask('lp creategroup approvers', 400); await s.ask('lp group approvers permission set stationmaster.approve true', 400)
  await s.ask('lp creategroup everything', 400); await s.ask('lp group everything permission set * true', 400)
  writeFileSync(cfg, goodCfg + `
  sneaky1: {label: "Sneaky one", group: Moderator}
  sneaky2: {label: "Sneaky two", group: helpers}
  sneaky3: {label: "Sneaky three", group: approvers}
  sneaky4: {label: "Sneaky four", group: everything}
  sneaky5: {label: "Sneaky five", group: owner}
`)
  from = s.log.length
  const rl = (await s.ask('stationmaster reload', 1500)).join(' ')
  const prof2 = await s.sm('profiles')
  check('config tricks are refused: restricted name in other capitals, inheriting one, granting approve, granting *, a child of admin',
    prof2.profiles.length === 5 && ['sneaky1', 'sneaky2', 'sneaky3', 'sneaky4', 'sneaky5'].every(id => prof2.problems.some(p => p.includes(id))), prof2.problems.join(' | ').slice(0, 300))
  check('a problem event fired for them', spy(from, /SPY problem code=restricted/).length >= 3)
  const viaConsole = await s.sm('apply', offlineUuid('P_menu'), 'P_menu', '+sneaky2', 'whitelist=no')
  check('the console can\'t grant them either', viaConsole.status === 'refused', viaConsole.code)
  w = await openMenu(adminA, '/profile P_menu edit')
  const shown = adminA.currentWindow.slots.slice(0, 54).filter(Boolean).length
  for (const sl of [13, 14, 15, 16, 19, 30, 31, 32]) rawClick(adminA, sl, 0, 0)
  await sleep(500)
  rawClick(adminA, SLOT.CONFIRM, 0, 0); await sleep(1500)
  check('they aren\'t in the menu, and crafted clicks on empty slots grant nothing', shown === 9 && same(await profilesOf('P_menu'), ['young', 'builders']), `${shown} items shown`)
  try { adminA.closeWindow(adminA.currentWindow) } catch {}
  // A group that turns unsafe after the menu opened.
  w = await openMenu(adminA, '/profile P_menu edit')
  await click(adminA, SLOT.grown)
  await s.ask('lp group grown parent add admin', 600)
  await click(adminA, SLOT.CONFIRM)
  await sleep(1800)
  check('a group that became unsafe after the menu opened is refused at save time', !(await profilesOf('P_menu')).includes('grown')
    && adminA.chat_log.some(m => /inherits the restricted group admin/.test(m)), adminA.chat_log.slice(-1)[0])
  await s.ask('lp group grown parent remove admin', 600)
  writeFileSync(cfg, goodCfg)
  await s.ask('stationmaster reload', 1500)

  // ---------- Bridging the chat circles ----------
  console.log('\n# Bridging the chat circles')
  const br = await s.sm('apply', offlineUuid('P_young'), 'P_young', '+grown', 'whitelist=no')
  check('adding a grown-up profile to a young explorer asks first, and changes nothing', br.status === 'warn' && br.warnings.some(x => x.code === 'bridge')
    && !(await profilesOf('P_young')).includes('grown'), JSON.stringify(br.warnings))
  const br2 = await s.sm('apply', offlineUuid('P_young'), 'P_young', '+grown', 'confirm=bridge', 'whitelist=no')
  check('with a yes from the Mac, it goes ahead and the yes is logged', br2.status === 'ok' && (await profilesOf('P_young')).includes('grown')
    && changesLog().some(e => e.event === 'done' && e.target === 'P_young' && (e.confirmed ?? []).includes('bridge')))
  await s.sm('apply', offlineUuid('P_young'), 'P_young', '-grown', 'whitelist=no')
  w = await openMenu(adminB, '/profile P_young edit')
  await click(adminB, SLOT.grown)
  await click(adminB, SLOT.CONFIRM)
  await sleep(1500)
  check('in the menu the same change shows a warning screen', itemAt(adminB, 20) === 'yellow_banner' && itemAt(adminB, SLOT.CONFIRM) === 'lime_concrete' && !(await profilesOf('P_young')).includes('grown'))
  await click(adminB, SLOT.CANCEL)
  check('Go back returns to the choices without saving', itemAt(adminB, SLOT.young) === 'oak_sapling' && !(await profilesOf('P_young')).includes('grown'))
  await click(adminB, SLOT.CONFIRM); await sleep(1500)
  await click(adminB, SLOT.CONFIRM); await sleep(1800)
  check('Yes, go ahead saves it', (await profilesOf('P_young')).includes('grown'))
  await s.sm('apply', offlineUuid('P_young'), 'P_young', '-grown', 'whitelist=no')
  const adult = await s.sm('apply', offlineUuid('P_grown'), 'P_grown', '+grown_sky', 'whitelist=no')
  check('the adult toggle asks for a yes', adult.status === 'warn' && adult.warnings[0].code === 'confirm:grown_sky')
  const adultBridge = await s.sm('apply', offlineUuid('P_young'), 'P_young', '+grown_sky', 'confirm=confirm:grown_sky', 'whitelist=no')
  check('a yes to the adult question doesn\'t cover the bridge', adultBridge.status === 'warn' && adultBridge.warnings.some(x => x.code === 'bridge'))

  // ---------- Two admins at once ----------
  console.log('\n# Two admins editing the same person at once')
  await s.sm('apply', offlineUuid('P_two'), 'P_two', '+builders')
  const wa = await openMenu(adminA, '/profile P_two edit'), wb = await openMenu(adminB, '/profile P_two edit')
  await click(adminA, SLOT.young)                       // A adds young
  await click(adminB, SLOT.builders)                    // B removes builders
  await click(adminB, SLOT.young_sky)                 // and adds young sky
  from = s.log.length
  rawClick(adminA, SLOT.CONFIRM, 0, 0); rawClick(adminB, SLOT.CONFIRM, 0, 0)
  await sleep(3000)
  check('both changes land: young and young sky added, builders removed', same(await profilesOf('P_two'), ['young', 'young_sky']), (await profilesOf('P_two')).join(','))
  const two = changesLog().filter(e => e.target === 'P_two' && e.event === 'done' && e.via === 'menu')
  check('both are logged, one each', two.length === 2 && new Set(two.map(e => e.actor)).size === 2)
  check('and both events fired', spy(from, /SPY changed target=P_two /).length === 2)
  // Same toggle from both: only one grant.
  await openMenu(adminA, '/profile P_two edit'); await openMenu(adminB, '/profile P_two edit')
  await click(adminA, SLOT.builders); await click(adminB, SLOT.builders)
  rawClick(adminA, SLOT.CONFIRM, 0, 0); rawClick(adminB, SLOT.CONFIRM, 0, 0)
  await sleep(3000)
  const dup = changesLog().filter(e => e.target === 'P_two').slice(-2)
  check('two admins adding the same profile: one grant, one "nothing to change"', same(dup.map(e => e.event), ['done', 'nochange'])
    && (await profilesOf('P_two')).filter(p => p === 'builders').length === 1, dup.map(e => e.event).join(','))
  check('the second admin is told it was already done', [adminA, adminB].some(b => b.chat_log.some(m => /already have Builders/.test(m))))

  // ---------- Names ----------
  console.log('\n# Names')
  from = s.log.length
  kicked = await join('CaseTest').then(() => false, () => true)
  check('control: CaseTest is turned away first', kicked)
  const rc = await s.sm('resolve', 'casetest'), rn = await s.sm('resolve', 'Nobody_here')
  check('resolve tells Leaf Juice who tried to join and who never did', rc.name === 'CaseTest' && rc.triedToJoin >= 1 && rc.lastTried
    && rn.triedToJoin === 0 && rn.playedBefore === false, JSON.stringify([rc.triedToJoin, rn.triedToJoin, rn.playedBefore]))
  w = await openMenu(adminA, '/approve casetest')
  check('/approve with other capitals finds the account that tried to join', /CaseTest/.test(JSON.stringify(adminA.currentWindow?.title ?? '')), JSON.stringify(adminA.currentWindow?.title))
  await click(adminA, SLOT.CONFIRM); await sleep(1500)
  const ct = await join('CaseTest').then(() => true, e => e.message)
  check('and then CaseTest can join', ct === true, String(ct))
  check('approving removes them from the attempts list', !(await s.sm('attempts')).attempts.some(a => a.name === 'CaseTest'))
  adminA.chat_log.length = 0
  adminA.chat('/approve bad-name!'); adminA.chat('/approve way_too_long_for_a_name'); await sleep(1500)
  check('impossible names fail clearly', adminA.chat_log.filter(m => /can't be a Minecraft name/.test(m)).length === 2, adminA.chat_log.join(' | '))
  const unk = await s.sm('apply', offlineUuid('P_menu'), 'P_menu', '+wizards', 'whitelist=no')
  check('an unknown profile fails clearly', unk.status === 'refused' && unk.code === 'unknown_profile' && /no profile called wizards/.test(unk.message))
  kicked = await join('Stranger2').then(() => false, () => true)
  check('strangers still can\'t join after all this', kicked)

  // ---------- Restart ----------
  console.log('\n# Restart')
  await Promise.all(bots.map(b => quit(b)))
  bots.length = 0
  await s.stop(); await sleep(1500)
  s = new Server(dir); await s.start()
  check('profiles survive a restart', same(await profilesOf('P_two'), ['young', 'young_sky', 'builders']) && same(await profilesOf('P_several'), ['young', 'builders', 'young_sky']))
  check('the attempts list survives a restart', (await s.sm('attempts')).attempts.some(a => a.name === 'Stranger2'))
  const after2 = await join('P_several')
  check('and the worlds are still right after it', same(await openWorlds(after2), expected(['young', 'builders', 'young_sky'])))
  await quit(after2)

  // ---------- Broken config ----------
  console.log('\n# Broken config')
  writeFileSync(cfg, goodCfg.replace('profiles:', 'profiles: [oops'))
  const brk = (await s.ask('stationmaster reload', 1500)).join(' ')
  check('reloading a broken config says so and keeps the old settings', /Couldn't reload/.test(brk) && /Still using the settings from before/.test(brk) && (await s.sm('profiles')).profiles.length === 5)
  await s.stop(); await sleep(1000)
  s = new Server(dir); await s.start()
  const brk2 = await s.sm('apply', offlineUuid('P_cfg'), 'P_cfg', '+young')
  check('starting with a broken config refuses every change, clearly', brk2.status === 'refused' && brk2.code === 'config_broken' && !whitelist().some(e => e.name === 'P_cfg'), brk2.message)
  check('and says so in the log at startup', s.log.some(l => /config.yml can't be read/.test(l)))
  writeFileSync(cfg, goodCfg)
  await s.ask('stationmaster reload', 1500)
  check('control: fixed and reloaded, it works again', (await s.sm('apply', offlineUuid('P_cfg'), 'P_cfg', '+young')).status === 'ok')

  // ---------- kill -9 in the middle of a change ----------
  console.log('\n# kill -9 in the middle of a change')
  for (const [when, prop] of [['after groups, before the whitelist', 'pause-after-groups'], ['before groups', 'pause-before-groups']]) {
    await s.stop(); await sleep(1000)
    s = new Server(dir, { props: [`-Dstationmaster.test.${prop}=15000`] }); await s.start()
    const name = prop === 'pause-after-groups' ? 'P_kill1' : 'P_kill2'
    s.send(`stationmaster apply k-${name} ${offlineUuid(name)} ${name} +builders`)
    const end = Date.now() + 10000
    while (Date.now() < end && !changesLog().some(e => e.event === 'started' && e.target === name)) await sleep(100)
    await sleep(prop === 'pause-after-groups' ? 3000 : 500)
    await s.kill9(); await sleep(1500)
    s = new Server(dir); await s.start(); await sleep(2000)
    const intr = changesLog().find(e => e.event === 'interrupted' && e.target === name)
    const has = (await profilesOf(name)).includes('builders')
    const wl = whitelist().some(e => e.name === name)
    check(`killed ${when}: the log records it as interrupted, with what LuckPerms holds`, intr && intr.groupsApplied === has && intr.whitelistedNow === wl, JSON.stringify(intr))
    check(`killed ${when}: LuckPerms is all or nothing (${has ? 'groups saved' : 'nothing saved'}), and not whitelisted`,
      (prop === 'pause-after-groups' ? has : !has) && !wl)
    check(`killed ${when}: a problem event told the admins`, s.log.some(l => /SPY problem code=interrupted/.test(l)))
  }
  const fix = await s.sm('apply', offlineUuid('P_kill1'), 'P_kill1', '+builders')
  check('approving again finishes it cleanly', fix.status === 'ok' && fix.newlyWhitelisted && same(await profilesOf('P_kill1'), ['builders']))
  check('every line in changes.log is readable', changesLog().every(e => !e.unreadable))

  // ---------- LuckPerms failing ----------
  console.log('\n# LuckPerms failing')
  if (STORAGE !== 'yaml') console.log(`SKIP  LuckPerms failing to save is only forced on YAML storage (a read-only folder). ${STORAGE} keeps its database open, so it can't be made to fail the same way.`)
  else {
  const users = path.join(dir, 'plugins/LuckPerms/yaml-storage/users')
  chmodSync(users, 0o555)
  from = s.log.length
  const fail = await s.sm('apply', offlineUuid('P_lpfail'), 'P_lpfail', '+builders')
  chmodSync(users, 0o755)
  check('when LuckPerms can\'t save: an error, and nobody is whitelisted', fail.status === 'error' && fail.code === 'luckperms_failed' && !whitelist().some(e => e.name === 'P_lpfail'), fail.message)
  const mem = (await s.ask('lp user P_lpfail parent info', 1200)).join(' ')
  check('LuckPerms in memory went back too, so nothing is half applied', !/builders/.test(mem), mem.slice(0, 160))
  check('a failure event fired', spy(from, /SPY problem code=luckperms_failed/).length === 1)
  check('the attempt is logged as failed', changesLog().some(e => e.event === 'failed' && e.target === 'P_lpfail'))
  check('control: once LuckPerms can save again, the same change works', (await s.sm('apply', offlineUuid('P_lpfail'), 'P_lpfail', '+builders')).status === 'ok')
  }

  // ---------- LuckPerms missing ----------
  console.log('\n# LuckPerms missing')
  await s.stop(); await sleep(1000)
  const lpJar = path.join(dir, 'plugins', readdirSync(path.join(dir, 'plugins')).find(f => /^LuckPerms-/.test(f)))
  renameSync(lpJar, lpJar + '.off')
  s = new Server(dir); await s.start(); await sleep(1500)
  const miss = await s.sm('apply', offlineUuid('P_nolp'), 'P_nolp', '+builders')
  check('without LuckPerms: refused clearly, and nobody whitelisted', miss.status === 'refused' && miss.code === 'luckperms_missing' && !whitelist().some(e => e.name === 'P_nolp'), miss.message)
  check('Stationmaster still loads and says why', s.log.some(l => /LuckPerms isn't running/.test(l)) && s.log.some(l => /SPY problem code=luckperms_missing/.test(l)))
  const unexpected = s.errors().filter(l => !/LuckPerms isn't running/.test(l))
  check('no other errors from Stationmaster in the log', unexpected.length === 0, unexpected[0])
  await s.stop()
  renameSync(lpJar + '.off', lpJar)
} catch (e) {
  console.log('CRASH ' + (e.stack ?? e))
  check('the suite ran to the end', false, e.message)
} finally {
  for (const b of bots) try { b.quit() } catch {}
  try { if (s.proc?.exitCode === null) await s.stop() } catch {}
  // One throwaway server at a time, and none left behind: delete it as soon as the run ends.
  if (process.env.STATIONMASTER_TEST_KEEP !== '1') rmSync(dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 500 })
}
summary()
console.log(`(${results.length} checks on Paper ${PAPER})`)
process.exit()
