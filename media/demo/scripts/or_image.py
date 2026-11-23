"""Generate one image through OpenRouter.

usage: or_image.py <keyfile> <model> <out.png> <prompt-file> [reference.png ...]
"""
import base64
import json
import sys
import urllib.request

keyfile, model, out, prompt_file, *refs = sys.argv[1:]
key = open(keyfile).read().strip()
prompt = open(prompt_file).read().strip()

content = [{"type": "text", "text": prompt}]
for r in refs:
    b64 = base64.b64encode(open(r, "rb").read()).decode()
    content.append({"type": "image_url", "image_url": {"url": f"data:image/png;base64,{b64}"}})

body = {
    "model": model,
    "messages": [{"role": "user", "content": content}],
    "modalities": ["image", "text"],
    "image_config": {"aspect_ratio": "16:9", "image_size": "2K"},
}
req = urllib.request.Request(
    "https://openrouter.ai/api/v1/chat/completions",
    data=json.dumps(body).encode(),
    headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json", "X-Title": "media-render"},
)
try:
    with urllib.request.urlopen(req, timeout=300) as r:
        d = json.load(r)
except urllib.error.HTTPError as e:
    print("HTTP", e.code, e.read().decode()[:600])
    sys.exit(1)

msg = d["choices"][0]["message"]
images = msg.get("images") or []
if not images:
    print("no image returned:", (msg.get("content") or "")[:400])
    sys.exit(1)
url = images[0]["image_url"]["url"]
open(out, "wb").write(base64.b64decode(url.split("base64,", 1)[1]))
usage = d.get("usage", {})
print("saved", out, "| text:", (msg.get("content") or "").strip()[:200], "| tokens:", usage.get("total_tokens"))
