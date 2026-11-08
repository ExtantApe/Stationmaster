# Paper 26.x audit

Stationmaster targets Paper 26.1 and newer on Java 25, and is tested on 26.1.2, 26.2 and 26.3. This note says what uses modern Paper APIs, what doesn't, and why. Each API's status was checked against Paper's documentation (docs.papermc.io) on 2026-11-07, and against the `paper-api` 26.1.2 jar it compiles with.

## Moved to modern APIs

| What | Now | Why |
| --- | --- | --- |
| Plugin descriptor | `paper-plugin.yml`, with LuckPerms as an optional server dependency that joins the classpath | The owner's rule: Paper only. Paper's docs still mark Paper plugins as experimental. That's accepted, and every test runs this way. |
| Commands | `BasicCommand`, registered in the `COMMANDS` lifecycle event, instead of `plugin.yml` commands | Paper plugins can't use the old `commands` section. `permission()` hides `/approve` and `/profile` from players without `stationmaster.approve`, so they never see them. |
| Menu items | Data components (`ITEM_NAME`, `LORE`, `ENCHANTMENT_GLINT_OVERRIDE`, `PROFILE`) instead of `ItemMeta` and `SkullMeta` | Shorter, and no meta copies. `ITEM_NAME` is never italic, so names don't need un-italicising. Paper warns that this API can change between versions. That's acceptable for a menu, and the tests cover all three versions. |
| Text | Adventure components everywhere | No legacy colour codes anywhere. |
| Java | Java 25: records, pattern matching and unnamed variables (`_`) where a lambda ignores its arguments | Only where they read more plainly. |
| Version floor | `api-version: '26.1'`, compiled against `paper-api` 26.1.2 | The oldest supported version, so nothing newer can creep in unnoticed. |

## Left as they were

| What | Why |
| --- | --- |
| Identifying the menu with a custom `InventoryHolder` | That's what Paper's docs recommend for telling your own inventories apart in events. |
| A chest inventory instead of `MenuType` | A plain 54-slot chest with a holder does the job, and `MenuType` would add nothing to it. |
| A chest confirmation screen instead of a dialog | The Dialog API would suit a yes/no question. But the chest screen is the same surface the admin is already in, the bots can drive it on every version, and the warnings already fit. Worth a look after real admins try the menu. |
| `AsyncPlayerPreLoginEvent` for join attempts | Not deprecated in 26.x (the build has deprecation warnings on and shows none), and it gives the account and name before the whitelist check. |
| No bootstrapper | Stationmaster registers nothing that needs one. Paper's docs call bootstrappers highly experimental. |
| No NMS | Nothing needs it. |

## Removed

1.21.11 support: `api-version`, the build target, Java 21, and the 1.21.11 test runs. No 1.21-only code paths were left to remove.
