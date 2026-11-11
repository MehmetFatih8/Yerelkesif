# Yerelkeşif

A cross-platform local network app for discovering nearby devices, chatting and transferring files. The Windows version uses asynchronous Asio networking with a WebView2 interface and a custom frameless title bar. The Android version uses the same web interface with a native Java networking service. Windows and Android devices can discover and communicate with each other on the same LAN.

## Usage

- Devices on the same LAN appear automatically. Use **+ IP Ekle** to probe a specific address.
- Select a device to open its panel. Drag files onto the drop zone or use **+ Dosya** and **+ Klasör** to add files. Folder selection includes nested files and preserves their relative paths.
- Select **Transferi Başlat** to send the queued files. Type in the chat box; Enter sends, while Shift+Enter adds a new line.
- On Android, use **+ Dosya** or **+ Klasör** to select content. Folder transfers retain the selected folder name and nested paths. Android can also receive files from the system share menu; choose Yerelkeşif, then select a device to send.
- Open the menu at the top right to change your username and download folder. Windows saves received files to `Downloads\Yerelkesif` by default; Android uses the `Downloads` folder.
- A persistent device ID distinguishes devices that use the same username. When a device's IP changes, it remains the same peer in the device list.
- Logs are written to `%TEMP%\!Yerelkesif_Logs`.

## Android

The Android app supports device discovery, chat, file transfers and folder transfers with the Windows app. It uses UDP discovery on port 8888, TCP chat on port 9999 and TCP file transfers on port 9998. The Android interface adapts to status and navigation bars, and its foreground network service closes peer connections when the app task is removed.

To build a debug APK, install JDK 17 or newer and Android SDK Platform 37, then accept the Android SDK licenses:

```sh
cd android-app
./gradlew assembleDebug
```

On Windows, use `gradlew.bat assembleDebug`. The APK is written to `android-app/app/build/outputs/apk/debug/app-debug.apk`.

## Project layout

| Path | Role |
| --- | --- |
| `main.cpp` | Entry point: creates the controller, window and UI bridge |
| `AppController.*` | Owns the network stack, runs the I/O thread and tracks discovered devices |
| `network/FinderClient.*`, `network/FinderServer.*` | UDP discovery and peer identity exchange |
| `network/ClientSocket.*` | Outgoing TCP connections, message queue and file send queue |
| `network/ServerSocket.*`, `network/PeerConnection.*` | Incoming TCP connections, frame decoding and file receive |
| `network/Protocol.h`, `network/NetEvents.h` | Wire protocol constants and network events |
| `settings/AppSettings.*` | Persistent username and download folder settings |
| `common/Text.*`, `common/Logger.h` | UTF-8/UTF-16, JSON and logging helpers |
| `ui/MainWindow.*` | Frameless window, resize behavior and rounded corners |
| `ui/UiBridge.*` | WebView2 setup, JavaScript bindings and event forwarding |
| `ui/NativeFiles.*` | Native file/folder pickers and drag-and-drop handler |
| `ui/ArayuzHtml.h` | Front-end HTML, CSS and JavaScript |
| `app_resources.rc`, `Yerelkesif.ico`, `resource.h` | Executable icon and Windows version metadata |
| `android-app/app/src/main/java/.../MainActivity.java` | Android activity, share intents, file pickers and WebView bridge |
| `android-app/app/src/main/java/.../PeerService.java` | Android UDP discovery and TCP chat/file-transfer service |
| `android-app/app/src/main/assets/index.html` | Android front-end, shared interaction model and responsive layout |
| `android-app/app/src/main/res/` | Android launcher icons and app resources |

## Wire protocol

| Port | Protocol | Messages |
| --- | --- | --- |
| UDP 8888 | discovery | `P2P_DISCOVER_HELLO:<username>:<device-id>` (broadcast or probe), `P2P_MATCH_HELLO:<username>:<device-id>` (reply) |
| TCP 9999 | main | `MSG:<byte length>:<text>` |
| TCP 9998 | file | `FILE_REQ:<name>:<bytes>:` followed by `FILE_ACK` or `FILE_ERR`, then raw file bytes |

A discovered peer uses both TCP ports: one for chat messages and one for file transfers.

## Build

Requires CMake 3.20+, a C++20 compiler (MinGW-w64 is tested) and internet access on the first configure, because webview and asio are fetched with FetchContent. The WebView2 SDK version is pinned in `CMakeLists.txt`.

```sh
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release
```

Libraries: [webview/webview](https://github.com/webview/webview) 0.12.0 and [chriskohlhoff/asio](https://github.com/chriskohlhoff/asio).
