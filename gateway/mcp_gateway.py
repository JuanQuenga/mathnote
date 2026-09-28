"""Authenticated, single-owner remote MCP endpoint for a self-hosted MathNote.

This process is only an OAuth *resource server*. An existing OAuth provider must
handle login, PKCE, client registration, and token issuance. TLS terminates at a
reverse proxy; this process binds to loopback and never accepts tablet tokens.
"""
from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import os
import sys
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

import jwt
from jwt import PyJWKClient
from mcp.server.auth.provider import AccessToken, TokenVerifier
from mcp.server.auth.settings import AuthSettings
from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings
from mcp.types import ImageContent, TextContent, ToolAnnotations

SERVER_DIR = Path(__file__).resolve().parents[1] / "server"
sys.path.insert(0, str(SERVER_DIR))
from sync_store import (  # noqa: E402
    clear_feedback, get_annotations, get_page as read_synced_page,
    list_pages, save_annotations, save_feedback,
)


@dataclass(frozen=True)
class Config:
    public_url: str
    issuer: str
    jwks_url: str
    owner_sub: str
    port: int = 8770

    @classmethod
    def from_env(cls) -> "Config":
        cfg = cls(
            public_url=os.environ.get("MATHNOTE_PUBLIC_MCP_URL", ""),
            issuer=os.environ.get("MATHNOTE_OAUTH_ISSUER", ""),
            jwks_url=os.environ.get("MATHNOTE_OAUTH_JWKS_URL", ""),
            owner_sub=os.environ.get("MATHNOTE_OWNER_SUB", ""),
            port=int(os.environ.get("MATHNOTE_GATEWAY_PORT", "8770")),
        )
        cfg.validate()
        return cfg

    def validate(self) -> None:
        for label, value in (("MATHNOTE_PUBLIC_MCP_URL", self.public_url),
                             ("MATHNOTE_OAUTH_ISSUER", self.issuer),
                             ("MATHNOTE_OAUTH_JWKS_URL", self.jwks_url)):
            url = urlparse(value)
            if url.scheme != "https" or not url.netloc or url.username or url.password or url.fragment:
                raise ValueError(f"{label} must be an absolute HTTPS URL without credentials or fragment")
        public = urlparse(self.public_url)
        if public.path != "/mcp" or public.query:
            raise ValueError("MATHNOTE_PUBLIC_MCP_URL must end in /mcp with no query")
        if not self.owner_sub.strip():
            raise ValueError("MATHNOTE_OWNER_SUB must be your identity provider's immutable subject")
        if not 1 <= self.port <= 65535:
            raise ValueError("MATHNOTE_GATEWAY_PORT must be between 1 and 65535")


class OwnerJwtVerifier(TokenVerifier):
    """Validate signed, audience-bound JWTs for exactly one local note owner."""

    def __init__(self, config: Config, jwks_client: PyJWKClient | None = None):
        self.config = config
        self.jwks = jwks_client or PyJWKClient(config.jwks_url, cache_jwk_set=True, lifespan=300)

    async def verify_token(self, token: str) -> AccessToken | None:
        if len(token) > 8192:
            return None
        try:
            key = await asyncio.to_thread(self.jwks.get_signing_key_from_jwt, token)
            claims = jwt.decode(
                token, key.key, algorithms=["RS256"], audience=self.config.public_url,
                issuer=self.config.issuer,
                options={"require": ["iss", "aud", "exp", "iat", "sub"]},
            )
        except (jwt.PyJWTError, ValueError, OSError):
            return None
        if claims.get("sub") != self.config.owner_sub:
            return None
        raw_scopes = claims.get("scope", claims.get("scp", ""))
        scopes = raw_scopes.split() if isinstance(raw_scopes, str) else raw_scopes
        if not isinstance(scopes, list) or not all(isinstance(s, str) for s in scopes):
            return None
        return AccessToken(
            token=token, client_id=str(claims.get("azp", "oauth-client")),
            scopes=scopes, expires_at=claims["exp"], resource=self.config.public_url,
            subject=claims["sub"], claims={"iss": claims["iss"]},
        )


