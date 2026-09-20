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
│   │   ├── MarketWidgetProvider   # 行情小部件：取数 / RemoteViews 渲染 / 手动刷新广播
│   │   └── MarketWidgetConfigActivity # 添加小部件时的配置页（数据源 + 标的）
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
| Home Widget | `📈 行情` app widget (2×2, resizable): per-instance source + symbol (or "follow the last symbol viewed in the app"), last price and change % coloured red-up / green-down, tap the card to open the K-line screen, tap 刷新 to fetch immediately; 30 min fallback refresh by the system, refreshed in sync whenever the market screen loads |

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

### 3D Human Model (Avatar)

Reachable from the dashboard Services card (`🧍 人物 3D 模型 · Avatar`). The pipeline is **parametric** and runs fully on-device — there is no photogrammetry / cloud reconstruction involved. A photo contributes *appearance and silhouette proportions*, the user supplies the *absolute scale*:

| Feature         | Description                                                            |
| :-------------- | :--------------------------------------------------------------------- |
| Photo Input     | Gallery (`ACTION_GET_CONTENT`) or camera (`FileProvider` + `ACTION_IMAGE_CAPTURE`); tap the thumbnail to preview the full image |
| Cloud (Tripo3D) | Optional `☁ Tripo3D 照片生成 3D`: upload the photo (`POST /v3/files` → `image-to-model` → poll `GET /v3/tasks/{id}` → download GLB), parse it with `GlbLoader` and normalize to the current height. Needs the user's own API key, stored locally |
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

### 2026-09

