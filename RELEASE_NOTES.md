# Usher 0.3.0

Stationmaster is now called Usher. This release renames it and changes nothing else in how it works. The owner chose the name on 2026-11-08.

This is an early 0.x release. It has passed bot tests on throwaway servers, but it hasn't been used on a real server or seen in a real game client.

## Upgrading from Stationmaster 0.2.1

- Swap `Stationmaster-0.2.1.jar` for `Usher-0.3.0.jar` with the server stopped.
- On first start, `plugins/Stationmaster/` is copied to `plugins/Usher/` if that doesn't exist yet, so your config, change log and join attempts come across. The old folder is left untouched as a backup. Nothing is ever overwritten.
- **Deprecated, removed in the next release:** the permission `stationmaster.approve` and the console command `stationmaster` (and `smaster`). They still work in 0.3.x and log a line each time saying what to use instead. Grant `usher.approve` and use `usher`.
- Changed outright: the console result line is `usher-result <request-id> <json>`, the plugin and jar are `Usher`, and the API package is `com.extantape.usher.api` (`UsherProblemEvent`, `ProfilesChangedEvent`, `JoinAttemptEvent`). Nothing published used the old ones.

## Tested

The full bot suite on a throwaway Paper 26.1.2 server, plus new checks for the rename: the old permission and command still work and log a warning, and a server with only `plugins/Stationmaster/` starts with its config and change log copied byte for byte, the old folder untouched, and no second copy on the next start. Unit tests cover the copy, including a half-finished copy left by a crash.
