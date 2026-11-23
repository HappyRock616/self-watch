# WP-06 garmin (Garmin protocol and features)

Lane A. Milestones M1 (link, messages, session, files, sync) and M5 (features, watch apps, app messages). Kotlin-only (JVM) module. Depends on: core-model, fit, protobuf-javalite (Java lite runtime, works on the JVM). **Never imports Android.**

## Goal

Implement `WatchDriver` (WP-02) for Garmin watches. It runs over any `BlePort`, so the whole stack can be tested on a computer by replaying recorded sessions. Learned from `references/Gadgetbridge/.../service/devices/garmin/` (read, never copied; facts get `// Ref:` comments).

## Layers

```
GarminDriver (WatchDriver)
 ├─ features/   notifications, calls, music, weather, find, alarms, time, settings, agps, web, watch apps, app messages
 ├─ files/      directory, download, upload, flags, SyncPlan
 ├─ pb/         protobuf request/response service (our own .proto subset)
 ├─ session/    handshake, capabilities, state machine, keep-alive, auth replies
 ├─ gfdi/       message framing, ids, status replies, request matching
 └─ link/       V1 single pipe, or V2 multi-link (registration + reliable layer) → BytePipe(s) from ble
```

### 1. `link/`: choosing and driving the Bluetooth link

```kotlin
interface GfdiTransport { val incoming: Flow<ByteArray>; suspend fun send(frame: ByteArray): Outcome<Unit>; val maxPacket: Int }
object LinkSelector { suspend fun open(port: BlePort): Outcome<GfdiTransport> }
```

**Constants:**

| Name | Value |
|---|---|
| V2 multi-link service | `6a4e2800-667b-11e3-949a-0800200c9a66` |
| V2 receive characteristics | `6a4e2810` … `2814` (same suffix) |
| V2 send characteristics | receive + 0x10: `6a4e2820` … `2824` |
| V1 service / send / receive | `6a4e2401-…` / `6a4e4c80-…` / `6a4ecd28-…` |
| V0 (unsupported) | `9b012401-bc30-ce9a-e111-0f67e491abde` |

All V1/V2 UUIDs end `-667b-11e3-949a-0800200c9a66`.

**V2 multi-link (`MultiLink`):**
- Uses the first receive/send pair that exists.
- **Handle 0 is the control channel:**
  - Register a service by sending a register request with the service code. The reply assigns a handle (1–15).
  - Service codes: GFDI 1, REALTIME_HR 6, REALTIME_STEPS 7, REALTIME_CALORIES 8, REALTIME_HRV 12, REALTIME_STRESS 13, KEEP_ALIVE 22, …
  - "Close all" resets the link. Control message types (confirmed against reference, T-A09): REGISTER_REQ 0, REGISTER_RESP 1, CLOSE_HANDLE_REQ 2, CLOSE_HANDLE_RESP 3, CLOSE_ALL_REQ 5, CLOSE_ALL_RESP 6. See "Confirmed against reference" below for byte layouts.
- **Reliable layer (`Mlr`)**, used when the handle's flag bit is set. Reference constants:

  | Constant | Value |
  |---|---|
  | `MLR_FLAG_MASK` | 0x80 |
  | `HANDLE_MASK` | 0x70 (shift 4) |
  | `REQ_NUM_MASK` | 0x0F |
  | `SEQ_NUM_MASK` | 0x3F |
  | max unacked | 0x20 (32) |
  | retransmit timeout | starts at 1,000 ms, max 20,000 ms |
  | ack timeout | 20 ms |
  | ack after | 5 frames |

  Implements sequence numbers, acknowledgements, retransmission and the send window.
- Each registered service becomes a logical channel. GFDI is the one `gfdi/` uses; realtime channels are opened only when a screen needs live data (later).

**V1:** one pipe, no registration. GFDI frames go straight over it.

### 2. `gfdi/`: messages

**Frame on the wire:** COBS-encoded with a 0x00 delimiter (`Cobs.encode/decode`, our own). Inside:
- length (u16 LE, the whole frame including CRC)
- message type (u16 LE)
- payload
- CRC-16 (u16 LE, the algorithm in WP-04)

**Compact ids:** if `type and 0x8000 != 0`, then `id = (type and 0xFF) + 5000` and `seq = (type shr 8) and 0x7F`.

**Message ids used in v1:**

