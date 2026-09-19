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
- 📊 Real-time sensor monitoring (accelerometer, gravity, linear acceleration)
- 🤖 AI chat powered by DeepSeek API (Chat + Deep Think models)
- 📈 Market quotes: multi-source K-line (A-shares, futures, forex/gold, crypto) with technical indicators
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
│   ├── market/                    # Market data
│   │   ├── QuoteSource            # 11 sources + symbol search/resolve + keyless HTTPS fetch & fallback
│   │   ├── Quote                  # Single bar (time, open/high/low/close, volume, amount)
│   │   └── Indicators             # SMA / EMA / MACD / RSI / KDJ math
│   ├── utils/                     # Utilities (Atom, MP4Header, WAVHeader, SoundRecord, WaveCanvas, HttpsRequest, Utils, etc.)
│   ├── view/                      # Custom views (WaveSurface, WaveformsView, KLineView)
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
| Symbol Input | Code (`sh600519`, `1.600519`, `BTCUSDT`) or Chinese name / pinyin (`茅台`, `gzmt`, `沪金`, `美元指数`); ambiguous hits open a picker, the resolved name shows on the chart |
| Chart      | Candles + wicks (red up / green down), MA5/MA10/MA20, last-price dashed line, adaptive price axis, smart time-axis labels |
| Indicators | Main: MA; sub-panel cycles on tap: 成交量 / MACD(12,26,9) / RSI(14) / KDJ(9,3,3) (falls back to MACD when the source has no volume) |
| Interaction | Drag to pan history (320 bars requested per screen), pinch to zoom bar width, tap the main chart for a crosshair + floating tooltip (time / OHLC / volume / change), tap the sub-panel to cycle panels |
| Auto Refresh | 15s polling toggle; screen stays on while enabled |
| Persistence | Source / interval / symbol / auto-refresh saved in `SharedPreferences` and restored on re-entry; retired source ids fall back to `auto` |

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
| `UDP_SERVER`  | C++ → Java | UDP server status                         |
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

### 2026-09

