# Switchboard

A native Android chat app that lets you pick where your replies come from. You can switch between providers and models at any point in a conversation, and the whole history goes to whichever model is active.

## Supported providers

| Format | Examples | Base URL |
|---|---|---|
| Anthropic Messages API | Anthropic | `https://api.anthropic.com/v1` |
| OpenAI-compatible `/chat/completions` | OpenRouter, OpenAI, Groq, Together, DeepSeek, LM Studio, Ollama | `https://openrouter.ai/api/v1`, `http://10.0.2.2:11434/v1` (Ollama running on the emulator's host) |

## Features
- Replies stream in as they're generated, and you can stop them at any time
- Model picker that reads each provider's `/models` list live, with search (OpenRouter returns hundreds of models)
- Regenerate the last reply on a different provider or model, so you can compare them
- Chat history is saved on the device, with a drawer to reopen or delete past chats
- API keys are encrypted with an Android Keystore AES-GCM key and are only sent to the base URL you set for that provider
- One system prompt for all providers, plus a max-output-tokens setting
- Light and dark themes that follow the system setting
- Markdown rendering for code blocks, headings, lists, **bold**, *italic* and `inline code`

## Build
Requires JDK 17+ and the Android SDK (platform 35).

```
gradle assembleRelease     # app/build/outputs/apk/release/app-release.apk
gradle testReleaseUnitTest # streaming and protocol tests against a local mock server
```

The release build is signed with the debug key so it installs straight away. Sign it with your own key before you distribute it.

## Tools: let the model run commands
A phone can't run Android Studio, so Switchboard gives the model four tools and runs them through `runner.py` (Python standard library only, in `app/src/main/assets/`) on a machine you choose:

| Tool | Runs on | What it does |
|---|---|---|
| `run_command` | runner machine | shell command (bash), up to 1 hour, returns exit code + output tail |
| `write_file` / `read_file` | runner machine | create, overwrite or read text files (relative paths are inside `~/switchboard-workspace`) |
| `install_apk` | this phone | downloads a built `.apk` from the runner and opens Android's installer; you tap Install |
| `send_file` | this phone | puts any file from the tool machine into the chat (up to 200 MB) with Open, Save and Share buttons; no approval needed |

The model loops on its own (call a tool, read the result, fix, retry, up to 40 steps), so it can install packages, write a project, build it and install the APK.

**Where tools run (Settings, then Tools, then "Run on")**
- **This phone** (default, no setup): commands run inside Switchboard with Android's toybox `sh`, and files live in the app's private storage. Android gives apps no package manager or compilers, so this can't build APKs.
- **Termux** (full Linux on the phone, no computer): Switchboard writes `runner.py` into Termux, installs Python if needed, and starts it through Termux's `RUN_COMMAND` API, either automatically or with "Start runner now". One-time setup: install Termux from F-Droid, run `mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings` in Termux, and grant Switchboard the Termux permission. The model is told how to build an APK in Termux without Gradle (aapt2, javac, d8, apksigner).
- **Computer**: run `python3 runner.py --host 0.0.0.0` and enter its URL and token.

**Safety**
- Every action shows Approve / Always / Deny first. "Always" lasts for that chat until the app restarts. You can turn approval off in Settings.
- The runner requires its token, listens only on 127.0.0.1 unless you pass `--host`, and runs with your user's permissions. Stop it with Ctrl+C when you're done.
- The model needs to support tool calling (on OpenRouter, pick a model that lists tool support).

## Devices: USB, Bluetooth and network
These tools always run on the phone, because that's where the hardware is:

| Tool | What it does |
|---|---|
| `list_devices` | USB devices plugged in (VID/PID, matching serial driver), Bluetooth (paired, plus a Classic and BLE scan), and the network (phone IP, mDNS/Bonjour services) |
| `device_connect` | opens `usb_serial` (built-in drivers: CDC-ACM, CP210x, CH34x, FTDI), `usb_raw` (bulk/interrupt endpoints of any interface), `bt_serial` (SPP), `ble` (GATT; lists services and characteristics), `tcp`, `udp` |
| `device_write` / `device_read` | send and receive bytes as text, hex or base64; BLE reads, writes and notifications by characteristic UUID |
| `device_close` | closes a handle |
| `http_request` | any HTTP(S) request; plain http is allowed to LAN addresses |
| `download_file` | (This phone mode) saves a URL into the workspace, for example firmware or data to send_file |

Android asks for USB access per device and for Bluetooth permission the first time. Listing, reading and closing don't need approval; connecting, writing, HTTP and downloads follow "Ask before each action".
Android apps can't load kernel drivers, so USB-serial chips are driven from user space, and other USB devices go through `usb_raw`. To install compilers, SDKs and other tools, use Termux or Computer mode (`pkg install`, `pip`, and so on). The phone sandbox can't run downloaded programs.

## Request limits and time zone
- **Per minute / per day** (Settings, then Request limits; blank means no limit) are shared by all chats and saved on the device. Every model request counts, including each step of a tool run.
- When a limit is hit, the request waits and is sent automatically when the window opens. A countdown shows above the message box, and Stop cancels it. The app has to stay open during a long daily wait.
- Remaining requests show under the message box, lower right (for example `4/5 remaining min   96/100 remaining 24hr`). They turn red when a limit is used up, and are hidden when no limit is set.
- The daily limit resets **at midnight** in your time zone, or on a **rolling 24 hours**.
- **Time zone**: device default or any IANA zone. It's used for the midnight reset and to tell the model your local date and time.

## Unrestricted mode
This switch in Settings asks you to confirm before it turns on. It lifts the app's own limits:
- plain `http://` works for any host (normally only localhost, `10.0.2.2`, `*.local` and `*.lan`)
- max output tokens go up to 1,000,000 and are also sent to OpenAI-compatible providers
- a raw JSON field (for example `{"temperature": 0.7, "provider": {"order": ["..."]}}`) is merged into every request body; `model`, `stream` and `messages` can't be overridden

It does not change what a model will or won't answer. The provider and the model you pick decide that.

## Notes
- Text only for now: no image or file attachments, and no tool use.
