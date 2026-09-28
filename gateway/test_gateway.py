"""Exercise the actual MCP HTTP boundary and signed-token admission rules."""
import base64
import json
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace

import jwt
from cryptography.hazmat.primitives.asymmetric import rsa
from starlette.testclient import TestClient

from mcp_gateway import Config, OwnerJwtVerifier, create_app
import sync_store

PAGE = "11111111-1111-4111-8111-111111111111"
PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6QKAAAAAASUVORK5CYII=")


class FixedKeys:
    def __init__(self, key):
        self.key = key

    def get_signing_key_from_jwt(self, token):
        return SimpleNamespace(key=self.key)


class GatewayTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        sync_store.ROOT = Path(self.tmp.name)
        sync_store.sync_page(PAGE, "Calculus II", PNG)
        self.private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        self.config = Config("https://notes.example.test/mcp", "https://auth.example.test/",
                             "https://auth.example.test/jwks.json", "owner-123")
        self.verifier = OwnerJwtVerifier(self.config, FixedKeys(self.private.public_key()))

    def tearDown(self):
        self.tmp.cleanup()

    def token(self, **changes):
        claims = {"iss": self.config.issuer, "aud": self.config.public_url,
                  "sub": self.config.owner_sub, "iat": int(time.time()),
                  "exp": int(time.time()) + 300, "scope": "notes.read notes.write"}
        claims.update(changes)
        return jwt.encode(claims, self.private, algorithm="RS256", headers={"kid": "test-key"})

    def post(self, client, method, params=None, token=None):
        headers = {"Accept": "application/json, text/event-stream", "Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        return client.post("/mcp", headers=headers, json={"jsonrpc": "2.0", "id": 1,
            "method": method, "params": params or {}})

    def test_fail_closed_config(self):
        with self.assertRaises(ValueError):
            Config("http://notes.example.test/mcp", self.config.issuer,
                   self.config.jwks_url, self.config.owner_sub).validate()
        with self.assertRaises(ValueError):
            Config(self.config.public_url, self.config.issuer,
                   self.config.jwks_url, "").validate()

    def test_jwt_rejects_other_owner_issuer_audience_expiry_and_scopes(self):
        import asyncio
        async def check():
            self.assertIsNotNone(await self.verifier.verify_token(self.token()))
            for changes in ({"sub": "someone-else"}, {"iss": "https://evil.test/"},
                            {"aud": "https://another.test/mcp"}, {"exp": int(time.time()) - 1}):
                self.assertIsNone(await self.verifier.verify_token(self.token(**changes)))
        asyncio.run(check())

    def test_http_requires_oauth_and_provides_discovery(self):
        app = create_app(self.config, self.verifier)
        with TestClient(app.streamable_http_app(), base_url="http://127.0.0.1:8770") as client:
            metadata = client.get("/.well-known/oauth-protected-resource/mcp")
            self.assertEqual(metadata.status_code, 200)
            self.assertEqual(metadata.json()["resource"], self.config.public_url)
            self.assertEqual(metadata.json()["authorization_servers"], [self.config.issuer])
            denied = self.post(client, "tools/list")
            self.assertEqual(denied.status_code, 401)
            self.assertIn("resource_metadata=", denied.headers["www-authenticate"])
            wrong_host = client.post("/mcp", headers={"Host": "evil.example.test",
                "Authorization": "Bearer " + self.token(), "Content-Type": "application/json",
                "Accept": "application/json, text/event-stream"},
                json={"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}})
            self.assertEqual(wrong_host.status_code, 421)
            for claims in ({"sub": "intruder"}, {"scope": "notes.read"}):
                denied = self.post(client, "tools/list", token=self.token(**claims))
                self.assertIn(denied.status_code, (401, 403))
            accepted = self.post(client, "tools/list", token=self.token())
            self.assertEqual(accepted.status_code, 200)
            result = accepted.json()["result"]
            self.assertIn("get_page", {tool["name"] for tool in result["tools"]})
            self.assertTrue(all(tool["securitySchemes"] == tool["_meta"]["securitySchemes"]
                                for tool in result["tools"]))
            page = self.post(client, "tools/call", {"name": "get_page", "arguments": {"page_id": PAGE}},
                             token=self.token())
            self.assertEqual(page.status_code, 200)
            self.assertTrue(any(item["type"] == "image" for item in page.json()["result"]["content"]))
            revision = sync_store.get_page(PAGE)[0]["revision"]
            marked = self.post(client, "tools/call", {"name": "mark_page", "arguments": {
                "page_id": PAGE, "revision": revision,
                "marks": [{"kind": "underline", "points": [[0.1, 0.2], [0.4, 0.2]],
                           "text": "Check this step"}]}}, token=self.token())
            self.assertEqual(marked.status_code, 200)
            self.assertFalse(marked.json()["result"].get("isError", False))
            self.assertEqual(sync_store.get_annotations(PAGE)["marks"][0]["kind"], "underline")
            feedback = self.post(client, "tools/call", {"name": "write_feedback", "arguments": {
                "page_id": PAGE, "revision": revision,
                "feedback": "The exponent should increase here. Which rule applies?"}},
                token=self.token())
            self.assertEqual(feedback.status_code, 200)
            self.assertFalse(feedback.json()["result"].get("isError", False))
            self.assertIn("exponent", sync_store.get_annotations(PAGE)["feedback"])
            stale = self.post(client, "tools/call", {"name": "write_feedback", "arguments": {
                "page_id": PAGE, "revision": "outdated", "feedback": "Wrong revision"}},
                token=self.token())
            self.assertEqual(stale.status_code, 200)
            self.assertTrue(stale.json()["result"]["isError"])
            self.assertIn("exponent", sync_store.get_annotations(PAGE)["feedback"])


if __name__ == "__main__":
    unittest.main()
