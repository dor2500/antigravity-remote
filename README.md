<p align="center">
  <img src="docs/screenshots/banner.png" alt="Antigravity Remote" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/dor2500/antigravity-remote/releases/latest"><img src="https://img.shields.io/github/v/release/dor2500/antigravity-remote?style=for-the-badge&label=release&color=1f6feb" alt="Latest release" /></a>
  <a href="https://github.com/dor2500/antigravity-remote/actions/workflows/build-apk.yml"><img src="https://img.shields.io/github/actions/workflow/status/dor2500/antigravity-remote/build-apk.yml?branch=main&style=for-the-badge&label=build" alt="Build status" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3fb950?style=for-the-badge&logo=android&logoColor=white" alt="Android 8.0+" />
  <a href="LICENSE"><img src="https://img.shields.io/github/license/dor2500/antigravity-remote?style=for-the-badge&color=8957e5" alt="License" /></a>
</p>

<p align="center">
  <a href="https://github.com/dor2500/antigravity-remote/releases/latest">
    <img src="https://img.shields.io/badge/Download-Latest%20APK-3fb950?style=for-the-badge&logo=android&logoColor=white&labelColor=0d1117" alt="Download latest APK" height="44" />
  </a>
</p>

**Antigravity Remote** puts Antigravity IDE on your Android phone. Follow the IDE's agent conversations live and reply to them, start new ones, approve or reject the commands the agent wants to run, use a terminal in the project folder, and browse the code. It works on your home network and, with Tailscale or a tunnel, from anywhere.

## Screenshots

| Pair | IDE conversation | Agent | Terminal | Files |
| :---: | :---: | :---: | :---: | :---: |
| <img src="docs/screenshots/connect-qr.png" width="170" alt="Pairing screen" /> | <img src="docs/screenshots/ide-conversation.png" width="170" alt="An Antigravity IDE conversation followed live on the phone" /> | <img src="docs/screenshots/remote-control.png" width="170" alt="Agent with an approval request" /> | <img src="docs/screenshots/terminal.png" width="170" alt="Terminal tab" /> | <img src="docs/screenshots/files.png" width="170" alt="Files tab" /> |
| Scan the QR code or enter the address | Follow and reply to IDE conversations | Live output with Approve / Reject | PowerShell in the workspace | Browse and read project files |

## Quick start

1. **Start the bridge on Windows.** Clone this repository and double-click one of:
   - `start-bridge-remote.bat`: works from anywhere (mobile data, any Wi-Fi) through an automatic Cloudflare HTTPS tunnel, with no VPN or router setup;
   - `start-bridge.bat`: home network and Tailscale only.

   The first run installs dependencies, builds the bridge, asks once (UAC) to open TCP port 8765 in Windows Firewall, and prints a QR code.

   ```powershell
   git clone https://github.com/dor2500/antigravity-remote; .\antigravity-remote\start-bridge-remote.bat
   ```

