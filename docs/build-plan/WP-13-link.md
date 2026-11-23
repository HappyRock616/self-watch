# WP-13 link (watch-app messages for other phone apps) and link-client

Lane F. Milestone M5 (can move earlier if the TCS Companion needs it). `service/link/` (AGPL, runs in `:watch`) and the `link-client` library (Apache-2.0). Depends on: core-model, core-db, garmin (via `WatchDriver`). The design and the comparison with Garmin's toolkit are in [../DESIGN.md](../DESIGN.md) §4.

## Goal

Any approved phone app can send messages to its own Connect IQ watch app and receive replies through Self Watch, with the same steps and message types as Garmin's phone toolkit, under our own names. Apps written for Gadgetbridge's Intent also work.

## Interface (AIDL, shared by both sides)

`link-client/src/main/aidl/app/selfwatch/link/ILinkService.aidl`, also compiled into `service`:
```aidl
package app.selfwatch.link;
import app.selfwatch.link.ILinkCallback;
interface ILinkService {
    int apiVersion();                                        // 1
    List<String> devices();                                  // JSON strings: {"id":…,"name":…,"connected":true}
    String appInfo(long deviceId, String appUuid);           // JSON: {"installed":true,"version":12} or error
    void register(String appUuid, ILinkCallback cb);         // receive messages for this watch app
    void unregister(String appUuid);
    int send(long deviceId, String appUuid, in byte[] message, int requestId);   // returns 0 queued, or an error code
    int openApp(long deviceId, String appUuid);
}
oneway interface ILinkCallback {
    void onMessage(long deviceId, String appUuid, in byte[] message);
    void onSendResult(int requestId, int status);            // 0 delivered, 1 failed, 2 too large, 3 not installed, 4 not connected, 5 denied
    void onDeviceStatus(long deviceId, boolean connected);
}
```

**Message bytes:** a compact binary encoding of `CiqValue` (`LinkCodec`: type byte + value; lists, maps, strings, ints, doubles, booleans, null). It's identical on both sides and is not Garmin's wire format. `service` converts it to `CiqValue` and `garmin` encodes it for the watch.

## `link-client` (what another app adds)

```kotlin
class SelfWatchLink private constructor(…) {
    companion object {
        fun connect(ctx: Context, listener: Listener): SelfWatchLink   // binds with Intent("app.selfwatch.link.BIND").setPackage("app.selfwatch")
        fun isInstalled(ctx: Context): Boolean
        const val SELF_WATCH_PACKAGE = "app.selfwatch"
    }
    suspend fun devices(): List<LinkDevice>
    suspend fun appInfo(device: LinkDevice, appUuid: UUID): AppInfo
    fun send(device: LinkDevice, appUuid: UUID, message: Any?, onResult: (SendStatus) -> Unit)   // Any? = List, Map, String, Number, Boolean, null
    fun onMessage(appUuid: UUID, handler: (LinkDevice, Any?) -> Unit)
    fun openApp(device: LinkDevice, appUuid: UUID, onResult: (SendStatus) -> Unit)
    fun close()
    interface Listener { fun onReady(); fun onUnavailable(reason: Reason) /* NOT_INSTALLED, DENIED, DISCONNECTED */ }
}
```

- **API 21+** (other apps may support older phones).
- **No dependencies:** no Kotlin coroutines needed by callers. Suspend functions are offered plus callback versions for Java.
- **Manifest:** `<queries><package android:name="app.selfwatch"/></queries>` (package visibility, API 30+). It's merged automatically from the library.
- **Size:** under 30 KB in the other app.
- **Sample app** (`samples/link-sample/`, not published to stores): shows one companion app that uses `SelfWatchLink` when Self Watch is installed, and Garmin's toolkit otherwise. The Garmin side is code the sample's own author would add under Garmin's license; our repo includes only the Self Watch side and a clearly marked stub.

## `service/link/` (Self Watch side)

