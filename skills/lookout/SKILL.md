---
name: lookout
description: See physical things through a Lookout camera phone. Finds the phones on the tailnet, pairs with one after a person approves it, then looks, reads displays, waits for a visual change and talks to the person at the phone. Use when a task needs eyes on real hardware (a board, an LED, a display, a 3D print), when the user mentions Lookout or a camera phone, or when a Lookout phone is not paired yet.
---

# Lookout

A Lookout phone serves its camera to agents over HTTP. `scripts/lookout.mjs` in this skill folder finds phones, pairs with one, and calls the REST API. It needs Node 18 or later and has no dependencies.

In this document, `lookout` means `node <this skill folder>/scripts/lookout.mjs`.

## Connect

1. Run `lookout devices`. If it lists the phone, go to "Use the camera".
2. Run `lookout discover`. Each line is one phone: host, device name, tailnet name, pairing state.
3. Run `lookout pair <host> "<agent name>"`. Take the IPv4 host from `discover`. Give a name the person can recognize, such as the project and the machine.
4. Tell the person the 4-digit code that `pair` printed, and ask them to tap Allow on the phone. Send this message before step 5, because the person compares your code with the card on the phone.
5. Run `lookout wait <host>`. It blocks for up to 2 minutes. The connection is done when it prints `Paired with <device>`.

The token is saved in `~/.config/lookout/devices.json`. A token from a tailnet pairing works only from the address of this machine.

### When a step fails

- `discover` finds nothing, and the `tailscale` CLI is missing: ask the person for the address of the phone, then run `LOOKOUT_HOSTS=<host> lookout discover`.
- `not_lan` with "Tailscale access is off": ask the person to turn on "Allow Tailscale" in Setup on the phone.
- `pairing_closed`: ask the person to turn on "Accept pairing requests" in Setup.
- `pair_denied` or `pair_expired`: ask the person what happened before you send a new request. Three denials block this machine for 10 minutes.
- `token_address_mismatch` or `unauthorized` on a paired phone: the address of this machine changed, or the person revoked the token. Pair again.

## Use the camera

Start with `lookout api GET /v1/describe`. It gives the lenses, the saved regions (ROIs) and the baselines.

An image reply prints `file: <path>`. Open that file with your image reader to see it. `meta:` is the frame data.

The frame data is short. It has the camera fields (`lens`, `zoom`, `iso` and others) only when they changed since your last image, and the battery and thermal fields only at a warning level. Add `verbose=true` to `look` to get all of them. Numbers have at most 3 decimals.

The frame data has the phone tilt from the gravity sensor. `pitch_deg` is -90 when the lens looks straight down and 0 when it looks level. A true vertical line shows turned clockwise by `roll_deg`, so correct for it before you say that something is level or crooked. A `moved` event, or `camera_moved: true` from `diff`, means that the phone tilted more than 2°. Then the saved regions and baselines can be out of line.

| Goal | Command |
| --- | --- |
| See the current view | `lookout look` |
| See it with a labelled grid | `lookout look grid=8x6` |
| Name a region from grid cells | `lookout api PUT /v1/rois/display from=C2 to=E4` |
| Look at one region | `lookout look roi=display` |
| Full-resolution still, for small text | `lookout look detail=full roi=display` |
| A closer view of a frame you already have | `lookout api GET /v1/frames/<frame_id>/crop roi=0.4,0.4,0.2,0.2` |
| Read a display as text and numbers | `lookout api GET /v1/read roi=display` |
| Wait for a change | `lookout api POST /v1/wait condition=change timeout_ms=120000` |
| Wait for a value | `lookout api POST /v1/wait condition=value roi=display above=200` |
| See what happened earlier | `lookout api GET /v1/history` |
| Speak to the person | `lookout api POST /v1/say text="Please power on the board"` |
| Ask the person | `lookout api POST /v1/ask text="Is the LED on?" buttons='["Yes","No"]'` |
| Show the person where to act | `lookout api POST /v1/point marks='[{"shape":"arrow","at":"C3"}]' text="Press this button" buttons='["Done"]'` |
| Label what you do in the images | `lookout api POST /v1/caption text="sent M106" duration_ms=5000` |
| Fetch an image a reply links to | `curl -o frame.jpg "<image_url>"` |
| Get JSON and a link, not the image | `lookout look inline=false` |
| Finish, so the phone can sleep | `lookout api POST /v1/done` |

