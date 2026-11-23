"""Generate one video shot through OpenRouter from a first frame.

usage: or_video.py <keyfile> <model> <first-frame.jpg or -> <prompt-file> <seconds> <out.mp4> [resolution] [last-frame.jpg]
"""
import base64
import json
import sys
import time
import urllib.request

keyfile, model, first, prompt_file, seconds, out, *rest = sys.argv[1:]
resolution = rest[0] if rest else "1080p"
last = rest[1] if len(rest) > 1 else None
key = open(keyfile).read().strip()
H = {"Authorization": f"Bearer {key}", "Content-Type": "application/json", "X-Title": "media-render"}


def call(method, url, body=None):
    req = urllib.request.Request(url, data=json.dumps(body).encode() if body else None, headers=H, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


body = {
    "model": model,
    "prompt": open(prompt_file).read().strip(),
    "duration": int(seconds),
    "aspect_ratio": "16:9",
    "resolution": resolution,
}
if first != "-":
    b64 = base64.b64encode(open(first, "rb").read()).decode()
    body["frame_images"] = [{"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}, "frame_type": "first_frame"}]
if last:
    lb = base64.b64encode(open(last, "rb").read()).decode()
    body.setdefault("frame_images", []).append({"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{lb}"}, "frame_type": "last_frame"})
code, raw = call("POST", "https://openrouter.ai/api/v1/videos", body)
if code not in (200, 202):
    print("submit failed", code, raw.decode()[:600])
    sys.exit(1)
job = json.loads(raw)
jid = job["id"]
print("job", jid, job.get("status"), flush=True)

while True:
    time.sleep(15)
    code, raw = call("GET", f"https://openrouter.ai/api/v1/videos/{jid}")
    if code != 200:
        print("poll", code, raw.decode()[:300], flush=True)
        continue
    st = json.loads(raw)
    status = st.get("status")
    print("status", status, flush=True)
    if status == "completed":
        break
    if status in ("failed", "cancelled", "expired"):
        print("job ended:", status, st.get("error"))
        sys.exit(1)

code, raw = call("GET", f"https://openrouter.ai/api/v1/videos/{jid}/content?index=0")
if code != 200:
    print("download failed", code, raw[:300])
    sys.exit(1)
open(out, "wb").write(raw)
print("saved", out, len(raw), "bytes; cost usd", (st.get("usage") or {}).get("cost"))