| Class | Job |
|---|---|
| `LinkService` | Bound service (`exported="true"`, action `app.selfwatch.link.BIND`). Returns the `ILinkService` binder |
| `LinkPermissions` | On each call: `Binder.getCallingUid()` → package name(s) → signing certificate SHA-256 (`PackageManager.getPackageInfo(pkg, GET_SIGNING_CERTIFICATES)` on 28+, `GET_SIGNATURES` below). Looks up `link_permission (package, app_uuid)`. Unknown pair → posts an approval notification ("Allow Third Coast Sailing to message its watch app on your vívoactive 5?" Allow / Deny) and returns `DENIED_PENDING`. A certificate mismatch is always denied (it protects against a look-alike app) |
| `LinkRouter` | Keeps `appUuid → (package, callback)` registrations. Delivers `WatchEvent.AppMessageReceived` to the registered callback. If none is registered, it sends an explicit broadcast `app.selfwatch.link.MESSAGE` to the approved package (so apps not bound right now still get it), and keeps up to 20 messages per app for 10 minutes for apps that bind later |
| `SendQueue` | Per (device, app), one message in flight. Size check before sending (about 266 bytes after encoding at the default packet size; reports `TOO_LARGE`). Retries once on timeout, then reports `FAILED` |
| `GadgetbridgeIntentReceiver` | Accepts `nodomain.freeyourgadget.gadgetbridge.command.SEND_WATCH_APP_MESSAGE` (extras `device` address, `appId` UUID, `payload` JSON or text; JSON is parsed into `CiqValue`, text becomes a string). Same permission check, using the sender's package: API 34+ `getSentFromPackage`, otherwise the approval flow by app id with "unknown app" wording. Replies from the watch go to the approved package's broadcast, which is what Gadgetbridge doesn't do |
| `CaloriesLeft` | Optional: sends the energy budget to one chosen watch app (WP-07) |

**Linked phone apps screen** (WP-10) lists `link_permission` rows with last use. Revoking deletes the row and unbinds that app's callbacks.

## Variables

`registrations: ConcurrentHashMap<UUID, Registration(pkg, uid, callback, deathRecipient)>`, `pendingApprovals: Map<Pair<String, UUID>, Long>`, `inbox: Map<UUID, ArrayDeque<Pending>>` (max 20, 10 min), `sendQueues: Map<Pair<Long, UUID>, Channel<OutMsg>>`, error codes as in the AIDL file.

## Compatibility checklist

- [ ] Client API 21+.
- [ ] Service checks certificates on API 26–27 (old API) and 28+.
- [ ] Bound service survives the `:watch` process restarting (client reconnects with `ServiceConnection.onBindingDied` → rebind).
- [ ] Broadcasts are explicit and package-restricted.

## Tests and definition of done

- [ ] `LinkCodec` round trips (all types, nesting, Unicode, limits).
- [ ] Permission flow with Robolectric shadows for `PackageManager` and `Binder` uid.
- [ ] Instrumented test with the sample app on the Pixel 5 and a fake driver: send, receive, too large, denied, revoked.
- [ ] Live TCS round trip with the vívoactive 5 (T-F12, with HappyRock).
- [ ] `link-client` published as an AAR in GitHub releases with its Apache-2.0 license and a README usage example.

## LinkCodec ↔ CiqValue mapping (F03, for the service agent)

`link-client` is written in **Java** (no Kotlin stdlib or coroutines reach other apps), so its API takes callbacks rather than `suspend` functions; the README shows a one-line coroutine wrapper. The service depends on `:link-client` for the AIDL stubs, `LinkCodec` and `LinkStatus`, and converts LinkCodec's Java values to `CiqValue` in its own Kotlin file (suggested: `service/link/LinkValues.kt`, `toCiq(Any?): CiqValue` and `fromCiq(CiqValue): Any?`). There is one codec implementation: always go through `LinkCodec.decode` / `LinkCodec.encode`.

