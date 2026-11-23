# Link sample (companion app)

A one-screen phone app that shows how a watch-app companion uses the
[Self Watch link client](../../link-client/README.md). Apache-2.0. Not published to any store.

- Enter your Connect IQ watch app's UUID (the `id` in its `manifest.xml`), tap **Send test message**,
  and the app sends `{"hello": "from the phone", "tSec": …, "values": [1, 2.5, true, null]}`. Delivery
  results and replies from the watch app appear below.
- The first send makes Self Watch ask you to approve the sample for that watch app; until you answer,
  the result is `PENDING_APPROVAL`.

## Two transports, one switch

`CompanionTransport` is the only thing the rest of the app sees:

| Class | When | What |
|---|---|---|
| `SelfWatchTransport` | Self Watch is installed | Uses `SelfWatchLink` |
| `GarminToolkitTransport` | otherwise | **Stub.** Self Watch's repository never includes Garmin's toolkit. Implement it in your own app with Garmin's toolkit under Garmin's license |

## Build

The sample is left out of the normal build. From the repository root:

```
./gradlew -PwithSamples :samples:link-sample:assembleDebug
```

The APK is in `samples/link-sample/build/outputs/apk/debug/`.
