# Lookout

Sideloaded Android app that turns a phone into a LAN camera for coding agents. REST under `/v1` and a stateless MCP server at `/mcp`, both on one Ktor server (port 8080), bearer token auth.

## Modules

- `core/`: pure Kotlin. Capability definitions (`Tools.kt`), frame store, change detector, compositor, camera power modes, ROIs, baselines, event log, human prompts. Images go through the `ImageOps` interface.
- `imaging-awt/`: `ImageOps` on java.awt, for JVM tests and the desktop runner.
- `server/`: Ktor routes, auth and LAN filter, MCP handler, OpenAPI, web page. REST routes and MCP tools both come from the capabilities in `core`.
- `desktop/`: runs the server on the Mac with a replay source.
- `app/`: Android. CameraX source, foreground service, Compose UI.

## Rules

- Define a capability once in `core/.../Tools.kt`. Add its REST route to `restRoutes()` in `server/.../LookoutServer.kt`. Do not write a separate MCP or REST handler.
- Pairing and tokens are not capabilities. They live in `server/` (`Auth.kt`, `Pairing.kt`), and `/v1/hello` and `/v1/pair` need no token.
- Only `FrameSource` implementations touch camera hardware. Tests use `ReplayFrameSource` or the test `PushSource`.

## Commands

Gradle needs JDK 21: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.

- Unit and route tests: `./gradlew :core:test :server:test`
- Build the APK: `./gradlew :app:assembleDebug`
- Install on a device: `./gradlew :app:installDebug` (USB or wireless adb)
- Lint: `./gradlew :app:lintDebug`
- Desktop replay server: `./gradlew :desktop:run --args="--port 8090 --fps 2"` (optional `--replay <folder of jpg/png>`, `--tailscale true` accepts tailnet clients, `LOOKOUT_TOKEN` fixes the token)
- Smoke test against a running server: `scripts/smoke.sh <ip[:port]> <token>`
- Discover and pair from an agent machine: `scripts/lookout-pair.sh discover|pair|wait` (the desktop runner prints the pairing card, type `Allow` to approve)

## Replay on the phone

Push images to `/sdcard/Android/data/app.lookout/files/replay/`, turn on "Replay fixture images" in Setup, then restart the app.
