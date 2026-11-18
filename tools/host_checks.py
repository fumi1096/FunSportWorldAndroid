"""Host-side checks for the Android Python core.

The script needs a Python interpreter with pycryptodome installed; the desktop
project's virtualenv is a convenient choice:

    # Linux / macOS
    FUNSPORT_PYTHON=/path/to/FunSportWorld/.venv/bin/python \
        "$FUNSPORT_PYTHON" tools/host_checks.py

    # Windows PowerShell
    $env:FUNSPORT_PYTHON = "C:\\path\\to\\FunSportWorld\\.venv\\Scripts\\python.exe"
    & $env:FUNSPORT_PYTHON tools/host_checks.py

The script puts the Android app's python source first on sys.path, so the
pure-Python crypto modules and the urllib-based `requests` shim are exercised,
and compares their output with pycryptodome where possible.
"""

import base64
import hashlib
import json
import os
import sys
import tempfile
import threading
import traceback
from http.server import BaseHTTPRequestHandler, HTTPServer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP_PYTHON = os.path.join(ROOT, "app", "src", "main", "python")
sys.path.insert(0, APP_PYTHON)
os.environ["FUNSPORT_DATA_DIR"] = tempfile.mkdtemp(prefix="funsport-android-test-")

try:
    from Crypto.Cipher import AES, PKCS1_v1_5  # noqa: E402
    from Crypto.PublicKey import RSA  # noqa: E402
    from Crypto.Util.Padding import pad, unpad  # noqa: E402
except ImportError:
    print(
        "pycryptodome is required for this compatibility check. "
        "Use the desktop project's virtualenv or run: pip install pycryptodome",
        file=sys.stderr,
    )
    raise SystemExit(2)

from funsport.crypto import aes as pure_aes  # noqa: E402
from funsport.crypto import envelope as env  # noqa: E402
from funsport.crypto.decrypt import derive_paes_key  # noqa: E402
from funsport.api import gt4  # noqa: E402
import requests  # noqa: E402  (the shim from app/src/main/python)


def check(name, fn):
    try:
        fn()
        print("PASS", name)
        return True
    except Exception:
        print("FAIL", name)
        traceback.print_exc()
        return False


def test_aes_vectors():
    for length in (0, 1, 15, 16, 17, 63, 64, 200, 1024):
        key = os.urandom(16)
        iv = os.urandom(16)
        data = os.urandom(length)
        reference = AES.new(key, AES.MODE_CBC, iv).encrypt(pad(data, 16))
        assert pure_aes.cbc_encrypt(key, data, iv) == reference, length
        assert pure_aes.cbc_decrypt(key, reference, iv) == data, length


def _rsa_test_key():
    """A throw-away keypair so encryption can be decrypted in the test."""
    return RSA.generate(1024)


def test_envelope_roundtrip():
    # Validate the PKCS#1 v1.5 implementation with a known private key first.
    test_key = _rsa_test_key()
    payload = b"hello-rsa"
    ciphertext = env._rsa.encrypt_pkcs1_v15(payload, test_key.n, test_key.e)
    decrypted = PKCS1_v1_5.new(test_key).decrypt(ciphertext, b"<invalid>")
    assert decrypted == payload
    # The real envelope uses the app's fixed public key; its AES layer and
    # digest can be fully checked, and the RSA ciphertext length/format too.
    session = env.EnvelopeSession.with_four("1234567890.1")
    built = env.build_envelope(session, json.dumps({"hello": "世界"}, ensure_ascii=False))
    fields = json.loads(built.json)
    assert set(fields) == {"d", "h", "k", "p", "t"}, fields.keys()
    request_key = built.req_key.encode()
    container = unpad(
        AES.new(request_key, AES.MODE_CBC, b"\0" * 16).decrypt(base64.b64decode(fields["d"])),
        16,
    )
    assert container == built.container
    assert fields["h"] == hashlib.md5(container).hexdigest()
    rsa_bytes = base64.b64decode(fields["k"])
    assert len(rsa_bytes) == (env.RSA_PUB_N.bit_length() + 7) // 8
    assert int.from_bytes(rsa_bytes, "big") < env.RSA_PUB_N
    # Same key material as the desktop app's PEM.
    desktop_key = RSA.import_key(env.RSA_PUB_PEM)
    assert desktop_key.n == env.RSA_PUB_N and desktop_key.e == env.RSA_PUB_E


