"""Generate narration lines with ElevenLabs.

usage: tts.py <dir with lines.json> <keyfile> <voice id>

Writes line1.mp3, line2.mp3, ... next to lines.json and prints each length against its window.
Each request passes the lines before and after it, so the voice stays even across clips.
"""
import json, sys, subprocess, urllib.request
w, keyfile, voice = sys.argv[1], sys.argv[2], sys.argv[3]
key = open(keyfile).read().strip()
lines = json.load(open(f"{w}/lines.json"))
for i, l in enumerate(lines):
    body = {
        "text": l["text"],
        "model_id": "eleven_multilingual_v2",
        "previous_text": " ".join(x["text"] for x in lines[:i]) or None,
        "next_text": " ".join(x["text"] for x in lines[i + 1:]) or None,
        "voice_settings": {"stability": 0.5, "similarity_boost": 0.75, "style": 0.0, "use_speaker_boost": True},
    }
    req = urllib.request.Request(
        f"https://api.elevenlabs.io/v1/text-to-speech/{voice}?output_format=mp3_44100_128",
        data=json.dumps(body).encode(), headers={"xi-api-key": key, "Content-Type": "application/json"})
    out = f"{w}/line{i + 1}.mp3"
    with urllib.request.urlopen(req, timeout=60) as r, open(out, "wb") as f:
        f.write(r.read())
    dur = float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", out]).strip())
    room = l["end"] - l["start"]
    print(f"line{i + 1}: {dur:5.2f} s of {room:4.1f} s {'OVER' if dur > room else 'ok'}  {l['text']}")
