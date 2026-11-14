#!/usr/bin/env python3
"""Dev-only stand-in for a model API. Never shipped in the app.

Speaks both wire formats the app supports:
  POST .../chat/completions   (OpenAI-compatible)
  POST .../v1/messages        (Anthropic)

Three kinds of request are recognised from the user message:
  * guide request (contains "SCREEN:")  -> a valid guide JSON pointing at a node
  * document check (contains "DOCCHECK") -> a verdict JSON (see doc_reply)
  * playbook conversion (contains "INSTRUCTIONS:") -> a small draft playbook JSON
  * anything else -> a fixed Vietnamese sentence

  python tools/stub/stub_server.py [port]         # default 8787
  curl "http://localhost:8787/pick?q=Reload"      # choose which node the stub points at
  curl "http://localhost:8787/pick?q="            # no match -> target null ("not sure")
  curl "http://localhost:8787/pick?q=Gui&caution=1&delay=1.5"
  curl "http://localhost:8787/pick?q=Search%20settings~Network;;Airplane~Internet"
      # rules: "text on screen ~ target"; the first rule whose text is on screen wins

When the app sends a PLAYBOOK the stub ignores the pick rules and follows the playbook's
LIKELY_STEP and target_hint, like a model that obeys the playbook.

From the emulator the host machine is http://10.0.2.2:8787 .
"""
import base64
import io
import json
import re
import sys
import time
import unicodedata
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

STATE = {"pick": "", "caution": False, "step": 0, "delay": 0.0}
NL = chr(10)
# Press/type verbs: a "Kéo ..." step that also contains one of these is a tap step.
PRESS_WORDS = {"bấm", "nhấn", "chạm", "ấn", "gõ", "chọn"}


def user_parts(body):
    """(text, jpeg bytes or None) of the last user message, for either wire format."""
    for m in reversed(body.get("messages", [])):
        if m.get("role") != "user":
            continue
        c = m.get("content")
        if isinstance(c, str):
            return c, None
        text, image = "", None
        for p in c:
            if not isinstance(p, dict):
                continue
            text += p.get("text", "")
            if p.get("type") == "image_url":  # OpenAI: data URL
                image = base64.b64decode(p["image_url"]["url"].split(",", 1)[1])
            if p.get("type") == "image":  # Anthropic: base64 source
                image = base64.b64decode(p["source"]["data"])
        return text, image
    return "", None


def label(n):
    return " ".join(str(n.get(k, "")) for k in ("text", "desc", "hint", "id"))


def find_node(nodes, pattern, literal=False):
    """First node matching the pattern; clickable nodes win over plain text."""
    hits = []
    for n in nodes:
        lab = label(n)
        ok = (pattern.lower() in lab.lower()) if literal else re.search(pattern, lab, re.I)
        if ok:
            hits.append(n)
    hits.sort(key=lambda n: 0 if n.get("clickable") else 1)
    return hits[0] if hits else None


def field(text, name):
    """Value of a 'NAME: value' line in the app's user message."""
    for line in text.split(NL):
        if line.startswith(name + ": "):
            return line[len(name) + 2:].strip()
    return None


def guide_reply(text):
    screen = json.loads(text.split("SCREEN:", 1)[1].strip())
    nodes = screen.get("nodes", [])
    alltext = NL.join(label(n) for n in nodes)
    target, step_n, total, caution, pitfall = None, None, 4, STATE["caution"], None
    scroll_do, scroll_up = None, False

    playbook = field(text, "PLAYBOOK")
    if playbook and playbook != "none":
        # Behave like a model that follows the playbook: take the hinted step, find its target.
        pb = json.loads(playbook)
        total = len(pb.get("steps", []))
        likely = field(text, "LIKELY_STEP")
        step = next((s for s in pb["steps"] if str(s.get("n")) == likely), None)
        if step and step.get("target_hint"):
            target = find_node(nodes, step["target_hint"], literal=True)
            step_n, pitfall = step.get("n"), step.get("pitfall")
            caution = caution or bool(step.get("caution"))
        # A step whose "do" starts with "Kéo" and asks for no press or typing is a scroll step:
        # the model should say to scroll. "Kéo ... rồi bấm X" stays a tap step.
        do = unicodedata.normalize("NFC", (step or {}).get("do", "")).strip()
        words = re.findall(r"\w+", do.lower())
        if do.lower().startswith("kéo") and not PRESS_WORDS.intersection(words):
            scroll_do, scroll_up = do, "lên" in words[:4]
            step_n = step.get("n")
    else:
        # Rules "signal~target;;signal~target": the first rule whose signal is on screen wins.
        rules = STATE["pick"].split(";;")
        for n_rule, rule in enumerate(rules, start=1):
            signal, _, pattern = rule.rpartition("~")
            if not pattern or (signal and not re.search(signal, alltext, re.I)):
                continue
            target = find_node(nodes, pattern)
            if target is not None:
                step_n = n_rule
                total = max(4, len(rules))
                break

    STATE["step"] += 1
    if scroll_do:
        # Scroll steps never say "bấm"; the target may still be below the screen (target null).
        say = "Bác %s%s." % (scroll_do[0].lower(), scroll_do[1:].rstrip("."))
        if pitfall:
            say += " " + pitfall
        reply = json.dumps({
            "service": screen.get("host") or screen.get("package"),
            "step": step_n, "total_steps": total, "target": target["i"] if target else None,
            "action": "scroll", "say": say,
            "subtitle": "Kéo màn hình lên" if scroll_up else "Kéo màn hình xuống",
            "caution": caution, "confidence": 0.9,
        }, ensure_ascii=False)
        return "```json" + NL + reply + NL + "```"
    if target is None:
        return json.dumps({"service": "stub", "step": step_n, "total_steps": total, "target": None,
                           "action": "none", "say": "", "subtitle": "", "caution": False,
                           "confidence": 0.2}, ensure_ascii=False)
    name = target.get("text") or target.get("desc") or target.get("hint") or "này"
    say = "Bác bấm vào nút %s đang được khoanh tròn." % name
    if pitfall:
        say += " " + pitfall
    reply = json.dumps({
        "service": screen.get("host") or screen.get("package"),
        "step": step_n or STATE["step"], "total_steps": total, "target": target["i"], "action": "tap",
        "say": say, "subtitle": "Bấm «%s»" % name, "caution": caution, "confidence": 0.9,
    }, ensure_ascii=False)
    # Wrapped in a code fence on purpose: the app must strip fences.
    return "```json" + NL + reply + NL + "```"


