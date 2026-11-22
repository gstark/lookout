<img src="assets/icon.svg" width="64" height="64" alt="">

# lookout

Lookout turns a spare Android phone into a camera that coding agents can see through. Claude Code connects to an MCP server that runs on the phone, over the local network. Scripts can use the same features through a plain HTTP API.

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
