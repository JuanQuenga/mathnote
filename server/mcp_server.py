"""MathNote tools for ChatGPT developer mode or Codex.

Runs on loopback by default. Put an authenticated HTTPS proxy with OAuth in front
before offering it to anyone else. The model never receives the tablet token.
"""
import base64
import json
import os
from mcp.server.fastmcp import FastMCP
from mcp.types import ImageContent, TextContent, ToolAnnotations
from sync_store import (clear_feedback, get_annotations, get_page as read_synced_page,
                        list_pages, save_annotations, save_feedback)

MCP_HOST = os.getenv("MATHNOTE_MCP_HOST", "127.0.0.1")
if MCP_HOST not in ("127.0.0.1", "localhost", "::1") and os.getenv("MATHNOTE_MCP_UNSAFE_REMOTE") != "1":
    raise SystemExit("MCP has no user auth. Keep it on loopback or explicitly set MATHNOTE_MCP_UNSAFE_REMOTE=1 for isolated testing.")

app = FastMCP(
    "MathNote",
    instructions=("Help the student with their current Calculus II page. Read the synced "
        "image with get_page before giving feedback or marking it. State uncertainty if "
        "handwriting is unclear. Identify the first questionable step, explain the reason, "
        "and give a hint before the full answer. Use mark_page to draw helpful marks "
        "over the student's ink. Coordinates are normalized 0..1. Use write_feedback "
        "to leave the same concise explanation on the tablet. In a voice session, "
        "also speak the explanation in your reply when tools are available. Re-read "
        "the page if its revision changed."),
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
    """Read a synced page and its PNG. Use the revision when writing tutor feedback."""
    metadata, image = read_synced_page(page_id)
    annotations = get_annotations(page_id)
    metadata["tutor_feedback"] = (annotations if annotations["revision"] == metadata["revision"]
                                  else {"page_id": page_id, "revision": metadata["revision"],
                                        "marks": [], "feedback": ""})
    return [TextContent(type="text", text=json.dumps(metadata)),
            ImageContent(type="image", data=base64.b64encode(image).decode(), mimeType="image/png")]


@app.tool(annotations=ToolAnnotations(readOnlyHint=False, destructiveHint=False, openWorldHint=False))
def mark_page(page_id: str, revision: str, marks: list[dict]) -> dict:
    """Replace tutor marks over a page. Each mark is {kind: highlight|underline|arrow|note, points:[[x1,y1],[x2,y2]], text:string}. Coordinates are 0..1 on the full 1600x1000 image. Keep marks near the step being discussed; use note text for a short explanation. This is reversible and does not change student ink."""
    return save_annotations(page_id, revision, marks)


@app.tool(annotations=ToolAnnotations(readOnlyHint=False, destructiveHint=False, openWorldHint=False))
def write_feedback(page_id: str, revision: str, feedback: str) -> dict:
    """Show a concise explanation or hint in the tablet's tutor panel. Writing here does not trigger audio; in a voice session, also speak it in your reply if tools are available. At most 1200 characters. Give a hint before a full answer; say when handwriting is unclear. Requires the current page revision and never edits student ink."""
    return save_feedback(page_id, revision, feedback)


@app.tool(annotations=ToolAnnotations(readOnlyHint=False, destructiveHint=True, openWorldHint=False))
def clear_tutor_feedback(page_id: str, revision: str) -> dict:
    """Clear tutor marks and written feedback for this page revision. Student ink stays intact."""
    return clear_feedback(page_id, revision)


if __name__ == "__main__":
    app.run(transport=os.getenv("MATHNOTE_MCP_TRANSPORT", "stdio"))
