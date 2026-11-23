#!/usr/bin/env node
// Lookout CLI: find Lookout camera phones on a tailnet, pair with one, and call its REST API.
// It has no dependencies. It needs Node 18 or later. `discover` also uses the tailscale CLI.

import { execFileSync } from "node:child_process";
import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { homedir, hostname, tmpdir } from "node:os";
import { dirname, join } from "node:path";

const USAGE = `Usage: lookout <command>

  discover                         List the Lookout phones this machine can reach.
  pair <host> [agent name]         Ask the phone for access. Prints a code for the person at the phone.
  wait <host>                      Wait for the person to tap Allow, then save the token.
  devices                          List the paired phones.
  forget <device>                  Delete a saved token from this machine.
  look [key=value ...]             Save the current view as a JPEG and print its path.
  api <METHOD> <path> [key=value ...]   Call any REST route. Images and video go to a file.
  mcp                              Print the URL and the header of the MCP server on the phone.

Options: -d <device> picks a phone when more than one is paired. --out <file> names the output file.
<host> is ip[:port]. Environment: LOOKOUT_PORT (default 8080), LOOKOUT_HOSTS (extra hosts for
discover, comma separated), LOOKOUT_DEVICE, LOOKOUT_CONFIG (default ~/.config/lookout/devices.json).`;

const PORT = process.env.LOOKOUT_PORT || "8080";
const CONFIG = process.env.LOOKOUT_CONFIG || join(homedir(), ".config", "lookout", "devices.json");

class Fail extends Error {
  constructor(message, exitCode = 1) {
    super(message);
    this.exitCode = exitCode;
  }
}

function loadConfig() {
  if (!existsSync(CONFIG)) return { devices: {}, pending: {} };
  return { devices: {}, pending: {}, ...JSON.parse(readFileSync(CONFIG, "utf8")) };
}

function saveConfig(config) {
  mkdirSync(dirname(CONFIG), { recursive: true });
  writeFileSync(CONFIG, JSON.stringify(config, null, 2) + "\n", { mode: 0o600 });
  chmodSync(CONFIG, 0o600);
}

const withPort = (host) => (host.includes(":") ? host : `${host}:${PORT}`);

/** Calls the phone. Throws Fail with the error message of the server on a non-2xx status. */
async function request(base, path, { method = "GET", token, body, timeoutMs = 30_000 } = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  let res;
  try {
    res = await fetch(base + path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(timeoutMs),
    });
  } catch (e) {
    throw new Fail(`Cannot reach ${base}: ${e.cause?.code || e.cause?.message || e.name}. Check that the phone serves and that this machine is on the same network.`);
  }
  if (res.ok) return res;
  const text = await res.text();
  let error;
  try {
    error = JSON.parse(text).error;
  } catch {}
  throw Object.assign(new Fail(error ? `${error.code}: ${error.message}` : `HTTP ${res.status}: ${text.slice(0, 200)}`), { code: error?.code });
}

/** The online IPv4 peers of this machine on the tailnet, as {host, name}. */
function tailnetPeers() {
  let status;
  try {
    status = JSON.parse(execFileSync("tailscale", ["status", "--json"], { timeout: 10_000, stdio: ["ignore", "pipe", "ignore"] }));
  } catch {
    console.error("The tailscale CLI gave no peer list. Only LOOKOUT_HOSTS and the paired phones are probed.");
    return [];
  }
  return Object.values(status.Peer || {})
    .filter((p) => p.Online)
    .map((p) => ({ host: (p.TailscaleIPs || []).find((ip) => !ip.includes(":")), name: p.HostName }))
    .filter((p) => p.host);
}

async function discover() {
  const config = loadConfig();
  const candidates = new Map();
  for (const p of tailnetPeers()) candidates.set(withPort(p.host), p.name);
  const extra = (process.env.LOOKOUT_HOSTS || "").split(",").map((s) => s.trim()).filter(Boolean).map(withPort);
  for (const host of [...extra, ...Object.values(config.devices).map((d) => new URL(d.url).host)]) {
    if (!candidates.has(host)) candidates.set(host, "");
  }

  const found = (
    await Promise.all(
      [...candidates].map(async ([host, name]) => {
        try {
          const hello = await (await request(`http://${host}`, "/v1/hello", { timeoutMs: 3_000 })).json();
          return hello.service === "lookout" ? { host, name, ...hello } : null;
        } catch {
          return null;
        }
      }),
    )
  ).filter(Boolean);
  if (found.length === 0) throw new Fail(`No Lookout phone answered. ${candidates.size} hosts were probed on port ${PORT}.`);
  for (const f of found) {
    const paired = config.devices[f.device]?.url === `http://${f.host}` ? "paired" : `not paired, pairing ${f.pairing}`;
    console.log([f.host, f.device, f.name, paired].filter(Boolean).join("\t"));
  }
}

async function pair(host, name = hostname().split(".")[0]) {
  host = withPort(host);
  const reply = await (await request(`http://${host}`, "/v1/pair", { method: "POST", body: { name } })).json();
  const config = loadConfig();
  config.pending[host] = reply.pair_id;
  saveConfig(config);
  console.log(`code: ${reply.code}`);
  console.log(`The phone now shows a pairing card for "${name}". Tell the person this code. They must compare it with the card and tap Allow within ${Math.round(reply.expires_in_ms / 1000)} seconds.`);
  console.log(`Then run: lookout wait ${host}`);
}

