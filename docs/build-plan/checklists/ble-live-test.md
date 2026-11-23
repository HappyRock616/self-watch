# Morning live test: pair and sync (A12)

For HappyRock: Pixel 5 (Android 17) and the vívoactive 5. About 30 minutes. Write what you see in the **Result** column and send this file back with the captures and logs.

## Before you start

- [ ] Phone plugged into the computer, Bluetooth on. Watch charged, within 1 m.
- [ ] On the watch: remove the phone from Garmin Connect. On the phone: forget the watch in **Settings → Connected devices**. Don't factory-reset anything.
- [ ] Install the debug build from the `SelfWatch` folder:
  `./gradlew :app:installDebug`
- [ ] Start the log in a second terminal and leave it running:
  `adb logcat -s SyncService DeviceManager BlePortFactory GarminDriverFactory garmin garmin.link BleConnection GattQueue Pairing Bonding WatchStatus`

## Steps

| # | Do | Success looks like | Result |
|---|---|---|---|
| 1 | Open **Self Watch**. A short setup (onboarding) opens first: tap **Skip setup** (or pair your watch at its step 4 instead of step 2 below). Then open the **Devices** tab. | "My watch" card with **Pair watch**. A **Debug** card with **Bluetooth capture** switched on. || Pass (2026-11-23). Gadgetbridge held the watch; Android relaunched it after a force-stop, so it had to be disabled (`pm disable-user`) for the test. |
| 2 | Tap **Pair watch**. | A one-line reason, then Android asks for **Nearby devices**. Allow. No location prompt. || Pass. Reason line, then Nearby devices; no location prompt. |
| 3 | The system picker opens. Pick the vívoactive 5. | Toast "Paired with vívoactive 5". || Pass. Picker was empty until Gadgetbridge let go; then it listed vívoactive 5. |
| 4 | Android asks about **notifications** (after a one-line reason). Allow. | A "Self Watch" notification appears (the watch connection). || Pass. |
| 5 | Watch the card. If the watch or phone shows a pairing code, confirm it. Note which side asked, and when. | Status goes **Connecting… → Setting up… → Connected**, then **Syncing n of m (…)**. || Pass. No pairing code on either side (existing bond reused). Connected in under 3 s: MTU 247, V2 multi-link, product 4426, firmware 17.05, protocol 151, 63 capabilities, READY. |
| 6 | Wait for the sync to finish (a few minutes for a full day). | Status back to **Connected**. "Last sync Today hh:mm" and a battery level. || Pass. 22 files, 0 failed, 16 s. |
| 7 | Look at **Data so far**. | Activity minutes today and Steps today above 0. Heart rate readings above 0. Sleep stages last night above 0 if you wore it last night. Watch files stored above 0. || Pass. 3,044 steps, 2,689 readings, 12 sleep stages, 22 files. Sleep only 2h 41m: Gadgetbridge had already collected most of the night. |
| 8 | Compare **Steps today** with the watch's step count. | Close (the watch may have walked on since the sync). || Pending: owner to compare with the watch. |
| 9 | Tap **Sync now**. | Toast "Syncing…". Status shows Syncing, then Connected; "Last sync" updates. || Pass. 1 new file, 0 failed, 2 s. |
| 10 | Walk away until the link drops (or put the watch in a metal tin), stay 2 minutes, come back. | Status shows "Connection failed… Trying again in N s", then reconnects by itself within a few minutes of coming back. || Not run (owner deferred). |

If a step fails, note the status text exactly, keep going if you can, and send everything below.

## Send back

1. In **Devices → Debug**, tap **Share Bluetooth capture** and send the `.bin` file(s) to yourself (email or Drive). They are in `files/blelog/` on the phone.
2. Tap **Share logs** and send `app.log` and `watch.log` (in `files/logs/`).
3. This file with the Result column filled in.

Fallback over USB if sharing fails:
`adb exec-out run-as app.selfwatch.debug sh -c 'cat files/blelog/*.bin' > capture.bin`
`adb exec-out run-as app.selfwatch.debug cat files/logs/watch.log > watch.log`

Captures contain message payloads, so keep them private; only sessions with no personal notifications go into `testdata/sessions/`.

## Afterwards (optional)

- **Remove watch** in Devices removes it from Self Watch; the data stays. Note whether it said to also forget the watch in Bluetooth settings.
- To start over: Remove watch, forget it in Android Bluetooth settings, then pair again from step 2.

## What gets checked in the files

- Pairing worked with no location permission; whether and when the watch needed a bond.
- MTU (`connected, mtu N` in the log), the V2 multi-link service, the handshake reaching `READY`.
- The sync: directory size, files fetched, any decode or archive failures.
- The capture reads back with `BleLogFormat.decode`.