| LinkCodec tag | Java value (client side) | `CiqValue` | Notes |
|---|---|---|---|
| `0x00` | `null` | `CiqValue.Null` | |
| `0x01` / `0x02` | `Boolean` | `Bool(false/true)` | |
| `0x03` zigzag varint | `Long` (encode also takes Byte, Short, Integer) | `Num(v)` | 64-bit on both sides. Choosing Monkey C Number (32-bit) or Long for the watch is `garmin`'s job |
| `0x04` 8-byte IEEE 754 | `Double` (encode also takes Float) | `Dbl(v)` | Float or Double on the watch is `garmin`'s job |
| `0x05` strict UTF-8 | `String` (encode also takes CharSequence, Character) | `Str(v)` | Unpaired surrogates are rejected |
| `0x06` | `ArrayList` | `Arr(list)` | |
| `0x07` | `LinkedHashMap` (order kept) | `Dict(map)` | Keys must be null, Boolean, Long, Double or String, and unique. A watch `Dict` with an `Arr`/`Dict` key can't be passed to a phone app: drop it and log at DEBUG |

- **Format:** the first byte is the format version (`1`), then exactly one value, no trailing bytes. Varints are shortest-form LEB128. Limits: `LinkCodec.MAX_MESSAGE_BYTES` = 16 KiB, `LinkCodec.MAX_DEPTH` = 16 nested lists/maps. Decoding bad bytes only ever throws the checked `LinkCodecException` (fuzz-tested); the service answers `INVALID_MESSAGE` (7).
- **Watch → phone:** `garmin` decodes to `CiqValue` → `fromCiq` → `LinkCodec.encode` (catch `IllegalArgumentException`: drop and log) → `ILinkCallback.onMessage`.
- **Phone → watch:** `LinkCodec.decode` → `toCiq` → `WatchCommand.AppMessage`. `SendQueue`'s size check uses `garmin`'s encoding, not the LinkCodec bytes.

**Status codes** (`app.selfwatch.link.client.LinkStatus`; typed as `SendStatus`): 0 `SUCCESS` (delivered; open request sent; as the return value of `send`, "queued"), 1 `FAILED`, 2 `TOO_LARGE`, 3 `NOT_INSTALLED`, 4 `NOT_CONNECTED`, 5 `DENIED`, 6 `PENDING_APPROVAL` (the approval notification is showing; this is the spec's `DENIED_PENDING`), 7 `INVALID_MESSAGE`, 8 `UNAVAILABLE` (client side only: not bound). The client maps unknown codes to `FAILED`, so codes can be added later.

**JSON strings in the AIDL:** `devices()` returns `{"id":<Long deviceId>,"name":"…","connected":true}` per watch. `appInfo()` returns `{"installed":true,"version":12}`, or `{"error":<code>}` (for example 4 when not connected, 5 or 6 when not approved).

**Client behavior the service can rely on:** `send` request ids are unique per client connection (a counter from 1); the client expects exactly one `onSendResult` per queued send, and fails pending sends itself when the binding drops. `register` is called again after every reconnect, with the same callback binder per client. A `SecurityException` thrown from a call reaches the app as `DENIED`, but prefer returning codes. Returning `null` from `onBind` reaches the app as `Reason.DENIED` (use it when the link feature is switched off).

## Service side as built (F05–F08)

- **Permissions:** `link_permission` rows hold the certificate digest seen when the user answered (SHA-256 hex of each signer, sorted, comma-joined). A different certificate is always `DENIED`. An unanswered question returns `PENDING_APPROVAL` and is shown again after 10 minutes. A `register` while pending is kept and becomes active when the user taps Allow. Revoking (deleting the row) unregisters the app's callback; its next call asks again. Setting `link.enabled=false` makes every call `DENIED` (`onBind` still answers, since reading settings on the main thread is avoided).
- **Gadgetbridge Intents** from an unknown sender use the pseudo package `?` (empty certificate); such a row never receives broadcasts.
- **Broadcast to apps that are not bound:** action `app.selfwatch.link.MESSAGE`, `setPackage(<approved package>)`, extras `deviceId` (Long), `appUuid` (String), `message` (byte[], LinkCodec) and `payload` (String, the same value as JSON, for apps written for Gadgetbridge).
- **Size check:** the app wires garmin's encoding (`AppMessages.pad(CiqCodec.encode(v))`) against 14 × 19 = 266 bytes; the driver's own "too large" also maps to `TOO_LARGE`.
- **CaloriesLeft:** setting `link.calories_app` (a watch-app UUID). `kcalLeft` uses the `KCAL_IN` goal in the goal table (the food module's automatic goal is not visible to `:watch`); it is null when no goal row exists.