| Id | Name | Direction | Purpose |
|---|---|---|---|
| 5000 | RESPONSE | both | Status reply to any message: original id + status (ACK 0, NAK 1, UNSUPPORTED 2, …) |
| 5002 | DOWNLOAD_REQUEST | phone→ | Start downloading a file (index, offset, size) |
| 5003 | UPLOAD_REQUEST | phone→ | Start uploading (apps, settings, GPS assistance, weight later) |
| 5004 | FILE_TRANSFER_DATA | both | File chunk with CRC and offset |
| 5005 | CREATE_FILE | phone→ | Create a file on the watch before upload |
| 5007 | FILTER | phone→ | Directory filter |
| 5008 | SET_FILE_FLAG | phone→ | Mark a file archived after a successful download |
| 5009 | FILE_AVAILABLE | ←watch | Watch says a new file is ready |
| 5014 | WEATHER_REQUEST | ←watch | Watch asks for weather |
| 5023 | BATTERY_STATUS | ←watch | Battery level |
| 5024 | DEVICE_INFORMATION | ←watch | Start of handshake; phone answers with its own info |
| 5026 | DEVICE_SETTINGS | phone→ | Settings (e.g. auto-upload) |
| 5030 | SYSTEM_EVENT | phone→ | Events: sync ready, sync complete, pair complete, setup complete, time updated, … |
| 5031 | SUPPORTED_FILE_TYPES_REQUEST | phone→ | Which file types the watch has |
| 5033–5036 | NOTIFICATION_UPDATE / CONTROL / DATA / SUBSCRIPTION | both | Notification relay (Garmin's notification service) |
| 5037 | SYNCHRONIZATION | ←watch | Watch asks to sync |
| 5039 / 5040 | FIND_MY_PHONE_REQUEST / CANCEL | ←watch | Find phone |
| 5041 / 5042 / 5049 | MUSIC_CONTROL / _CAPABILITIES / _ENTITY_UPDATE | both | Music |
| 5043 / 5044 / 5045 | PROTOBUF_REQUEST / RESPONSE / CANCEL | both | Protobuf services (section 4) |
| 5050 | CONFIGURATION | both | Capability flags exchange |
| 5052 | CURRENT_TIME_REQUEST | ←watch | Time and time zone |
| 5101 | AUTH_NEGOTIATION | ←watch | Answer as the reference does (COMPATIBILITY R1, R2) |
| 5102–5112 | secure pairing | ←watch | Not implemented: detect it, log it, tell the user (R2) |

Other ids get an UNSUPPORTED status reply, never silence.

```kotlin
sealed interface GfdiMessage { val id: Int }      // one data class per message above, with encode()/decode()
class GfdiChannel(transport: GfdiTransport) {
    val incoming: Flow<GfdiMessage>
    suspend fun send(m: GfdiMessage): Outcome<Unit>
    suspend fun request(m: GfdiMessage, timeoutMs: Long = 5_000): Outcome<GfdiStatus>   // waits for RESPONSE with matching id
}
```

### 3. `session/`: handshake and state

**State machine:** `DISCONNECTED → LINK_UP → INFO_EXCHANGED → CONFIGURED → READY → SYNCING ⇄ READY`.

**Handshake:**
1. The watch sends DEVICE_INFORMATION (protocol version, product number, unit id, software version, max packet size, Bluetooth name, device name, model). The phone replies with RESPONSE carrying its own info:
   - protocol version
   - product number (confirmed against reference: 0xFFFF, i.e. -1, not a real phone product)
   - unit id
   - software version
   - max packet
   - Bluetooth name
   - manufacturer "Self Watch"
   - model "Android"
2. The watch sends CONFIGURATION (capability bit set). Store it as `Set<GarminCapability>`. Reply with ours: the capabilities Self Watch implements.
3. The phone sends what the reference sends on connect (confirmed against reference): SUPPORTED_FILE_TYPES_REQUEST, DEVICE_SETTINGS (auto upload on; weather off until A18), SYSTEM_EVENT TIME_UPDATED, SYSTEM_EVENT SYNC_READY; on the first connect only, PAIR_COMPLETE, SYNC_COMPLETE, SETUP_WIZARD_COMPLETE. The reference does not send PAIR_START (commented out) and sends HOST_DID_ENTER_FOREGROUND/BACKGROUND only when the phone app changes state. Notification subscription is started by the watch (NOTIFICATION_SUBSCRIPTION, A16) when `GNCS` is set.
4. Time: answer CURRENT_TIME_REQUEST with RESPONSE(ACK) + reference id, Garmin-epoch time, the whole UTC offset (DST included; there is no separate DST field), then the transition after next and the next offset transition (confirmed against reference).
5. `READY` → `driver.info` is published (product, firmware, features from the capability map in COMPATIBILITY §5) → sync starts if this is a new connection.

**Keep-alive:** confirmed against reference: none. The reference never registers the KEEP_ALIVE service (code 22) and sends no periodic message; Self Watch does the same. The link stays up while the BLE connection does.

**Auth (R1):**
- AUTH_NEGOTIATION gets the reference's minimal reply (confirmed: RESPONSE ACK, auth status 0 "guess OK", the watch's unknown byte and its flags echoed).
- Credential and OAuth protobuf requests get "not available".
- Fake success only when setting `garmin.fake_auth` is true.

### 4. `pb/`: protobuf services

