# ServerAccessHub

A personal cloud storage application with a web-based file manager and an in-browser terminal, built entirely in **pure Java 21** without any frameworks. The server handles HTTPS, HTTP routing, WebSocket communication, and process management from scratch using only the Java standard library.

---

## Main Idea

ServerAccessHub turns any Linux machine into a private cloud server. Once deployed inside a Docker container, it exposes a single HTTPS port that serves two web interfaces:

1. **File Browser** — a mobile-friendly single-page application for managing files (upload, download, rename, delete, preview, folder ZIP download).
2. **Web Terminal** — a fully interactive bash shell running in the browser via [xterm.js](https://xtermjs.org/).

All communication is encrypted with TLS. The server runs as a self-contained Java JAR — no Spring, no Jetty, no external web server.

---

## How It Works

### Architecture Overview

```
┌──────────────────────────────────────────────────────────┐
│                        Browser                           │
│  ┌─────────────────┐           ┌────────────────────┐    │
│  │  index.html     │           │  terminal.html     │    │
│  │  (File Browser) │           │  (xterm.js shell)  │    │
│  └────────┬────────┘           └─────────┬──────────┘    │
│           │ WebSocket (wss://)           │ WebSocket     │
└───────────┼──────────────────────────────┼───────────────┘
            │                              │
┌───────────▼──────────────────────────────▼───────────────┐
│                  Server.java (HTTPS)                     │
│         SSLServerSocket on port 8080                     │
│                                                          │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────────┐  │
│  │ HTTPHandler  │  │ WebSocket    │  │ Terminal       │  │
│  │              │  │ Handler      │  │ Handler        │  │
│  │ Static files │  │ File system  │  │ /bin/bash      │  │
│  │ Upload(PUT)  │  │ commands:    │  │ via Process    │  │
│  │ Download(GET)│  │ ls, cd, rm,  │  │ Builder        │  │
│  │ ZIP folders  │  │ mkdir, rename│  │                │  │
│  └──────────────┘  └──────┬───────┘  └────────────────┘  │
│                           │                              │
│                  ┌────────▼────────┐                     │
│                  │ FileSystem      │                     │
│                  │ Service         │                     │
│                  │ (sandboxed to   │                     │
│                  │  cloudStorage/) │                     │
│                  └─────────────────┘                     │
└──────────────────────────────────────────────────────────┘
```

### Request Flow

1. **Client connects** to `https://host:8080`. The `Server` class accepts the TLS socket and spawns a new thread.
2. **HTTP request is parsed** manually — the server reads raw bytes, detects `\r\n\r\n` (end of headers), and extracts the method, path, and headers.
3. **Routing decision:**
   - If the request contains `Upgrade: websocket` header → the server performs the RFC 6455 handshake (SHA-1 + Base64 of the `Sec-WebSocket-Key`) and upgrades the connection.
     - Path `/terminal-ws` → **TerminalHandler** — spawns `/bin/bash` via `ProcessBuilder` and bridges stdin/stdout with WebSocket frames.
     - Any other path → **WebSocketHandler** — creates an isolated `FileSystemService` instance and enters a command loop (`ls`, `cd`, `mkdir`, `rm`, `rename`, `goto`, `suggest`, `undo`).
   - Otherwise → **HTTPHandler** — serves static files from `web/`, or handles file upload (`PUT /upload`) and download (`GET /download`, `GET /downloadFolder`, `GET /downloadZip`).

### Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| No frameworks (pure `javax.net.ssl`) | Learning exercise; full control over HTTP and WebSocket protocols |
| One thread per connection | Simple concurrency model suitable for a personal server |
| `FileSystemService` per WebSocket session | Each client gets its own current directory and navigation history (thread-safe isolation) |
| Sandboxed file operations | All paths are resolved relative to `cloudStorage/` — prevents directory traversal |
| Multi-stage Docker build | Build with Maven in a temporary container, run with a slim JDK image |
| Streamed directory listing | Large folders are sent in chunks (`LIST_CHUNK` / `LIST_END`) to keep the UI responsive |

---

## Features

- **File Upload** — streaming `PUT` with progress, supports files up to 2 GB, writes to a `.part` temp file and renames on success
- **File Download** — serves files with correct MIME types for inline preview (PDF, images, video, text)
- **Folder Download** — compresses folders into ZIP archives on-the-fly (up to 3 GB)
- **File Preview** — PDFs, images, videos, and text files render directly in the browser
- **Web Terminal** — interactive bash shell via xterm.js with color support and terminal resize handling
- **Path Autocomplete** — the `suggest` command returns matching folder names as the user types
- **Navigation History** — `undo` command reverts to the previous directory
- **HTTPS** — all traffic is encrypted using a Java KeyStore (`keystore.jks`)

---

## Tech Stack

| Layer | Technology |
|-------|------------|
| Backend | Java 21 — `SSLServerSocket`, `ProcessBuilder`, raw socket I/O |
| Frontend | HTML5, CSS3, vanilla JavaScript |
| Terminal | [xterm.js](https://xtermjs.org/) 5.3 with fit and web-links addons |
| Build | Maven 3.9+ |
| Container | Docker (multi-stage build) & Docker Compose |
| Deployment | Ansible playbook for Ubuntu servers |

---

## Project Structure

```
ServerAccessHub/
│
├── src/main/java/org/example/
│   ├── Main.java                       # Entry point — starts the server
│   ├── Server.java                     # HTTPS server, TLS setup, request routing
│   ├── Config.java                     # Reads config from env vars or .env file
│   ├── HTTPHandler/
│   │   └── HTTPHandler.java            # Static file serving, upload, download, ZIP
│   ├── websocket/
│   │   ├── WebSocketHandler.java       # File browser commands over WebSocket
│   │   └── WebSocketUtils.java         # RFC 6455 handshake (SHA-1 + Base64)
│   ├── filesystem/
│   │   └── FileSystemService.java      # Sandboxed file operations (ls, cd, rm, mkdir…)
│   └── terminal/
│       └── TerminalHandler.java        # Bash session via ProcessBuilder + WebSocket
│
├── web/
│   ├── index.html                      # File browser UI (single-page app)
│   ├── script.js                       # WebSocket client, uploads, navigation logic
│   └── terminal.html                   # xterm.js terminal UI
│
├── ansible/
│   ├── playbook.yml                    # Automated deployment to Ubuntu server
│   ├── inventory.ini                   # Target server configuration
│   └── .env.yml.example                # Example variables for Ansible
│
├── cloudStorage/                       # Mounted volume — user files are stored here
├── Dockerfile                          # Multi-stage build (Maven → JDK 21 runtime)
├── docker-compose.yml                  # Container orchestration
└── pom.xml                             # Maven build configuration
```

---

## How the WebSocket Protocol Is Implemented

The server implements the WebSocket protocol (RFC 6455) manually:

1. **Handshake** — when the server detects an `Upgrade: websocket` header, it extracts the `Sec-WebSocket-Key`, concatenates it with the magic GUID (`258EAFA5-E914-47DA-95CA-C5AB0DC85B11`), computes SHA-1, encodes it in Base64, and responds with HTTP 101.
2. **Frame parsing** — the server reads the FIN bit, opcode (text `0x1`, close `0x8`), mask bit, payload length (7-bit, 16-bit, or 64-bit extended), masking key, and applies XOR unmasking as required by the spec.
3. **Frame sending** — outgoing frames are unmasked (server-to-client), with proper length encoding for payloads under 126 bytes, under 65536 bytes, and larger.

---

## How the Terminal Works

1. The browser opens a WebSocket connection to `/terminal-ws`.
2. `TerminalHandler` spawns a bash process using `ProcessBuilder` with `/usr/bin/script -q -c /bin/bash /dev/null` to get pseudo-terminal behavior.
3. A background thread continuously reads bash stdout and sends it to the browser as WebSocket text frames.
4. The main thread reads WebSocket frames from the browser (keystrokes) and writes them to bash stdin.
5. When the client disconnects, the bash process is destroyed.

---

## License

MIT
