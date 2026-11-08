# Stationmaster 0.2.1

Approve new players on a whitelisted Paper server and choose their LuckPerms groups ("profiles") from a chest menu or the console, without typing LuckPerms commands.

This is an early 0.x release. It has passed bot tests on throwaway servers, but it hasn't been used on a real server or seen in a real game client. Read the README's testing section before relying on it.

## What it does

- `/approve <name>` opens a menu to whitelist someone and turn profiles on or off. `/profile <name>` shows and changes them.
- Console commands with one parseable result line each, for scripts and desktop tools: `profiles`, `resolve`, `show`, `apply`, `attempts`, `log`, `reload`.
- Groups are saved through LuckPerms before the whitelist entry is added. Every change is logged, and a change cut off by a crash is found and reported at the next start.
- Safety rules: only `stationmaster.approve` can approve, nobody can approve themselves, restricted and admin-like groups are refused, and a yes is needed before a change links two chat circles or adds a profile with a `confirm` question.
- Someone who loses a world is sent to the lobby. Their things stay with the world they left.
- Paper events for approvals and profile changes, join attempts by people not on the whitelist, and problems, for a Discord bridge or another plugin to use. They can show who is a child, so post them only where just the admins can read.
- The menu reads without colour: each state has a word and a symbol, and it avoids red against green.

## Changes since 0.2.0

- The example profiles and test worlds use new made-up names.
- The leak check now runs in CI.
- The test harness says why a test server stopped, and that Paper 26 needs Java 25.

## Requirements

- Paper 26.1 or newer, on Java 25. There's no 1.21.x support.
- LuckPerms 5.5. Without it, Stationmaster loads but refuses every change.
- Multiverse-Core and Multiverse-Inventories are optional.

## Tested

Bot tests ran on throwaway local Paper 26.1.2, 26.2 and 26.3 servers with LuckPerms, Multiverse-Core, Multiverse-Inventories and made-up worlds and groups, with 100 checks on each. On 26.1.2 the bots are native 26.1 clients. On 26.2 and 26.3 they join through ViaVersion and ViaBackwards and send no movement packets, since those get them kicked. The events were checked with a small test plugin, not a real Discord bridge.

## Not tested

- A real game client, and how the menu reads in one.
- Mojang name lookups on an online-mode server. The tests use offline mode.
- Leaf Juice's own screen, Waystation gates, a Discord bridge, and LuckPerms storage other than YAML.

## Install

1. Put `Stationmaster-0.2.1.jar` in `plugins/` next to LuckPerms, and start the server once.
2. Edit `plugins/Stationmaster/config.yml`. The profiles in it are examples.
3. Create each profile's group in LuckPerms and give it its worlds, then run `stationmaster reload`.
4. Grant `stationmaster.approve` to the people who approve, and nobody else.