- **Our own `.proto` files** in `garmin/src/main/proto/`, written fresh with only the messages we use. Field numbers are learned from the reference's `GBProtobufGenerator/.../garmin/*.proto` files, with Ref comments.
- **Files needed in v1:**
  - `smart.proto` (the envelope that wraps service messages)
  - `core.proto`
  - `device_status.proto`
  - `installed_apps.proto` (list/delete apps)
  - `app_settings.proto` (Connect IQ settings)
  - `http.proto` (watch-app web requests)
  - `data_transfer.proto` (large transfers)
  - `find_my_watch.proto`
  - `calendar.proto` (later)
  - `sms_notification.proto` (canned replies, later)
  - `credentials.proto` (for refusing politely)
- **Build:** the protobuf Gradle plugin with `option java_lite_runtime`. Generated classes are kept by R8 rules (WP-01).
- **API:**
  ```kotlin
  class ProtobufService(ch: GfdiChannel) {
      suspend fun request(msg: Smart, timeoutMs: Long = 10_000): Outcome<Smart>
      val incoming: Flow<Smart>
  }
  ```
  Handles chunking: request id, offset, total length.

### 5. `files/`: directory and transfers

- **Directory:** DOWNLOAD_REQUEST for file index 0 returns the directory. Each entry is 16 bytes: index (u16), data type (u8), subtype (u8), number (u16), specific flags (u8), file flags (u8), size (u32), timestamp (u32 Garmin epoch). Confirmed against reference (little-endian; timestamp 0 = no date; file flag 0x10 = archived).
- **File types we fetch** (type 128 = FIT):

  | Subtype | File | Handling |
  |---|---|---|
  | 4 | ACTIVITY | Workout: saved as received, then decoded for the summary |
  | 9 | WEIGHT | Decoded |
  | 15 | MONITOR_A | Decoded |
  | 28 | MONITOR_DAILY | Decoded |
  | 32 | MONITOR | Decoded |
  | 44 | METRICS | Decoded (when decoders exist) |
  | 49 | SLEEP | Decoded |
  | 68 | HRV_STATUS | Decoded |
  | 73 | SKIN_TEMP | Decoded (when decoders exist) |

  Others are skipped.
- **`SyncPlan`:** fetch entries where `!sink.alreadyHave(key)`, oldest first, skipping files over 20 MB. Activities first, then sleep, then monitoring.
- **Download:**
  1. DOWNLOAD_REQUEST (index, offset 0, size).
  2. FILE_TRANSFER_DATA chunks (flags, CRC of data so far, offset, bytes); ACK each chunk.
  3. On completion, verify the CRC.
  4. Hand the bytes to the decoder (fit, `Dispatchers.Default`).
  5. `sink.ingest(batch)`.
  6. Only after the ingest succeeds, SET_FILE_FLAG archive (so a crash mid-way re-downloads; it never loses data).
- **Upload:** CREATE_FILE (type, subtype, size) → UPLOAD_REQUEST → FILE_TRANSFER_DATA chunks → finish. Used for `.prg` apps (type 255/17), settings, and GPS assistance data.
- **Progress:** `ConnectionState.Syncing(done, total, label)`.

### 6. `features/`

| Feature | Messages | Behavior |
|---|---|---|
| Notifications | 5033–5036 (GNCS) | `WatchCommand.Notify` queues a notification; the watch asks for attributes (title, text, app, actions) and we answer from the cached `PhoneNotification`. Actions from the watch become `WatchEvent.NotificationAction`. Text cut to the watch's limits (reference values, T-A16) |
| Calls | GNCS with category CALL | Incoming call notification with accept/reject actions → `WatchEvent.CallAction` |
| Music | 5041/5042/5049 | Send capabilities and entity updates (artist, title, album, state, volume); watch commands → `WatchEvent.MusicControl` |
| Weather | 5014 + FIT weather records over FIT_DATA (5011/5012) | On request (or every 30 min if enabled), encode current + daily forecast as FIT weather messages (reference: FitWeatherConditions) |
| Find phone / watch | 5039/5040; find-my-watch protobuf | → `WatchEvent.FindPhone`; `WatchCommand.FindWatch` |
| Alarms | FIT settings file upload (128/2; confirmed in A20) | `WatchCommand.SetAlarms`; not on REALTIME_SETTINGS watches |
| Time | 5052, SYSTEM_EVENT time updated | Answer requests; push on phone time-zone change |
| GPS assistance | upload of an ephemeris file | Downloaded by `service` through `net` (feature GPS_ASSIST, off by default), uploaded here |
| Watch-app web | http.proto | → `WatchEvent.WebRequest`; `service` routes it to `net` (or to the local phone app for 127.0.0.1); reply via `WatchCommand.WebResponse`. Watch-side size limits from the reference |
| Watch apps | installed_apps.proto, upload of PRG | List → `WatchEvent.InstalledApps`; install = upload `.prg`; delete; launch (`LAUNCH_REMOTE_CIQ_APP_SUPPORT`) |
| App settings | app_settings.proto | Read current values → `WatchEvent.AppSettings`; write values. Settings definitions are parsed in `service/watchapps` from `<app>-settings.json` |
| App messages | Connect IQ channel (see below) | `WatchCommand.AppMessage` ↔ `WatchEvent.AppMessageReceived` |

