# Self Watch link client

A tiny Android library (Apache-2.0) that lets your phone app exchange messages with **your own
Connect IQ watch app** through [Self Watch](../README.md), the open replacement for the phone side of a
Garmin watch.

- Android 5.0 (API 21) and up. Written in Java: **no dependencies**, not even the Kotlin standard library.
- About 20 KB in your app (less after R8).
- The steps mirror a typical watch-companion toolkit: connect, list watches, check the watch app is
  installed, send with a delivery result, receive, open the app. Porting a companion app is mostly
  renaming.
- Messages use the same value types as Monkey C: `null`, `Boolean`, numbers, `String`, `List`, `Map`.

The user approves your app once per watch app in Self Watch. Until they answer, calls report
`PENDING_APPROVAL`.

## Add it

```kotlin
dependencies { implementation(files("libs/selfwatch-link-client-1.0.aar")) }
```

The library's manifest adds `<queries><package android:name="app.selfwatch"/></queries>`, so Self Watch
is visible to your app on Android 11+. Its consumer R8 rules keep the IPC interfaces.

## Use it (Kotlin)

```kotlin
class WatchBridge(context: Context) : SelfWatchLink.Listener {
    private val watchApp = UUID.fromString("6a1e2c3d-0000-4000-8000-00000000abcd") // your manifest id
    private val link = SelfWatchLink.connect(context, this)

    override fun onReady() {
        link.onMessage(watchApp) { device, message ->
            Log.i("Bridge", "From ${device.name}: $message")      // e.g. {"tack"=true}
        }
        link.devices { status, devices ->
            val watch = devices.firstOrNull { it.isConnected } ?: return@devices
            link.appInfo(watch, watchApp) { _, info ->
                if (info?.isInstalled != true) return@appInfo
                link.send(watch, watchApp, mapOf("speed" to 6.4, "wind" to listOf(12, 270))) { result ->
                    Log.i("Bridge", "send: $result")             // SUCCESS once the watch confirms
                }
            }
        }
    }

    override fun onUnavailable(reason: SelfWatchLink.Reason) {
        // NOT_INSTALLED or DENIED: fall back to another transport. DISCONNECTED: it reconnects by itself.
    }

    fun close() = link.close()
}
```

Every callback runs on the main thread; no method blocks. Need `suspend` functions? Wrap a callback
with your own coroutines library, for example
`suspendCancellableCoroutine { c -> link.devices { s, d -> c.resume(s to d) } }`.

## Use it (Java)

```java
SelfWatchLink link = SelfWatchLink.connect(context, new SelfWatchLink.Listener() {
    @Override public void onReady() { /* make calls */ }
    @Override public void onUnavailable(SelfWatchLink.Reason reason) { }
});
link.send(device, watchApp, Collections.singletonMap("hello", 1), status -> Log.i(TAG, "" + status));
```

## API in brief

| Call | What it does |
|---|---|
| `SelfWatchLink.isInstalled(ctx)` | Whether Self Watch is on the phone |
| `SelfWatchLink.connect(ctx, listener)` | Binds to Self Watch; `onReady` / `onUnavailable(reason)` follow |
| `devices(cb)` | Watches known to Self Watch (`LinkDevice`: id, name, connected) |
| `appInfo(device, uuid, cb)` | Is the watch app installed, and its version |
| `send(device, uuid, message, cb)` | Send; `cb` gets `SUCCESS` when delivered or the reason it failed |
| `onMessage(uuid, handler)` | Receive messages from your watch app (`null` handler removes it) |
| `openApp(device, uuid, cb)` | Ask the watch to open your app |
| `setDeviceStatusListener(l)` | Watches connecting and disconnecting |
| `close()` | Unregister and unbind |

`SendStatus`: `SUCCESS`, `FAILED`, `TOO_LARGE`, `NOT_INSTALLED`, `NOT_CONNECTED`, `DENIED`,
`PENDING_APPROVAL`, `INVALID_MESSAGE`, `UNAVAILABLE`.

Keep messages small: the watch accepts only a few hundred bytes per message. Received numbers arrive
as `Long` or `Double`, maps as an ordered `LinkedHashMap`.

## License

Apache License 2.0, see [LICENSE](LICENSE). Self Watch itself is AGPL-3.0-or-later; using this client
does not put your app under the AGPL. This library contains no Garmin code.
