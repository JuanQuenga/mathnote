#!/usr/bin/env bash
set -euo pipefail

# Register MathNote's local MCP process with the Codex host. The ChatGPT
# desktop app, Codex CLI, and IDE extension share this host configuration.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
server_dir="$repo_root/server"
python_bin="$server_dir/.venv/bin/python"
server_script="$server_dir/mcp_server.py"

if ! command -v codex >/dev/null 2>&1; then
  printf 'Codex CLI is required. See https://learn.chatgpt.com/docs/extend/mcp\n' >&2
  exit 1
fi
if [[ ! -x "$python_bin" ]]; then
  printf 'Creating the MathNote Python environment...\n'
  python3 -m venv "$server_dir/.venv"
  "$server_dir/.venv/bin/pip" install -r "$server_dir/requirements.txt"
fi

if codex mcp get mathnote >/dev/null 2>&1; then
  current="$(codex mcp get mathnote)"
  if [[ "$current" == *"$server_script"* ]]; then
    printf 'MathNote is already registered with Codex.\n'
  else
    printf 'An MCP server named mathnote already exists. Review it with: codex mcp get mathnote\n' >&2
    printf 'Remove it with codex mcp remove mathnote only if you want to replace it.\n' >&2
    exit 1
  fi
else
  codex mcp add mathnote -- "$python_bin" "$server_script"
fi

printf '\nCheck the server in Codex with /mcp, or run: codex mcp list\n'
printf 'In the ChatGPT desktop app, restart the Codex host and select Voice in a Codex or Work task.\n'
