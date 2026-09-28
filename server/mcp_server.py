"""MathNote tools for ChatGPT developer mode or Codex.

Runs on loopback by default. Put an authenticated HTTPS proxy with OAuth in front
before offering it to anyone else. The model never receives the tablet token.
"""
import base64
import json
import os
from mcp.server.fastmcp import FastMCP
from mcp.types import ImageContent, TextContent, ToolAnnotations
from sync_store import get_page as read_synced_page, list_pages, save_annotations

MCP_HOST = os.getenv("MATHNOTE_MCP_HOST", "127.0.0.1")
if MCP_HOST not in ("127.0.0.1", "localhost", "::1") and os.getenv("MATHNOTE_MCP_UNSAFE_REMOTE") != "1":
    raise SystemExit("MCP has no user auth. Keep it on loopback or explicitly set MATHNOTE_MCP_UNSAFE_REMOTE=1 for isolated testing.")

app = FastMCP(
    "MathNote",
    instructions=("Help the student with their current Calculus II page. Read the synced "
        "image with get_page before giving feedback or marking it. State uncertainty if "
        "handwriting is unclear. Identify the first questionable step, explain the reason, "
        "and give a hint before the full answer. Use mark_page to draw helpful marks "
        "over the student's ink. Coordinates are normalized 0..1. Re-read the page "
        "if its revision changed."),
    host=MCP_HOST,
    port=int(os.getenv("MATHNOTE_MCP_PORT", "8766")),
    stateless_http=True,
    json_response=True,
)


@app.tool(annotations=ToolAnnotations(readOnlyHint=True, destructiveHint=False, openWorldHint=False))
def list_synced_pages() -> list[dict]:
    """List pages that the student explicitly enabled for live sync."""
    return list_pages()


@app.tool(annotations=ToolAnnotations(readOnlyHint=True, destructiveHint=False, openWorldHint=False))
def get_page(page_id: str) -> list[TextContent | ImageContent]:
    """Read a synced page and its PNG. Use the revision when calling mark_page."""
    metadata, image = read_synced_page(page_id)
    return [TextContent(type="text", text=json.dumps(metadata)),
            ImageContent(type="image", data=base64.b64encode(image).decode(), mimeType="image/png")]
@app.tool(annotations=ToolAnnotations(readOnlyHint=False, destructiveHint=False, openWorldHint=False))
def mark_page(page_id: str, revision: str, marks: list[dict]) -> dict:
    """Replace tutor marks over a page. Each mark is {kind: highlight|underline|arrow|note, points:[[x1,y1],[x2,y2]], text:string}. Coordinates are 0..1 on the full 1600x1000 image. Keep marks near the step being discussed; use note text for a short explanation. This is reversible and does not change student ink."""
    return save_annotations(page_id, revision, marks)


if __name__ == "__main__":
    app.run(transport=os.getenv("MATHNOTE_MCP_TRANSPORT", "stdio"))
