# Recorded Bluetooth sessions

BleLog captures (WP-05 "Bluetooth capture") of real watch sessions, replayed by
`garmin/src/test/kotlin/app/selfwatch/garmin/replay/ReplayTest.kt` on every build. None yet (T-A08).

## File format

Each `.bin` file is a sequence of records, big-endian (Java `DataOutputStream`):

| Field | Size | Meaning |
|---|---|---|
| time | 8 bytes | milliseconds (any epoch; only the order matters) |
| direction | 1 byte | 0 = phone wrote to the watch, 1 = notification from the watch |
| pipe | 1 byte | order the pipe was opened: 0 = GFDI pipe (multi-link or V1), 1 = watch-app message pipe |
| length | 2 bytes | number of bytes that follow |
| bytes | length | the GATT value |

The `ble` module's `BleLog` writer must use this exact layout (WP-05 does not yet say "big-endian";
this README is the contract until it does).

## Naming

`<watch>-<what>-<yyyymmdd>.bin`, for example `vivoactive5-pairing-20261201.bin`. Sessions are assumed
to use the V2 multi-link service; put `.v1.` in the name for a V1 watch (`fr235.v1.sync-20261201.bin`).

## Privacy

Captures contain health data and notification text. Only commit sessions recorded on a test account,
with notifications off or containing test text, and never the unit's real serial if it can be avoided.
