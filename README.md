# Yerelkeşif

A program that discovers devices on your local network and lets you chat and transfer files with them. It uses asynchronous Asio networking and a WebView2 interface with a custom frameless title bar.

## Usage

- Devices on the same LAN appear in the left panel automatically. Use **+ IP Ekle** to probe a specific address.
- Select a device to open its panel. Drag files onto the drop zone or use **+ Dosya** and **+ Klasör** to add files. Folder selection includes nested files and preserves their relative paths.
- Select **Transferi Başlat** to send the queued files. Type in the chat box; Enter sends, while Shift+Enter adds a new line.
- Open the menu at the top right to change your username and download folder. Received files are saved to `Downloads\Yerelkesif` by default.
- A persistent device ID distinguishes devices that use the same username. When a device's IP changes, it remains the same peer in the device list.
- Logs are written to `%TEMP%\!Yerelkesif_Logs`.

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
