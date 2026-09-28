# MathNote development build

MathNote is a native Kotlin Android notebook for handwriting Calculus II solutions. The app keeps notebooks, pages, pressure samples, undo and redo, and eraser edits in private tablet storage. It works for writing without a server. S Pen input is accepted by default; Settings can enable finger writing if needed. Hold the S Pen side button to erase temporarily; release it to return to your selected pen or highlighter. Each tool has its own color and width settings. Tutor marks draw over the ink and disappear when the page changes.

## Install on the Galaxy Tab

Open the repository's **Releases → MathNote development APK** page on the tablet, download `MathNote-debug.apk`, and allow installation from the browser or file manager when Android asks. This is a debug build. Development APKs published from the stable-signing-key workflow can update in place and keep private notes. Builds downloaded before that signing change used a different key and may need a one-time uninstall; uninstalling deletes private notes, so keep any important work before replacing an older build.

The GitHub workflow builds and publishes a new debug APK on each push to `main`. It also runs an Android emulator test for stylus input, the S Pen button eraser, undo/redo, and saved-page reopening. There is no connected Galaxy Tab here, so S Pen hardware behavior still needs a tablet test. The server and MCP flow have automated tests.

## Use a ChatGPT subscription

`Check in ChatGPT` opens Android's share sheet with a PNG page snapshot and a tutor prompt. Choose the ChatGPT app, sign in with your own account, and ask it to review the page. `ChatGPT voice` shares the same page so you can continue speaking in ChatGPT. The share sheet may require pasting the prompt if the receiving app does not preserve accompanying text. Sharing a snapshot does not connect ChatGPT to live tablet ink or allow it to place marks back on the page.

For interactive teacher marks, MathNote has an opt-in `Live sync` button. It sends the current page to **your own Linux server** after a 2.5-second writing pause and checks for new marks every five seconds while the app is open. The MCP server exposes three tools to an AI host: list synced pages, read a PNG page, and place highlights, underlines, arrows, or short notes at normalized coordinates. A Codex session can connect to the local stdio server now. ChatGPT's current [developer-mode guide](https://developers.openai.com/api/docs/guides/developer-mode) says Plus and Pro users can connect remote MCP tools, including write tools, on **web**. ChatGPT must initiate the read and mark actions during a conversation; MathNote cannot start a ChatGPT turn by itself. The [mobile voice guide](https://help.openai.com/en/articles/20001274-chatgpt-voice) says voice can use available plugins, but this specific custom MCP flow has not been tested on the ChatGPT Android app. Other [Help Center guidance](https://help.openai.com/en/articles/12584461-developer-mode-and-mcp-apps-in-chatgpt) gives narrower plan and mobile availability, so do not rely on mobile MCP until confirmed in your account.

The local MCP endpoint binds to loopback and **does not implement user OAuth**. Run `scripts/connect-codex.sh` on this Linux host to register it with Codex, then use desktop Codex Voice where that control is available to ask it to inspect and mark the live-synced page. OpenAI's [Work/Codex Voice guide](https://help.openai.com/en/articles/20001275-chatgpt-work-and-codex) says desktop Voice uses the tools available to that experience. Ordinary ChatGPT Voice does not provide this live MCP connection. See [voice and MCP setup](docs/voice-and-mcp.md) for desktop and tablet paths.

Connecting a personal plugin to ChatGPT Work on web or Android requires a remote HTTPS MCP endpoint and user authorization before exposing private pages. The [private gateway](gateway/README.md) implements the OAuth-protected resource server and a TLS proxy example; you must supply a public HTTPS address and compatible OAuth identity provider. No public endpoint is deployed here. OpenAI's [MCP authentication guide](https://developers.openai.com/plugins/build/auth) specifies OAuth for private user data and write actions. OpenAI's [Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels) uses a Platform runtime key, so it is not the no-Platform-account route requested here. OpenAI's [Work Voice guide](https://help.openai.com/en/articles/20001275-chatgpt-work-and-codex) says Work Voice on Android can use connected apps, but this personal plugin path has not been tested with a ChatGPT account.

