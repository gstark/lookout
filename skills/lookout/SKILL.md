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

| Goal | Command |
| --- | --- |
| See the current view | `lookout look` |
| See it with a labelled grid | `lookout look grid=8x6` |
| Name a region from grid cells | `lookout api PUT /v1/rois/display from=C2 to=E4` |
| Look at one region | `lookout look roi=display` |
| Full-resolution still, for small text | `lookout api POST /v1/capture roi=display` |
| Read a display as text and numbers | `lookout api GET /v1/read roi=display` |
| Wait for a change | `lookout api POST /v1/wait condition=change timeout_ms=120000` |
| Wait for a value | `lookout api POST /v1/wait condition=value roi=display above=200` |
| See what happened earlier | `lookout api GET /v1/history` |
| Speak to the person | `lookout api POST /v1/say text="Please power on the board"` |
| Ask the person | `lookout api POST /v1/ask text="Is the LED on?" buttons='["Yes","No"]'` |
| Show the person where to act | `lookout api POST /v1/point marks='[{"shape":"arrow","at":"C3"}]' text="Press this button" buttons='["Done"]'` |
| Fetch an image a reply links to | `lookout api GET /v1/frames/c12.jpg` |

`lookout api GET /openapi.json` lists every route with its parameters. Read it before you use `record`, `clip`, `diff`, `baselines` or `camera`.

### Rules

- Use `look` by default. It is fast and about 1024 px. Use `capture` only for small text, with an `roi`.
- `look` returns a frame taken after your call, so it shows the result of the action you just made. Add `settle_ms=500` when things still move.
- To wait, call `/v1/wait` one time. It blocks on the phone, so a loop of `look` calls is slower and costs more context. Keep `timeout_ms` at or under 120000, and call it again for a long job.
- For a display or a label, use `/v1/read`. It returns text, and an image costs far more context.
- For motion faster than one second, such as a blink code, use `/v1/recordings`, then `/v1/wait condition=recording_done`.
- With more than one paired phone, add `-d <device>` to each command.

## Add the MCP server

The phone also has an MCP server with the same tools. `lookout mcp` prints the `claude mcp add` command for the paired phone. The tools load in the next session, so keep to the `lookout` commands in this session.
