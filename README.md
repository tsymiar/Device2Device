[![Build Status](https://tsymiar.visualstudio.com/MyAutomatic/_apis/build/status/tsymiar.Device2Device?repoName=tsymiar%2FDevice2Device&branchName=main)](https://tsymiar.visualstudio.com/MyAutomatic/_build/latest?definitionId=72&repoName=tsymiar%2FDevice2Device&branchName=main)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/6cb8f83fb83d4e50a33bc39e470f2891)](https://app.codacy.com/gh/tsymiar/Device2Device/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)

# Device2Device

Android app for peer-to-peer communication and multimedia processing — Bluetooth serial, multi-protocol
networking, GPU/media pipelines, a parametric 3D human model, AI chat, market quotes with home widgets,
and embedded HTTP / SSH servers. UI layer in Java, heavy lifting in C++ via JNI.

---

## Architecture

![Architecture](image/device2device.png)

### Stack

| Category | Technologies |
| :------- | :----------- |
| Platform | Android **API 26+**, target / compile 31 |
| Language | Java, C++11 |
| Native | JNI, CMake 3.18.1, NDK 23, OpenGL ES 2.0, OpenSL ES, ARM NEON |
| Network | Bluetooth RFCOMM, TCP, UDP multicast, KCP, HTTP, SSH |
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
│   ├── service/        # Subscribe, Publish, Http & Ssh server, FileMsg server,
│   │                   #   Receiver, Window, SaveData, Toast
│   ├── widget/         # Market widgets: list + chart + heatmap
│   ├── market/         # QuoteSource (11 sources), Quote, CapRow, Indicators
│   ├── avatar/         # AnnyModel/Targets/Params, MeshBuilder, HumanMesh, Renderer, PhotoAnalyzer
│   ├── dialog/         # ChatBox (multi-provider AI), FileMsg
│   ├── acceleration/   # Sensor & voice modules
│   ├── entity/ event/  # Data entities, observer bus
│   ├── utils/ view/    # Utilities (Atom, SoundRecord, HttpsRequest …), custom views (KLineView …)
│   └── wrapper/        # JNI bridge: callback, network, view, media, time
└── cpp/                # JniMethods, bitmap, callback, convert, display,
                        #   message, socket (kcp submodule), scadup (submodule),
                        #   test, time, utils
```

---

## Features

**Bluetooth** — scanning & pairing, bidirectional RFCOMM serial with directional commands, auto logging, draggable floating receive window.

**Networking** — TCP / UDP multicast / KCP reliable UDP, Pub/Sub floating dialogs, chunked file transfer with progress, HTTP file server (FS + SAF, sortable listing, in-page viewer), SSH server on port 2222.

**Market quotes** — `MarketActivity` + `KLineView`; keyless feeds from `auto` (smart pick + fallback), `tencent`, `eastmoney`, `gold`/`xau`/`gc`, `crude`/`brent`/`ng`, `usd`, `binance`. Code / name / pinyin picker, candles + MA + indicators (MACD, RSI, KDJ), drag-pan, pinch-zoom, crosshair, 15 s refresh.

Home widgets:

| Widget | Size | What it does |
| :----- | :--- | :----------- |
| 📈 Quote list | 2×2, resizable | Several watched symbols in one widget; rows land one by one as quotes arrive |
| 📈 Quote chart | 2×2, resizable | Single-symbol trend sparkline; gold symbols also show CNY per gram |
| 📊 Market cap heatmap | 4×4 | Squarified treemap of the top-30 A-shares by market cap — tile area = cap, colour = daily change, tap to open the quote |

**Messaging (C++ ↔ Java)** — thread-safe queue + `MASSAGER` enum bridging native events (toast, status, file progress, texture, socket state) to the UI.

**Multimedia** — EGL/GLES2 and CPU image & video rendering, 16 kHz PCM recording with live waveform, WAV/MP4/OGG/MP3/AAC/AMR playback, built-in STT.

**Avatar** — fully on-device parametric body. Photo contributes appearance and silhouette; `Native` engine is SDF + SurfaceNets (procedural), `High-res` uses **Anny** ([naver/anny](https://github.com/naver/anny), Apache-2.0, MakeHuman/CC0) whose phenotype blendshapes are baked offline into `anny.mhb` and solved as `w = Π c`; falls back to `Native` when missing. Silhouette/color extraction, face shapes, 8 hairstyles, life-size measurements (BMI, girths, inseam), GL preview, OBJ+MTL export.

> Rebuild the bundle: `python3 tools/export_anny_targets.py export --anny-dir /path/to/anny --out app/src/main/assets/anny/anny.mhb`
> (`--synthetic` for a placeholder, `selftest` for byte-exactness).

**AI chat** — DeepSeek (built-in key fallback), Ollama (local host editable / Cloud), OpenRouter (free models only). Model lists are fetched per provider; selection is remembered.

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

### 2026-10
- **Market:** 4×4 市值热力图部件（沪深京 A 股市值前30家，面积=市值、颜色=涨跌幅，点击进个股行情）；行情列表部件标签改为「行情列表」并随取数顺序逐行落地；K线默认标的改为紫金矿业（601899），1 分钟周期走分时图（昨收基准线 + 涨跌幅刻度）。
- **ChatBox:** 多provider（DeepSeek / Ollama local + Cloud / OpenRouter），Ollama 主机可改，模型列表按需拉取（OpenRouter 仅免费）。
- **Avatar:** Air Bangs；Anny Bust（更高更圆、无缝）；Hip Fullness 校准。

### 2026-09
- **Avatar:** Anny 引擎（内置 `anny.mhb`、表型缩放），面部与发型、真实网格头部、A-pose 腿部、裸足、量体数据、照片兜底、接缝平滑。
- **Sensors:** 仪表盘缩放、罗盘/水平仪全屏、分贝卡片。
- **Market:** K线（11 个数据源）+ 2×2 列表小部件。
- **System:** SSH 服务端（2222 端口）、HTTP 服务端 + SAF 修复、深色感知 spinner。

### 2026-06
- **Messaging:** `MSG_HINT`、Pub/Sub 异步与话题隔离、订阅缓冲修复、端口解析保护、UI 打磨。