- **Market Quotes Module** (`MarketActivity.java`, `market/QuoteSource.java`, `market/Indicators.java`, `market/Quote.java`, `view/KLineView.java`): New K-line screen reachable from the dashboard (`📈 行情 K线 · Market`, replacing the retired Echo Resonance game entry). Source / interval / symbol pickers, name-or-code symbol input with a disambiguation dialog, 320 bars per screen with drag-to-pan and pinch-to-zoom, tap-to-inspect OHLC, and a 15s auto-refresh toggle that keeps the screen on. Main chart draws candles (red up / green down) with MA5/MA10/MA20 and a last-price dashed line; the sub-panel cycles 成交量 / MACD(12,26,9) / RSI(14) / KDJ(9,3,3) on tap and defaults to MACD for volume-less sources. Source / interval / symbol / auto-refresh persist in `SharedPreferences`.
- **Multi-Source Quote Resolution** (`QuoteSource.java`): 11 sources (auto, tencent, eastmoney, gold, xau, gc, crude, brent, ng, usd, binance) over keyless public HTTPS endpoints, with mirror hosts, per-source interval tables, per-request bar caps (Tencent 640 / Eastmoney 10000 / Binance 1000) and automatic paging; `auto` walks the candidate list and falls back on failure. Chinese-name / pinyin / alias resolution (`茅台`, `gzmt`, `沪金`, `伦敦金`, `美元指数`, …) plus prefix rules for unlisted contract spellings (`au2412`).
- **US Dollar Index Sources** (`QuoteSource.java`): Minute bars are aggregated from Eastmoney `trends2` (`100.UDI`), falling back to the Sina FX snapshot (`DINIW`) when Eastmoney is unreachable; daily / weekly / monthly bars are reverse-computed from ECB reference rates (`api.frankfurter.dev`, EUR/JPY/GBP/CAD/SEK/CHF) using the ICE USDX weights, so `1d`–`1M` are now available instead of failing.
- **Switching Source Clears Symbol Input** (`MarketActivity.java`): Selecting a different source category now clears the symbol box (instead of keeping a mismatched code from the previous category), resets the hint text to that source's code format, clears the chart title, and loads the new source's default symbol — so no stale code can be queried against the wrong source.
- **Day/Night Contrast for Status Text** (`activity_select.xml`, `values/colors.xml`, `values-night/colors.xml`): `txt_hint` / `txt_status` / the divider no longer use hardcoded colors. They now read `@color/hint_text`, `@color/status_text`, `@color/card_divider`, which are defined per configuration (`#546E7A` / `#00796B` / `#B0BEC5` in light, `#90A4AE` / `#80CBC4` / `#546E7A` in night), keeping text above 4.5:1 contrast and the divider one step dimmer on both light and dark card backgrounds.
- **Multi-Select ZIP Download** (`HttpBrowserService.java`): Every file/folder row now has a checkbox with a select-all toggle in the column header and a "下载" toolbar button. Selected items (folders packed recursively, hidden files skipped) are packed per-file over `?zip=<name>&zip=<name>…` — each selected file becomes its own independent ZIP entry streamed to the browser (HTTP/1.1 chunked, no temp file on disk), images/videos/audios and other already-compressed files skip re-deflating, and a single unreadable file is skipped instead of corrupting the whole archive.
- **HTTP Server Sortable File List** (`HttpBrowserService.java`): Web directory listing gained clickable column headers (Name / Size / Modified). Click toggles ascending/descending, folders always stay on top, name column uses natural numeric ordering (`IMG_2 < IMG_10`), active sort shows ▲/▼.
- **Responsive File List Layout**: File names now claim all remaining row width (`flex:1`). Below 760px the size/date columns shrink; below 580px the modified-date and its clickable sortable column header both move to a second line (date stays visible and time sorting stays available on phones); full file name shows as a tooltip (`title`) when truncated.
- **Viewer Ordering Fix**: The in-page image viewer now resolves image indexes from the *current* DOM order on every open, so prev/next navigation stays in sync after re-sorting the list.
- **HTTP Transfer Throughput Tuning**: Client connections now run on a cached thread pool; sockets enable `TCP_NODELAY` and a 512KB send buffer; files > 10MB stream with a 256KB buffer instead of 64KB (fewer syscalls and ContentProvider IPC round-trips) for better Wi-Fi LAN download speed.
- **SAF Directory Detection Fix**: Directory entries are now detected via `Document.MIME_TYPE_DIR` instead of the non-existent `DocumentsContract.Document.FLAG_DIRECTORY` constant, fixing the compile error during batch directory queries.

### 2026-06

- **MSG_HINT Message Type**: Added `MSG_HINT = 9` to `MASSAGER` enum for displaying auxiliary hint text in `SelectActivity`. C++ can now send hints via `Message::instance().setMessage(msg, MSG_HINT)`.
- **UI Layout Improvements**: `sample_text` moved to fixed footer (outside `ScrollView`). `txt_hint` added above `txt_status` with visual differentiation: italic 12sp gray hint vs. bold 14sp dark status, separated by a divider line.
- **Pub/Sub Async Overhaul** (`JniMethods.cpp`): `StartSubscribe` now returns immediately (non-blocking), connection results sent asynchronously via Message system. `Publish` moved to detached thread to prevent UI thread blocking (ANR). Added real-time feedback (Toast) for both Subscribe and Publish operations.
- **Publish/Subscribe Topic Isolation** (`PubSubSetting.java`): Separate `topic` (subscribe) and `pubTopic` (publish) fields to prevent cross-contamination between services.
- **Subscriber Use-After-Free Fix** (`Subscriber.cpp`): Fixed random trailing characters in received messages. Root cause: `body` buffer was freed immediately after enqueuing callback to thread pool, but the callback's `content` pointer still referenced freed memory. Fixed by using `shared_ptr<vector<char>>` to manage buffer lifecycle, ensuring it persists until callback completion.
- **Port Parsing Protection** (`SubscribeService.java`): Added `try-catch` around `Integer.parseInt()` calls for port input, falling back to default port 9999 on invalid input.
