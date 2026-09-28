# Private ChatGPT/Codex MCP gateway

This process serves the same synced page snapshots and teacher marks as the
tablet's Linux server, but adds OAuth bearer verification. It is a **resource
server**, not a login provider. An existing OAuth 2.1 identity provider must
handle account login, PKCE S256, client identification (CIMD or DCR), and issue
signed JWT access tokens. A public HTTPS hostname or HTTPS tunnel must forward
`/mcp` and `/.well-known/oauth-protected-resource/mcp` to this process. Nothing
here calls the OpenAI API or needs an OpenAI API key.

The gateway deliberately serves **one owner per deployment**. Every accepted
token must have an RS256 signature from the configured provider, the exact
issuer and MCP URL audience, a valid expiry, both `notes.read` and
`notes.write` scopes, and the configured immutable owner `sub`. The separate
tablet sync service is **not** proxied to the internet. The app's device token
remains local to your tablet and Linux server.

## Configure and test locally

1. Obtain a public HTTPS hostname, such as `notes.example.com`, and set up a
   compatible OAuth provider. Give it `https://notes.example.com/mcp` as the
   resource/audience and allow `notes.read` and `notes.write`. Configure it to
   copy the `resource` parameter into JWT `aud` for both authorization and token
   requests. Enable S256 PKCE, CIMD or DCR, and allow ChatGPT's **exact**
   redirect URI shown on the connection management page. A generic web-login
   proxy or Cloudflare Access token alone does not satisfy this contract.
2. Set these server-only environment variables (never put them in the APK):

   ```sh
   export MATHNOTE_PUBLIC_MCP_URL=https://notes.example.com/mcp
   export MATHNOTE_OAUTH_ISSUER=https://your-provider.example.com/
   export MATHNOTE_OAUTH_JWKS_URL=https://your-provider.example.com/.well-known/jwks.json
   export MATHNOTE_OAUTH_METADATA_URL=https://your-provider.example.com/.well-known/openid-configuration
   export MATHNOTE_OWNER_SUB='immutable-subject-from-your-provider'
   export MATHNOTE_DATA=/absolute/path/to/mathnote/server/data
   ```

3. From the repository root, install and start the gateway. It **only** listens
   on `127.0.0.1:8770`; the SDK rejects an unexpected Host header.

   ```sh
   python3 -m venv gateway/.venv
   gateway/.venv/bin/pip install -r gateway/requirements.txt
   gateway/.venv/bin/python gateway/mcp_gateway.py
   ```

4. Configure a TLS reverse proxy. `Caddyfile.example` is a minimal Caddy
   configuration: set `MATHNOTE_DOMAIN=notes.example.com`, copy it to your
   active Caddy config, and run Caddy. Forward **only** to the gateway on port
   8770; do not forward the Android sync service on port 8765. Open ports 80/443
   and point DNS at this host, or use a trusted HTTPS tunnel that preserves the
   public hostname. Keep port 8770 private.
5. Check provider and public resource metadata:

   ```sh
   gateway/.venv/bin/python gateway/check_provider.py
   ```

   This check cannot prove token issuance or ChatGPT connection. Use your
   provider's test account to verify that a real access token has the exact
   `aud`, `iss`, `sub`, scopes, and expiry. Then use MCP Inspector with OAuth to
   call `list_synced_pages`, `get_page`, and `mark_page` before connecting
   ChatGPT. On this repo alone, run `gateway/.venv/bin/python -m unittest -v
   discover -s gateway -p 'test_*.py'`; its temporary RSA key and mocked JWTs
   test the HTTP boundary without user credentials or an external provider.

6. On **ChatGPT web**, enable Settings → Security and login → Developer mode.
   In ChatGPT Plugins add your `https://notes.example.com/mcp` endpoint, then
   connect the OAuth account. In a new **Work** conversation, select the plugin
   and ask it to review your synced page. Refresh the plugin connection after
   changing tool metadata. A linked ChatGPT subscription makes ChatGPT the MCP
   client; it does not turn the Android app into a direct ChatGPT API client.

The tablet must have **Live sync** enabled for the page, with its server address
set to the local Linux sync server. ChatGPT reads the synchronized PNG and can
write highlights, arrows, notes, and concise feedback to that revision. The
tablet polls and shows those marks. The gateway never edits the student's ink.

ChatGPT/Codex Voice availability with personal developer plugins depends on
the host surface and account rollout. We have tested gateway protocol behavior
locally, **not** a live ChatGPT OAuth login or a mobile Voice session. Do not
assume mobile Voice can invoke this personal plugin until tested on your account.

Official references: [OpenAI plugin authentication](https://developers.openai.com/plugins/build/auth),
[connect and test a plugin](https://developers.openai.com/plugins/deploy/connect-chatgpt),
[plugin tool metadata](https://developers.openai.com/plugins/reference).
