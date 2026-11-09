# Kids first

Children play on the servers Usher is built for, and it's the plugin that adds people to kids' groups. This note says how it protects them and what it leaves to the people running it. Reviewed 2026-11-07.

## What Usher does

- **Nobody gets in by accident.** The whitelist stays on. Approving someone takes an admin with `usher.approve` or the console, and nobody can approve themselves.
- **The right account.** Before approving, the menu says whether the account has played here, has tried to join (and when), or has never come near the server. An account that has never tried to join gets a ⚠ warning to check it's the right person. `resolve` gives Leaf Juice the same facts (`playedBefore`, `triedToJoin`, `lastTried`), so it can warn too. A mistyped name that happens to belong to a stranger is the main way a stranger could end up in a kids' world. This makes that mistake visible before it happens.
- **Kids' and adults' chat stay apart unless an admin says yes.** A profile can name its chat circle. Any change that would put someone in two circles at once stops and asks first, in the menu and from Leaf Juice. A yes to one question never covers another.
- **Adult-only extras ask first.** A profile with a `confirm` question can't be added without a yes.
- **No admin powers through a profile.** Restricted groups, groups that inherit them, and groups granting `*`, the approve permission or LuckPerms editing are refused. This holds even if the config is edited to offer them.
- **Leaving a world is safe.** Someone who loses a world is sent to the lobby with a kind message. Their things stay in that world, ready if it's given back.
- **Little data, kept on the server.** Usher stores names, account IDs, profiles, times and who made each change. That's in `changes.log` and `join-attempts.json`. It stores no IP addresses, chat or locations, and sends nothing anywhere except name lookups to Mojang.

## What it leaves to the people running it

- **Where events go.** `JoinAttemptEvent` and `ProfilesChangedEvent` carry names and which profiles someone has, which can show who is a child. A Discord bridge should post them only to a channel that just the admins can read.
- **Group permissions.** Usher adds and removes groups. Which worlds and chat a group opens is set in LuckPerms. The kids' groups should open only the kids' worlds.
- **Setting circles.** The two-circles warning only works for profiles that have `circle` set. Every kids' and adults' profile should have one.
- **Who holds the permission.** Grant `usher.approve` to the trusted adults who approve, and nobody else.
