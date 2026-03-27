import hashlib
import json
import math
import random
from http.server import BaseHTTPRequestHandler, HTTPServer

HOST = "127.0.0.1"
PORT = 11434
DIM = 4096


def deterministic_vec(text: str):
    h = hashlib.sha256(text.encode("utf-8")).hexdigest()
    seed = int(h[:16], 16)
    rnd = random.Random(seed)
    vec = [rnd.uniform(-1.0, 1.0) for _ in range(DIM)]
    norm = math.sqrt(sum(x * x for x in vec)) or 1.0
    return [x / norm for x in vec]


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        return

    def do_GET(self):
        if self.path == "/api/tags":
            return self._send(200, {"models": [{"name": "qwen3-embedding:8b-fp16"}]})
        return self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/api/embed":
            return self._send(404, {"error": "not found"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(length) if length > 0 else b"{}"
            req = json.loads(raw.decode("utf-8"))
            inp = req.get("input", "")
            if isinstance(inp, list):
                text = "\n".join(str(x) for x in inp)
            else:
                text = str(inp)
            vec = deterministic_vec(text)
            return self._send(200, {"embeddings": [vec]})
        except Exception as e:
            return self._send(500, {"error": str(e)})


if __name__ == "__main__":
    server = HTTPServer((HOST, PORT), Handler)
    print(f"mock ollama embedding server running at http://{HOST}:{PORT}", flush=True)
    server.serve_forever()