- **3D Human Model Module** (`AvatarActivity.java`, `avatar/BodyProfile.java`, `avatar/MeshBuilder.java`, `avatar/HumanMesh.java`, `avatar/AvatarRenderer.java`, `avatar/AvatarSurfaceView.java`, `avatar/PhotoAnalyzer.java`): New Services entry (`🧍 人物 3D 模型 · Avatar`). Pick a photo or take one, and the app derives appearance (hair / top / bottom median colors, plus a light-invariant skin estimate — see below) plus silhouette proportions (shoulder / chest / waist / hip row-width ratios) from the picture; combined with the user's gender / height / weight / head ratio it builds a parametric life-size mesh in metres (feet at `y=0`, crown at `y=height`). 7.5-head canon landmarks, BMI-driven girth (limbs ≈ √BMI, waist and hip steeper), 6 face shapes driving the head profile and 8 hairstyles adding hair volumes (side strands, back curtain, ponytail, bun, curly noise). Preview runs on OpenGL ES 2.0 with a 0.2 m ground grid, a 0.1 m height ruler with scale numbers and a height marker line, tap the thumbnail to preview the source photo, drag-rotate / pinch-zoom, and reports BMI, shoulder width, chest / waist / hip circumference, arm span and inseam. Exports OBJ + MTL grouped per body part, or a PNG screenshot of the preview. All parameters persist in `SharedPreferences`.
- **Market Quotes Module** (`MarketActivity.java`, `market/QuoteSource.java`, `market/Indicators.java`, `market/Quote.java`, `view/KLineView.java`): New K-line screen reachable from the dashboard (`📈 行情 K线 · Market`, replacing the retired Echo Resonance game entry). Source / interval / symbol pickers, name-or-code symbol input with a disambiguation dialog, 320 bars per screen with drag-to-pan and pinch-to-zoom, tap-to-inspect OHLC, and a 15s auto-refresh toggle that keeps the screen on. Main chart draws candles (red up / green down) with MA5/MA10/MA20 and a last-price dashed line; the sub-panel cycles 成交量 / MACD(12,26,9) / RSI(14) / KDJ(9,3,3) on tap and defaults to MACD for volume-less sources. Source / interval / symbol / auto-refresh persist in `SharedPreferences`.
- **Multi-Source Quote Resolution** (`QuoteSource.java`): 11 sources (auto, tencent, eastmoney, gold, xau, gc, crude, brent, ng, usd, binance) over keyless public HTTPS endpoints, with mirror hosts, per-source interval tables, per-request bar caps (Tencent 640 / Eastmoney 10000 / Binance 1000) and automatic paging; `auto` walks the candidate list and falls back on failure. Chinese-name / pinyin / alias resolution (`茅台`, `gzmt`, `沪金`, `伦敦金`, `美元指数`, …) plus prefix rules for unlisted contract spellings (`au2412`).
- **US Dollar Index Sources** (`QuoteSource.java`): Minute bars are aggregated from Eastmoney `trends2` (`100.UDI`), falling back to the Sina FX snapshot (`DINIW`) when Eastmoney is unreachable; daily / weekly / monthly bars are reverse-computed from ECB reference rates (`api.frankfurter.dev`, EUR/JPY/GBP/CAD/SEK/CHF) using the ICE USDX weights, so `1d`–`1M` are now available instead of failing.
- **Market Home-Screen Widget** (`widget/MarketWidgetProvider.java`, `widget/MarketWidgetConfigActivity.java`, `layout/widget_market.xml`, `xml/market_widget_info.xml`): `📈 行情` app widget (2×2, resizable, multiple instances allowed). Adding one opens a config screen to pick the source and symbol (name / pinyin input is resolved to a code first, so refreshes do not re-run a search); symbols are stored per `appWidgetId`, and an unconfigured widget simply follows the symbol last viewed in the K-line screen. Each refresh fetches 3 daily bars, shows the last price with adaptive decimals plus the change % coloured red-up / green-down (matching `KLineView`), and stamps the update time. Tapping the card opens the market screen, tapping 刷新 fetches immediately; the system's 30-minute `updatePeriodMillis` is the fallback. `goAsync()` keeps the broadcast alive across the async fetch with a 30 s watchdog, and widget configs are cleaned up in `onDeleted`.
- **Switching Source Clears Symbol Input** (`MarketActivity.java`): Selecting a different source category now clears the symbol box (instead of keeping a mismatched code from the previous category), resets the hint text to that source's code format, clears the chart title, and loads the new source's default symbol — so no stale code can be queried against the wrong source.
- **Day/Night Market Palette** (`market/MarketPalette.java`, `res/values/colors.xml`, `res/values-night/colors.xml`, `MarketActivity.java`, `view/KLineView.java`, `widget/MarketWidgetProvider.java`, `widget/MarketWidgetConfigActivity.java`, `layout/widget_market.xml`, `drawable/widget_market_bg.xml`): the K-line screen, its config screen and the home-screen widget no longer hard-code the dark palette — background / panel / stroke / text / accent / grid / MA / MACD-RSI-KDJ series / tooltip and the red-up green-down pair now come from `market_*` color resources with a `-night` variant, so everything follows the system day/night mode. `KLineView.setPalette()` swaps the whole set in one call; the widget re-applies the current text colors on every update so the launcher does not keep a stale palette.
- **Market Widget: Reorderable Symbols** (`widget/MarketWidgetConfigActivity.java`): the selected-symbols list is now a `RecyclerView` with an `ItemTouchHelper` — grab the ☰ handle (40 dp touch target) or long-press a row to drag it, rows swap one step at a time so the drag tracks the finger instead of jumping, and every row shows its position number so the order is visible. Dragging disallows the outer `ScrollView` from stealing the gesture (list is `nestedScrollingEnabled=false`, plus `requestDisallowInterceptTouchEvent` up the parent chain). The order is persisted with the rest of the config on 添加到桌面 and becomes the widget's row order; the provider matches cached quotes by symbol when rebuilding rows, so reordering never cross-wires prices. The page is also **no longer one big ScrollView** — 数据源 / the symbol field / 添加 live in a fixed, opaque header (elevation 2 dp) and the list takes the remaining height with `weight=1` and scrolls on its own (`LinearLayout.LayoutParams` for the list, since a `RecyclerView.LayoutParams` loses its margins when LinearLayout re-wraps it), so a long list can no longer scroll the input field out of view; `windowSoftInputMode="adjustResize"` keeps the field visible when the keyboard opens. The list itself is drawn as an outlined card (page-colour fill + stroke, 8 dp padding) with 22 dp of clear space above it — no elevation on the header, because its shadow lands right in that gap and reads as "the list is covering the input".
- **Market Widget: Scrollable Rows & Market Codes** (`widget_market.xml`, `widget_market_item.xml`, new `widget/MarketWidgetService.java`, `widget/MarketWidgetProvider.java`, `widget/MarketWidgetConfigActivity.java`, `AndroidManifest.xml`, `strings.xml`): the symbol rows moved out of stacked `addView` containers into a real **ListView** — RemoteViews rejects ScrollView on inflate, so an `AdapterView` backed by a `RemoteViewsService` is the only way to scroll, which is why "taller widget = more rows" is now "any number of rows, scroll them". The provider keeps a process-local row cache, fills it from one fetch, then `notifyAppWidgetViewDataChanged` re-draws; if the process was recycled the factory's `onDataSetChanged` tops the cache up once (60 s staleness + in-flight dedupe, so scrolling never re-requests). Every row now shows its **market code in 9sp** under the name (`sh600519 · 沪`, `hk00700 · 港`, `BTCUSDT · 加密货币` — market taken from the code prefix, falling back to the data source), rows are individually clickable through a pending-intent template + fill-in and open the market screen on that symbol (`MarketWidgetProvider.applyLaunchSymbol`), and the empty/loading state uses `setEmptyView`. The config screen's controls got proper touch targets: 50 dp input field and 添加 button at 15 sp, 48 dp source spinner, 52 dp bottom bar at 15 sp bold, and a 48 dp-tall 删除 hit area per row. Those were later tightened back down — the whole form is one compact scale now: 40 dp inputs and 添加 at 14 sp, 40 dp spinner, 44 dp bottom bar, 40 dp ☰ / 删除 per row — so the page fits on small screens without shrinking the text.
- **Widget "Can't Add" Fixes** (`widget/MarketWidgetConfigActivity.java`, `widget/MarketWidgetProvider.java`, `widget_market.xml`): the config screen no longer finishes immediately when the launcher doesn't pass `EXTRA_APPWIDGET_ID` (some third-party launchers only put `EXTRA_APPWIDGET_IDS`, others pass nothing) — it falls back to the newest existing widget id and, failing that, stays open with a hint instead of vanishing, which is what "can't add" looked like. The page is now a ScrollView with the **「添加到桌面」button pinned to the bottom**, so a long symbol list can't push the button off-screen, and saving calls the new `MarketWidgetProvider.refreshNow()` to render the widget directly instead of relying on a broadcast (a few ROMs drop those, leaving the widget blank). `refreshOne()` wraps skeleton/render/update in try/catch and paints the failure reason into the widget rather than letting the host crash, and the layout dropped its bare `<View>` spacer (an unknown View can fail RemoteViews inflation) in favour of a `0dp + weight` row container.
- **Leg & Foot Parameters, Finer Body** (`avatar/BodyProfile.java`, `avatar/HumanMesh.java`, `AvatarActivity.java`): four new sliders under **⑤ 腿脚** — `thighR` (大腿围 0.70–1.45×), `calfR` (小腿围 0.70–1.45×), `footR` (脚长 0.85–1.20×) and `footWR` (脚宽 0.80–1.30×) — all persisted like the other ratios and reported in the stats line (大腿围 / 小腿围 / 脚长 cm). The foot itself is no longer a single rounded box (which read as two bricks from the front): `buildFoot()` now composes **sole plate + upper + narrowed toe + taller heel + ankle-to-shoe transition**, with the ankle sitting at the rear third of the shoe, so there is an instep, a toe taper and a heel, and the calf no longer detaches from the shoe. Body voxels went 168 → **176** (auto-rebuild at ¾ density if the short-index ceiling is approached), giving ~43 k vertices / 85 k triangles for a standard body at ~0.4–0.6 s on the desktop JVM; thigh/calf circumference and foot length scale linearly with the new sliders.
- **Market Widget: Multiple Symbols & Smooth Minute Line** (`widget/MarketWidgetProvider.java`, `widget/MarketWidgetConfigActivity.java`, `view/KLineView.java`, `widget_market.xml`, `widget_market_item.xml`, `market_widget_info.xml`, `strings.xml`): one widget can now hold **several symbols** (stocks / gold / oil / crypto mixed, up to 8) — the config screen collects a list with add/remove instead of a single pair, and the provider renders one `RemoteViews` row per symbol via `addView`, fetching them in parallel and filling rows in order. Size follows the content: the row count is derived from the widget's *current* height (`onAppWidgetOptionsChanged` + `OPTION_APPWIDGET_MIN_HEIGHT`), so dragging the widget taller reveals more rows, a single symbol gets the big 20sp price while several get the compact 15sp rows, and leftovers collapse into a "还有 N 个" footer; the provider info now declares `resizeMode` + `minResize/maxResize` + `widgetFeatures="reconfigurable"` so the list can be edited later by long-pressing. The 1-minute trend line is now drawn with **monotone cubic interpolation** (Fritsch–Carlson tangents, clamped to 3× the neighbouring slopes and flattened at local extrema) instead of straight `lineTo` segments — smooth to the eye, but it can never overshoot into a high/low that isn't in the data, which plain Catmull-Rom would.
- **Better Skin Tone & Finer Human Mesh** (`avatar/PhotoAnalyzer.java`, `avatar/HumanMesh.java`, `avatar/HeadMesh.java`, `avatar/SdfModel.java`, `avatar/MeshBuilder.java`, `AvatarActivity.java`): skin colour is no longer a plain median of the face band (that pulled in hair, collars and background). It now runs a **light-invariant chroma test** (`skinTone`: normalised r/(r+g+b), g/(r+g+b) + channel ordering + a "darker pixels must be redder" rule that separates hair from dark skin), collects only skin pixels, drops the darkest/brightest 15 % (shadow reads cold, highlights read white), refines the estimate **inside the located face box**, and finally calibrates luma into a usable range and pulls chroma toward typical skin by 25–80 % depending on saturation — so grey/blue/over-exposed shots no longer produce a dead-grey or purple avatar. `faceSkin()` re-samples the cropped face texture so the baked face and the body skin share one base colour (no more two-tone face/neck), and the analysis note prints the detected hex for checking. The mesh is finer and more human: body voxels 150 → **168** over height, head 96×84 → **128×112**, hair shell 48×22 → 60×28, denser hands/eyes/lips/ears; the torso is now a **continuous interpolated section loft** (shoulder → chest → waist → hip) instead of three stacked ellipsoids that bulged between chest and belly, limbs are two-segment with a calf belly, wrist and ankle, and `SdfModel.field()` skips primitives by height so the extra detail costs no extra build time. `MeshBuilder` now degrades gracefully at the 65535 short-index ceiling (extreme short+heavy bodies rebuild at ¾ density).
- **1-Minute Trend Line & 2×2 Widget** (`view/KLineView.java`, `MarketActivity.java`, `xml/market_widget_info.xml`, `layout/widget_market.xml`): the 1-minute interval now draws a **line chart** instead of candles — `KLineView.setLineMode(true)` plots the visible closes as a polyline with a translucent area fill and a dot on the last price (colour `market_trend`, day/night aware), because hundreds of 1-minute candles per screen turn into mush; MA lines, the last-price dashed line, pan/zoom and the tap-to-inspect tooltip are unchanged. The home-screen widget shrank from 3×2 to **2×2** (`targetCellWidth/Height=2`, `minWidth/minHeight=110dp`) with a tightened layout (padding 9 dp, name 13 sp, price 20 sp, meta 10 sp) so all four rows still fit in the smaller cell.
- **Tripo3D Cloud Generation (optional)** (`avatar/TripoClient.java`, `avatar/GlbLoader.java`, `AvatarActivity.java`): new `☁ Tripo3D 照片生成 3D` entry on the avatar screen. The dialog asks for the user's own API key (persisted in `SharedPreferences`, never bundled), a face-limit and whether to generate textures; `TripoClient` then uploads the photo, creates the `image-to-model` task, polls it (3 s interval, 5 min cap, progress shown in the status line) and downloads the GLB, which `GlbLoader` parses (POSITION / NORMAL / indices, byteStride aware) and normalizes to the current height exactly like an imported OBJ. Pure `HttpURLConnection` + `org.json`, no new dependency.
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
