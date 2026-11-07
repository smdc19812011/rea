# SimBridge

Use one phone's physical SIM card to make and receive calls on another phone — **no cloud, no Firebase, no internet required**.

## How it works

```
CALLER ←──cellular──→ HOST phone (SIM) ←──audio bridge──→ CLIENT phone
```

| Role | Device | What it does |
|------|--------|-------------|
| **HOST** | Phone with the SIM card | TCP + BT servers, call bridge |
| **CLIENT** | Any Android phone | Dials and receives calls as if it owned the SIM |

### Audio bridge mechanism

When a call is active on the HOST, it switches the cellular call to speakerphone. The HOST microphone captures the caller's voice and streams it to the CLIENT. The CLIENT's voice travels back and plays through the HOST speaker; hardware AEC removes loopback so the cellular caller hears the CLIENT and not their own echo.

**WiFi / WiFi Direct**: audio streamed over WebRTC (IP-based, low latency).  
**Bluetooth**: audio streamed as raw PCM over the RFCOMM socket (WebRTC requires IP routing that RFCOMM doesn't provide).

---

## Connection options

All three are available simultaneously on the HOST — start HOST mode once and the CLIENT picks any method.

### Option 1 — Same WiFi (auto-discovery, recommended)

Both phones on the same router. CLIENT taps **WiFi** tab → **Scan** → tap the HOST name. Uses mDNS (NSD) over TCP.

### Option 2 — WiFi Direct (no router needed)

1. **HOST**: tap **Start HOST mode** → tap **Create WiFi Direct Group**.
2. **CLIENT**: tap **Direct** tab → **Scan** → tap the HOST device name.
   The app auto-connects TCP to the group owner IP (always `192.168.49.1`).

You can also manually enter `192.168.49.1` in the **Enter IP manually** field on the Direct tab.

> WiFi Direct establishes a direct wireless link; it uses the same WebRTC audio path as normal WiFi, so quality is equivalent.

### Option 3 — Bluetooth (no WiFi at all)

1. **HOST**: tap **Start HOST mode** → tap **Make Discoverable (5 min)**.
2. **CLIENT**: tap **Bluetooth** tab.
   - If the HOST is already paired: tap it in the **Paired devices** list.
   - If not: tap **Scan for new devices** → pair when prompted → tap the device.

> Audio travels directly over the Bluetooth RFCOMM link as 16 kHz mono PCM.
> No WiFi or internet is used.

---

## Prerequisites

- Android 8.0+ (API 26) on both phones
- Install the **same APK** on both phones

---

## Build & install

```bash
cd simbridge
# Open in Android Studio (Hedgehog 2023.1+), or:
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## First-time setup

### HOST phone (has the SIM)

1. Open SimBridge → **HOST** tab.
2. Tap **Start HOST mode**.
3. Android prompts to set SimBridge as the **default Phone app** — accept (required for `InCallService`).
4. The app shows the in-call screen and waits for a CLIENT connection.
5. *(Optional)* Tap **Create WiFi Direct Group** to allow WiFi Direct connections.
6. *(Optional)* Tap **Make Discoverable** to allow Bluetooth connections.

### CLIENT phone

1. Open SimBridge → **CLIENT** tab.
2. Pick a connection method and connect to the HOST.
3. Use the dialpad to place calls or accept incoming calls via the HOST's SIM.

---

## Permissions

| Permission | Why |
|-----------|-----|
| `RECORD_AUDIO` | Microphone for the audio bridge |
| `CALL_PHONE` | Place cellular calls on HOST |
| `READ_PHONE_STATE` | Detect call state changes |
| `BLUETOOTH_CONNECT` (API 31+) | RFCOMM socket operations |
| `BLUETOOTH_SCAN` (API 31+) | Bluetooth device discovery |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` (API ≤ 30) | Legacy equivalents |
| `ACCESS_FINE_LOCATION` (API ≤ 32) | Required by Android for WiFi Direct |
| `NEARBY_WIFI_DEVICES` (API 33+) | Replaces location for WiFi Direct |
| `FOREGROUND_SERVICE_PHONE_CALL` | HOST background service |

---

## Project structure

```
app/src/main/java/com/simbridge/
├── App.kt
├── MainActivity.kt
├── data/Models.kt                    CallCommand, SignalingMessage, enums
├── network/
│   ├── Transport.kt                  Interface: messages, isConnected, send, disconnect
│   ├── AppTransport.kt               Singleton: holds the active transport for the session
│   ├── WireMessage.kt                Protocol data class (CALL_CMD, SIG_*, PING/PONG)
│   ├── ConnectionManager.kt          TCP Transport — WiFi and WiFi Direct connections
│   ├── BluetoothTransport.kt         Bluetooth Transport — RFCOMM multiplexed frames
│   ├── BluetoothAudioStreamer.kt      PCM audio bridge used in Bluetooth mode
│   ├── BluetoothHelper.kt            BT adapter access and device discovery
│   ├── LocalSignaling.kt             Call commands + WebRTC signaling via AppTransport
│   ├── NsdHelper.kt                  mDNS registration (HOST) + discovery (CLIENT)
│   ├── WifiDirectHelper.kt           WiFi Direct group/peer management
│   └── WifiDirectReceiver.kt         Manifest-declared BroadcastReceiver stub
├── host/
│   ├── SimInCallService.kt           InCallService — intercepts calls, runs audio bridge
│   └── SimHostService.kt             Foreground service — TCP + BT servers, NSD, relay
├── client/
│   └── ClientViewModel.kt            CLIENT state: dialing, incoming, audio
└── ui/
    ├── HomeScreen.kt                 Three-tab connection UI (WiFi / Direct / Bluetooth)
    ├── InCallScreen.kt               Dialpad, incoming call, active call
    └── theme/Theme.kt
```

---

## Socket / Bluetooth frame protocol

### TCP (WiFi / WiFi Direct)

Newline-delimited JSON.  Gson escapes embedded newlines, so SDP is safe as a JSON field.

| `type` | Sent by | Payload | Meaning |
|-------|---------|---------|---------|
| `CALL_CMD` | either | `callCmd` | INCOMING\_CALL, MAKE\_CALL, ANSWER\_CALL, REJECT\_CALL, END\_CALL, CALL\_ACTIVE, CALL\_ENDED |
| `SIG_TO_HOST` | CLIENT | `signal` | WebRTC SDP or ICE candidate → HOST |
| `SIG_TO_CLIENT` | HOST | `signal` | WebRTC SDP or ICE candidate → CLIENT |
| `PING` / `PONG` | either | — | Keepalive |

### Bluetooth RFCOMM frame

Binary frames over a single RFCOMM socket:

```
[1 byte: type][4 bytes: payload length BE][N bytes: payload]
```

| type byte | Payload |
|-----------|---------|
| `0x01` JSON | UTF-8 encoded WireMessage (same set as TCP) |
| `0x02` PCM  | Raw 16-bit mono 16 kHz PCM samples (audio bridge) |

---

## Audio quality notes

| Transport | Audio path | AEC |
|-----------|-----------|-----|
| WiFi / WiFi Direct | WebRTC (Opus codec, hardware echo canceller) | Hardware AEC via telephony stack |
| Bluetooth | 16 kHz PCM over RFCOMM | `AcousticEchoCanceler` on `AudioRecord` + `VOICE_COMMUNICATION` source |

Bluetooth audio latency is typically 150–300 ms (Bluetooth Classic RFCOMM), which is acceptable for a phone call but slightly higher than WiFi WebRTC.

---

## Limitations

| Limitation | Detail |
|-----------|--------|
| HOST must be default Phone app | Required by Android for `InCallService`. |
| Single CLIENT | One connection per server; first to connect wins. |
| Bluetooth requires prior pairing | Android RFCOMM requires the phones to be paired before connecting. |
| Audio quality varies by device | Acoustic bridge via speaker + mic; hardware AEC quality differs across phones. |
