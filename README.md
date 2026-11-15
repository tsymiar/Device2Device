[![Build Status](https://tsymiar.visualstudio.com/MyAutomatic/_apis/build/status/tsymiar.Device2Device?repoName=tsymiar%2FDevice2Device&branchName=main)](https://tsymiar.visualstudio.com/MyAutomatic/_build/latest?definitionId=72&repoName=tsymiar%2FDevice2Device&branchName=main)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/6cb8f83fb83d4e50a33bc39e470f2891)](https://app.codacy.com/gh/tsymiar/Device2Device/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)
[![Anny](https://img.shields.io/badge/GitHub-naver%2Fanny-1f8837?logo=github&logoColor=white)](https://github.com/naver/anny)
[![KCP](https://img.shields.io/badge/GitHub-skywind3000%2Fkcp-0969ca?logo=github&logoColor=white)](https://github.com/skywind3000/kcp)
[![MyAutomatic](https://img.shields.io/badge/GitHub-tsymiar%2Fscadup-8250ff?logo=github&logoColor=white)](https://github.com/tsymiar/scadup)

# Device2Device

Android app for peer-to-peer communication and multimedia processing — Bluetooth serial, multi-protocol
networking (incl. a KCP LAN messenger), GPU/media pipelines, a parametric 3D human model, AI chat,
market quotes with home widgets, a voice-deduction board game, and embedded HTTP / SSH servers.
UI layer in Java, heavy lifting in C++ via JNI.

---

## Architecture

![Architecture](image/device2device.png)

### Stack

| Category | Technologies |
| :------- | :----------- |
| Platform | Android **API 26+**, target / compile 31 |
| Language | Java, C++11 |
| Native | JNI, CMake 3.18.1, NDK 23, OpenGL ES 2.0, OpenSL ES, ARM NEON |
| Network | Bluetooth RFCOMM, TCP, UDP multicast, KCP, LAN messenger (KCP over UDP), HTTP, SSH |
| Market Data | Keyless HTTPS: Tencent, Eastmoney, Sina, Binance, Frankfurter (ECB) |
| Media | AudioRecord/Track, MediaExtractor, YUV↔RGB, PCM↔WAV |
| 3D | Parametric mesh + SDF/SurfaceNets, OBJ/MTL export |
| UI | Material Components, ConstraintLayout, custom views, Day/Night |
| Build | Gradle 7.0.2 (AGP 4.1), JDK 8 / 11 |

### Layout

```
app/src/main/
├── java/com/tsymiar/device2device/
│   ├── activity/       # 14: Main (dashboard) / Select / Market / Avatar / Texture /
│   │                   #   Wave / Graph / Sensor / Bt* / Bugger / MyGit / Thanks
│   ├── service/        # Subscribe, Publish, Http (:8080) & Ssh (:2222) server,
│   │                   #   FileMsg server, Receiver, Window, SaveData, Toast
│   ├── dialog/         # ChatBox (multi-provider AI), FileMsg, LanMsg, Game (deduction board)
│   ├── game/           # Deduction engine: propositions, voice samples, psych profile
│   ├── widget/         # Market widgets: list + chart + heatmap, compass (tap to measure)
│   ├── market/         # QuoteSource (11 sources), Quote, CapRow, Indicators
│   ├── avatar/         # AnnyModel/Targets/Params, MeshBuilder, HumanMesh, Renderer, PhotoAnalyzer
│   ├── acceleration/   # Sensor & voice modules
│   ├── entity/ event/  # Data entities, observer bus
│   ├── utils/ view/    # Utilities (Atom, SoundRecord, HttpsRequest …), custom views
│   │                   #   (KLineView, DeductionBoard / DeductionGameScreen …)
│   └── wrapper/        # JNI bridge: callback, network, view, media, time
└── cpp/                # JniMethods, bitmap, callback, codec, convert, display,
                        #   message, socket (kcp submodule), scadup (submodule),
                        #   test, time, utils
```

---

## Features

**Bluetooth** — scanning & pairing, bidirectional RFCOMM serial with directional commands, auto logging, draggable floating receive window.

**Networking** — TCP / UDP multicast / KCP reliable UDP, LAN messenger (KCP-over-UDP text & image chat with UDP-broadcast peer discovery), Pub/Sub floating dialogs, chunked file transfer with progress (LAN auto-discovery via UDP `8801` beacons), HTTP file server (FS + SAF, sortable listing, in-page viewer), SSH server on port 2222.

**LAN messenger** — `LanMsgDialog`: one KCP-over-UDP socket bound to `0.0.0.0:<port>` is both client and server — no connect handshake, peers are addressed by IP; periodic UDP-broadcast discovery with timeout eviction; oversized images are fragmented and reassembled on the peer; discovery address / port configurable.

**Market quotes** — `MarketActivity` + `KLineView`; keyless feeds from `auto` (smart pick + fallback), `tencent`, `eastmoney`, `gold`/`xau`/`gc`, `crude`/`brent`/`ng`, `usd`, `binance`. Code / name / pinyin picker, candles + MA + indicators (MACD, RSI, KDJ), drag-pan, pinch-zoom, crosshair bubble with the selected bar's cap / turnover / PE (auto-hides after 5 s idle), 15 s refresh.

**Market widgets** — three home-screen widgets bring quotes to the launcher without opening the app. The **quote list** (2×2, resizable) shows several watched symbols at once; rows land one by one as responses arrive, so the widget fills in progressively instead of flashing in all at once. The **quote chart** (2×2, resizable) draws a single symbol's trend as a sparkline, and gold symbols additionally annotate the CNY-per-gram price. The **market cap heatmap** (4×4) paints a squarified treemap of the top-30 A-shares: tile area encodes market cap, colour encodes the daily change, and a tap opens the full quote page. All three share one repaint path that follows day/night mode, size themselves adaptively to their host, and refresh on the same 15 s cadence as the in-app K-line view; each widget ships a configuration activity for picking symbols and the refresh interval.

**Compass widget** — tap-to-measure heading on the home screen: ticks, cardinal marks and needles rotate together with the measured azimuth, a fixed marker at the dial top points along the phone's forward direction, and the status text sits on a transparent band. The full-screen `CompassView` adds coordinates, 5° ticks and the same inward index line.

**Messaging (C++ ↔ Java)** — thread-safe queue + `MASSAGER` enum bridging native events (toast, status, file progress, texture, socket state) to the UI.

**Multimedia** — EGL/GLES2 and CPU image & video rendering, 16 kHz PCM recording with live waveform, WAV/MP4/OGG/MP3/AAC/AMR playback, built-in STT.

**Avatar** — fully on-device parametric body. Photo contributes appearance and silhouette; `Native` engine is SDF + SurfaceNets (procedural), `High-res` uses **Anny** ([naver/anny](https://github.com/naver/anny), Apache-2.0, MakeHuman/CC0) whose phenotype blendshapes are baked offline into `anny.mhb` and solved as `w = Π c`; falls back to `Native` when missing. Silhouette/color extraction, face shapes, 8 hairstyles, life-size measurements (BMI, girths, inseam), GL preview, OBJ+MTL export.

> Rebuild the bundle: `python3 tools/export_anny_targets.py export --anny-dir /path/to/anny --out app/src/main/assets/anny/anny.mhb`
> (`--synthetic` for a placeholder, `selftest` for byte-exactness).

**AI chat** — DeepSeek (built-in key fallback), Ollama (local host editable / Cloud), OpenRouter (free models only). Model lists are fetched per provider; selection is remembered.

**Deduction board** — *Voiceprint Deduction*: full-screen `GameDialog` hosting `DeductionGameScreen`; `DeductionEngine` builds propositions from voice samples (`DeductionAudio`) and tracks the three psychological traps — echo reinforcement, negative overwrite, self-learning; adaptive board text fitting.

**System** — observer event bus, native time sync, global toast, one-tap exit, sensor dashboard with 7.0 m/s² acceleration alert.

---

## Build

Requirements: SDK 31 + Build Tools 30.0.3, NDK 23.0.7599858, CMake 3.18.1, Gradle 7.0.2, JDK 8 / 11.
ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`.

```bash
git submodule update --init --recursive   # app/src/main/cpp/scadup, app/src/main/cpp/socket/kcp
./build.sh                                # or: ./build.sh release | clean | cleanall
# BUILD_OFFLINE=1 ./build.sh
```

`build.sh` writes `local.properties` from your environment and copies the APKs to `dist/`.
CI (Azure Pipelines, `macos-latest`) checks out the repo plus the `tsymiar/scadup` submodule and builds all three ABIs.

Permissions: `BLUETOOTH*` (incl. `SCAN`/`CONNECT`/`ADVERTISE`), location (BT scan), `INTERNET`,
`ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, storage & media read,
`CAMERA`, `RECORD_AUDIO`, `VIBRATE`, `WAKE_LOCK`, `FOREGROUND_SERVICE`,
`SYSTEM_ALERT_WINDOW`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `ACTIVITY_RECOGNITION`,
`HIGH_SAMPLING_RATE_SENSORS`, `READ_PHONE_STATE`.

## Screenshots

<img src="image/MainActivity.jpg" title="MainActivity" height="50%" width="50%">

## License

MIT

## Recent Changes

### 2026-11
- **File transfer:** `FileMsgSocket` aligned with LinxSrvc `TransferEngine` — `RESP_ACCEPT` (0-byte pulls), chunk validation, original-name saving with `-1/-2` dedupe, thread-safe progress callback, per-transfer cancel.
- **FileMsg discovery:** LAN auto-discovery on UDP `8801` — 1 s beacon (global + subnet broadcast), unicast reply so both sides pair in one round trip, clickable device list, live broadcast toggle.
- **FileMsg UI:** two-page dialog (receive / send) with shared status card and log; `SEND_START` progress event; disconnect / cancel are terminal; notification progress while the server runs in background; `ACTION_SEND` share entry shows a floating device picker (no app UI) and sends on tap.
- **Market:** K-line tooltip with cap / float cap / PE / turnover, auto-hide after 5 s.
- **Compass:** rotating widget dial, fixed heading marker, inward index line on the full-screen dial.
- **UI:** shorter splash (~800 ms), fixed source link; trimmed verbose comments.

### 2026-10
- **LAN:** KCP-over-UDP messenger with broadcast discovery.
- **Game:** *Voiceprint Deduction* board (voice samples, propositions, 3 psychological traps).
- **Market:** market-cap heatmap + progressive quote-list widgets.
- **ChatBox:** DeepSeek / Ollama / OpenRouter providers.
- **System:** SSH controls merged into Network, MediaCodec→TextureView, BT on worker thread.
- **Avatar / Sensors:** Air Bangs, Anny bust, hip calibration; smoothed decibel card.

### 2026-09
- **Avatar:** Anny engine, face shapes/hairstyles, body measurements, photo fallback.
- **Market / Sensors:** K-line view (11 sources) + quote-list widget; scalable dashboard, compass/level, decibel card.
- **System:** SSH server (2222), HTTP + SAF fixes.

### 2026-06
- **Messaging:** `MSG_HINT`, async Pub/Sub, subscription buffer fix, port parsing hardening.