class AuthenticatedFastMCP(FastMCP):
    async def list_tools(self):
        tools = await super().list_tools()
        # MCP Python SDK 1.30 exposes arbitrary metadata only under _meta. The
        # OpenAI plugin descriptor also calls for a top-level securitySchemes.
        for tool in tools:
            tool.__pydantic_extra__["securitySchemes"] = tool.meta["securitySchemes"]
        return tools


def create_app(config: Config | None = None, verifier: TokenVerifier | None = None) -> FastMCP:
    config = config or Config.from_env()
    config.validate()
    verifier = verifier or OwnerJwtVerifier(config)
    required_scopes = ["notes.read", "notes.write"]
    public_host = urlparse(config.public_url).netloc
    app = AuthenticatedFastMCP(
        "MathNote private tutor",
        instructions=(
            "Read the current synced page image before giving feedback. Identify the first "
            "questionable calculus step, explain why, and give a hint before the answer. "
            "Say when handwriting is unclear. Mark or highlight the relevant step only after "
            "reading its revision. In voice chat, say your explanation aloud in the reply as "
            "well as writing it to the tablet's tutor panel."
        ),
        host="127.0.0.1", port=config.port, stateless_http=True, json_response=True,
        token_verifier=verifier,
        transport_security=TransportSecuritySettings(
            enable_dns_rebinding_protection=True,
            allowed_hosts=[public_host, f"127.0.0.1:{config.port}", f"localhost:{config.port}"],
            allowed_origins=[f"https://{public_host}", "https://chatgpt.com"],
        ),
        auth=AuthSettings(
            issuer_url=config.issuer,
            resource_server_url=config.public_url,
            validate_token_resource=True,
            required_scopes=required_scopes,
        ),
    )

    # Every tool is private. The SDK enforces both scopes on every MCP request.
    # The metadata is also supplied per tool for ChatGPT's linking UI.
    security = {"securitySchemes": [{"type": "oauth2", "scopes": required_scopes}]}
    read = ToolAnnotations(readOnlyHint=True, destructiveHint=False, openWorldHint=False)
    write = ToolAnnotations(readOnlyHint=False, destructiveHint=False, openWorldHint=False)

    @app.tool(annotations=read, meta=security)
    def list_synced_pages() -> list[dict]:
        """List the owner's pages explicitly enabled for live sync."""
        return list_pages()

    @app.tool(annotations=read, meta=security)
    def get_page(page_id: str) -> list[TextContent | ImageContent]:
        """Read a synced page's PNG, revision, and existing tutor marks."""
        metadata, image = read_synced_page(page_id)
        metadata["tutor_feedback"] = get_annotations(page_id)
        return [TextContent(type="text", text=json.dumps(metadata)),
                ImageContent(type="image", data=base64.b64encode(image).decode(), mimeType="image/png")]

    @app.tool(annotations=write, meta=security)
    def mark_page(page_id: str, revision: str, marks: list[dict]) -> dict:
        """Replace teacher marks for the current page revision. Marks: kind highlight|underline|arrow|note, two normalized 0..1 points, optional short text. The student's ink is never changed."""
        return save_annotations(page_id, revision, marks)

    @app.tool(annotations=write, meta=security)
    def write_feedback(page_id: str, revision: str, feedback: str) -> dict:
        """Put a brief step-specific explanation or hint in the tablet tutor panel. Also speak it in your voice reply; this tool does not create audio."""
        return save_feedback(page_id, revision, feedback)

    @app.tool(annotations=ToolAnnotations(readOnlyHint=False, destructiveHint=True, openWorldHint=False), meta=security)
    def clear_tutor_feedback(page_id: str, revision: str) -> dict:
        """Clear teacher marks and feedback for this revision; student ink remains intact."""
        return clear_feedback(page_id, revision)

    @app.tool(annotations=read, meta={**security, "openai/profile": True})
    def get_profile() -> dict:
        """Return the stable identity of this private MathNote connection."""
        digest = hashlib.sha256((config.issuer + "\n" + config.owner_sub).encode()).hexdigest()[:24]
        return {"id": "mathnote_" + digest, "name": "MathNote owner"}

    return app


if __name__ == "__main__":
    create_app().run(transport="streamable-http")
