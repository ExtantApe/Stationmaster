# Decisions

Decisions the owner made for Stationmaster, with dates. Follow them without asking again. If one needs to change, ask the owner and add the new decision here with its date. Don't edit the old entry.

## From the build brief (before 2026-11-07)

- Stationmaster must make sense on a server without Waystation. Nothing in its commands, messages or default config assumes stations or gates.
- More than one profile can be granted to a person.
- `/approve` and the console take a name that has never joined, in any capitals, find the real account, and fail clearly on a typo.
- Leaf Juice connects through the console, so the safety rules live in one place. Stationmaster provides a console command taking the UUID, correct name and profiles; a list of the profiles it can offer; one parseable result line per request ID; a way to confirm warned changes from the Mac; and groups applied before the whitelist.
- Requests from the Mac are logged as console actions from Leaf Juice, and the no-self-approval rule doesn't apply to them.
- The whitelist stays on, with no visitor mode or kick timer.
- Approved with no profile means whatever LuckPerms' default group allows.
- Profiles and extra toggles live in config. One toggle is for adults and asks for a yes.
- Some groups are never selectable. They're listed in config and refused even if config tries to offer them.
- Only the approve permission can approve. Nobody can approve themselves or edit their own profiles, even with it.
- Adding an adult profile to someone with the kids' profile warns first, because it would link the kids' and adults' chat.
- Changes apply at once and survive restarts, through LuckPerms' API.
- Removing a profile while someone is in that world sends them safely to the lobby. Their inventory stays with that world.
- `/profile <name>` shows current profiles. Every change is logged: who, what and when.
- Results are friendly messages, not system text.
- Not in version one: NPC requests and `/approvals`.

## 2026-11-07

- **Permission:** `stationmaster.approve`.
- **Where approval happens:** mainly outside the game, through Leaf Juice. A chest menu stays as the in-game fallback.
- **Hooks for Discord and the like:** Paper events only. Stationmaster sends nothing over the network itself. The events cover approvals and profile changes, join attempts by people not on the whitelist, and failures.
- **How Leaf Juice sees what's waiting and what changed:** console commands that answer with one parseable line each. No separate JSON file.
- **Minecraft versions:** Paper 26.1 and newer only, on Java 25, tested on 26.1, 26.2 and 26.3. No 1.21.x. (From the owner's standards in `~/Code/CLAUDE.md`.)
- **Paper only:** `paper-api` and `paper-plugin.yml`, not Spigot. (Same source.)
- **Done well, not just done:** pick the accessible option with good UX where there's a choice, including colours that work for colour-blind players. (Same source.)
- **Kids come first:** default to children's safety and privacy. (Same source.) See `docs/kids-first.md`.
- **Releases:** release as soon as it's good, as 0.x versions on GitHub Releases only. Modrinth, Hangar and Spigot wait for the owner's own code review. (Relayed from the tracking chat.)
- **Public:** the repo went public after the first-public-push audit. Two old example names in early commits are ordinary Minecraft words, and the owner chose to leave history as it is.
- **Name (2026-11-08, owner):** Stationmaster is renamed Usher, one word in every place: repo, plugin name, jar, package and prose. Released as 0.3.0. Existing servers get their `plugins/Stationmaster/` folder copied on first start (never overwritten), and the old permission and console command keep working for one release with a log warning. The `stationmaster-result` console line became `usher-result` outright, since Leaf Juice doesn't parse it yet.
- **Invites (2026-11-09, owner):** when a Latchkey invite is redeemed, the username is whitelisted at once and Usher, if installed, applies the profile the invite names, through its usual safety rules. To be built with Latchkey 0.2.0 (crossing `latchkey-usher`).
