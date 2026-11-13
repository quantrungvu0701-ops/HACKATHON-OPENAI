#!/usr/bin/env python3
"""Dev-only stand-in for a model API. Never shipped in the app.

Speaks both wire formats the app supports:
  POST .../chat/completions   (OpenAI-compatible)
  POST .../v1/messages        (Anthropic)

If the user message contains a "SCREEN:" node list (a guide request) it answers with a
valid guide JSON whose target is the first node matching the current pick regex, so the
overlay can be tested end to end. Otherwise it answers with a fixed Vietnamese sentence.

  python tools/stub/stub_server.py [port]         # default 8787
  curl "http://localhost:8787/pick?q=Reload"      # choose which node the stub points at
  curl "http://localhost:8787/pick?q="            # no match -> target null ("not sure")
  curl "http://localhost:8787/pick?q=Gui&caution=1&delay=1.5"

From the emulator the host machine is http://10.0.2.2:8787 .
"""
import json
import re
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

STATE = {"pick": "", "caution": False, "step": 0, "delay": 0.0}


def user_text(body):
    for m in reversed(body.get("messages", [])):
        if m.get("role") != "user":
            continue
        c = m.get("content")
        if isinstance(c, str):
            return c
        return "".join(p.get("text", "") for p in c if isinstance(p, dict))
    return ""


def guide_reply(text):
    screen = json.loads(text.split("SCREEN:", 1)[1].strip())
    nodes = screen.get("nodes", [])
    target = None
    if STATE["pick"]:
        rx = re.compile(STATE["pick"], re.I)
        for n in nodes:
            label = " ".join(str(n.get(k, "")) for k in ("text", "desc", "hint", "id"))
            if rx.search(label):
                target = n
                break
    STATE["step"] += 1
    if target is None:
        return json.dumps({"service": "stub", "step": STATE["step"], "total_steps": 4, "target": None,
                           "action": "none", "say": "", "subtitle": "", "caution": False,
                           "confidence": 0.2}, ensure_ascii=False)
    name = target.get("text") or target.get("desc") or target.get("hint") or "này"
    reply = json.dumps({
        "service": screen.get("host") or screen.get("package"),
        "step": STATE["step"], "total_steps": 4, "target": target["i"], "action": "tap",
        "say": "Bác bấm vào nút %s đang được khoanh tròn." % name,
        "subtitle": "Bấm «%s»" % name, "caution": STATE["caution"], "confidence": 0.9,
    }, ensure_ascii=False)
    # Wrapped in a code fence on purpose: the app must strip fences.
    return "```json\n" + reply + "\n```"


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        raw = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        u = urlparse(self.path)
        if u.path == "/pick":
            q = parse_qs(u.query, keep_blank_values=True)
            STATE["pick"] = q.get("q", [""])[0]
            STATE["caution"] = q.get("caution", ["0"])[0] == "1"
            STATE["delay"] = float(q.get("delay", ["0"])[0])
            STATE["step"] = 0
        self._send(200, STATE)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
        path = urlparse(self.path).path
        key = self.headers.get("x-api-key") or self.headers.get("Authorization", "").replace("Bearer ", "")
        text = user_text(body)
        print("POST %s model=%s key=%s chars=%d" % (path, body.get("model"), "yes" if key else "NO", len(text)), flush=True)
        if not key.strip():
            return self._send(401, {"error": {"message": "missing api key"}})
        if STATE["delay"]:
            time.sleep(STATE["delay"])
        reply = guide_reply(text) if "SCREEN:" in text else "Chào bác, kết nối tốt rồi ạ. (máy chủ thử)"
        if path.endswith("/v1/messages"):
            return self._send(200, {"type": "message", "role": "assistant", "stop_reason": "end_turn",
                                    "content": [{"type": "text", "text": reply}]})
        if path.endswith("/chat/completions"):
            return self._send(200, {"choices": [{"index": 0, "finish_reason": "stop",
                                                 "message": {"role": "assistant", "content": reply}}]})
        self._send(404, {"error": {"message": "unknown path %s" % path}})

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8787
    print("stub model server on http://0.0.0.0:%d" % port, flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
