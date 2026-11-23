# Release APK size budget

`check.sh` fails CI when the release APK is bigger than `budget.txt` (bytes) or contains any
`lib/` entry (native code is not allowed: F-Droid, 16 KB pages, no non-free blobs).

Run locally:

```sh
./gradlew :app:assembleRelease
sh tools/size/check.sh app/build/outputs/apk/release/app-release-unsigned.apk
```

## Baseline (M0)

| Date | Commit | Unsigned release APK | Budget | Why |
|---|---|---|---|---|
| 2026-11-23 | M0 skeleton + launcher icon | 1,218,568 bytes (1.16 MiB) | 1,584,139 bytes (baseline x 1.3, rounded up) | First budget |
| 2026-11-23 | CameraX removed (native code, Dagger, Guava) | 968,971 bytes (946 KiB) | 1,259,663 bytes (baseline x 1.3, rounded up) | Decision; barcode scanning uses built-in Camera2 |
| 2026-11-23 | Real modules landed: garmin + fit, food, interop (+ Health Connect client), service, charts, screens | 1,723,070 bytes (1.64 MiB): dex 1.65 MB, resources 0.52 MB | 2,239,991 bytes (x 1.3) | Growth is our own code plus Material; no single heavy library |
| 2026-11-23 | All screens + Garmin protobuf + Health Connect client | 2,930,091 bytes (2.79 MiB): dex 3.75 MB | 3,809,119 bytes (x 1.3) | TEMPORARY, pending HappyRock: the Health Connect client pulls in Guava, a shaded protobuf and coroutines-guava. Proposal: use the built-in Health Connect API on Android 14+ (no library) and drop the client |
| 2026-11-23 | Health Connect via built-in Android 14+ API; client library removed | 2,783,365 bytes (2.65 MiB): dex 3.40 MB | 3,618,375 bytes (x 1.3) | HappyRock's decision: no Guava, shaded protobuf or coroutines-guava. Saves 146,726 bytes (dex 3.75 to 3.40 MB); the rest of the earlier jump is our own screens and the Garmin protobuf |

The M0 app is empty, but its modules already pull in their libraries (material, camera, Health
Connect client, coroutines), which R8 shrinks to this size.

## Resolved: CameraX native code

CameraX shipped native libraries (`libimage_processing_util_jni.so`, `libsurface_util_jni.so`) and pulled in Dagger and Guava through camera-pipe. Decision (2026-11-23): CameraX is removed; WP-11 drives the camera with the built-in Camera2 API. The native-code check stays strict.

## Raising the budget

Raise `budget.txt` only in a commit (and pull request) that says why. Any increase over 100 KB
needs a sentence explaining what was added. Add a row to the table above.