def sharpness(jpeg):
    """Mean absolute difference between neighbouring pixels; low means blurred. Needs Pillow."""
    from PIL import Image
    img = Image.open(io.BytesIO(jpeg)).convert("L")
    img.thumbnail((640, 640))
    w, h = img.size
    px = img.load()
    total, count = 0, 0
    for y in range(0, h - 1, 2):
        for x in range(0, w - 1, 2):
            total += abs(px[x, y] - px[x + 1, y]) + abs(px[x, y] - px[x, y + 1])
            count += 2
    return total / max(count, 1), img.size


def doc_reply(jpeg):
    """A stand-in verdict: rejects photos that are measurably blurred, passes the rest."""
    if jpeg is None:
        return json.dumps({"ok": False, "document": "không rõ", "problems": ["Không nhận được ảnh."],
                           "advice": "Bác chụp lại ảnh giúp tôi."}, ensure_ascii=False)
    try:
        score, size = sharpness(jpeg)
    except Exception as e:  # Pillow missing: cannot judge, say so instead of guessing
        return json.dumps({"ok": False, "document": "không rõ",
                           "problems": ["Máy chủ thử không đọc được ảnh (%s)." % type(e).__name__],
                           "advice": "Cài Pillow cho máy chủ thử: pip install pillow"}, ensure_ascii=False)
    print("  doc image %dx%d sharpness=%.2f bytes=%d" % (size[0], size[1], score, len(jpeg)), flush=True)
    if score < 4.0:
        return json.dumps({"ok": False, "document": "Căn cước công dân",
                           "problems": ["Ảnh bị mờ, chữ không đọc được."],
                           "advice": "Bác giữ chắc tay, để thẻ ở nơi đủ sáng rồi chụp lại."}, ensure_ascii=False)
    return json.dumps({"ok": True, "document": "Căn cước công dân", "problems": [],
                       "advice": "Ảnh rõ, đủ bốn góc. Bác dùng ảnh này được."}, ensure_ascii=False)


def playbook_reply(text):
    """A stand-in conversion: one step per non-empty instruction line."""
    raw = text.split("INSTRUCTIONS:", 1)[1].strip()
    lines = [re.sub(r"^\s*(?:bước\s*)?\d+[\.\):]\s*", "", ln.strip(), flags=re.I) for ln in raw.split(NL)]
    lines = [ln for ln in lines if ln]
    steps = []
    for i, ln in enumerate(lines, start=1):
        quoted = re.findall(r"[«\"“']([^»\"”']+)[»\"”']", ln)
        hint = quoted[0] if quoted else ln.split()[-1]
        steps.append({"n": i, "signals": [hint], "do": ln, "target_hint": hint})
    return json.dumps({"id": "ban-nhap", "service": field(text, "SERVICE") or "Dịch vụ",
                       "match": {"packages": [], "hosts": []}, "goal": field(text, "GOAL") or "Làm theo hướng dẫn",
                       "steps": steps}, ensure_ascii=False)


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
        text, image = user_parts(body)
        print("POST %s model=%s key=%s chars=%d image=%s" % (
            path, body.get("model"), "yes" if key else "NO", len(text), "yes" if image else "no"), flush=True)
        if not key.strip():
            return self._send(401, {"error": {"message": "missing api key"}})
        if STATE["delay"]:
            time.sleep(STATE["delay"])
        if "SCREEN:" in text:
            reply = guide_reply(text)
        elif "DOCCHECK" in text:
            reply = doc_reply(image)
        elif "INSTRUCTIONS:" in text:
            reply = playbook_reply(text)
        else:
            reply = "Chào bác, kết nối tốt rồi ạ. (máy chủ thử)"
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
