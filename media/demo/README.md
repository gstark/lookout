# Lookout demo video

This folder is the [HyperFrames](https://hyperframes.heygen.com) project for `assets/screens/lookout-demo.mp4`. HyperFrames renders HTML and GSAP timelines to MP4. The film is 104 s, 1920 × 1080, 30 fps.

## Scenes

| Time | Composition | What it shows |
| --- | --- | --- |
| 0–25 s | `compositions/intro.html` | The problem: the agent builds and flashes, but cannot see the board. |
| 25–36 s | `compositions/setup.html` | The desk with the phone on an arm (Veo shot A), then a push into the left monitor. |
| 32.05–55.25 s | `compositions/pair.html` | The phone joins the tailnet, `npx skills add gstark/lookout`, discovery, and the code check before Allow. The setup scene covers its first 3.95 s. |
| 51.95–90.95 s | `compositions/demo.html` | Claude Code and the phone UI: look, guided capture, record, trace, ask. The pair scene covers its first 3.3 s. |
| 90.95–99.55 s | `compositions/fix.html` | The developer plugs in USB and the LED goes steady (Veo shot B). |
| 99.55–104.05 s | `compositions/outro.html` | The end card. |

`index.html` mounts the scenes and holds every sound: the sound effects on lane 3, the narration (`vo-in-*`, `vo-pr-*`, `vo-*`) on lane 4.

`pair.html` and `demo.html` draw the same terminal and phone at the same positions. `pair.html` starts with the same two entrance moves as `demo.html`, so the push into the monitor at the end of the setup scene lands on its terminal.

## Preview and render

The HyperFrames CLI comes from npm. `package.json` pins its version.

```bash
cd media/demo
npm run check     # lint, layout, motion, contrast and audio checks
npm run dev       # preview in the browser
npm run render    # writes renders/<name>.mp4 (renders/ is not in git)
```

To update the copy in the repo, re-encode the render and write the poster:

```bash
ffmpeg -i renders/<name>.mp4 -c:v libx264 -preset slow -crf 26 -pix_fmt yuv420p \
  -c:a aac -b:a 128k -movflags +faststart ../../assets/screens/lookout-demo.mp4
ffmpeg -ss 25.8 -i renders/<name>.mp4 -frames:v 1 -vf scale=1280:-1 -q:v 3 ../../assets/screens/demo-poster.jpg
```

## Keys

The scripts read API keys from a file path that you pass as an argument. Do not put a key in this folder.

- OpenRouter key: still images and video shots.
- ElevenLabs key: narration.

## Narration

The voice is ElevenLabs `eleven_multilingual_v2`, voice Eric (`cjVigY5qzO86Huf0OWal`). The lines are in `assets/voice/lines.json`, `assets/voice/intro/lines.json` and `assets/voice/pair/lines.json`.

1. Edit a line in `lines.json`.
2. Run `python3 scripts/tts.py assets/voice <keyfile> cjVigY5qzO86Huf0OWal`. It writes `lineN.mp3` for every line.
3. Trim the silence at both ends, and replace the WAV:
   ```bash
   ffmpeg -i assets/voice/lineN.mp3 -af "silenceremove=start_periods=1:start_threshold=-45dB,areverse,silenceremove=start_periods=1:start_threshold=-45dB,areverse" \
     -ar 48000 -ac 2 assets/voice/vo-N.wav
   ```
4. In `index.html`, set `data-duration` of the `vo-N` clip to the new length. Move its `data-start` if the line got longer.
5. Set the level: `npx hyperframes normalize-audio . --target=vo-N --lufs=-16 --write`.
6. Delete the `lineN.mp3` files.

## Desk scenes

The desk scenes use generated media. Each step used the file before it as a reference.

| File in `assets/gen/` | Made with | Prompt in `scripts/prompts/` | Reference |
| --- | --- | --- | --- |
| first desk still (not kept) | `or_image.py`, `google/gemini-3-pro-image` | `prompt-desk.txt` | `layout-ref.png` |
| phone moved above the board (not kept) | same | `prompt-desk-edit.txt` | the first still |
| `desk-v3.png`, LED off | same | `prompt-led-off.txt` | the previous still |
| cable on the desk | same | `prompt-cable.txt` | `desk-v3.png` |
| cable plugged in | same | `prompt-plugged.txt` | the cable still |
| `shotA2.mp4` | `or_video.py`, `google/veo-3.1`, 8 s, 1080p | `prompt-shotA2.txt` | first and last frame `desk-plate.jpg` |
| `shotB2.mp4` | same | `prompt-shotB2.txt` | first `desk-cable-plate.jpg`, last `desk-plugged-plate.jpg` |

- The plates (`*-plate.jpg`) are the stills scaled to 1935 × 1080 and cropped to 1920 × 1080 at x = 7: `ffmpeg -i in.png -vf "scale=1935:1080:flags=lanczos,crop=1920:1080:7:0" -q:v 2 out.jpg`.
- `shotA2-last.jpg` and `shotB2-last.jpg` are the last frame of each shot. The scenes show them under the video, so the picture holds after the clip ends.
- Give Veo the first and the last frame. With only a first frame, the camera drifted and the action did not finish.
- A Veo shot costs about 3.20 USD for 8 s at 1080p (November 2026).

Example calls:

```bash
python3 scripts/or_image.py <keyfile> google/gemini-3-pro-image assets/gen/out.png scripts/prompts/prompt-led-off.txt assets/gen/in.png
python3 scripts/or_video.py <keyfile> google/veo-3.1 assets/gen/desk-plate.jpg scripts/prompts/prompt-shotA2.txt 8 assets/gen/shotA2.mp4 1080p assets/gen/desk-plate.jpg
```

## Overlays on the desk scenes

`scripts/gen_photo.py` writes `compositions/setup.html` and `compositions/fix.html`: the terminal and the code editor on the monitors, the LED glow, the camera outline and the labels. Run it from this folder:

```bash
python3 scripts/gen_photo.py .
```

The script overwrites both files. Make changes in the script, not in the two HTML files. The constants at the top of the script are pixel positions in the shots:

- `LEFT`, `RIGHT`: the four corners of each monitor screen. They were measured on `desk-plate.jpg`. A new shot needs new corners.
- `LED`: the STAT LED, (1357, 868).
- `LENS`, `FOOT`: the phone lens and the area that the camera outline covers on the mat.

If a new shot moves the board or the monitors, measure the positions again before you render.

`intro.html`, `pair.html`, `demo.html` and `outro.html` are written by hand. Edit them directly.