2. **Install the app on Android.** Download [`AntigravityRemote-latest.apk`](https://github.com/dor2500/antigravity-remote/releases/latest/download/AntigravityRemote-latest.apk) on the phone and open it. Allow installs from your browser if Android asks.

3. **Scan the QR code.** Tap **Scan Bridge QR Code** and point the camera at the terminal. The phone remembers the PC and reconnects automatically from then on.

To reach the PC when you are away from home, see [Use it from anywhere](#use-it-from-anywhere).

### Requirements

| On the PC | On the phone |
| --- | --- |
| Windows 10 or 11 | Android 8.0 (API 26) or newer |
| [Antigravity](https://antigravity.google) with its CLI (`agy`) signed in: run `agy` once in a terminal | Same Wi-Fi as the PC, Tailscale, or a public tunnel URL |
| Node.js 18+ (installed automatically with winget if missing) | |

## How it works

```
 Android app  -- REST + WebSocket (token) -->  Bridge (Node.js, Windows)
   LAN / Tailscale / https tunnel                 |- Antigravity hub .. IDE conversations: follow, reply, stop, approve
                                                  |- agy acp ......... bridge agent session (Agent Client Protocol)
                                                  |- PowerShell ...... terminal tab, runs in the workspace
                                                  |- file system ..... files tab, read-only, sandboxed to the workspace
                                                  '- Antigravity IDE . "Open in IDE" for the active folder
```

### IDE conversations

Antigravity IDE runs its agent in a local hub process (`agy --hub`). The bridge finds the running hub and talks to it on localhost, so the phone sees the same conversations as the IDE. Open the **Agent** tab menu, choose **IDE conversations**, and pick one: its history loads, new steps stream in live, and what you type is sent into that conversation, exactly as if you typed it in the IDE. You can also start a new IDE conversation from the same sheet. **Stop** cancels the running turn, and **Leave IDE conversation** returns to the bridge's own agent session.

The hub interface is internal to Antigravity and not documented. It was mapped against Antigravity 1.5.14 (hub 2.34.0), so a future Antigravity update may change it. When the IDE asks for approval (only if your IDE settings do not auto-run commands), the request appears on the phone as an approval card. That path follows the protocol definitions but could not be exercised on the development PC, because its IDE auto-approves everything.

### Bridge agent session

When no IDE conversation is open, the bridge starts the Antigravity agent through its **Agent Client Protocol** server (`agy acp`) in the active workspace. Everything the agent does streams to the phone: replies, thinking, tool calls and command output. When the agent asks permission to run a command or edit a file, the request appears on the phone as an approval card. **Approve** and **Reject** answer that request directly, **Stop** cancels the current turn, and **New session** in the menu starts a fresh conversation.

### Features

- **Agent tab**: multi-line prompt box, live markdown output, approval cards, a queue for follow-up prompts, copy-all and long-press-to-copy. Prefix a prompt with `!` to run it as a shell command instead.
- **Quick actions**: `Approve`, `Reject`, `Stop` and `Clear`, enabled only when they apply.
- **Terminal tab**: PowerShell in the workspace. `cd` persists between commands, text typed while a command runs goes to its stdin (answer `y/n` prompts, `Read-Host`, and so on), and there are one-tap chips for common git and npm commands.
- **Files tab**: breadcrumb navigation and a line-numbered viewer with copy. Reading is limited to the active workspace, and files over 256 KB are truncated.
- **Workspaces**: switch the project folder from the phone. The list is filled from Antigravity IDE's recent folders and common project roots, and you can type any path. **Open in IDE** opens the folder in Antigravity IDE on the PC.
- **Connection**: QR pairing, manual entry, a saved profile with every route to the PC, automatic failover between routes, reconnect with backoff, and an immediate reconnect when the network changes or the app returns to the foreground. The status bar shows whether you are connected via LAN, Tailscale or Internet.

## Use it from anywhere

The pairing QR code carries every route to the PC: its LAN address, its Tailscale address, and an optional public URL. The phone tries them in order and switches by itself, for example from Wi-Fi at home to mobile data on the road.

**Quickest: `start-bridge-remote.bat`.** It installs `cloudflared` with winget if needed, opens a free Cloudflare quick tunnel and adds its `https://...trycloudflare.com` address to the QR code. The address changes every time the bridge starts, so scan the code again after a restart. Your LAN and Tailscale routes keep working in between.

**Most reliable: Tailscale (free, private, encrypted).**

1. Install [Tailscale](https://tailscale.com/download) on the PC and on the phone, and sign in to the same account on both.
2. Restart `start-bridge.bat`. The banner now lists a `Tailscale` address, and the QR code includes it.
3. Scan the QR code again once. From then on the app reaches the PC from any network.

Nothing is exposed to the internet, and the firewall rule already allows Tailscale's address range.

**Your own stable URL.** If you run a tunnel or reverse proxy with a fixed `https://` address that forwards to `http://localhost:8765` (a named Cloudflare Tunnel, an ngrok static domain), put it in the config file as `publicUrl`, or pass `start-bridge.bat -PublicUrl https://your-address`. It is added to the QR code as a route and does not change between restarts.

With either tunnel, anyone who finds the URL reaches the bridge's token check, so the bridge locks out an address for 10 minutes after 10 wrong tokens.

## Bridge reference

`start-bridge.ps1` accepts these options (`start-bridge.bat` passes them through):

| Option | Purpose |
| --- | --- |
| `-Port 8765` | Port to listen on |
| `-Workspace C:\path\to\project` | Folder to open first |
| `-Name "Office PC"` | PC name shown in the app |
| `-ResetToken` | Issue a new token, which unpairs every phone |
| `-Tunnel` | Open a Cloudflare quick tunnel and add its URL to the QR code (what `start-bridge-remote.bat` does) |
| `-PublicUrl https://...` | Your own public https address that forwards to the bridge (added to the QR code) |
| `-SkipFirewall` | Do not create the firewall rule |

Settings and the token are stored in `%APPDATA%\antigravity-remote\bridge-config.json`. You can also set `agentCommand` (path to `agy.exe`), `ideCli`, `shell` (for example `pwsh.exe`) and `advertiseHost` (the address put in the QR code, such as your Tailscale IP) there.

<details>
<summary>HTTP and WebSocket API</summary>

Every `/api` call needs `Authorization: Bearer <token>`. The WebSocket takes the token as `?token=`.

| Method | Path | Body | Purpose |
| --- | --- | --- | --- |
| GET | `/health` | | Liveness check (no token) |
| GET | `/api/status` | | PC, workspace, agent, terminal and IDE state |
| GET | `/api/workspaces` | | Known workspaces |
| POST | `/api/workspaces/switch` | `{ "path" }` | Change the active workspace |
| POST | `/api/prompt` | `{ "prompt" }` | Send to the agent (`!cmd` runs in the terminal) |
| POST | `/api/action` | `{ "action", "target" }` | `approve`, `reject`, `stop`, `restart`, `retry`, `clear`. `target` is `agent` or `terminal` |
| POST | `/api/terminal` | `{ "command" }` | Run a command |
| POST | `/api/terminal/input` | `{ "text" }` | Write a line to the running command's stdin |
| GET | `/api/files?path=` | | List a folder |
| GET | `/api/files/read?path=` | | Read a file |
| POST | `/api/ide/open` | `{ "path"? }` | Open a folder in Antigravity IDE |
| GET | `/api/ide/conversations` | | Antigravity IDE conversations, newest first |
| POST | `/api/ide/conversations/open` | `{ "id" }` | Follow an IDE conversation; prompts and actions go to it |
| POST | `/api/ide/conversations/new` | `{ "prompt" }` | Start a new IDE conversation and follow it |
| POST | `/api/ide/conversations/close` | | Return to the bridge agent session |
| WS | `/ws?token=` | | Event stream: `log`, `status`, `clear`, `permission`, `permission_resolved` |

</details>

## Security

The token gives full control of the agent and a shell on your PC, so treat it like a password.

- The bridge only answers requests that carry the 48-character token (compared in constant time), and locks out an address for 10 minutes after 10 wrong tokens.
- The firewall rule allows the port from the local subnet and Tailscale (`100.64.0.0/10`) only. Do not forward the port on your router; use Tailscale, or a tunnel that gives you `https`.
- Traffic is plain HTTP on your LAN, encrypted end to end over Tailscale, and HTTPS through a tunnel URL.
- The bridge reaches Antigravity's hub on localhost with the CSRF token the hub writes for local clients; nothing from the hub is exposed beyond what the phone's token already allows.
- Run `start-bridge.bat -ResetToken` to revoke every paired phone.

## Building from source

```powershell
.\build-apk.ps1            # builds dist\AntigravityRemote-latest.apk
.\build-apk.ps1 -Install   # also installs it on a connected device with adb
```

The script finds JDK 17 and the Android SDK on its own. Release signing reads `android/keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) or the `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` environment variables. Without either, it signs with the debug key, so the APK still installs.

The bridge on its own:

```powershell
cd bridge
npm ci
npm run build
node dist/index.js --port 8765
```

### Continuous integration

[`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml) type-checks the bridge, builds and signs the APK, and publishes it:

- a pushed `v*` tag creates a versioned release marked **Latest** with `AntigravityRemote-latest.apk`;
- every push to `main` refreshes the **Development build** pre-release.

Store the release key in the repository secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` so that every build installs over the previous one.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| Phone says it cannot reach the bridge | Make sure both devices are on the same network, then run `start-bridge.bat` again and accept the UAC prompt so it can add the firewall rule. Wi-Fi with client isolation (guest networks) blocks device-to-device traffic. |
| QR code shows the wrong IP | Use the address listed under **Also on**, or set `advertiseHost` in the config file. |
| Works at home but not on mobile data | Install Tailscale on both devices (see [Use it from anywhere](#use-it-from-anywhere)), restart the bridge and scan again. |
| "IDE conversations (IDE not running)" | Open Antigravity IDE on the PC. The menu enables itself once the IDE's hub is running. |
| "Antigravity CLI (agy) not found" | Install Antigravity, run `agy` once in a terminal to sign in, then restart the bridge. |
| Agent never starts | Run `agy` in a terminal on the PC and check that it works there. Its sign-in is shared with the bridge. |
| App installed from the old release will not update | Uninstall it once; releases are now signed with a permanent key. |

## License

[MIT](LICENSE)
