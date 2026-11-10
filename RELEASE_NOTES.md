# Usher 0.4.1

A fix to how `attempts` pages, so it matches the shared command format. Nothing else changes.

This is an early 0.x release. It has passed bot tests on throwaway servers, but it hasn't been used on a real server or seen in a real game client.

## Fixed

- **`usher attempts --json` answers in the spec's list shape:** `items`, and `next` as a string cursor, or `null` on the last page. 0.4.0 sent the list under `attempts`, with a numeric `next`, which Leaf Juice found. The cursor is still a position, written as a string. One that Usher didn't hand out is refused with `bad-argument`.
- `describe` gives the cursor's type as `string`.
- Signalbox 0.3.0, whose `Answer.page` makes this shape the default.

## Upgrading from 0.4.0

Swap the jar with the server stopped. Scripts that read `attempts` from the answer read `items` instead.

## Tested

- 51 unit tests pass, including the cursor rules.
- The bot suite passes 121/121 on throwaway Paper 26.1.2, 26.2 and 26.3, including a check of the list's shape.
