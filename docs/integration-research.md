# Subscription integration research, 27 September 2026

The practical subscription path is **ChatGPT or Codex calling MathNote tools**, not MathNote calling a ChatGPT model. An OpenAI [billing article](https://help.openai.com/en/articles/9039756-managing-billing-for-chatgpt-and-the-api-platform) says ChatGPT and the API are billed separately. It does not offer a supported way for an arbitrary Android app to exchange a ChatGPT login for model calls.

## What can work

| Route | What the tutor can do | Trigger and current limit |
| --- | --- | --- |
| Share page to ChatGPT Android | Read a PNG in ChatGPT, then discuss it there if the receiving app keeps the image in voice context | User taps Share and continues in ChatGPT. Feedback and marks do not flow back into MathNote automatically. |
| ChatGPT web developer-mode MCP | Call `get_page` to inspect current ink and `mark_page` to put highlights, arrows, underlines, and notes over it | A ChatGPT conversation must call the tools. The current [developer-mode guide](https://developers.openai.com/api/docs/guides/developer-mode) lists Plus and Pro and read/write MCP tools on web. It requires a remote MCP server. |
| ChatGPT desktop Work/Codex Voice or Codex CLI MCP | Read the synced image, speak the explanation in desktop Voice, and write overlay marks and text feedback using MCP tools | Register the local stdio MCP server with [Codex MCP](https://learn.chatgpt.com/docs/extend/mcp), then ask a supported desktop Voice task or Codex session to check the page. The desktop Voice path still needs a live acceptance test. |
| Local vision model | Native manual checks, pause-triggered automatic feedback, local voice text response | The Linux bridge supports Ollama or a deterministic mock. It runs separately from a ChatGPT subscription and cannot match large-model math reliability without evaluation. |

OpenAI's [MCP server guide](https://developers.openai.com/plugins/build/mcp-server) supports image-bearing tools and structured write actions. MathNote implements the core exchange: the tablet uploads a PNG only while Live sync is on, `get_page` returns that PNG and revision, and `mark_page` writes bounded geometric marks. The tablet polls and draws the marks over its editable ink. Marks attach to a page revision so stale feedback does not appear on changed work.

The current [ChatGPT Voice guide](https://learn.chatgpt.com/docs/features/voice) documents Voice in Chat, Work, and Codex desktop tasks. The [Codex MCP guide](https://learn.chatgpt.com/docs/extend/mcp) says local desktop Codex, CLI, and IDE clients share host MCP configuration. This gives a subscription-backed desktop Voice route where the control appears, but Linux preview Voice availability and an actual MathNote Voice call remain unverified. [OpenAI's Work guide](https://help.openai.com/en/articles/20001275-chatgpt-work-and-codex) also describes Work Voice on Android using connected apps. [Plugins](https://learn.chatgpt.com/docs/plugins) can appear in mobile Work. [Developer mode](https://developers.openai.com/api/docs/guides/developer-mode) documents personal Plus/Pro custom MCP setup on the web; whether a specific personal developer-mode MathNote plugin can be used in Android Work Voice needs a live account test. Ordinary Chat Voice, Work Voice, and desktop Codex Voice should be tested separately. See the [connection guide](voice-and-mcp.md).

For a public app other people can connect, the server needs stable HTTPS, per-user OAuth 2.1, page ownership checks, and plugin review. The [OpenAI authentication guide](https://developers.openai.com/plugins/build/auth) requires OAuth authorization-code + PKCE and bearer-token verification for private user data and write actions. The [plugin deployment guide](https://developers.openai.com/plugins/build/mcp-server) requires a stable public HTTPS endpoint for public submission. The [private gateway](../gateway/README.md) now verifies an OAuth token and isolates one configured owner, but it is not a multi-user deployment and has not been connected to ChatGPT. The local MCP server remains loopback-only.

OpenAI's [Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels) can keep a personal server private, but its `tunnel-client` requires a Platform runtime API key. It is useful for private development if a Platform account is acceptable, but it does not meet the strict "subscription only, no separate API access" setup. A third-party HTTPS tunnel plus OAuth avoids OpenAI model API billing, but its endpoint and authorization still need to be built and tested.

Custom GPT Actions are a weaker fit. The current [GPT documentation](https://help.openai.com/en/articles/8554407-gpts-in-chatgpt) says personal accounts cannot create new GPTs, and GPTs live inside ChatGPT rather than embedding ChatGPT in an Android app. Plugins with MCP are the better documented path for reading a live page and sending marks back.

## Teacher markup contract

1. The Android page renders to a 1600 × 1000 PNG. The app sends it to the Linux bridge after a writing pause only when Live sync is on.
2. `get_page(page_id)` gives ChatGPT or Codex the image, title, dimensions, and a revision hash.
3. The tutor identifies the first questionable step or says the writing is unclear. It calls `mark_page(page_id, revision, marks)` with up to 20 `highlight`, `underline`, `arrow`, or `note` marks.
4. Each mark has two normalized points and optional short text. The tablet renders them over the note and clears them on the next ink edit. The server rejects marks for an old revision.

This geometry is more controllable than asking a model to generate a new raster image of the student's page. It keeps student ink editable and makes wrong tutor marks reversible. It still depends on the model locating handwriting accurately, which needs real-device testing and careful tutor prompts.

## Remaining verification

- Install the APK on the Galaxy Tab S11 Ultra and check S Pen pressure, palm rejection, latency, undo, eraser, and page reopening.
- Connect ChatGPT web developer mode to an authenticated remote MCP endpoint and verify `get_page` returns an image the model can actually inspect, then verify `mark_page` reaches the tablet.
- Check whether that same connection is available in ChatGPT Android voice for the user's plan. If it is, test a split-screen tablet flow with ChatGPT voice beside MathNote.
- Evaluate real handwritten Calculus II pages for uncertain writing and mathematical error localization. The mock response tests transport and UI flow only.
