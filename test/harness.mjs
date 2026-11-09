// Test harness: builds a fresh throwaway Paper server in a temp folder, on 127.0.0.1 only, and drives it from the
// console with mineflayer bots as players. It never copies or touches a real server.
//
//   USHER_TEST_JARS   folder with paper-<version>.jar, LuckPerms-Bukkit-*.jar, multiverse-core-*.jar,
//                             multiverse-inventories-*.jar, ViaVersion-*.jar and ViaBackwards-*.jar
//   USHER_TEST_EULA   an eula.txt the owner already accepted. It's copied, never written.
//   USHER_TEST_RUN    where servers are built. Must be under a temp folder.
//   USHER_TEST_PAPER  26.1.2 (default), 26.2 or 26.3
//   USHER_TEST_JAVA   a Java 25 to run Paper with
//   USHER_TEST_STORAGE  LuckPerms storage: yaml (default) or h2
import { spawn } from 'node:child_process'
import { existsSync, readFileSync, readdirSync, writeFileSync, copyFileSync, mkdirSync, rmSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import mineflayer from 'mineflayer'

export const PORT = 25615
export const PAPER = process.env.USHER_TEST_PAPER ?? '26.1.2'
// mineflayer speaks up to 26.1, so on newer servers the bots join through ViaVersion and ViaBackwards.
export const BOT_VERSION = '26.1'
export const VIA = !PAPER.startsWith('26.1')
export const JAVA = process.env.USHER_TEST_JAVA ?? 'java'
export const STORAGE = process.env.USHER_TEST_STORAGE ?? 'yaml'
if (!['yaml', 'h2'].includes(STORAGE)) throw new Error('USHER_TEST_STORAGE must be yaml or h2, not ' + STORAGE)
const JARS = must('USHER_TEST_JARS')
const EULA = must('USHER_TEST_EULA')
export const RUN = path.resolve(must('USHER_TEST_RUN'))
const temp = [os.tmpdir(), '/tmp', '/private/tmp', '/var/folders'].map(p => path.resolve(p))
if (!temp.some(t => RUN.startsWith(t + path.sep))) throw new Error('USHER_TEST_RUN must be inside a temp folder, not ' + RUN)
if (!/^eula=true$/m.test(readFileSync(EULA, 'utf8'))) throw new Error('That eula.txt isn\'t accepted. Accepting it is the owner\'s call.')

const ROOT = new URL('../', import.meta.url).pathname
const LIBS = path.join(ROOT, 'build/libs/')
const built = existsSync(LIBS) ? readdirSync(LIBS).filter(f => /^Usher-.*\.jar$/.test(f)) : []
if (built.length !== 1) throw new Error(`Run ./gradlew clean build first: expected one Usher jar in build/libs, found ${built.length}`)
export const JAR = path.join(LIBS, built[0])

function must(name) {
  const v = process.env[name]
  if (!v) throw new Error('Set ' + name + ' (see the top of test/harness.mjs)')
  return v
}
const jar = re => {
  const f = readdirSync(JARS).find(n => re.test(n))
  if (!f) throw new Error('No jar matching ' + re + ' in ' + JARS)
  return path.join(JARS, f)
}

export const sleep = ms => new Promise(r => setTimeout(r, ms))

/** A brand-new server folder: flat lobby, whitelist on, offline mode so bots can join, 127.0.0.1 only. */
export function freshServer(name = 'server', { luckperms = true, storage = STORAGE } = {}) {
  const dir = path.join(RUN, name)
  rmSync(dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 500 })
  mkdirSync(path.join(dir, 'plugins'), { recursive: true })
  copyFileSync(jar(new RegExp('^paper-' + PAPER.replace(/\./g, '\\.') + '\\.jar$')), path.join(dir, 'paper.jar'))
  copyFileSync(EULA, path.join(dir, 'eula.txt'))
  const plugins = [/^multiverse-core-.*\.jar$/, /^multiverse-inventories-.*\.jar$/]
  if (luckperms) plugins.push(/^LuckPerms-Bukkit-.*\.jar$/)
  if (VIA) plugins.push(/^ViaVersion-.*\.jar$/, /^ViaBackwards-.*\.jar$/)
  for (const re of plugins) { const j = jar(re); copyFileSync(j, path.join(dir, 'plugins', path.basename(j))) }
  installJar(dir)
  writeFileSync(path.join(dir, 'server.properties'), [
    'server-ip=127.0.0.1', `server-port=${PORT}`, 'online-mode=false', 'white-list=true', 'enforce-whitelist=true',
    'level-type=minecraft\\:flat', 'generate-structures=false', 'spawn-protection=0', 'enable-query=false', 'enable-rcon=false',
    'view-distance=4', 'simulation-distance=4', 'difficulty=peaceful', 'spawn-monsters=false', 'motd=Usher test'].join('\n') + '\n')
  if (luckperms) {
    mkdirSync(path.join(dir, 'plugins/LuckPerms'), { recursive: true })
    writeFileSync(path.join(dir, 'plugins/LuckPerms/config.yml'), `server: global\nstorage-method: ${storage}\nwatch-files: false\n`)
  }
  return dir
}

export const SPY = path.join(ROOT, 'build/test-spy/UsherSpy.jar')

export function installJar(dir) {
  copyFileSync(JAR, path.join(dir, 'plugins', 'Usher.jar'))
  if (!existsSync(SPY)) throw new Error('Run ./gradlew spyJar first')
  copyFileSync(SPY, path.join(dir, 'plugins', 'UsherSpy.jar'))
}

