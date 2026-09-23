[![Build Status](https://tsymiar.visualstudio.com/MyAutomatic/_apis/build/status/tsymiar.Device2Device?repoName=tsymiar%2FDevice2Device&branchName=main)](https://tsymiar.visualstudio.com/MyAutomatic/_build/latest?definitionId=72&repoName=tsymiar%2FDevice2Device&branchName=main)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/6cb8f83fb83d4e50a33bc39e470f2891)](https://app.codacy.com/gh/tsymiar/Device2Device/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)

# Device2Device

A feature-rich Android application for **peer-to-peer communication** and **multimedia processing** between devices.

**Core capabilities:**
- 🔵 Bluetooth RFCOMM serial communication with device discovery and data logging
- 🌐 Multi-protocol networking: TCP, UDP (multicast), KCP (reliable UDP)
- 📨 Pub/Sub messaging via scadup message queue library
- 🎨 GPU (OpenGL ES) and CPU image/video rendering
- 🎤 Audio recording (16kHz PCM) with real-time waveform visualization and speech recognition
- 📊 Real-time sensor monitoring (accelerometer, gravity, linear acceleration) and a dashboard (compass, bubble level, altitude, magnetic field, steps, proximity)
- 🖥 SSH server: remote shell into the device over Wi-Fi
- 🧍 Photo / camera driven life-size 3D human model (parametric body, face & hair styling, OBJ export)
- 🤖 AI chat powered by DeepSeek API (Chat + Deep Think models)
- 📈 Market quotes: multi-source K-line (A-shares, futures, forex/gold, crypto) with technical indicators and a home-screen quote widget
- 📁 Embedded HTTP file server with directory browsing

---

## Architecture

![Architecture](image/device2device.png)

---

### Technology Stack

| Category    | Technologies                                              |
| :---------- | :-------------------------------------------------------- |
| Platform    | Android (API 21+, target 31)                              |
| Language    | Java, C++ (C++11)                                        |
| Native      | JNI, CMake 2.8+, OpenGL ES 2.0, OpenSL ES, NDK 23       |
| Network     | UDP (Multicast), TCP, KCP (Reliable UDP), HTTP, Bluetooth |
| Market Data | Keyless public HTTPS endpoints: Tencent, Eastmoney, Sina, Binance, Frankfurter (ECB rates) |
| Media       | AudioRecord, AudioTrack, MediaExtractor, OpenGL ES, YUV↔RGB, PCM↔WAV |
| 3D          | OpenGL ES 2.0 (parametric human mesh, GLSurfaceView), OBJ/MTL export |
| UI          | Material Design, ConstraintLayout, Custom Views (incl. `KLineView`), Day/Night color resources, System Overlay |
| Build       | Gradle 7.x, CMake, NDK (ARM NEON optimizations)           |

---

### Project Structure

```
app/src/main/
├── java/com/tsymiar/device2device/
│   ├── activity/                  # Activities (13)
│   │   ├── MainActivity           # Splash screen → auto-navigate
│   │   ├── SelectActivity         # Main dashboard (network, sensor, chat, files, Bluetooth, market)
│   │   ├── MarketActivity         # Market quotes: source/interval/symbol pickers + K-line chart
│   │   ├── AvatarActivity         # Photo/camera → life-size 3D human model (params, face, hair, export)
│   │   ├── TextureActivity        # Image/Video GPU & CPU rendering
│   │   ├── WaveActivity           # Audio recording & real-time waveform + speech recognition
│   │   ├── GraphActivity          # Sensor real-time data display
│   │   ├── SensorActivity         # List all device sensors
│   │   ├── CommitActivity         # Bluetooth serial communication (RFCOMM)
│   │   ├── ConnectActivity        # Bluetooth discoverability & device scanning
│   │   ├── DevicesActivity        # Paired & discovered Bluetooth devices list
│   │   ├── BuggerActivity         # Bug report / email feedback
│   │   ├── MyGitActivity          # Open project GitHub page
│   │   └── ThanksActivity         # Acknowledgements page
│   ├── service/                   # Background services (7)
│   │   ├── SubscribeService       # Pub/Sub subscribe floating window
│   │   ├── PublishService         # Pub/Sub publish floating window
│   │   ├── HttpBrowserService        # Embedded HTTP file server (File & SAF modes)
│   │   ├── ToastNotificationService  # Global floating toast notifications
│   │   ├── ReceiverService        # Bluetooth data receive floating window
│   │   ├── WindowService          # Generic text overlay floating window
│   │   ├── SaveDataService        # Persist Bluetooth received data to file
│   │   └── Voice                  # Audio alert playback service
│   ├── acceleration/              # Sensor & Voice modules
│   │   ├── SensorFragment         # Real-time accelerometer chart (Bézier-smoothed)
│   │   ├── DefaultFragment        # Basic sensor chart variant
│   │   └── Voice                  # Over-acceleration warning (threshold: 7.0 m/s²)
│   ├── dialog/                    # UI dialogs
│   │   ├── ChatBoxDialog          # DeepSeek AI chat (Chat / Reasoner models)
│   │   └── FileMsgDialog          # File transfer with progress & SAF file picker
│   ├── entity/                    # Data entities (PubSubSetting, Receiver)
│   ├── event/                     # Observer-pattern event system (EventHandle, EventNotify)
│   ├── widget/                    # Home-screen app widget
│   │   ├── MarketWidgetProvider   # Quote widget: fetch / RemoteViews rendering / manual refresh broadcast
│   │   └── MarketWidgetConfigActivity # Widget config screen shown when added (data source + symbol)
│   ├── market/                    # Market data
│   │   ├── QuoteSource            # 11 sources + symbol search/resolve + keyless HTTPS fetch & fallback
│   │   ├── Quote                  # Single bar (time, open/high/low/close, volume, amount)
│   │   └── Indicators             # SMA / EMA / MACD / RSI / KDJ math
│   ├── avatar/                    # 3D human model
│   │   ├── BodyProfile            # Body params (gender/height/weight/head ratio) + BMI & girth derivation
│   │   ├── MeshBuilder            # Tube (lofted) & ellipsoid primitives → normals/colors/parts
│   │   ├── HumanMesh              # Parametric life-size body from BodyProfile + OBJ/MTL export
│   │   ├── AvatarRenderer         # OpenGL ES 2.0 renderer: lighting, ground grid, height ruler, capture
│   │   ├── AvatarSurfaceView      # GLSurfaceView wrapper: drag to rotate, pinch to zoom, screenshot
│   │   └── PhotoAnalyzer          # Photo silhouette (shoulder/waist/hip ratios) + region color extraction
│   ├── utils/                     # Utilities (Atom, MP4Header, WAVHeader, SoundRecord, WaveCanvas, HttpsRequest, Utils, etc.)
│   ├── view/                      # Custom views (WaveSurface, WaveformsView, KLineView, CompassView, BubbleLevelView, DecibelView)
│   └── wrapper/                   # JNI native bridge (Callback, Network, View, Media, Time)
├── cpp/                           # Native C++ code
│   ├── JniMethods.cpp/h           # All JNI entry points
│   ├── bitmap/                    # BMP image processing
│   ├── callback/                  # Java↔C++ bidirectional callbacks
│   ├── convert/                   # PCM↔WAV, YUV↔RGB format conversion
│   ├── display/                   # GPU (EGL/GLES2) & CPU rendering
│   ├── message/                   # Thread-safe message queue
│   ├── socket/                    # UDP / TCP / KCP / FileMsg protocol
│   ├── scadup/                    # Message queue library (tsymiar/scadup)
│   ├── test/                      # Unit tests (FileMsgSocket)
│   ├── time/                      # Timestamp utilities
│   └── utils/                     # File utilities, logging, constants
```

---

## Features

### Bluetooth Communication

| Feature        | Description                                                          |
| :------------- | :------------------------------------------------------------------- |
| Device Scanning | Scan for nearby Bluetooth devices                                    |
| Pairing         | List paired devices and discover new ones                            |
| Serial Comm     | Bidirectional RFCOMM communication with directional control commands |
| Data Logging    | Auto-save received data to local file                                |
| Floating Window | Receive data display in draggable system overlay                     |

### Network Communication

| Feature     | Description                                                     |
| :---------- | :--------------------------------------------------------------- |
| UDP Server  | Start a UDP multicast server to receive data                     |
| UDP Client  | Start a UDP multicast client to send data                        |
| TCP Server  | Start a TCP server to receive data                               |
| KCP         | KCP (Reliable UDP) protocol for low-latency transmission         |
| Pub/Sub     | Subscribe & Publish messages via floating dialog windows; async connection with real-time status feedback |
| File Transfer | Custom binary protocol with chunked transfer (64KB/chunk) and progress callback |
| HTTP Server | Embedded HTTP server (filesystem & SAF) with sortable/responsive HTML directory listing, in-page image viewer, and streaming transfers tuned for Wi-Fi LAN throughput |
| SSH Server  | Embedded SSH server (Apache MINA SSHD) on port 2222 with a built-in shell command set, run as a foreground service |

### Market Quotes (K-Line)

`MarketActivity` + `market/` package + `KLineView`. All endpoints are keyless public HTTPS; callbacks run on the main thread; bar fields (`time, open, high, low, close, volume, amount`) match the `matkline.py` toolset so scripts and the app share one parsing/indicator convention.

| Source (`QuoteSource`) | Covers | Intervals |
| :--------------------- | :----- | :-------- |
| `auto` | Picks source by symbol shape, then falls back one by one | 1m…1M |
| `tencent` | A-shares / indices / funds (name, pinyin or code) | 1m, 5m, 15m, 30m, 60m, 1h, 1d, 1w, 1M |
| `eastmoney` | A-shares / futures / HK (`1.600519`) | same as above |
| `gold` / `xau` / `gc` | Shanghai gold AU0 (AG0) / London spot XAU / COMEX GC | 1m…1d (Sina futures daily-max) |
| `crude` / `brent` / `ng` | WTI CL / Brent OIL / US natural gas NG | 1m…1d |
| `usd` | US Dollar Index UDI — minute bars aggregated from Eastmoney ticks (falls back to Sina FX snapshot), daily+ reverse-computed from ECB reference rates | 1m…1M |
| `binance` | Crypto pairs (`BTCUSDT`) | 1m, 3m, 5m, 15m, 30m, 1h, 2h, 4h, 6h, 8h, 12h, 1d, 3d, 1w, 1M |

| Feature     | Description                                                                 |
| :---------- | :-------------------------------------------------------------------------- |
| Symbol Input | Code (`sh600519`, `1.600519`, `BTCUSDT`) or Chinese name / pinyin (`茅台` Kweichow Moutai, `gzmt`, `沪金` Shanghai gold, `美元指数` USD index); ambiguous hits open a picker, the resolved name shows on the chart |
| Chart      | Candles + wicks (red up / green down), MA5/MA10/MA20, last-price dashed line, adaptive price axis, smart time-axis labels |
| Indicators | Main: MA; sub-panel cycles on tap: Volume / MACD(12,26,9) / RSI(14) / KDJ(9,3,3) (falls back to MACD when the source has no volume) |
| Interaction | Drag to pan history (320 bars requested per screen), pinch to zoom bar width, tap the main chart for a crosshair + floating tooltip (time / OHLC / volume / change), tap the sub-panel to cycle panels |
| Auto Refresh | 15s polling toggle; screen stays on while enabled |
| Persistence | Source / interval / symbol / auto-refresh saved in `SharedPreferences` and restored on re-entry; retired source ids fall back to `auto` |
| Home Widget | `📈 Quotes` app widget (2×2, resizable): per-instance source + symbol (or "follow the last symbol viewed in the app"), last price and change % coloured red-up / green-down, tap the card to open the K-line screen, tap Refresh to fetch immediately; 30 min fallback refresh by the system, refreshed in sync whenever the market screen loads |

### Message System (C++ ↔ Java)

The app uses a thread-safe message queue (`Message.h`) to bridge C++ native code with Java UI. Messages are dispatched via the `MASSAGER` enum:

| Type          | Direction | Description                               |
| :------------ | :-------: | :---------------------------------------- |
| `MESSAGE`     | C++ → Java | General toast notifications               |
| `TOAST`       | C++ → Java | Status text update (`txt_status`)         |
| `MSG_HINT`    | C++ → Java | Hint text update (`txt_hint`)             |
| `SUBSCRIBER`  | C++ → Java | Subscribe service feedback                |
| `PUBLISHER`   | C++ → Java | Publish service feedback                  |
| `FILE_PROGRESS` | C++ → Java | File transfer progress update             |
| `TEXTURE`     | C++ → Java | Texture rendering callback                |
| `UDP_SERVER`  | C++ → Java | UDP server: first message after start is the status (port), every later one is received data |
| `UDP_CLIENT`  | C++ → Java | UDP client status                         |
| `KCP_VIEW`    | C++ → Java | KCP connection status                     |

The SelectActivity UI features a dual-status display: `txt_hint` (italic 12sp, secondary blue-grey) for supplementary hints and `txt_status` (bold 14sp, teal) for primary status, separated by a divider line. The three colors come from `@color/hint_text`, `@color/status_text`, `@color/card_divider`, which have separate `values` / `values-night` definitions (`SelectActivity` runs on a `DayNight` theme), so contrast stays above 4.5:1 on both light and dark card backgrounds.

### Multimedia Processing

| Feature        | Description                                                            |
| :------------- | :--------------------------------------------------------------------- |
| GPU Rendering  | Image/video rendering via OpenGL ES 2.0 (EGL/GLESv2)                  |
| CPU Rendering  | Software-based image/video decoding and display                        |
| Audio Recording | 16kHz PCM recording with real-time waveform visualization              |
| Audio Playback  | Play WAV/MP4/OGG/MP3/AAC/AMR files with waveform analysis              |
| Speech to Text  | Built-in speech recognition (STT) integration                          |
| Sensor Monitor  | Real-time accelerometer, gravity, and linear acceleration data display |

### 3D Human Model (Avatar)

Reachable from the dashboard Services card (`🧍 3D Human Model · Avatar`). The pipeline is **parametric** and runs fully on-device — there is no photogrammetry / cloud reconstruction involved. A photo contributes *appearance and silhouette proportions*, the user supplies the *absolute scale*:

| Feature         | Description                                                            |
| :-------------- | :--------------------------------------------------------------------- |
| Photo Input     | Gallery (`ACTION_GET_CONTENT`) or camera (`FileProvider` + `ACTION_IMAGE_CAPTURE`); tap the thumbnail to preview the full image |
| Cloud (Tripo3D) | Optional `☁ Tripo3D photo-to-3D`: upload the photo (`POST /v3/files` → `image-to-model` → poll `GET /v3/tasks/{id}` → download GLB), parse it with `GlbLoader` and normalize to the current height. Needs the user's own API key, stored locally |
| Silhouette Fit  | Border-based background estimate → foreground mask → per-row width profile at shoulder / chest / waist / hip → `chestR` / `waistR` / `hipR` multipliers |
| Color Extract   | Median foreground color of hair / face / upper / lower bands → hair, skin, top & bottom colors |
| Body Params     | Gender, height (120–210 cm), weight (30–150 kg), head-to-body ratio (6–8.5), shoulder / chest / waist / hip fine tuning |
| Face & Hair     | 6 face shapes (oval/round/square/long/heart/diamond) and 8 hairstyles (bald → long, ponytail, bun, curly) driving head profile & hair volumes |
| Life-Size Mesh  | 7.5-head canon landmarks (shoulder 0.82H … ankle 0.045H) in metres, feet at `y=0`; girth from BMI (limbs ≈ √BMI, waist/hip steeper) |
| Preview         | OpenGL ES 2.0: ground grid (0.2 m), height ruler with on-screen scale numbers (0.5 m steps + the current height reading) and contact shadow, drag-rotate / pinch-zoom / double-tap reset |
| Measurements    | BMI + CN grading, shoulder width, chest / waist / hip circumference (ellipse perimeter), arm span, inseam |
| Export          | OBJ + MTL grouped by body part with per-part colors, and PNG screenshot of the preview |
| Persistence     | All params & colors saved in `SharedPreferences` and restored on re-entry |

### Intelligent Features

| Feature      | Description                                                        |
| :----------- | :------------------------------------------------------------------ |
| AI Chat      | DeepSeek API integration with Chat / Reasoner (Deep Think) models   |
| Sensor Alert | Automatic warning audio when acceleration exceeds 7.0 m/s² threshold |

### System Integration

| Feature       | Description                                                    |
| :------------ | :-------------------------------------------------------------- |
| Event System  | Observer-pattern event broadcast for inter-component communication |
| Time Sync     | Native timestamp acquisition and synchronization                |
| Global Toast  | Floating toast notification service (3s auto-dismiss)            |
| One-Tap Exit | Global exit manager to terminate all activities and services     |

---

## Build Requirements

| Component      | Version                          |
| :------------- | :------------------------------- |
| Android SDK    | 31 (compileSdk) / 21+ (minSdk)  |
| Android NDK    | 23.0.7599858                     |
| Build Tools    | 30.0.3                           |
| Gradle         | 7.x                              |
| CMake          | 2.8+                             |
| JDK            | 8 or 11                          |
| ABIs           | arm64-v8a, armeabi-v7a, x86_64   |
| C++ Standard   | C++11 (ARM NEON optimized)       |

---

## Permissions

```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.HIGH_SAMPLING_RATE_SENSORS" />
```

---

## Building

```bash
# Local build
./build.sh

# CI build (Azure Pipelines)
# See azure-pipelines.yml
```

---

## CI/CD

This project uses Azure Pipelines for continuous integration on `macos-latest`. The pipeline:

- Checks out the main repo and `tsymiar/scadup` submodule
- Copies scadup native library to `app/src/main/cpp/scadup/`
- Installs Android SDK components (NDK 23, build-tools 30.0.3, platform 31)
- Sets up JDK 11 and builds with Gradle 7.0.2
- Produces debug APK for `arm64-v8a`, `armeabi-v7a`, `x86_64`

---

## Screenshots

<img src="image/MainActivity.jpg" title="MainActivity" height="30%" width="30%">

---

## License

MIT License

---

## Recent Changes

Short log — one line per change.

### 2026-09

- **Sensor Card Zoom**: tap the compass / bubble-level card → gauge goes full-screen; tap again or back to restore; readings keep updating.
- **Compass (zoomed)**: 5° ticks + degree numbers every 15°, lat/lon above a triangle pointing at the dial top; cardinals / bearing / needle scale up.
- **Bubble Level (zoomed)**: only the two angles, no left-right / front-back labels; the "Level" verdict is kept.
- **Decibel Card**: visible only while recording; placeholder text removed.
- **SSH Server**: MINA SSHD on port 2222 (next 9 ports if busy), user `d2d` + random password; built-in shell commands; status shown in the card.
- **Sensor Dashboard**: compass + bubble level in one row, magnetic / altitude / steps / proximity in another; altitude from GPS, text auto-shrinks, placeholders for missing hardware.
- **Interval Labels**: hourly bars show `1h`; picker reads `1m 5m 30m 1h 1d 1w 1M 1Q 1Y` (daily / weekly / monthly / quarterly / yearly).
- **Misc**: Texture / Wave screens show their own titles; chart entry dropped `ic_chart`.
- **3D Human Model**: photo-driven parametric body, silhouette + color extraction, face / hair styling, OBJ+MTL or PNG export, optional Tripo3D.
- **Market K-Line**: 11 sources, name/pinyin resolution, pan/zoom, MA + MACD/RSI/KDJ sub-panels, day/night palette.
- **Market Widget**: 2×2 resizable multi-instance widget, drag-to-reorder rows, tap to open the market screen.
- **HTTP Server**: multi-select ZIP download, sortable columns, responsive layout, throughput tuning.
- **SAF Fix**: directory rows via `Document.MIME_TYPE_DIR`.

### 2026-06

- **MSG_HINT**: new `MSG_HINT = 9`, C++ can push auxiliary hints to `SelectActivity`.
- **UI Polish**: `sample_text` pinned to the footer; `txt_hint` (italic 12sp) above `txt_status` (bold 14sp) with a divider, colors day/night aware.
- **Pub/Sub Async** (`JniMethods.cpp`): subscribe returns immediately, publish runs on a detached thread — no ANR.
- **Topic Isolation** (`PubSubSetting.java`): separate `topic` and `pubTopic` fields.
- **Subscribe Use-After-Free Fix** (`Subscriber.cpp`): body buffer held by `shared_ptr`, fixes trailing garbage in received messages.
- **Port Parsing Guard** (`SubscribeService.java`): `parseInt` wrapped in try-catch, invalid input falls back to 9999.
