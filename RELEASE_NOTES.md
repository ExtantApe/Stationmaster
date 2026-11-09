# Usher 0.3.1

Usher now works with Latchkey 0.2.0's invites. Nothing else changes, and Usher still runs the same without Latchkey.

This is an early 0.x release. It has passed bot tests on throwaway servers, but it hasn't been used on a real server or seen in a real game client.

## New: profiles on Latchkey invites

- **When an admin makes an invite that names profiles,** Usher checks them straight away with its usual rules. Unknown or restricted profiles are refused, so a bad invite is never made. Anything that needs a yes, such as joining two chat circles or a profile with a `confirm` question, comes back as a question. The admin answers it with `--yes` on the same command.
- **When the invite is used,** Usher applies the profiles through the same path as any approval, with the yeses given when the invite was made. The change log records it as "Latchkey invite". If something has changed since, nothing is applied and the server log says why.
- **An invite with no profiles** gives lobby-only access until an admin adds more.
- Usher doesn't need Latchkey to build or run. It finds Latchkey's events by name when Latchkey is installed.

## Upgrading from 0.3.0

Swap the jar with the server stopped. Nothing else changes. The old names from Stationmaster (`stationmaster.approve`, the `stationmaster` and `smaster` commands) still work in 0.3.x, as promised, and go in the next release.

## Tested

- 43 unit tests pass, including 9 new ones for the invite checks.
- Latchkey's bot test runs this code with Latchkey 0.2.0 on throwaway Paper 26.1.2, 26.2 and 26.3 servers, and passes 38/38 on each. It covers a refused profile, a question answered with `--yes`, and both profiles applied when the invite is used.
- Not tested yet: a real server or client.
