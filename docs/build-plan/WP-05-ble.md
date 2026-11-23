# WP-05 ble (Bluetooth pairing and connection)

Lane A. Milestone M1. Android library. Depends on: core-model, coroutines, androidx-core.

## Goal

Pair a watch without the location permission, keep a reliable connection, and give `garmin` ordered, lossless byte pipes (`BlePort` / `BytePipe` from WP-02). Every Android-version difference and phone-maker quirk is hidden behind that interface.

## Components

### `Pairing` (Companion Device Manager)

```kotlin
class Pairing(private val activity: ComponentActivity) {
    suspend fun pair(filter: PairFilter): Outcome<PairedDevice>     // shows the system picker
    fun associations(): List<PairedDevice>
    suspend fun unpair(device: PairedDevice)
}
data class PairFilter(val namePattern: Regex?, val manufacturerId: Int? /* Garmin 0x0087 */)
data class PairedDevice(val address: String, val name: String?, val associationId: Int?)
```

**How it works:**
- `CompanionDeviceManager.associate(AssociationRequest)` with a `BluetoothLeDeviceFilter`: the name pattern from `garmin/models.json` (all known Garmin names), or the scan filter `setManufacturerData(0x0087, …)`. Single-device mode off, so the user picks.
- **API 33+:** `associate(request, executor, callback)`. `onAssociationPending(IntentSender)` launches the picker through an `ActivityResultLauncher<IntentSenderRequest>`. `onAssociationCreated(AssociationInfo)` gives the id and address.
- **API 26–32:** `associate(request, callback, handler)`. `onDeviceFound(IntentSender)` launches the picker. The result intent carries `EXTRA_DEVICE` (a `ScanResult` for BLE filters).
- **After association:**
  - Bond if needed: `device.createBond()` when `bondState != BOND_BONDED`, then wait for `ACTION_BOND_STATE_CHANGED` (timeout 60 s, the user may need to confirm a passkey on the watch).
  - Some Garmin models connect without bonding: try connecting first, and bond only if the watch asks (GATT insufficient-authentication error). Confirm the vívoactive 5's behavior in T-A07.
- **Unpair:** `disassociate(associationId)` (API 33+) or `disassociate(address)`. Optionally `removeBond` isn't public API, so the user is told to forget the watch in Bluetooth settings if they want it fully gone.

### `BleConnection` implements `BlePort`

```kotlin
class BleConnection(ctx: Context, override val address: String, private val log: BleLog?) : BlePort {
    override val state: StateFlow<PortState>
    suspend fun connect(auto: Boolean): Outcome<Unit>   // auto = true for background reconnect (low power)
    override suspend fun serviceUuids(): Set<UUID>
    override suspend fun openPipe(service: UUID, rx: UUID, tx: UUID): Outcome<BytePipe>
    override suspend fun setHighSpeed(on: Boolean)      // CONNECTION_PRIORITY_HIGH / BALANCED
    suspend fun close()
}
```

**Connection steps:**
1. `device.connectGatt(ctx, auto, callback, BluetoothDevice.TRANSPORT_LE)`.
2. On `STATE_CONNECTED`: wait 600 ms (Samsung needs it), then `discoverServices()` (timeout 15 s, one retry).
3. `requestMtu(517)`; use the value from `onMtuChanged`. Pipe `maxWrite = mtu − 3`. If it's never called, use 20.
4. For each pipe: enable notifications on `rx`. That's `setCharacteristicNotification(rx, true)` plus writing `ENABLE_NOTIFICATION_VALUE` to descriptor `00002902-0000-1000-8000-00805f9b34fb`.

**GATT operation queue (the key to reliability):**
- One operation in flight at a time across the whole connection: discover, MTU, descriptor write, characteristic write, read. Android drops overlapping operations silently.
- Operations are a `Channel<GattOp>` processed by one coroutine on a single-threaded dispatcher (`Dispatchers.IO.limitedParallelism(1)`). Each op waits for its callback through a `CompletableDeferred`, with a 10 s timeout.
- **Writes:** API 33+ `gatt.writeCharacteristic(ch, value, writeType)` returns a status code; below 33, `ch.value = value; ch.writeType = …; gatt.writeCharacteristic(ch)`. Write type is `WRITE_TYPE_DEFAULT` (with response) unless `garmin` asks for no-response (`BytePipe` option).
- **Notifications:** API 33+ `onCharacteristicChanged(gatt, ch, value)`; below 33 `onCharacteristicChanged(gatt, ch)` reading `ch.value` immediately (it can be overwritten). Copy the bytes and push them into the pipe's `Channel<ByteArray>(Channel.UNLIMITED)`, never dropping.

