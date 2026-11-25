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

The frame data is short. It has the camera fields (`lens`, `zoom`, `focus_mm`, `iso` and others) only when they changed since your last image, and the battery and thermal fields only at a warning level. Add `verbose=true` to `look` to get all of them. When focus is at the lens close limit, `look` adds `focus_at_limit: true` and a hint (`describe` gives each lens's `min_focus_mm`, `focal_length_mm`, `fov_h_deg` and `fov_v_deg`). Numbers have at most 3 decimals.

The frame data has the phone tilt from the gravity sensor. `pitch_deg` is -90 when the lens looks straight down and 0 when it looks level. A true vertical line shows turned clockwise by `roll_deg`, so correct for it before you say that something is level or crooked. A `moved` event, or `camera_moved: true` from `diff`, means that the phone tilted more than 2°. It clears the stored scale, and saved regions and baselines can be out of line.

| Goal | Command |
| --- | --- |
| See the current view | `lookout look` |
| See it with a labelled grid | `lookout look grid=8x6` |
| See edge coordinate ticks (works on crops) | `lookout look grid=ticks` |
| Name a region from grid cells | `lookout api PUT /v1/rois/display from=C2 to=E4` |
| Calibrate mm scale from two points | `lookout api PUT /v1/scale from=0.2,0.5 to=0.6,0.5 mm=40` |
| Look at one region | `lookout look roi=display` |
| Focus on a region | `lookout look roi=display focus=roi` |
| Full-resolution still, for small text | `lookout look detail=full roi=display` |
| A closer view of a frame you already have | `lookout api GET /v1/frames/<frame_id>/crop roi=0.4,0.4,0.2,0.2` |
| Measure a flat part and its holes in mm | `lookout api POST /v1/flatten frame=<frame_id> corners='["0.21,0.30","0.78,0.31","0.79,0.70","0.20,0.69"]' width_mm=80 height_mm=50` |
| The same, with the part on the measuring mat | `lookout api POST /v1/flatten mat=true` |
| Read a display as text and numbers | `lookout api GET /v1/read roi=display` |
| Wait for a change | `lookout api POST /v1/wait condition=change timeout_ms=50000` |
| Measure how far a part moved between two frames | `lookout api POST /v1/diff a=<frame_id> b=<frame_id> roi=holder image=false` |
| Wait for a value | `lookout api POST /v1/wait condition=value roi=display above=200` |
| See what happened earlier | `lookout api GET /v1/history` |
| Speak to the person | `lookout api POST /v1/say text="Please power on the board"` |
| Ask the person | `lookout api POST /v1/ask text="Is the LED on?" buttons='["Yes","No"]'` |
| Ask the person to set up a shot | `lookout look guide_text="Hold the board label-side up" guide_image=<frame_id>` |
| Show the person where to act | `lookout api POST /v1/point marks='[{"shape":"arrow","at":"C3"}]' text="Press this button" buttons='["Done"]'` |
| Label what you do in the images | `lookout api POST /v1/caption text="sent M106" duration_ms=5000` |
| Fetch an image a reply links to | `curl -o frame.jpg "<image_url>"` |
| Get JSON and a link, not the image | `lookout look inline=false` |
| Finish, so the phone can sleep | `lookout api POST /v1/done` |

`lookout api GET /openapi.json` lists every route with its parameters. Read it before you use `record`, `clip`, `diff`, `scale`, `baselines` or `camera`.

### Rules

- Use `look` with the default `detail=fast`. It is about 1024 px and takes under 1 s when warm; the first look after an idle period or a sleep takes a few seconds while the camera opens. Use `detail=full` only for small text, with an `roi`. A full-resolution still takes more time.
- An image costs about width × height / 750 tokens. Lower `width` (or pass `image=false` to `diff`) to save tokens; `quality` changes file size only.
- If a `look` frame is black (brightness below 0.04), Lookout returns JSON with `dark: true` and no image, and skips `save_as`. Pass `force=true` to return the image anyway, or `torch=true` to turn the light on.
- `look` returns a frame taken after your call, so it shows the result of the action you just made. Add `settle_ms=500` when things still move, or `sharp=true` to take a short burst and return the sharpest frame. `save_as` and `baseline` save from a still burst and refuse blurred frames unless `force=true`.
- Pass `grid=ticks` on `look`, `crop`, or `/v1/frames/<frame_id>.jpg` to draw edge ruler ticks in full-frame 0..1 coordinates. On crops, the tick spacing tightens to fit the crop while keeping full-frame coordinates.
- Pass `focus=roi` on `look` (with an `roi`) to run autofocus on that region for that call.
- `PUT /v1/scale` stores one resolution-independent mm scale from two points (`from`, `to`, as `x,y` or grid cells) and `mm`, valid for one plane at a fixed distance. `GET /v1/scale` reads it and `DELETE /v1/scale` clears it. When valid, `look` and `crop` include `mm_per_px` for the returned image, and `diff` and `clip mode=track` report mm automatically unless an explicit `mm_per_px` is given. A `moved` event clears it, and a lens or zoom change marks it stale.
- To wait, call `/v1/wait` one time. It blocks on the phone, so a loop of `look` calls is slower and costs more context. Keep `timeout_ms` at or under 50000, because some clients stop a call at 60 s. Call it again for a long job.
- A guided shot (`guide_text` or `guide_image` on `look` or `POST /v1/recordings`) waits up to `wait_ms` (default 45000) for the person to tap Ready. If they take longer, the reply is `state: "waiting"` with a `request_id`, and the prompt stays on the phone. Call `/v1/wait condition=request request_id=<id>` until it returns the result. A Cancel or a Ready timeout comes back as `state: "declined"` or `state: "timeout"`. The result has `released_by: "tap"` and `ready_after_ms`. Only one guided shot can wait at a time.
- `guide_image` takes a `frame_id`, an `image_id` or a baseline name, so you do not have to send the image. `guide_marks` draws marks on it, as in `point`. To show a file from disk, POST it to `upload_url` from `describe` with no token: `curl --data-binary @example.png "<upload_url>"`. The reply is `{"image_id":"c12"}`. The URL works for 10 minutes, for images up to 4 MB.
- `diff` first aligns b to a for a small camera shift and reports it as `global_shift_px`. `aligned: false` means that the frames did not line up, so it compared them as they are. With an `roi`, `dx` and `dy` give how far the part in the roi moved, in px of frame a, and `shift_confidence` gives the confidence (no `dx` below 0.6). The search reaches one roi width and one roi height to each side. `mm_per_px` (or a stored scale) adds `dx_mm` and `dy_mm`. With `image=false`, one step of a mechanism costs one short reply.
- To measure a flat part, use `POST /v1/flatten`. It returns a top-down view at a fixed `px_per_mm` and a `circles` table: `x_mm` and `y_mm` from the top-left corner, `d_mm`, `rms_mm` (how round) and `inside` (dark or light). Read the 4 corners of a rectangle of known size, such as the board outline, off `look detail=full grid=ticks`, in any order. They snap to the edges; `edges` gives the fit per side in px, and `none` means it kept your corners on that side. `width_mm` is the side nearer horizontal in the frame. With `frame=<frame_id>` it takes no new shot and does not wake the phone.
- `mat=true` finds the four markers of the measuring mat (`assets/measure-mat.pdf` in the repository, printed at 100 %) and measures in mm from the center of marker 0. A part's top face lies above the mat, so it reads larger by its thickness divided by the camera distance (0.5 % for a 1.6 mm board at 300 mm). Lens distortion is not corrected: expect about 0.1 to 0.3 mm over 80 mm.
- For a display or a label, use `/v1/read`. It returns text, and an image costs far more context.
- When only a script needs the image, pass `inline=false`. The reply then has the metadata and `image_url`, and no image. Every image route takes it.
- `image_url` and `video_url` are full URLs that work for 10 minutes with no token. Download them with `curl` or give them to ffmpeg or OpenCV. After that, call the route again for a new URL.
- Each frame that a reply returns (`frame_id`, and `before_frame_id` and `after_frame_id` from `/v1/wait`) stays for 10 minutes, a still at full resolution. Use it in `diff`, in `/v1/frames/<frame_id>/crop`, or download `/v1/frames/<frame_id>.jpg`. `crop` takes no new shot, so it works after the scene changed and does not wake the phone.
- For motion faster than one second, such as a blink code, use `/v1/recordings`, then `/v1/wait condition=recording_done`. Add `clip='{"mode":"trace"}'` (or any `clip` options) to get that clip in the same call, or `sheet=false` to skip the default contact sheet. An `roi` uses `look` coordinates in `record` and `clip`; when the 16:9 video is narrower than a `look` frame, `video_crop` gives the part it covers. The recording summary has `video_url` for the MP4 file.
- The summary is ready about a second after the end, and `finalize_ms` gives that time. While it has `motion_source: "preview"`, `activity` and `pick=motion` come from the preview frames: their times are accurate to about 70 ms, and a very short event can be missing. `mode=trace` and `mode=track` always read the video. An exact pass follows in the background (event `recording_exact`). Add `exact=true` to the wait when you need it.
- When a script triggers the event, mark its steps after the run: `lookout api POST /v1/recordings/mark marks='[{"label":"sent M106","at":"2026-11-25T14:03:07.250Z"}]' client_now=<your clock now, ISO>`. `client_now` corrects for the phone clock. `at` can also be `t_ms`. Then `lookout api GET /v1/recordings/<id>/clip at_marks=true offset_ms=500` shows each mark.
- A clip gives `tiles` as arrays in the order of `tile_columns`, for example `[t_ms, index, motion]`. The wall time of a tile is `started_at` + `t_ms`. `at_ms=1200,1250` picks exact times. Thin `roi` strips fit more tiles in one sheet, up to 200. `layout` gives the grid: tile i is at x = (i % cols) × (tile_w + gap), y = (i / cols) × (tile_h + band + gap).
- To measure how far and when a part moved, use `lookout api GET /v1/recordings/<id>/clip mode=track roi=0.2,0.48,0.5,0.03`, with a thin `roi` across the path of one edge. It returns numbers, not images: the edge position in video px for each frame, `holds`, `moves`, and for each mark where the part settled. Add `mm_per_px` (or set a stored scale) for mm, `raw=true` for every frame, and `image=true` for a time stack (one line of the roi per frame). Do not cut contact sheets for this.
- When you finish the task and will not need the camera for a while, call `POST /v1/done`. The phone turns off the light and sleeps. Your next call wakes it, but the light stays off.
- With more than one paired phone, add `-d <device>` to each command.

## Add the MCP server

The phone also has an MCP server with the same tools, over streamable HTTP. `lookout mcp` prints its URL and the `Authorization` header for the paired phone. Add them to the MCP settings of your agent. Most agents load a new MCP server at the start of a session, so keep to the `lookout` commands in this session.
