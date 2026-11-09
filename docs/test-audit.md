# Test audit

Kept by this repo's owning session (Minecraft: Plugins Lead), per `~/Code/CLAUDE.md`, "Test self-audits". Newest first.

## 2026-11-09: first full audit (at 0.3.0)

Usher adds people to kids' groups, so the promises in `docs/kids-first.md` come first. "Live" is `test/acceptance.mjs` on throwaway Paper servers with bots and LuckPerms; "unit" is `src/test`.

| Promise | Covered by |
| --- | --- |
| Nobody gets in by accident: whitelist on, approving needs `usher.approve` or the console | live "a stranger is turned away", "a player without the permission gets no menu", "every command is hidden from them", "strangers still can't" |
| Nobody approves themselves, in any capitals | live "self-approval and self-editing are refused, in any capitals" |
| **The right account: an account that has never tried to join gets a ⚠ warning in the menu** | **new** live "…gets the ⚠ warning in the menu", and "an account that did try to join gets no never-tried warning"; `resolve` facts: live "resolve tells Leaf Juice who tried to join and who never did" |
| Kids' and adults' circles stay apart unless an admin says yes; a yes never covers another question | unit `bridgingCirclesNeedsAYes`, `bridgingWhenBothAreAddedAtOnce`, `adultToggleAsksForAYesAndAYesForOneThingDoesNotCoverAnother`; live warning screen, Go back, Yes |
| No admin powers through a profile, even with an edited config | unit `aProfileThatInheritsARestrictedGroupIsRefused`, `aProfileThatGrantsAdminPowersIsRefused`, `restrictedGroupsAreRefusedEvenWithOtherCapitals`; live "config tricks are refused", "a group that became unsafe after the menu opened is refused at save time" |
| Losing a world sends them to the lobby kindly; their things stay with the world | live "they land in the lobby", "lobby inventory is back", "they were told kindly", "the 7 emeralds are still there" |
| **Stores no IP addresses** | **new** live "no IP address is stored" (`changes.log`, `join-attempts.json`, after bots from 127.0.0.1) |
| Groups saved before the whitelist; LuckPerms all or nothing; a crash is found and reported | unit `findsChangesCutOffByACrash…`, `onlyFinishedRequestsAreReplayed`; live kill -9 at each step, "when LuckPerms can't save" |
| Menu items can't be taken or duplicated | live "no menu item can be taken…", "pressing Save three times applies once" |
| One answer line per console request; repeats not applied twice | live "every request gets exactly one result line", "a repeated request ID is answered again but not applied twice" |
| Broken config refuses every change; a broken reload keeps the old settings | unit `brokenYamlRefusesEverything`, `brokenSettingsRefuse`; live |
| Without LuckPerms: loads, refuses, says why | live "LuckPerms missing" |
| Upgrade from Stationmaster: folder copied, old names work for one release | unit `MigrationTest`; live "The rename from Stationmaster" |

**Gaps added this audit:** the two in bold. Both passed on Paper 26.1.2 and 26.3 (119/119 each).

**Skipped, with reasons:**
- "Sends nothing anywhere except name lookups to Mojang": the only network call in the code is `NameResolver`'s Mojang lookup (checked by reading the code at this audit). A test that watches all network traffic would cost more than it proves. The bots run with offline lookups.
- How Usher looks to a person (menu colours, wording): needs a real client. It's on the real-client list.
