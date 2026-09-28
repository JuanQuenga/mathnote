"""Small checks for the desktop setup command's pairing and state boundaries."""

import importlib.machinery
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from urllib.parse import parse_qs, urlparse


COMMAND = Path(__file__).with_name("mathnote-desktop")
loader = importlib.machinery.SourceFileLoader("mathnote_desktop", str(COMMAND))
spec = importlib.util.spec_from_loader(loader.name, loader)
desktop = importlib.util.module_from_spec(spec)
loader.exec_module(desktop)


class DesktopSetupTests(unittest.TestCase):
    def test_pairing_uri_has_exact_server_and_token(self):
        uri = desktop.pairing_uri("http://192.168.50.236:8765", "a1b2c3")
        parsed = urlparse(uri)
        self.assertEqual((parsed.scheme, parsed.netloc, parsed.path),
                         ("mathnote", "pair", ""))
        self.assertEqual(parse_qs(parsed.query),
                         {"server": ["http://192.168.50.236:8765"], "token": ["a1b2c3"]})

    def test_token_is_persistent_and_private(self):
        with tempfile.TemporaryDirectory() as temp:
            original_config, original_file = desktop.CONFIG, desktop.TOKEN_FILE
            try:
                desktop.CONFIG = Path(temp) / "config"
                desktop.TOKEN_FILE = desktop.CONFIG / "device-token"
                first = desktop.device_token()
                self.assertEqual(first, desktop.device_token())
                self.assertEqual(len(first), 48)
                self.assertEqual(desktop.CONFIG.stat().st_mode & 0o777, 0o700)
                self.assertEqual(desktop.TOKEN_FILE.stat().st_mode & 0o777, 0o600)
            finally:
                desktop.CONFIG, desktop.TOKEN_FILE = original_config, original_file

    def test_lan_address_rejects_public_and_loopback(self):
        self.assertEqual(desktop.lan_ip("192.168.1.5"), "192.168.1.5")
        for invalid in ("127.0.0.1", "169.254.1.2", "8.8.8.8", "192.0.0.1", "::1", "bad-ip"):
            with self.subTest(invalid=invalid), self.assertRaises(RuntimeError):
                desktop.lan_ip(invalid)

    def test_qr_matrix_encodes_exact_pairing_uri(self):
        uri = desktop.pairing_uri("http://192.168.1.5:8765", "ab" * 24)
        with tempfile.TemporaryDirectory() as temp:
            original_config, original_qr = desktop.CONFIG, desktop.QR_FILE
            try:
                desktop.CONFIG = Path(temp) / "config"
                desktop.QR_FILE = desktop.CONFIG / "tablet-pairing.svg"
                self.assertTrue(desktop.write_qr(uri))
                expected = Path(temp) / "expected.svg"
                python = desktop.REPO / "server" / ".venv" / "bin" / "python"
                code = ("import qrcode,qrcode.image.svg,sys; "
                        "qrcode.make(sys.stdin.read(),image_factory=qrcode.image.svg.SvgPathImage).save(sys.argv[1])")
                subprocess.run([str(python), "-c", code, str(expected)], input=uri, text=True,
                               check=True, stdout=subprocess.DEVNULL)
                self.assertEqual(desktop.QR_FILE.read_bytes(), expected.read_bytes())
                self.assertEqual(desktop.QR_FILE.stat().st_mode & 0o777, 0o600)
                self.assertEqual(desktop.CONFIG.stat().st_mode & 0o777, 0o700)
            finally:
                desktop.CONFIG, desktop.QR_FILE = original_config, original_qr


if __name__ == "__main__":
    unittest.main()
