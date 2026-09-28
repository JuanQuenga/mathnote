import asyncio
import base64
import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

import server
import sync_store

PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6QKAAAAAASUVORK5CYII=")
PAGE = "11111111-1111-4111-8111-111111111111"


class FlowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        sync_store.ROOT = Path(cls.temp.name)
        os.environ["MATHNOTE_DATA"] = cls.temp.name
        os.environ["MATHNOTE_MOCK"] = "1"
        os.environ["MATHNOTE_TOKEN"] = "test-token"
        cls.http = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        cls.thread = threading.Thread(target=cls.http.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.http.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown(); cls.http.server_close(); cls.thread.join()
        cls.temp.cleanup()

    def post(self, route, data, token="test-token"):
        req = urllib.request.Request(self.base + route, data=json.dumps(data).encode(),
            headers={"Content-Type": "application/json", "X-Device-Token": token})
        try:
            with urllib.request.urlopen(req) as result:
                return result.status, json.load(result)
        except urllib.error.HTTPError as error:
            try:
                return error.code, json.load(error)
            finally:
                error.close()

    def test_handwriting_image_manual_auto_and_voice(self):
        image = base64.b64encode(PNG).decode()
        status, manual = self.post("/check", {"image": image, "automatic": False})
        self.assertEqual(status, 200)
        self.assertEqual(manual["status"], "issue")
        self.assertIn("Line 2", manual["step"])
        self.assertEqual(manual["answer"], "")
        status, revealed = self.post("/check", {"image": image, "reveal": True})
        self.assertEqual(status, 200)
        self.assertIn("x^3/3", revealed["answer"])
        status, automatic = self.post("/check", {"image": image, "automatic": True})
        self.assertEqual(status, 200)
        self.assertEqual(automatic["status"], "issue")
        status, limited = self.post("/check", {"image": image, "automatic": True})
        self.assertEqual(status, 429)
        self.assertIn("limit", limited["error"])
        status, voice = self.post("/voice", {"image": image, "transcript": "Why is line two wrong?"})
        self.assertEqual(status, 200)
        self.assertIn("power rule", voice["reply"])
        status, denied = self.post("/check", {"image": image}, token="wrong")
        self.assertEqual(status, 401)

    def test_unreadable_math_is_not_reported_as_correct(self):
        raw = json.dumps({"status": "looks_good", "step": "",
                          "explanation": "There are no readable mathematical expressions on this page.",
                          "hint": "", "answer": ""})
        self.assertEqual(server.sanitize_feedback(raw)["status"], "unclear")

    def test_sync_annotations_and_revision(self):
        image = base64.b64encode(PNG).decode()
        status, synced = self.post("/sync", {"page_id": PAGE, "title": "Integration", "image": image})
        self.assertEqual(status, 200)
        revision = synced["revision"]
        marks = [{"kind": "highlight", "points": [[.1, .2], [.4, .2]], "text": "Check this line"}]
        sync_store.save_annotations(PAGE, revision, marks)
        req = urllib.request.Request(self.base + "/annotations?page_id=" + PAGE,
                                     headers={"X-Device-Token": "test-token"})
        with urllib.request.urlopen(req) as result:
            self.assertEqual(json.load(result)["marks"], marks)
        with self.assertRaises(ValueError):
            sync_store.save_annotations(PAGE, "outdated", marks)

    def test_mcp_reads_image_and_writes_mark(self):
        sync_store.sync_page(PAGE, "Integration", PNG)
        from mcp import ClientSession, StdioServerParameters
        from mcp.client.stdio import stdio_client
        async def flow():
            params = StdioServerParameters(command=sys.executable, args=[str(Path(__file__).parent / "mcp_server.py")],
                                           env=os.environ.copy())
            async with stdio_client(params) as (read, write):
                async with ClientSession(read, write) as session:
                    await session.initialize()
                    names = [tool.name for tool in (await session.list_tools()).tools]
                    self.assertEqual(set(names), {"list_synced_pages", "get_page", "mark_page",
                                                  "write_feedback", "clear_tutor_feedback"})
                    pages = await session.call_tool("list_synced_pages")
                    self.assertFalse(pages.isError)
                    page = await session.call_tool("get_page", {"page_id": PAGE})
                    self.assertFalse(page.isError)
                    self.assertTrue(any(part.type == "image" for part in page.content))
                    rev = sync_store.get_page(PAGE)[0]["revision"]
                    mark = await session.call_tool("mark_page", {"page_id": PAGE, "revision": rev,
                        "marks": [{"kind": "arrow", "points": [[.2,.3],[.4,.5]], "text": "Try the power rule"}]})
                    self.assertFalse(mark.isError)
                    self.assertEqual(sync_store.get_annotations(PAGE)["marks"][0]["kind"], "arrow")
                    note = await session.call_tool("write_feedback", {"page_id": PAGE, "revision": rev,
                        "feedback": "The exponent increases before dividing. What does the power rule suggest?"})
                    self.assertFalse(note.isError)
                    self.assertIn("power rule", sync_store.get_annotations(PAGE)["feedback"])
                    self.assertEqual(len(sync_store.get_annotations(PAGE)["marks"]), 1)
                    req = urllib.request.Request(self.base + "/annotations?page_id=" + PAGE,
                        headers={"X-Device-Token": "test-token"})
                    with urllib.request.urlopen(req) as response:
                        self.assertIn("power rule", json.load(response)["feedback"])
                    stale = await session.call_tool("write_feedback", {"page_id": PAGE, "revision": "old",
                        "feedback": "Stale"})
                    self.assertTrue(stale.isError)
                    too_long = await session.call_tool("write_feedback", {"page_id": PAGE, "revision": rev,
                        "feedback": "x" * 1201})
                    self.assertTrue(too_long.isError)
                    cleared = await session.call_tool("clear_tutor_feedback", {"page_id": PAGE, "revision": rev})
                    self.assertFalse(cleared.isError)
                    self.assertEqual(sync_store.get_annotations(PAGE)["marks"], [])
                    self.assertEqual(sync_store.get_annotations(PAGE)["feedback"], "")
                    sync_store.sync_page(PAGE, "Integration", PNG + b"\x00")
                    stale_clear = await session.call_tool("clear_tutor_feedback", {"page_id": PAGE,
                        "revision": rev})
                    self.assertTrue(stale_clear.isError)
        asyncio.run(flow())

    def test_mcp_streamable_http(self):
        sync_store.sync_page(PAGE, "Integration", PNG)
        from mcp import ClientSession
        from mcp.client.streamable_http import streamablehttp_client
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        environment = os.environ.copy()
        environment.update({"MATHNOTE_MCP_TRANSPORT": "streamable-http", "MATHNOTE_MCP_PORT": str(port)})
        process = subprocess.Popen([sys.executable, str(Path(__file__).parent / "mcp_server.py")],
                                   env=environment, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            for _ in range(40):
                try:
                    with socket.create_connection(("127.0.0.1", port), timeout=.2): break
                except OSError:
                    if process.poll() is not None: self.fail("MCP HTTP server exited early")
                    import time
                    time.sleep(.1)
            else: self.fail("MCP HTTP server did not start")
            async def flow():
                async with streamablehttp_client(f"http://127.0.0.1:{port}/mcp") as (read, write, _):
                    async with ClientSession(read, write) as session:
                        await session.initialize()
                        page = await session.call_tool("get_page", {"page_id": PAGE})
                        self.assertFalse(page.isError)
                        self.assertTrue(any(part.type == "image" for part in page.content))
            asyncio.run(flow())
        finally:
            process.terminate(); process.wait(timeout=5)


if __name__ == "__main__":
    unittest.main()
