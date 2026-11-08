# Stationmaster

A Paper plugin for whitelisted servers. Admins approve a new player and choose their profiles, and Stationmaster sets their whitelist entry and LuckPerms groups. Nobody types LuckPerms commands.

**Status:** pre-release. It passes its bot tests on throwaway Paper 26.1.2, 26.2 and 26.3 servers with LuckPerms and Multiverse. It hasn't been used on a real server or seen in a real game client yet. See [Testing](#testing).

- Paper 26.1 or newer, on Java 25. There's no support for 1.21.x.
- Needs LuckPerms. Without it, Stationmaster still loads but refuses every change and says why.
- Works with Multiverse and Multiverse-Inventories, but doesn't need them.

## What it does

A profile is a LuckPerms group, such as "Builders", plus the worlds that group opens. You list profiles in `config.yml`. Approving someone or changing their profiles:

- Adds or removes groups through LuckPerms' API. The change applies at once and survives restarts.
- Saves the groups first, then adds the person to the whitelist. If LuckPerms fails, they aren't whitelisted.
- Sends anyone standing in a world they just lost to the lobby. It's an ordinary teleport, so Multiverse-Inventories keeps their things with the world they left.
- Logs who changed what and when, in `plugins/Stationmaster/changes.log`.

Approving someone with no profile gives them whatever LuckPerms' default group allows.

There are three ways to do it:

- **In the game:** `/approve <name>` opens a chest menu. Click profiles on or off, then press Approve.
- **From Leaf Juice or a script:** through the server console. See [Console commands](#console-commands).
- **From Discord or another plugin:** Stationmaster fires Paper events that a bridge plugin can listen to. See [Events](#events).

### Safety rules

- Only `stationmaster.approve` can approve or change profiles. Players without it don't see the commands at all.
- Nobody can approve themselves or change their own profiles, even with the permission. The console can.
- `restricted-groups` are never added or removed. A profile is refused, with a warning in the log, if its group:
  - is on that list, in any capitals,
  - inherits a group on that list,
  - grants every permission (`*`), or
  - grants `stationmaster.approve` or LuckPerms editing.
  These checks run at startup and again on every change, so they still hold if a group changes after a menu opens.
- Adding a profile that puts someone in two chat circles at once (see `circle` in the config) asks for a yes first. A profile with a `confirm` question, such as an adults-only world, does too. A yes to one question doesn't cover another.
- Changes run one at a time. Two admins editing the same person both get their change. If both add the same profile, it's added once.
- Nothing is changed until Approve or Save is pressed. Closing the menu cancels. Every click in the menu is cancelled, so no item can be taken out.
- The menu says whether the account has played here, has tried to join, or has never tried. An account that has never tried gets a warning, so a mistyped name that belongs to a stranger is easy to spot.

Children play on the servers this was built for. [docs/kids-first.md](docs/kids-first.md) says how Stationmaster protects them and what it leaves to the people running it. Owner decisions are recorded in [docs/decisions.md](docs/decisions.md).

### The menu

Each profile shows its state in words with a symbol: ✔ On, + Will be added, − Will be removed, ○ Off, or ↳ Comes from another group. On and added are aqua, removed is gold. So the menu reads the same without colour, and it avoids the red and green that colour-blind players most often can't tell apart. Results in chat start with ✔, and errors with ✘. The menu shows up to 14 main profiles and 14 extras. If there are more, it says so, and the rest still work from the console.

## Install

1. Put `Stationmaster-<version>.jar` in `plugins/`, next to LuckPerms.
2. Start the server once, then edit `plugins/Stationmaster/config.yml`. The profiles in it are examples.
3. Create each profile's group in LuckPerms, and give it its worlds, for example `multiverse.access.<world>`.
4. Run `stationmaster reload` in the console.
5. Grant `stationmaster.approve` to the people who approve, and nobody else.

The lobby must be open to everyone, since people are sent there. If Multiverse's `enforce-access` is on, the default group needs `multiverse.access.<lobby>`.

### Config

| Setting | What it does |
| --- | --- |
| `lobby-world` | Where people go when a world closes to them. Blank means the main world. |
| `name-lookup` | How typed names become accounts. `auto` asks Mojang in online mode. `mojang` always asks, which is right behind a proxy. `offline` never asks. |
| `restricted-groups` | Groups Stationmaster never touches. |
| `profiles.<id>.group` | The LuckPerms group. It must exist. |
| `profiles.<id>.worlds` | Worlds this profile opens. `*` matches anything, so `explore*` covers `explore_nether`. |
| `profiles.<id>.circle` | The chat circle it joins, for the two-circles warning. |
| `profiles.<id>.confirm` | A question that needs a yes before it's added. |
| `profiles.<id>.toggle` | `true` puts it in the menu's lower rows, as an extra. |
| `profiles.<id>.granted` | What the player sees when it's added. |

A config that can't be read at startup makes Stationmaster refuse every change until it's fixed. A broken file on `reload` keeps the settings already in use.

### Names

`/approve` and the console take a name that has never joined, in any capitals, and find the real account. Stationmaster checks, in this order:

1. Players the server already knows.
2. People the whitelist recently turned away.
3. Mojang's public profile lookup, in online or `mojang` mode.

A typo fails with "No Minecraft account is called …". If Mojang can't be reached, Stationmaster says that instead of guessing, and nothing changes. In `offline` mode, capitals matter for names the server hasn't seen, because offline accounts are built from the exact name.

## Commands

| Command | Who | What it does |
| --- | --- | --- |
| `/approve <name>` | approvers | Opens the menu to whitelist someone and choose their profiles. |
| `/profile <name>` | approvers, console | Shows someone's profiles, with a button to change them. |
| `/profile <name> edit` | approvers | Opens the menu to change their profiles. |
| `/stationmaster reload` | approvers, console | Re-reads `config.yml`. |
| `stationmaster <command> <request-id> …` | console | The commands below, for Leaf Juice and scripts. Also `smaster`. |

## Console commands

Each takes a request ID you choose (1 to 64 letters, digits, `.`, `_`, `:` or `-`). Each answers with exactly one line, `stationmaster-result <request-id> <json>`, sometimes a moment later. Every answer has `status` (`ok`, `warn`, `refused` or `error`), `code` and a `message` that's fine to show a person.

| Command | Answers with |
| --- | --- |
| `profiles <id>` | The profiles that can be offered, restricted groups, and config problems. |
| `resolve <id> <name>` | The account's `uuid`, correctly capitalized `name`, `whitelisted`, `playedBefore`, and `triedToJoin` (how many times the whitelist turned it away) with `lastTried`. Warn before approving an account that has neither played nor tried. |
| `show <id> <uuid or name>` | Their `profiles`, profiles they get `throughOtherGroups`, their `circles`, and `whitelisted`. |
| `apply <id> <uuid> <name> [+profile] [-profile] [confirm=code,code] [whitelist=no] [via=leaf-juice]` | Adds and removes profiles, then whitelists them unless `whitelist=no`. |
| `attempts <id>` | People turned away by the whitelist: name, uuid, count, first and last try. |
| `log <id> [count]` | The last entries in `changes.log`, newest last. |

How `apply` works:

- If a change needs a yes, the answer is `status: warn` with `warnings` (each has a `code` and `text`), and nothing changes. To go ahead, send the same command again with `confirm=` and those codes, such as `confirm=bridge`.
- Sending the same request ID again after it went through returns the first answer with `replayed: true`. It isn't applied twice.
- Changes from the console are logged with `actor: Console` and the `via` you give.

Example:

```
> stationmaster apply r42 069a79f4-44e9-4726-a5be-fca90e38aaf5 Notch +builders via=leaf-juice
stationmaster-result r42 {"status":"ok","code":"applied","message":"Notch is approved and can join now, with Builders.", ...}
```

## Events

Stationmaster sends nothing over the network itself, apart from name lookups to Mojang. To post to Discord, listen to these events from another plugin. All fire on the main thread. They carry names and profiles, which can show who is a child, so post them only to a channel that just the admins can read.

| Event | When |
| --- | --- |
| `com.extantape.stationmaster.api.ProfilesChangedEvent` | Someone was approved or their profiles changed. It has who, by whom, how, what was added and removed, and any warnings said yes to. |
| `com.extantape.stationmaster.api.JoinAttemptEvent` | Someone not on the whitelist tried to join. At most once every ten minutes per account. |
| `com.extantape.stationmaster.api.StationmasterProblemEvent` | LuckPerms missing or failing, a config problem, a refused restricted group, self-approval, or a change cut off by a crash. |

## If the server stops mid-change

Each change writes a `started` line to `changes.log` before LuckPerms is touched, and a `done` or `failed` line after. Every line is flushed to disk first. If the server is killed in between, the next startup finds the open change. It checks what LuckPerms and the whitelist actually hold, logs an `interrupted` line saying so, and fires a problem event. It doesn't redo the change. Approving the person again finishes it.

## Testing

`./gradlew build` runs the unit tests: config parsing, the safety rules, the warnings and the change log's crash handling.

The bot tests in `test/` build fresh Paper servers in a temp folder, on 127.0.0.1 port 25615. Each has LuckPerms, Multiverse-Core, Multiverse-Inventories, made-up worlds and groups, and mineflayer bots as players. Every result that says "blocked" has a check beside it showing the same thing works when it should. See the top of `test/harness.mjs` for setup, then run `node test/acceptance.mjs`. It runs one server at a time and deletes it when the run ends. Set `STATIONMASTER_TEST_KEEP=1` to keep it for a look.

**Passed on Paper 26.1.2, 26.2 and 26.3 (2026-11-07), 100 checks each:**

- Approving with each profile alone, several at once, and none. Exactly the right worlds open, by walking in with Multiverse's own `/mvtp`.
- Removing a profile while the player is in its world. They land in the lobby with their lobby inventory, and their things in that world are still there when it's given back.
- Players without the permission: no menu, no commands, no effect from crafted clicks.
- Self-approval and self-editing refused in any capitals. The console can still change an admin.
- Restricted groups, including config tricks (other capitals, inheritance, `*`, the approve permission, a child of a restricted group) and a group turned unsafe while a menu was open.
- Menu abuse: shift-clicks, number keys, offhand swap, drop, middle click, double click, drags, out-of-range slots, a forged "took the item" packet, Save pressed three times, and closing mid-way.
- Two admins editing one person at once, and both adding the same profile.
- The two-circles warning and the adults-only question, by console and in the menu.
- Names: a stranger approved with other capitals, `resolve` telling who tried to join and who never did, impossible names, unknown profiles, repeated request IDs.
- Restarts, a broken config at reload and at startup, `kill -9` before and after the LuckPerms save, LuckPerms unable to save (a read-only storage folder), and LuckPerms missing.
- The three events fire, on the main thread, as expected.

**How the bots connect:** mineflayer speaks up to 26.1, so on 26.1.2 the bots are native clients. On 26.2 and 26.3 they join as 26.1 clients through ViaVersion and ViaBackwards. Through ViaBackwards (5.12.2 and the 5.12.3 dev build), a 26.1 bot's movement packets get it kicked for "invalid player movement". So on those servers the bots don't send movement. Every teleport in the tests is done by the server, so nothing the tests check depends on it.

**Not tested yet:**

- How the menu's new symbols, colours and hints look in a real client. The bots check which items are where, not how they read.

- A real game client: whether the menu is clear and pleasant for a non-technical admin, and whether the messages read well.
- Mojang name lookups on a real online-mode server. The tests run offline-mode servers.
- Leaf Juice itself. Its screen belongs to Leaf Juice; these tests drive the same console commands directly.
- Waystation gates appearing or disappearing when a profile changes. Waystation decides that from `multiverse.access.<world>`, which LuckPerms changes at once, but no test has watched a gate do it.
- A Discord bridge. The events were checked with a small test plugin that logs them.
- MySQL or other LuckPerms storage. The tests use YAML storage.

## Building

```bash
./gradlew build
```

The jar is written to `build/libs/`. It needs Java 25. Pushing a `v*` tag builds the jar and attaches it to a GitHub Release, with `RELEASE_NOTES.md` as the notes. The release stops if that file names another version. CI also runs `scripts/leak-check.sh`, which looks for email addresses and committed build output.

## License

MIT. See [LICENSE](LICENSE).