`lookout api GET /openapi.json` lists every route with its parameters. Read it before you use `record`, `clip`, `diff`, `baselines` or `camera`.

### Rules

- Use `look` with the default `detail=fast`. It is about 1024 px and takes under 1 s when warm; the first look after an idle period or a sleep takes a few seconds while the camera opens. Use `detail=full` only for small text, with an `roi`. A full-resolution still takes more time.
- An image costs about width × height / 750 tokens. Lower `width` (or pass `image=false` to `diff`) to save tokens; `quality` changes file size only.
- If a `look` frame is black (brightness below 0.04), Lookout returns JSON with `dark: true` and no image, and skips `save_as`. Pass `force=true` to return the image anyway, or `torch=true` to turn the light on.
- `look` returns a frame taken after your call, so it shows the result of the action you just made. Add `settle_ms=500` when things still move.
- To wait, call `/v1/wait` one time. It blocks on the phone, so a loop of `look` calls is slower and costs more context. Keep `timeout_ms` at or under 120000, and call it again for a long job.
- For a display or a label, use `/v1/read`. It returns text, and an image costs far more context.
- When only a script needs the image, pass `inline=false`. The reply then has the metadata and `image_url`, and no image. Every image route takes it.
- `image_url` and `video_url` are full URLs that work for 10 minutes with no token. Download them with `curl` or give them to ffmpeg or OpenCV. After that, call the route again for a new URL.
- Each frame that a reply returns (`frame_id`, and `before_frame_id` and `after_frame_id` from `/v1/wait`) stays for 10 minutes, a still at full resolution. Use it in `diff`, in `/v1/frames/<frame_id>/crop`, or download `/v1/frames/<frame_id>.jpg`. `crop` takes no new shot, so it works after the scene changed and does not wake the phone.
- For motion faster than one second, such as a blink code, use `/v1/recordings`, then `/v1/wait condition=recording_done`. Add `clip='{"mode":"trace"}'` (or any `clip` options) to get that clip in the same call, or `sheet=false` to skip the default contact sheet. An `roi` uses `look` coordinates in `record` and `clip`; when the 16:9 video is narrower than a `look` frame, `video_crop` gives the part it covers. The recording summary has `video_url` for the MP4 file.
- When a script triggers the event, mark its steps after the run: `lookout api POST /v1/recordings/mark marks='[{"label":"sent M106","at":"2026-11-25T14:03:07.250Z"}]' client_now=<your clock now, ISO>`. `client_now` corrects for the phone clock. `at` can also be `t_ms`. Then `lookout api GET /v1/recordings/<id>/clip at_marks=true offset_ms=500` shows each mark.
- A clip gives `tiles` as arrays in the order of `tile_columns`, for example `[t_ms, index, motion]`. The wall time of a tile is `started_at` + `t_ms`. `at_ms=1200,1250` picks exact times. Thin `roi` strips fit more tiles in one sheet, up to 200.
- When you finish the task and will not need the camera for a while, call `POST /v1/done`. The phone turns off the light and sleeps. Your next call wakes it, but the light stays off.
- With more than one paired phone, add `-d <device>` to each command.

## Add the MCP server

The phone also has an MCP server with the same tools, over streamable HTTP. `lookout mcp` prints its URL and the `Authorization` header for the paired phone. Add them to the MCP settings of your agent. Most agents load a new MCP server at the start of a session, so keep to the `lookout` commands in this session.
