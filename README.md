[![Build Status](https://tsymiar.visualstudio.com/MyAutomatic/_apis/build/status/tsymiar.Device2Device?repoName=tsymiar%2FDevice2Device&branchName=main)](https://tsymiar.visualstudio.com/MyAutomatic/_build/latest?definitionId=72&repoName=tsymiar%2FDevice2Device&branchName=main)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/6cb8f83fb83d4e50a33bc39e470f2891)](https://www.codacy.com/gh/tsymiar/Device2Device/dashboard?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=tsymiar/Device2Device&amp;utm_campaign=Badge_Grade)

# Device2Device

An Android application for peer-to-peer communication between devices, featuring multi-protocol networking, multimedia processing, sensor data collection, AI chat integration, and an HTTP file server.

---

## Architecture

![Architecture](image/device2device.png)

---

### Technology Stack

| Category | Technologies |
| :------ | :----------- |
| Platform | Android (API 21+, target 31) |
| Language | Java, Kotlin, C++ (C++11) |
| Native | JNI, CMake 3.18+, OpenGL ES 2.0, NDK 23 |
| Network | UDP (Multicast), TCP, KCP (Reliable UDP), HTTP |
| Media | AudioRecord, OpenGL ES, YUV↔RGB, PCM↔WAV |
| UI | Material Design, ConstraintLayout, Custom Views |
| Build | Gradle 7.x, CMake, NDK (NEON optimizations) |

---

### Project Structure

```
app/src/main/
├── java/com/tsymiar/device2device/
│   ├── activity/                # Activity components (8)
│   │   ├── MainActivity         # Splash screen → auto-navigate
│   │   ├── SelectActivity       # Main control panel (network, sensor, chat, files)
│   │   ├── TextureActivity      # Image/Video CPU/GPU rendering test
│   │   ├── WaveActivity         # Audio recording & real-time waveform
│   │   ├── GraphActivity        # Sensor real-time line chart
│   │   ├── SensorActivity       # List all device sensors
│   │   ├── BuggerActivity       # Bug report / email feedback
│   │   └── ThanksActivity       # Acknowledgements page
│   ├── service/                 # Background services (4)
│   │   ├── SubscribeService     # MQTT-like floating subscribe window
│   │   ├── PublishService       # MQTT-like floating publish window
│   │   ├── HttpFileService      # Lightweight HTTP file server (File & SAF modes)
│   │   └── ToastNotificationService  # Global floating toast notifications
│   ├── acceleration/            # Sensor & Voice modules
│   │   ├── SensorFragment       # Real-time accelerometer chart (dual-curve)
│   │   ├── DefaultFragment      # Legacy sensor chart fragment
│   │   └── Voice/               # Over-acceleration warning audio
│   ├── dialog/                  # UI dialogs
│   │   ├── ChatBoxDialog        # DeepSeek AI chat (multi-model, HTTPS)
│   │   └── FileMsgDialog        # File transfer with progress display
│   ├── entity/                  # Data entities (PubSubSetting, EventEntity, ChatInfo)
│   ├── event/                   # Observer-pattern event system (EventHandle, EventNotify)
│   ├── utils/                   # Utilities (Atom, Utils, HttpsRequest, SoundRecord, etc.)
│   ├── view/                    # Custom views (WaveCanvas, NewChart, WaveformsView)
│   └── wrapper/                 # JNI native bridge (Network, View, Callback, Config, Time)
├── cpp/                         # Native C++ code (13 modules)
│   ├── JniMethods.cpp/h         # All JNI entry points
│   ├── bitmap/                  # BMP image processing
│   ├── callback/                # Java↔C++ bidirectional callbacks
│   ├── convert/                 # PCM↔WAV, YUV↔RGB format conversion
│   ├── display/                 # GPU (EGL/GLES2) & CPU rendering
│   ├── message/                 # Thread-safe message queue
│   ├── socket/                  # UDP / TCP / KCP / FileMsg protocol implementations
│   ├── scadup/                  # Message queue library
│   ├── test/                    # Unit tests (FileMsgSocket)
│   ├── time/                    # Timestamp utilities
│   └── utils/                   # File utilities, logging, constants
├── res/
│   ├── layout/                  # 18 XML layouts (Material Design)
│   ├── values/                  # Colors, strings, themes (light + night)
│   └── ...
```

---

## Features

### Multimedia Processing

| Feature | Description |
| :----- | :---------- |
| TEXTURE | Image/Video encode/decode test with GPU (OpenGL ES) / CPU dual rendering paths |
| WAVE | Record 16kHz PCM audio and draw real-time waveforms with WAV playback support |
| CHART | Display real-time accelerometer data (linear + gravity) with Bézier-smoothed line charts |

### Network Communication

| Feature | Description |
| :----- | :---------- |
| SERVER | Start a UDP multicast server to receive data |
| CLIENT | Start a UDP multicast client to send data |
| TCP | Start a TCP server to receive data |
| KCP | Test KCP (Reliable UDP) protocol for low-latency transmission |
| SUBSCRIBE | Subscribe to Pub/Sub messages via floating dialog window |
| PUBLISH | Publish messages to subscribers via floating dialog window |
| FILE TRANSFER | Custom binary protocol with chunked transfer (64KB/chunk) and progress callback |
| HTTP SERVER | Lightweight embedded HTTP server with HTML directory listing, supports SAF mode (Android 10+) |

### Intelligent Features

| Feature | Description |
| :----- | :---------- |
| AI CHAT | DeepSeek AI chat integration (Chat / Reasoner models) via HTTPS API |
| SENSOR ALERT | Automatic warning audio when acceleration exceeds 7.0 m/s² |

### System Integration

| Feature | Description |
| :----- | :---------- |
| EVENT | Observer-pattern event broadcast system for inter-component communication |
| TIME | Native timestamp acquisition and synchronization |
| TOAST | Global floating toast notification service (3s auto-dismiss) |

---

## Build Requirements

| Component | Version |
| :-------- | :------ |
| Android SDK | 31 (compileSdk) / 21+ (minSdk) |
| Android NDK | 23.0.7599858 |
| Build Tools | 30.0.3 |
| Gradle | 7.x |
| CMake | 3.18+ |
| ABIs | arm64-v8a, armeabi-v7a, x86_64 |
| C++ Standard | C++11 (NEON optimized) |

---

## Permissions

```xml
android.permission.BLUETOOTH_CONNECT
android.permission.WRITE_EXTERNAL_STORAGE
android.permission.READ_EXTERNAL_STORAGE
android.permission.RECORD_AUDIO
android.permission.INTERNET
android.permission.ACCESS_WIFI_STATE
android.permission.CHANGE_WIFI_MULTICAST_STATE
android.permission.SYSTEM_ALERT_WINDOW
android.permission.ACCESS_NETWORK_STATE
android.permission.READ_PHONE_STATE
android.permission.HIGH_SAMPLING_RATE_SENSORS
```

---

## Building

```bash
./build.sh
```

---

## Screenshot

<img src="image/MainActivity.jpg" title="MainActivity" height="50%" width="50%">

---

## License

MIT License