/** The shipped config with test settings: offline lookups and the lobby. */
export function writeConfig(dir, edit = t => t) {
  let text = readFileSync(path.join(ROOT, 'src/main/resources/config.yml'), 'utf8')
  for (const [from, to] of [['name-lookup: auto', 'name-lookup: offline'], ['lobby-world: ""', 'lobby-world: world']]) {
    if (!text.includes(from)) throw new Error('The default config no longer has: ' + from)
    text = text.replace(from, to)
  }
  mkdirSync(path.join(dir, 'plugins/Usher'), { recursive: true })
  writeFileSync(path.join(dir, 'plugins/Usher/config.yml'), edit(text))
}

export class Server {
  log = []
  constructor(dir, { memory = '2G', props = [] } = {}) { Object.assign(this, { dir, memory, props }) }
  async start() {
    const from = this.log.length
    this.proc = spawn(JAVA, [`-Xms${this.memory}`, `-Xmx${this.memory}`, '-Dterminal.jline=false', '-Dterminal.ansi=false',
      '-Dpaper.disableChannelLimit=true', ...this.props, '-jar', 'paper.jar', 'nogui'], { cwd: this.dir, stdio: ['pipe', 'pipe', 'pipe'] })
    this.exited = new Promise(r => this.proc.on('exit', r))
    const proc = this.proc
    process.on('exit', () => { if (proc.exitCode === null) proc.kill('SIGKILL') }) // never leave a server behind
    const add = d => { for (const l of d.toString().split('\n')) if (l.trim()) this.log.push(l) }
    this.proc.stdout.on('data', add)
    this.proc.stderr.on('data', add)
    await this.waitFor(/Done \(\d/, 240000, from)
    await sleep(1500)
    return from
  }
  async waitFor(re, ms = 15000, from = 0) {
    const end = Date.now() + ms
    while (Date.now() < end) {
      const hit = this.log.slice(from).find(l => re.test(l))
      if (hit) return hit
      if (this.proc.exitCode !== null) {
        const last = this.log.slice(-5).map(strip).join('\n    ')
        throw new Error(`Server exited (code ${this.proc.exitCode}) while waiting for ${re}. Its last lines:\n    ${last}\n` +
          '  Paper 26 needs Java 25. Set USHER_TEST_JAVA if `java` is older.')
      }
      await sleep(50)
    }
    throw new Error('Timed out waiting for ' + re)
  }
  send(cmd) { this.proc.stdin.write(cmd + '\n') }
  /** Sends a console command and returns the log lines it produced. */
  async ask(cmd, ms = 600) {
    const from = this.log.length
    this.send(cmd)
    await sleep(ms)
    return this.log.slice(from).map(strip)
  }
  /** Runs a usher console command and returns its parsed result line. */
  async sm(sub, ...args) {
    const req = 't' + (++Server.req)
    const from = this.log.length
    this.send(['usher', sub, req, ...args].join(' '))
    const line = await this.waitFor(new RegExp('usher-result ' + req + ' '), 30000, from)
    const json = strip(line).slice(strip(line).indexOf('{'))
    const hits = this.log.slice(from).filter(l => l.includes('usher-result ' + req + ' ')).length
    return { req, hits, ...JSON.parse(json) }
  }
  /** Same, with a request ID chosen by the caller, for replays. */
  async smAs(req, sub, ...args) {
    const from = this.log.length
    this.send(['usher', sub, req, ...args].join(' '))
    await sleep(300)
    const line = await this.waitFor(new RegExp('usher-result ' + req.replace(/[.]/g, '\\.') + ' '), 30000, from)
    return JSON.parse(strip(line).slice(strip(line).indexOf('{')))
  }
  errors() { return this.log.filter(l => /Usher/.test(l) && /(ERROR|Exception|SEVERE)/.test(l)) }
  async stop() {
    this.send('stop')
    const done = await Promise.race([this.exited.then(() => true), sleep(90000).then(() => false)])
    if (!done) { console.log('  server stuck on stop: killing it'); await this.kill9() }
  }
  kill9() { this.proc.kill('SIGKILL'); return this.exited }
}
Server.req = 0
const strip = l => l.replace(/^\[[\d:]+ \w+\]: /, '').replace(/^\[[\d:]+\] \[[^\]]+\/\w+\]: /, '')

/** A bot that keeps its chat. Rejects with the kick reason if it can't join. */
export async function bot(username) {
  const b = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username, version: BOT_VERSION, auth: 'offline', viewDistance: 'tiny', hideErrors: true })
  if (VIA) {
    // Through ViaBackwards (5.12.2 and the 5.12.3 dev build), a 26.1 bot's movement packets reach a 26.2+ server as
    // "invalid player movement" and get it kicked. These tests never need a bot to walk, since every teleport is done
    // by the server, so on those servers the bot stays put: it confirms teleports but never reports moving.
    const write = b._client.write.bind(b._client)
    b._client.write = (name, data) => /^(position|position_look|look|flying)$/.test(name) ? undefined : write(name, data)
  }
  b.chat_log = []
  b.on('messagestr', m => b.chat_log.push(m))
  b.on('error', () => {})
  await new Promise((resolve, reject) => {
    b.once('spawn', resolve)
    b.once('kicked', r => reject(new Error('kicked: ' + (typeof r === 'string' ? r : JSON.stringify(r)))))
    b.once('end', r => reject(new Error(username + ' disconnected before spawning: ' + r)))
    setTimeout(() => reject(new Error(username + ' spawn timeout')), 60000)
  })
  await sleep(1200)
  return b
}

export const results = []
export function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`)
  return ok
}
export function summary() {
  const failed = results.filter(r => !r.ok)
  console.log(`\n${results.length - failed.length}/${results.length} passed`)
  for (const f of failed) console.log('  FAILED: ' + f.name + (f.detail ? ' (' + f.detail + ')' : ''))
  if (failed.length) process.exitCode = 1
  return failed.length === 0
}
