# Usher 0.4.0

Usher's console commands move to the shared Signalbox format, so Leaf Juice and scripts talk to Usher the same way as to Latchkey. The old Stationmaster names are gone, as 0.3.x said they would be.

This is an early 0.x release. It has passed bot tests on throwaway servers, but it hasn't been used on a real server or seen in a real game client.

## Changed

- **Console commands use [Signalbox](https://github.com/ExtantApe/Signalbox) format 1.**
  - A person types `usher <command> …` and gets plain words.
  - A tool adds `--json <id>` and gets exactly one line back, `ea-result <id> <json>`.
  - Questions that need a yes come back as `needs` and change nothing; answer them with `--yes`, such as `--yes bridge`.
  - `describe` lists every command with its typed arguments, marking the optional ones.
  - The README's "Console commands" section has the full list.
- **`apply` takes plain positional arguments:** `apply <uuid> <name> <add|none> <remove|none> [whitelist] [via]`, with profile lists comma-separated. The old `+profile`, `-profile`, `confirm=`, `whitelist=` and `via=` forms are gone.
- **The request id is the `--json` id** and still makes a change safe to resend: it's applied once and answered again with `replayed: true`.
- **Error codes use dashes:** `unknown-profile`, `luckperms-missing` and so on.
- **A profile can't be called `none`,** since commands use `none` for an empty list. A config that has one leaves that profile out and says why.

## Removed

- `usher-result` lines. Nothing published read them.
- The `stationmaster` and `smaster` commands, and the `stationmaster.approve` permission. Grant `usher.approve` instead: `lp group <group> permission set usher.approve true`.

## Upgrading from 0.3.x

- Swap the jar with the server stopped.
- If anything still grants `stationmaster.approve`, grant `usher.approve` too, or that admin can no longer approve.
- Scripts that call `usher` need the new form.
- The old `plugins/Stationmaster/` folder is still copied across on first start if `plugins/Usher/` doesn't exist.

## Tested

- 50 unit tests pass, including the argument rules for `apply`, the reserved `none` and the optional markers in `describe`.
- The bot suite drives every console command in the new format and passes 120/120 on throwaway Paper 26.1.2, 26.2 and 26.3 servers. It covers the raw `ea-result` line, `unknown-command` and `bad-argument` answers, `describe`, questions answered with `--yes`, resending an id, a kill -9 mid-change, and the old Stationmaster names being gone.
- Not tested yet: a real server or client.