**Errors and reconnects:**

| Event | Action |
|---|---|
| status 133 (GATT_ERROR) or 8 / 19 / 22 disconnect | `gatt.close()`; reconnect after 1, 2, 5, 10, 30, 60 s, then every 5 min (the back-off resets on success) |
| Bluetooth turned off (`ACTION_STATE_CHANGED`) | Close; wait for `STATE_ON`; reconnect |
| Bond lost (API 36 broadcast, or repeated insufficient-authentication errors) | Stop retrying; `PortState.CLOSED` with reason `REPAIR_NEEDED`; service shows a "re-pair" notification |
| Permission `BLUETOOTH_CONNECT` revoked (API 31+) | Catch `SecurityException`; fail with `SwError.PermissionMissing` |
| App process killed | Service restarts via presence (API 31+) or boot/start; reconnects with `auto = true` |

### `BleLog` (optional capture)

When "Bluetooth capture" is turned on in About → Debug, every write and notification is appended to `files/blelog/<date>.bin`:
- 8-byte time in ms
- 1 byte direction
- 1 byte pipe index
- 2-byte length
- the bytes

The files are shared through the export screen, and become **recorded sessions for `garmin`'s replay tests** (WP-06). Off by default; files deleted after 7 days.

### `profile/` (standard Bluetooth health devices)

`HealthSensorDriver` is a `WatchDriver` for devices that follow the open Bluetooth SIG health profiles,
written from the public GATT Specification Supplement (no reference code): Weight Scale (0x181D /
0x2A9D), Body Composition (0x181B / 0x2A9C), Heart Rate (0x180D / 0x2A37) and Blood Pressure (0x1810 /
0x2A35), plus the Battery Level (0x2A19) read. Pairing uses the same Companion Device Manager flow with a
service-UUID filter (`PairFilter.serviceUuids`), so no location permission. Measurement characteristics are
read-only, so a pipe opens with the same UUID as `rx` and `tx`; `BleConnection` picks indications or
notifications from the characteristic properties, and offers one-off reads through `CharacteristicReader`.
Not yet: scales that need User Data Service registration, multi-packet body composition merging, Current
Time Service writes.

## Variables and constants

```kotlin
const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"
const val MTU_REQUEST = 517; const val DEFAULT_MTU = 23
const val OP_TIMEOUT_MS = 10_000L; const val CONNECT_TIMEOUT_MS = 30_000L; const val DISCOVERY_DELAY_MS = 600L
val BACKOFF_SEC = intArrayOf(1, 2, 5, 10, 30, 60, 300)
const val GARMIN_MANUFACTURER_ID = 0x0087
```

## Compatibility checklist

COMPATIBILITY §2 rows 31, 33, 34, 36; §3 Bluetooth rows; §4 Bluetooth quirks.
- [ ] Every Bluetooth call wrapped for `SecurityException`.
- [ ] API branches for associate, write and notify callbacks.
- [ ] No `BLUETOOTH_SCAN` and no location permission.
- [ ] `PendingIntent` and receivers flagged correctly.

## Tests and definition of done

- [ ] Robolectric unit tests for the operation queue (order, timeouts, back-off) using a fake `BluetoothGatt` wrapper (`GattApi` interface around the real one, so the queue is testable).
- [ ] Instrumented test on the Pixel 5 with the vívoactive 5: pair, connect, discover, MTU, open the multi-link pipes, receive a notification (T-A07, with HappyRock).
- [ ] Soak test: 8 hours connected overnight, Bluetooth toggled 5 times and the watch walked out of range twice. Reconnects every time, no leaked `BluetoothGatt` objects (T-Q06).