ChatGPT subscription billing and API billing are [separate](https://help.openai.com/en/articles/9039756-managing-billing-for-chatgpt-and-the-api-platform). This project does not use the OpenAI API, does not ask for an API key, and cannot authenticate directly into ChatGPT from the Android app. The subscription-powered path runs inside ChatGPT or Codex through user-selected sharing and tools. Automatic checks inside MathNote use only a local model.

## Start the Linux server

On the Linux computer that runs Codex, from this repository, run:

```bash
./scripts/mathnote-desktop start
```

This registers the local MCP server with Codex, starts the tablet bridge, and opens a private pairing QR if the computer has a desktop session. Scan the QR with the tablet Camera, then open its link in MathNote. You can also tap MathNote's **Connect** button and paste the pairing link. Keep the tablet and computer on the same trusted Wi-Fi network, then turn on **Live sync** on the page you want Codex to inspect. The QR includes a private device token; do not share it. Restart the Codex desktop app after the first registration, then ask it to use MathNote to read and mark your synced page. Use `./scripts/mathnote-desktop status` to check the bridge and `./scripts/mathnote-desktop stop` to stop a bridge this command started. `./scripts/mathnote-desktop pairing --show-token` gives a manual address and token if scanning fails. The token is saved privately at `~/.config/mathnote/device-token` and reused across restarts.

The setup reports when Linux firewalld may block the tablet and shows the command to allow the bridge port; it does not change your firewall. Tablet access uses plain HTTP on your local network, so use a trusted Wi-Fi network. The local Codex MCP process stays on the Linux computer and does not require an OpenAI API key. It does not automatically connect ChatGPT mobile Work/Voice; see the [connection guide](docs/voice-and-mcp.md).

For advanced manual setup or local mock testing, the HTTP bridge uses Python's standard library:

```bash
cd server
export MATHNOTE_TOKEN="$(python3 -c 'import secrets; print(secrets.token_hex(24))')"
export MATHNOTE_HOST=0.0.0.0
export MATHNOTE_MOCK=1
python3 server.py
```

Use a terminal on the Linux box to find its LAN IP address. In MathNote Settings, enter `http://LAN_IP:8765` and the same `MATHNOTE_TOKEN`. Connect tablet and Linux box to the same trusted network. Notes remain on the tablet even if the server stops. `MATHNOTE_HOST=0.0.0.0` is needed only for tablet access. The development connection is plain HTTP, so use it on a trusted LAN; a public deployment needs HTTPS and per-user authentication.

To try a local image model, install [Ollama](https://ollama.com/) on the Linux box and run `ollama pull qwen3-vl:4b-instruct`. Then run `server.py` without `MATHNOTE_MOCK=1`. The model can be changed with `MATHNOTE_MODEL`. On this 7.4 GiB RAM Linux box, the default model identified an incorrect antiderivative in a typed page and accepted a corrected page, taking about 44–56 seconds per check on CPU. Handwritten Calculus II accuracy on your own pages has **not** been verified. The local model's feedback is an aid, not a correctness guarantee.

In Settings, automatic feedback is off by default. If enabled, the app waits 2.5 seconds after writing and checks no more often than the chosen 30 seconds, one minute, two minutes, or five minutes. The server also caps automatic checks at 40 per day. Manual checks remain available. The local voice button uses Android speech recognition to turn a spoken question into text, sends the text and page image to your server, and speaks the returned text with Android Text-to-Speech. On-device recognition is preferred when Android offers it; otherwise the Android speech service may need connectivity. The app does not send a raw microphone recording to the MathNote server.

## Run and test the MCP bridge

```bash
cd server
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python -m unittest -v test_flow
```

After enabling `Live sync` on the tablet, run the MCP server locally in stdio mode:

```bash
cd server
.venv/bin/python mcp_server.py
```

Point Codex's MCP configuration at this command, or use the CLI form below with absolute paths:

```bash
codex mcp add mathnote -- /absolute/path/to/server/.venv/bin/python /absolute/path/to/server/mcp_server.py
codex mcp list
```

The MCP server and HTTP bridge read and write the same `server/data` directory. For ChatGPT developer-mode testing, `MATHNOTE_MCP_TRANSPORT=streamable-http` serves `/mcp` on `127.0.0.1:8766`; a properly authenticated HTTPS proxy is still needed before registering it as a remote ChatGPT plugin. The local MCP protocol, image return, and annotation write are covered by `test_flow.py`. A real ChatGPT connection has not been tested.

## Current test scope

The server test exercises mock manual and automatic feedback, the auto rate limit, spoken-question text flow, authenticated sync, stale-revision rejection, MCP image reading, and MCP annotation writing. A live Codex CLI session on this Linux host used the ChatGPT subscription connection to open a synthetic calculus page, identify the incorrect second step, and write an underline and hint through MathNote MCP; the stored marks were independently checked. The CI emulator exercises stylus event handling, the button eraser, and note persistence. These tests cannot verify S Pen hardware, Android speech services, the ChatGPT share sheet, desktop Voice, or a remote ChatGPT MCP login. Use the tablet APK to test those paths and report what happens.