async function wait(host) {
  host = withPort(host);
  const config = loadConfig();
  const pairId = config.pending[host];
  if (!pairId) throw new Fail(`No pairing request waits for ${host}. Run: lookout pair ${host}`);
  let reply;
  try {
    reply = await (await request(`http://${host}`, `/v1/pair/${pairId}?wait_ms=120000`, { timeoutMs: 130_000 })).json();
  } catch (e) {
    if (["pair_denied", "pair_expired", "pair_not_found"].includes(e.code)) {
      delete config.pending[host];
      saveConfig(config);
    }
    throw e;
  }
  if (reply.state !== "approved") throw new Fail("The request still waits on the phone. Run the same command again.", 3);
  delete config.pending[host];
  config.devices[reply.device] = { url: `http://${host}`, token: reply.token, bound_to: reply.bound_to, paired_at: new Date().toISOString() };
  saveConfig(config);
  console.log(`Paired with ${reply.device}. The token is saved in ${CONFIG}.`);
  if (reply.bound_to) console.log(`The token works only from ${reply.bound_to}.`);
  console.log("Next: lookout api GET /v1/describe");
}

function pickDevice(name) {
  const { devices } = loadConfig();
  const names = Object.keys(devices);
  name ||= process.env.LOOKOUT_DEVICE || (names.length === 1 ? names[0] : undefined);
  if (names.length === 0) throw new Fail("No phone is paired. Run: lookout discover");
  if (!name) throw new Fail(`More than one phone is paired. Add -d with one of: ${names.join(", ")}`);
  if (!devices[name]) throw new Fail(`No paired phone named ${name}. Paired: ${names.join(", ")}`);
  return { name, ...devices[name] };
}

async function api(deviceName, method, path, params, out) {
  const device = pickDevice(deviceName);
  method = method.toUpperCase();
  const entries = params.map((p) => {
    const i = p.indexOf("=");
    if (i < 1) throw new Fail(`Parameters are key=value, got: ${p}`);
    return [p.slice(0, i), p.slice(i + 1)];
  });
  const bodyMethod = method === "POST" || method === "PUT";
  // The server reads numbers and booleans from strings. Only lists and objects need real JSON.
  const body = bodyMethod ? Object.fromEntries(entries.map(([k, v]) => [k, /^[[{]/.test(v) ? JSON.parse(v) : v])) : undefined;
  if (!bodyMethod && entries.length) path += (path.includes("?") ? "&" : "?") + new URLSearchParams(entries);

  let res;
  try {
    res = await request(device.url, path, { method, token: device.token, body, timeoutMs: 150_000 });
  } catch (e) {
    if (e.code === "token_address_mismatch" || e.code === "unauthorized") {
      e.message += `\nThe saved token for ${device.name} does not work from this machine. Run: lookout pair ${new URL(device.url).host}`;
    }
    throw e;
  }
  const type = res.headers.get("content-type") || "";
  if (!/^(image|video)\//.test(type)) return console.log(await res.text());
  const ext = type.includes("mp4") ? "mp4" : type.includes("svg") ? "svg" : "jpg";
  const file = out || join(tmpdir(), "lookout", `${device.name}-${Date.now()}.${ext}`);
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, Buffer.from(await res.arrayBuffer()));
  console.log(`file: ${file}`);
  const meta = res.headers.get("x-lookout-meta");
  if (meta) console.log(`meta: ${meta}`);
}

function devices() {
  const list = Object.entries(loadConfig().devices);
  if (list.length === 0) throw new Fail("No phone is paired. Run: lookout discover");
  for (const [name, d] of list) console.log([name, d.url, d.bound_to ? `only from ${d.bound_to}` : "", d.paired_at].filter(Boolean).join("\t"));
}

function forget(name) {
  const config = loadConfig();
  if (!config.devices[name]) throw new Fail(`No paired phone named ${name}.`);
  delete config.devices[name];
  saveConfig(config);
  console.log(`Deleted the token for ${name} from this machine. To end its access on the phone, tap Revoke in Setup.`);
}

function mcp(deviceName) {
  const d = pickDevice(deviceName);
  console.log(`name: ${d.name}`);
  console.log(`url: ${d.url}/mcp`);
  console.log(`header: Authorization: Bearer ${d.token}`);
  console.log("Add an HTTP MCP server with this URL and header in the MCP settings of your agent.");
}

async function main(argv) {
  const args = [];
  let device, out;
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "-d" || argv[i] === "--device") device = argv[++i];
    else if (argv[i] === "--out") out = argv[++i];
    else args.push(argv[i]);
  }
  const [command, ...rest] = args;
  const need = (n) => {
    if (rest.length < n) throw new Fail(USAGE, 2);
  };
  switch (command) {
    case "discover": return discover();
    case "pair": need(1); return pair(rest[0], rest.slice(1).join(" ") || undefined);
    case "wait": need(1); return wait(rest[0]);
    case "devices": return devices();
    case "forget": need(1); return forget(rest[0]);
    case "look": return api(device, "GET", "/v1/frame", rest, out);
    case "api": need(2); return api(device, rest[0], rest[1], rest.slice(2), out);
    case "mcp": return mcp(device);
    default: throw new Fail(USAGE, 2);
  }
}

main(process.argv.slice(2)).catch((e) => {
  console.error(e instanceof Fail ? e.message : e);
  process.exit(e.exitCode || 1);
});
