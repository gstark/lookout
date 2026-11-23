<img src="assets/icon.svg" width="64" height="64" alt="">

# lookout

Lookout turns a spare Android phone into a camera that coding agents can see through. Claude Code connects to an MCP server that runs on the phone, over the local network. Scripts can use the same features through a plain HTTP API.

## See it work

[![Demo: Claude Code reads a blink code through the phone](assets/screens/demo-poster.png)](assets/screens/lookout-demo.mp4)

In the demo, Claude Code looks at a board through the phone. It asks the person to center the STAT LED, records 6 s at 60 fps, and reads the blink code from a brightness trace. Click the picture to play the video (43 s).

![Phone screens: main, guide, recording, ask, setup](assets/screens/phone-screens.png)

From left to right:

- **Main:** the live preview with a named region, the Activity feed of agent requests, and Pause.
- **Guide:** a `capture` or `record` call with `guide_text`. The phone shows the shot the agent wants, and the agent takes it after the person taps Ready.
- **Recording:** a countdown while `record` runs.
- **Ask:** a full-screen question from `ask`.
- **Setup:** the QR code and the `claude mcp add` command.

In portrait, the guide sits below a full-width preview. In landscape, the guide and the preview are side by side:

![Guide panel in landscape](assets/screens/guide-landscape.png)

The screens are mock-ups of the Compose UI in `app/`.

## What an agent can do

- `look`: a fresh, right-sized frame (about 1024 px), optionally cropped to a named region, with a labelled grid.
- `capture`: a full-resolution still for small text, with lens, zoom, focus and torch for that shot.
- `wait_for`: block until the view changes, something moves, the scene goes still, or the person taps Done.
- `history`: one contact sheet of the last minutes.
- `record` and `clip`: record video at 30 or 60 fps for up to 60 seconds, then read it back as contact sheets, single frames or a brightness trace (for LED blink codes). `wait_for(condition=recording_done)` waits for the end. `GET /v1/recordings/{id}/video` downloads the MP4.
- `diff` and `baseline`: compare against a saved reference frame.
- `roi`: name regions, from grid cells or by dragging on the phone.
- `say` and `ask`: talk to the person near the phone.

## Set up

1. Build and install: `./gradlew :app:installDebug`.
2. Open Lookout on the phone and allow the camera. Turn off battery optimization when the checklist asks.
3. Tap Setup, then copy the `claude mcp add ...` command and run it on your Mac.
4. Give the phone a DHCP reservation on your router, so its address does not change.

See `CLAUDE.md` for the module layout and the test commands.