def test_derive_key_stability():
    key = derive_paes_key("nhang.school", "5K0E8400-E29", "597DEA1AFB49", "1234567890.1")
    assert len(key) == 16
    assert key == derive_paes_key("nhang.school", "5K0E8400-E29", "597DEA1AFB49", "1234567890.1")


def test_gt4_helpers():
    str_16 = "0123456789abcdef"
    plaintext = json.dumps({"setLeft": 43, "userresponse": 44.7}, separators=(",", ":"))
    reference = AES.new(str_16.encode(), AES.MODE_CBC, b"0" * 16).encrypt(pad(plaintext.encode(), 16))
    assert gt4._aes_o(plaintext, str_16) == reference.hex()

    limbs = gt4._LIMBS
    n = 0
    for limb in reversed(limbs):
        n = n * (1 << 28) + limb
    public_key = RSA.construct((n, 65537))
    assert gt4._gee_rsa_public_key().n == public_key.n
    # Validate _encrypt_data with a throw-away keypair whose private half we own.
    test_key = _rsa_test_key()
    payload = b"android-gt4-check"
    got = bytes.fromhex(gt4._encrypt_data(gt4._rsa.PublicKey(test_key.n, test_key.e), payload.decode()))
    assert PKCS1_v1_5.new(test_key).decrypt(got, b"<invalid>") == payload
    # The production GT4 key produces a correctly sized ciphertext.
    production = bytes.fromhex(gt4._encrypt_data(gt4._gee_rsa_public_key(), "android"))
    assert len(production) == (n.bit_length() + 7) // 8
    assert int.from_bytes(production, "big") < n


class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _reply(self, status, body, content_type="application/json"):
        data = body if isinstance(body, bytes) else body.encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.startswith("/status/404"):
            self._reply(404, '{"error": "not found"}')
        else:
            self._reply(200, json.dumps({"method": "GET", "path": self.path, "headers": dict(self.headers)}))

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        self._reply(200, json.dumps({"method": "POST", "body": body.decode(errors="replace")}))

    def do_PUT(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        self._reply(200, json.dumps({"method": "PUT", "bytes": len(body)}))


def test_requests_shim():
    server = HTTPServer(("127.0.0.1", 0), _Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    port = server.server_address[1]
    base = "http://127.0.0.1:{}".format(port)
    try:
        session = requests.Session()
        session.headers.update({"User-Agent": "FunSportWorld-Test"})
        response = session.get(base + "/hello", params={"a": "1", "b": "中文"}, timeout=5)
        assert response.status_code == 200
        payload = response.json()
        assert payload["method"] == "GET"
        assert "a=1" in payload["path"] and "b=" in payload["path"]
        header_map = {key.lower(): value for key, value in payload["headers"].items()}
        assert header_map.get("user-agent", "").startswith("FunSportWorld-Test"), header_map

        posted = session.post(base + "/echo", data='{"x":1}', headers={"Content-Type": "application/json"}, timeout=5)
        posted.raise_for_status()
        assert posted.json()["body"] == '{"x":1}'

        put = session.put(base + "/object", data=b"abc", headers={"Content-Type": "application/json"}, timeout=5)
        assert put.json()["bytes"] == 3

        with requests.get(base + "/ctx", timeout=5) as context_response:
            assert context_response.status_code == 200
            assert context_response.json()["method"] == "GET"

        missing = session.get(base + "/status/404", timeout=5)
        try:
            missing.raise_for_status()
        except requests.HTTPError:
            pass
        else:
            raise AssertionError("raise_for_status must raise HTTPError")
        assert isinstance(requests.HTTPError(), requests.RequestException)
    finally:
        server.shutdown()
        server.server_close()


def main():
    results = [
        check("pure-Python AES-128 CBC/PKCS7 matches pycryptodome", test_aes_vectors),
        check("envelope AES/RSA round-trip", test_envelope_roundtrip),
        check("paes key derivation", test_derive_key_stability),
        check("GT4 AES/RSA helpers", test_gt4_helpers),
        check("requests shim (GET/POST/PUT/params/context/errors)", test_requests_shim),
    ]
    if not all(results):
        sys.exit(1)
    print("all host checks passed")


if __name__ == "__main__":
    main()