**Connect IQ app messages (`AppMessages`):**
- The payload is a `CiqValue`, encoded to Monkey C's serialized form (`CiqCodec`; reference `GarminJson` / `GarminWatchAppCommunicator`).
- Then split into fragments: up to 14 fragments of `maxPacket`-sized chunks; about 266 bytes at default size.
- The reference flow (and Tony's findings):
  1. OPEN with the 16-byte app UUID.
  2. KEY: a random 16-byte key per message, XXTEA encryption (our own `Xxtea`).
  3. START with the cipher length.
  4. DATA fragments, each ACKed.
  5. LAST with the count and CRC-16.
  6. CLOSE.
- Watch-to-phone is the mirror image.
- **Which channel carries it on the vívoactive 5 is confirmed in T-A11** (the dedicated watch-app characteristics in Tony's findings, or a multi-link service).
- Timeout 5 s per message; one message at a time per app, queued.

### 7. `models/`

`models.json` (resource), loaded by `GarminModels`:
```json
{ "version": 1, "models": [
  { "name": "vívoactive 5", "pattern": "^vívoactive 5$", "productIds": [0], "family": "vivoactive",
    "tier": "VERIFIED", "link": "V2", "overrides": { "trainingLoad": false, "vo2MultiSport": false } } ] }
```
- Product ids are filled in T-A02 from the reference's model list and `GarminProductNumbers`; vívoactive 5's id is confirmed from its DEVICE_INFORMATION.
- `features = capabilitiesFromConfiguration ∩ implemented − overrides`.

## Confirmed against reference (T-A09, A10, A11, A25; Gadgetbridge 160beab41)

Implemented in `garmin/` with `// Ref:` comments; these supersede anything above that disagrees.

- **Multi-link control (handle 0):** request = `00 type clientId(u64 LE) service(u16 LE) extra(u8)`, 13 bytes. Client id 2 (the reference's). Register extra = 2 to ask for the reliable layer, 0 for plain. Close-all sends service 0. REGISTER_RESP = `00 01 clientId service(u16) status(u8) handle(u8) [reliable(u8)]` (older watches omit the reliable byte). CLOSE_HANDLE_RESP = `service(u16) handle(u8) status(u8)`; when the watch closes the GFDI handle on its own, the phone re-registers it. Responses for another client id are ignored. Start-up: CLOSE_ALL, then register GFDI on CLOSE_ALL_RESP.
- **Multi-link data:** plain handles: each write is `handle` + up to `maxWrite − 1` bytes of the COBS stream. Reliable handles: each write is the 2-byte MLR header + up to `maxWrite − 2` bytes. A packet with the top bit set but no reliable instance for its handle is treated as plain (reference #5476).
- **MLR:** header `1hhh rrrr | rrss ssss`: 3-bit handle, 6-bit request number (= cumulative ack, the next sequence expected), 6-bit sequence; sequence numbers wrap at 64; window 32; retransmit after 1 s doubling to 20 s, halving the window on each timeout and regrowing it as acks arrive; ack after 5 unacknowledged packets or 20 ms; out-of-order or duplicate packets are dropped and re-acked (go-back-N). Self Watch adds bounds: 4,096 queued fragments, link declared dead after 12 timeouts without progress.
- **COBS:** frames are `00 <COBS> 00` (the leading delimiter is Garmin's addition); a frame ending in a zero byte gets a final `01` block.
- **GFDI:** outgoing frames always use the full 16-bit id. Each incoming message gets its status reply first, then any reply message of its own: DEVICE_INFORMATION gets a plain RESPONSE(ACK) and then a second RESPONSE(ACK) carrying our info (protocol 150, product 0xFFFF, unit −1, software 7791, max packet 0xFFFF, Bluetooth name, manufacturer, model, then a protocol-flags byte = 1 if the watch's protocol version is 1xx); CONFIGURATION gets RESPONSE(ACK) and then our own CONFIGURATION (5050). RESPONSEs are never answered.
- **Capabilities:** 129 flags, bit n = ordinal n (CONNECT_MOBILE_FIT_LINK 0 … GENERIC_CREDENTIALS 99, CAPABILITY_100 … 128). The reference advertises almost all of them; Self Watch advertises only what it implements (`GarminCapability.SELF_WATCH`) and keeps the reference set (`GarminCapability.REFERENCE`) as a fallback for live testing.
- **Files:** DOWNLOAD_REQUEST = `index(u16) offset(u32) type(u8: 0 continue, 1 new) crcSeed(u16) size(u32) flags(u8)`; its RESPONSE adds `downloadStatus(u8, 0 OK) size(u32)`. FILE_TRANSFER_DATA = `flags(u8) crc(u16) offset(u32) data`, where crc is the running CRC-16 of the file from byte 0; each chunk is answered RESPONSE(ACK) + `transferStatus(u8, 0 OK) nextOffset(u32)`. SET_FILE_FLAG = `index(u16) flags(u8)`, ARCHIVE = 0x10; its RESPONSE adds `flagsStatus(u8, 0 applied) index(u16) flags(u8)`. SYNCHRONIZATION from the watch → FILTER(3 = only new) → directory download. Upload: CREATE_FILE → UPLOAD_REQUEST → chunks of `maxPacket − 13` bytes → SYSTEM_EVENT SYNC_COMPLETE.
- **Supported file types reply:** `count(u8)` then `(type u8, subtype u8, name str8)` entries.
- **Battery (5023):** `status(u8) voltage×100(u8) percent(u8)` + 4 unknown bytes. The reference also polls battery through a protobuf request (A15).
- **App messages:** service `6a4e8022-…` with the V1 characteristic UUIDs (rx `6a4ecd28`, tx `6a4e4c80`); which channel the vívoactive 5 really uses is still for T-A11 live. Only OPEN, KEY, START and LAST are acknowledged by the watch, not each fragment. Fragments are `(i shl 4) or 1` + up to `maxWrite − 1` bytes; at most 14 fragments (the reference caps the cipher at 14 × 19 = 266 bytes; Self Watch allows 14 × fragment size). Payload = XXTEA(pad(Monkey C serialization), random 16-byte key), little-endian words; pad = padding length first, then data, to a multiple of 4.
- **Not implemented, by design for now:** the reference's newer protobuf file-sync protocol (off by default there too), its RFCOMM fallback for watches whose BLE directory lists no FIT files, and realtime services.

## Confirmed against reference (A15–A19, A24; Gadgetbridge 160beab41)

Implemented in `garmin/` (`pb/`, `features/`, `gfdi/FeatureMessages.kt`, wired by `GarminFeatures`) with `// Ref:` comments. Not yet tried on a watch (T-A27).

- **Extension points:** handlers get commands through `GfdiHandler.command(cmd, session)` (null = not mine) before the driver's own; `GarminSessionContext` also exposes the connection `scope` and `transfers`. The driver's default handler list is `GarminFeatures(clock).handlers`. Every handler checks its capability flag itself: without it, the watch's messages get UNSUPPORTED and commands fail with `SwError.Unsupported(feature)`.
- **Advertised flags** (`SELF_WATCH`) now add GNCS, BASIC/ADVANCED_MUSIC_CONTROLS, FIND_MY_PHONE, FIND_MY_WATCH, WEATHER_CONDITIONS. GPS_EPHEMERIS_DOWNLOAD stays off, as in the reference. DEVICE_SETTINGS now sends WEATHER_CONDITIONS_ENABLED = true (the reference's value).
- **Protobuf (5043/5044):** payload `requestId(u16) offset(u32) total(u32) chunkLen(u32) chunk`; chunk size `maxPacket − 20`; request ids `(last + 1) mod 65536`, first 1. Chunk status body (after original id and ACK): `requestId(u16) offset(u32) chunkStatus(u8: 0 kept, 1 discarded) code(u8: 0, 100 unknown request id, 101 duplicate, 102 missing, 103 exceeded length, 200 parse error, 201 unknown)`. A one-chunk message that gets a reply is acknowledged with a plain ACK, then the PROTOBUF_RESPONSE; handled without reply → "kept, 0"; nobody handles it → "discarded, 100"; the next outgoing chunk follows the watch's status for the previous one. The reference never answers the core connection-ready notification (some firmware misbehaves). Credential (OAuth) requests are left unhandled ("discarded, 100") unless `garmin.fake_auth`. Self Watch bounds: 1 MiB per message, 8 unfinished messages. The API takes the session: `ProtobufService.request(session, smart)` / `send(session, smart)`; features register a `PbHandler`.
- **.proto subset:** own files in `garmin/src/main/proto/` (`smart`, `core`, `device_status`, `find_my_watch`, `credentials`, and empty service stubs `installed_apps`, `app_settings`, `http`, `data_transfer` for A21–A23). Smart fields: http 2, installed apps 3, app settings 4, data transfer 7, device status 8, find my watch 12, core 13, credentials 27. Unknown services survive parsing as unknown fields.
- **GNCS:** NOTIFICATION_SUBSCRIPTION (5036) = `enable(u8) [unknown(u8)]`, answered `status(u8: 0 enabled) enable(u8) unknown(u8)`; notifications sent before it are dropped (reference). NOTIFICATION_UPDATE (5033) = `type(u8: add 0, modify 1, remove 2) categoryFlags(u8: foreground 0x02 | action-decline 0x10) category(u8: other 0, incoming call 1, social 4, schedule 5, email 6, SMS 12) count(u8, same category) id(u32) phoneFlags(u8: new actions 0x02, attachments 0x04)`. NOTIFICATION_CONTROL (5034) commands: 0 get attributes (`id(u32)` then attribute ids; TITLE 1, SUBTITLE 2, MESSAGE 3 carry a u16 max length, ACTIONS 127 a u16 and one byte), 1 app attributes (null-terminated app id, then APP_NAME 0), 2 legacy action, 128 action (`id(u32) action(u8) [text\0]`). Each control message is answered `chunkStatus(u8 0) code(u8 0; 160 unknown command)`. Attribute data goes in NOTIFICATION_DATA (5035) = `size(u16) crc(u16, running CRC-16) offset(u16) chunk`, at most **300 bytes** per chunk, next chunk after the watch's `transferStatus(u8) = 0`. Data block = `command(u8) id(u32)` then `attribute(u8) length(u16) value`, MESSAGE_SIZE last. **Text limits:** cut to the max length the watch asks for, in UTF-16 characters (Self Watch never splits a surrogate pair); MESSAGE_SIZE is the full text length in characters; DATE is `yyyyMMdd'T'HHmmss` local time; 64 notifications kept. Actions value = `count(u8)` then `code(u8) iconBits(u8: bottom 1, right 2, left 4) labelLen(u8) label`, or four zero bytes for none. Codes: custom 1–5, reply-to-call 94, reply 95, accept call 96, reject call 97, dismiss 98, mute app 99.
- **Calls:** a notification in category incoming call, id = hash of the number, title = name or number, actions reply 94 / reject 97 / accept 96 (labels not shown by the watch); accept → `CallAction(true)`, reject → `CallAction(false)`, reply-to-call → reject plus a reply action.
- **Music:** MUSIC_CONTROL_CAPABILITIES (5042) may have an empty payload; answer `count(u8)` + command ordinals (toggle 0, next 1, previous 2, volume up 3, down 4, play 5, pause 6, skip forward 7, skip back 8). MUSIC_CONTROL (5041) = `command(u8)`. ENTITY_UPDATE (5049) entries `len(u8 = value + 3) entity(u8: player 0, queue 1, track 2) attribute(u8) 0 value`, value ≤ 252 bytes; track artist 0, album 1, title 2, duration 3 (seconds); player playback info 1 = `"%d,%.1f,%.3f"` (playing, rate, position s), volume 2 = `"%.2f"` (0..1).
- **Weather:** WEATHER_REQUEST (5014) = `format(u8) lat(i32) lon(i32) hours(u8)`, plain ACK. Weather goes as FIT `weather_conditions` (global 128) records: FIT_DEFINITION (5011, all definitions in one message) then FIT_DATA (5012) packed to `maxPacket` including 6 framing bytes; each answered `status(u8: 0 applied)`. Definitions are big-endian (the reference's builder). Fields: report 0 (current 0, hourly 1, daily 2), temperature 1 (sint8 °C), condition 2 (FIT WeatherStatus 0–22), wind direction 3 (u16), wind speed 4 (u16 m/s ×1000, max 65.53), precipitation 5, feels like 6, humidity 7, location 8 (string + 0), observed at 9, day of week 12 (Sunday 0), high 13, low 14, timestamp 253. Only sent when the watch has WEATHER_CONDITIONS.
- **Find my phone:** 5039 = `duration(u8)`, 5040 empty; plain ACK each. **Find my watch:** protobuf FindMyWatchService (find request 1 with timeout 60 s, cancel request 3; responses 2/4, status OK 100), sent without waiting, as in the reference.
- **GPS assistance:** the reference does **not** upload ephemeris; the watch fetches `api.gcs.garmin.com/ephemeris/…` (or `.cn`) through a Connect IQ web request and the phone answers from a cached file (tar of `CPE_GPS/GLO/GAL/QZSS.BIN`, gzip starting `01 00` (RxNetworks), or Sony CPE starting `2a 12 a0 02`). `GpsAssist.isEphemerisRequest` is ready for A23; the upload route (`GpsAssist.upload`, file type a parameter) is a skeleton until a live test confirms a type. GPS_ASSIST is not in `Features.IMPLEMENTED`.

## Confirmed against reference (A20–A23, A11 channel, A26; Gadgetbridge 160beab41)

Implemented in `garmin/features/` (`AlarmsHandler`, `WatchAppsHandler`, `PrgFile`, `AppSettingsHandler`, `WebRequestHandler`, `CiqJson`) with `// Ref:` comments, wired by `GarminFeatures`. Not yet tried on a watch (T-A27).

- **Extension points added:** `GfdiHandler.onReady(session)` (called once per connection at READY, each in its own coroutine); `ProtobufService.respond(session, requestId, smart)` (a later PROTOBUF_RESPONSE to a watch request); `PbHandler.onMessage(…, requestId, …)` (defaulted overload that also gives the watch's request id). Calls that are not watch commands live on the handlers: `GarminFeatures.apps.refresh()` and `GarminFeatures.appSettings.request(uuid)`. `GarminFeatures(webResponseTimeoutMs = 30_000)`.
- **Advertised flags** (`SELF_WATCH`) now add CONNECTIQ_HTTP, CONNECTIQ_SETTINGS, CONNECTIQ_WIDGET/WATCH_FACE/DATA_FIELD_DOWNLOAD, CONNECTIQ_APP_MANAGEMENT, LAUNCH_REMOTE_CIQ_APP_SUPPORT, HTTP_RAW_RESOURCE_REQUEST. `Features.IMPLEMENTED` adds ALARMS, WATCH_APP_INSTALL/MANAGE/SETTINGS/WEB/LAUNCH (GPS_ASSIST still out).
- **Alarms (A20): a FIT settings file upload, not a GFDI message or protobuf.** File type 128/2 with `file_id` (type 2, manufacturer 1, product 65534, serial 1, number 1, time created), one `alarm_settings` (global 222) per alarm: time 0 (u16 minutes), repeat 1 (u32z: Monday 1 … Sunday 64, 128 = once), enabled 2, sound 3 (3 = sound + vibration), backlight 4 (1), time created 5, snooze 7 (0), label 8 (FIT AlarmLabel NONE 0 … BEDTIME 8), message index 254; then one `device_settings` (2) with arrays alarms_time 8, alarms_mode 9 (5 = custom), alarms_enabled 28, alarms_repeat 92. Records big-endian (the reference's builder). The list replaces all alarms; at most **10** (the reference's slot count). **Watches with REALTIME_SETTINGS get 0 slots** in the reference (their alarms live in the realtime settings service, not implemented there either), so Self Watch reports ALARMS unsupported for them. The vívoactive 5 may be one of these: T-A27 checks its flags. DEVICE_SETTINGS (5026) itself stays as in A09/A18.
- **Installed apps (A21):** `InstalledAppsService` (Smart field 3): GetInstalledAppsRequest(app_type) 1 / Response 2 (`available_space` 1, `available_slots` 2, `installed_app` 3: store_app_id 1 (16-byte UUID, big-endian), app_type 2, name 3, disabled 4, version 5, filename 6, filesize 7, native_app_id 8, favorite 9), DeleteAppRequest(store_app_id, app_type) 3 / Response 4 (OK 1, FAILED 2), LaunchAppRequest(app_uuid, startup mode 0 = normal) 9 / Response 10 (success 0, already running 1, not installed 2, error 3). AppType: watch app 1, widget 2, watch face 3, data field 4, ALL 5, audio provider 7, activity 8. The reference asks for WATCH_APP on connect; Self Watch asks for ALL at READY and after install/delete, and leaves out system apps (no filename), as the reference marks them. Delete uses the store id and type from the last list. **The reference never launches apps**, so launch follows the proto only.
- **Install:** the reference uploads every `.prg` as file type **255/17** over the GFDI upload (its MLR/file-access route is off by default and not used). CREATE_FILE = `size(u32) type(u8) subtype(u8) fileIndex(u16 0) reserved(u8) subtypeMask(u8 0) numberMask(u16 0xFFFF) path(str16 "") fileId(u64, random in the reference)`; its status = `createStatus(u8: OK 0, duplicate 1, no space 2, unsupported 3, no slots 4, no space for type 5) fileIndex(u16) type subtype number(u16)`; UPLOAD_REQUEST = `fileIndex(u16) size(u32) offset(u32) crcSeed(u16)`, status = `uploadStatus(u8: OK 0, index unknown 1, not writeable 2, no space 3, invalid 4) offset(u32) maxSize(u32) crcSeed(u16)`; chunks of `maxPacket − 13` bytes, each with the running CRC-16 from byte 0; finished when the watch's next offset reaches the size, then SYSTEM_EVENT SYNC_COMPLETE. Self Watch gates on the PRG's type: watch face → CONNECTIQ_WATCH_FACE_DOWNLOAD, data field → DATA_FIELD_DOWNLOAD, widget → WIDGET_DOWNLOAD, else WATCH_APP_DOWNLOAD.
- **PRG header** (`PrgFile`, our own reader): big-endian sections `magic(u32) length(u32) content`, ending at magic 0 or EOF; file starts `d0 00 d0` (head `d000d000` or versioned `d000d00d`); entry points `6060c0de` = `count(u16)` then `uuid[16] module(u32) class(u32) label(u32) iconLabel(u32) appType(u32)`; app types watch face 0, watch app 1, data field 2, widget 3, background 4, audio provider 5, glance 6. Limit 10 MiB. Store-signed PRGs also carry a store header that makes the watch report the store id and version; self-signed ones do not, so the store id the watch lists for a sideloaded app is to be confirmed live.
- **App settings (A22):** `AppSettingsService` (Smart field 4): GetAppSettingsRequest(app_identifier) 1 / Response 2 (success 1, not installed 2); SaveAppSettingsRequest(app_identifier, settings_values) 3 / Response 4 (success 1, failed 2); the values come back in a **watch-initiated** SendAppSettingsRequest 5 (answered with a "kept" status only, as the reference does). Values are a Monkey C serialized dictionary (`CiqCodec`, the reference's GarminJson) of string key → value; the reference only writes booleans, numbers (float when not whole) and strings.
- **Web requests (A23):** `HttpService` (Smart field 2): ConnectIQHTTPRequest 1 (url 1, method 2 (0 unknown → GET, 1 GET … 6 HEAD), header fields 3 and body 4 as Monkey C values, max_response_length 5, include headers 6, compress 7, response type 8 (JSON 0, URL-encoded 1, text 2, XML 3), version 9) / ConnectIQHTTPResponse 2 (status 1: OK 100, invalid header/body/method 200–202, timed out 300, invalid response body 400, invalid response headers 401, too large 402, insecure 1001; http code 2; body 3 as a Monkey C value; headers 4; inflated size 5 = 0 uncompressed); RawResourceRequest 5 (url 1, max size 2, method 3, body string 4, headers 5, use_data_xfer 6, raw body 7, forms 8, timeouts 9/10, encrypted 11) / RawResourceResponse 6 (status OK 100, timed out 200, too large 300, transfer failure 400, decryption failure 500; data 3; xfer item 4 {id, size}; headers 5). Connect IQ content-type codes: "0" = form, "1" = JSON. The answer is **asynchronous**: the request chunk gets "kept", and the PROTOBUF_RESPONSE carries the watch's original request id. Rules copied from the reference: body over `max_response_length` → 402 (Self Watch uses 64 KiB when the watch sends none); JSON content type parsed (failure → 400, code 0); non-JSON answer when JSON was expected → 400 with the HTTP code; headers returned only when asked; raw answers gzip-compressed when `accept-encoding` is gzip; forms/encrypted raw requests unsupported. **Data transfer** (Smart field 7): with `use_data_xfer` the body is stored under an id and the watch pulls it with DataDownloadRequest(id 1, offset 2, max_chunk_size 3) → DataDownloadResponse(status 1: success 1, invalid id 2, invalid offset 3; id 2; offset 3; payload 4). Self Watch caps chunks at 64 KiB, keeps 8 items / 8 MiB, 16 waiting requests, 30 s for the app's answer (then "timed out"). `WebResponse.status` 0 = network error (→ 300), negative = failed or blocked (→ UNKNOWN). Ephemeris URLs (`GpsAssist.isEphemerisRequest`) get the header `X-SelfWatch-Ephemeris: 1`. The reference blocks every `*.garmin.com`, `*.garmin.cn` and `dciwx.com` request it does not serve itself; that policy belongs to `service` (B25).
- **App-message channel (A11): the dedicated watch-app GATT service `6a4e8022-…` with the V1-style characteristics** (rx `6a4ecd28`, tx `6a4e4c80`), which is what the reference uses (`GarminWatchAppCommunicator`) and what Tony found; there is no multi-link service for it. Already wired: `WatchCommand.AppMessage` → `AppMessages.send`, received messages → `WatchEvent.AppMessageReceived`; WATCH_APP_MESSAGES is dropped from the features when the service is missing. Gate: CONNECTIQ_WATCH_APP_DOWNLOAD (the reference's).
- **A26:** no recorded sessions exist yet, so `replay/ScriptedSessionTest` runs one scripted FakeWatch connection through every feature (handshake, time, battery, sync + archive, notification, call, music, weather, find phone/watch, app list, install, settings write/read, launch, local web request, alarms, app message) and checks every watch message is answered without UNSUPPORTED. `ReplayTest` still replays `testdata/sessions/` when recordings land.

## Variables summary (session-scoped, in `GarminSession`)

`deviceInfo: DeviceInfoMsg`, `capabilities: Set<GarminCapability>`, `maxPacket: Int`, `linkVersion: V1|V2`, `handles: Map<ServiceCode, Int>`, `mlrState` (send/receive sequence numbers, window, timers), `pendingRequests: Map<Int, CompletableDeferred<GfdiStatus>>`, `notificationCache: LinkedHashMap<Int, PhoneNotification>` (max 100), `uploadInProgress`, `downloadQueue: ArrayDeque<DirEntry>`, `appMessageQueue: Map<UUID, Channel<CiqValue>>`.

## Tests and definition of done

- [ ] Unit tests per layer: COBS and CRC vectors; MLR window, retransmit and reorder; frame parse and build for every message in the table; protobuf round trips; directory parse; `CiqCodec` and XXTEA test vectors (made from recorded sessions).
- [ ] **Replay tests:** recorded sessions from `testdata/sessions/` (T-A08) drive `FakeBlePort`. The driver reaches READY, downloads the same files, ingests the same totals, and answers every watch message.
- [ ] Fuzz: random and truncated frames never crash or hang the session (bounded queues, timeouts).
- [ ] Live test on the vívoactive 5 (M1 parts in T-A12, everything in T-A27): pair, sync a day, notification with reply, call, music, find phone, weather, install Just Go 5K, edit its settings, TCS message round trip.
- [ ] Capability gating: a fake watch without `GNCS` never gets notification messages.
