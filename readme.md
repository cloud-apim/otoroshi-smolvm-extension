# Otoroshi smolvm extension

An [Otoroshi](https://github.com/MAIF/otoroshi) plugin that turns a route into a
**FaaS endpoint backed by [smolvm](https://smolmachines.com/) micro-VMs**.

On each request, the plugin picks a smolvm host (round-robin over a static list or a
list fetched from a URL), provisions a micro-VM from the function's configuration,
runs the function, and returns the response — then tears the VM down.

> Status: **v1** — self-hosted smolvm only, *ephemeral VM per request* (no warm pool yet).
> API mapping validated against **smolvm 1.0.4** (`smolvm serve openapi`); `service` mode
> verified end-to-end against a running host. See [`SPEC.md`](./SPEC.md) for design, the
> validation notes, and the roadmap.

## Two execution contracts

| Mode | How it works | Best for |
|---|---|---|
| `service` | Your OCI image runs an HTTP server on `service_port`; Otoroshi reverse-proxies to the forwarded port. Streaming, binary, native HTTP status. | services / streaming / perf |
| `exec` | The HTTP request is serialized to JSON on **stdin** of your `exec_command`; the function writes a JSON response on **stdout**. (OpenFaaS classic-watchdog style.) | simple one-shot functions |

### `exec` request / response envelope

stdin (request):
```json
{ "method": "POST", "path": "/x", "query": {"a": "b"},
  "headers": {"content-type": "application/json"},
  "body_base64": "..." }
```
stdout (response):
```json
{ "status": 200, "headers": {"content-type": "application/json"},
  "body_base64": "..." }
```
`body` (plain string) is accepted instead of `body_base64`.

## Requirements

- An Otoroshi instance (the plugin is built `provided` against Otoroshi).
- One or more reachable smolvm servers: `smolvm serve start --listen 0.0.0.0:8080`.

## Install

Build the jar and drop it on Otoroshi's classpath:

```bash
sbt package
# target/scala-2.12/otoroshi-smolvm-extension_2.12-<version>.jar
```

Then add the plugin to a route (plugin ref
`cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.smolvm.plugins.SmolVmFunctionBackend`).

## Configuration

| Field | Type | Default | Maps to |
|---|---|---|---|
| `mode` | `service` \| `exec` | `service` | execution contract |
| `hosts` | `[string]` | `[]` | static smolvm hosts (`http://h:8080`) |
| `hosts_url` | string | – | URL returning a JSON array of hosts |
| `image` | string | – | smolvm `image` |
| `network_enabled` | bool | `false` | `network` (outbound egress on/off) |
| `allow_cidrs` | `[string]` | `[]` | `allowedCidrs` (egress by CIDR; smolvm has no host-based egress) |
| `cpus` / `memory_mb` | int | `2` / `512` | `cpus` / `memoryMb` |
| `env` | object | `{}` | exec mode only → exec `env:[{name,value}]` (no machine-level env) |
| `volumes` / `ports` | `[string]` | `[]` | `mounts:[{source,target}]` / `ports:[{host,guest}]` |
| `service_port` | int | `8080` | (service) guest port forwarded |
| `readiness_path` | string | `/` | (service) readiness probe path |
| `exec_command` | `[string]` | – | (exec) command reading stdin |
| `request_timeout` / `boot_timeout` | duration | `30s` / `60s` | timeouts |
| `isolation` | `ephemeral` \| `reuse` | `ephemeral` | v1 honors `ephemeral` only |

See [`SPEC.md`](./SPEC.md) for the complete reference.

---

## v2 — persistent machines (`SmolMachine` entity + admin extension)

v2 adds **non-ephemeral, pooled machines** managed as a first-class Otoroshi entity, on top
of an **admin extension**. The v1 plugin above is unchanged and keeps working as-is.

- **Entity `SmolMachine`** (group `smolvm.extensions.cloud-apim.com`, plural `smol-machines`)
  carries a `SmolMachineSpec`: image, resources, network, **`instances` (1..n)**, execution
  **`mode`**, **`runtime`**, the machine's own smolvm **hosts**, readiness, timeouts and
  `idle_timeout`. Managed via the admin API and the back-office **SmolVM Machines** page.
- **Lazy pool**: instances are created on the first request (up to `instances`), reused
  across requests, and idle ones reaped (leader-only). The placement (`instance → host`)
  lives in an **external Redis** reached through otoroshi's StatefulClient, so it is
  **cluster-safe**.
- **Ephemeral mode**: set `instances: 0` for a fresh micro-VM created per request and torn
  down after (the v1 behaviour, but driven by the entity). No pool, no registry, no reaper —
  maximum isolation (great for one-shot node code execution).
- **New plugin `SmolMachineBackend`** — config is **only a reference** to a `SmolMachine`
  (`cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.smolvm.plugins.SmolMachineBackend`).

### Execution modes (v2)

| Mode | How it works |
|---|---|
| `service` | image runs the HTTP server; proxied (now persistent, reused) |
| `exec` | stdin JSON → stdout JSON per request (classic watchdog), on a reused machine |
| `service-via-exec` | generic kept-alive image; the plugin **launches the HTTP server with an `exec`** (`launch_command`), waits readiness, then proxies |

### Node runtime (`runtime: node`)

A kept-alive `node:22-alpine` machine exposing the `smolvm-sdk` node preset over HTTP →
`exec`: `POST /run` (`node -e`), `/eval`, `/run-file`, `/npm`, `/npm/install`, `/npx`,
`GET /version`, `PUT /files/<path>`. Combine with `mode: service-via-exec` to run a node
HTTP app as a proxied service. See [`examples/smolmachine-node`](./examples/smolmachine-node)
and [`examples/smolmachine-service-via-exec`](./examples/smolmachine-service-via-exec).

**Inline code**: instead of the per-request RPC, you can bake the JS on the entity. Set
`spec.code` (and optionally `spec.dependencies` for an `npm install` at provisioning, and
`spec.code_file`, default `/app/index.js`). When `code` is set it **overrides the command**:
- `mode: exec` → each request runs `node <code_file>` with the request envelope on stdin
  (stdin JSON → stdout JSON contract); the RPC endpoints are disabled.
- `mode: service-via-exec` → the code IS the HTTP server (`node <code_file>` is launched),
  then proxied.

> The micro-VM must stay alive to be `exec`-able. smolvm has no `cmd` at machine creation,
> so the **image's CMD must keep PID 1 alive** (e.g. `CMD ["sleep","infinity"]`); the node
> examples ship such an image.

### Enable it

```hocon
# otoroshi config (or env)
CLOUD_APIM_EXTENSIONS_SMOLMACHINE_ENABLED=true
CLOUD_APIM_EXTENSIONS_SMOLMACHINE_STATE_URI=redis://127.0.0.1:6379/0   # optional; falls back to otoroshi's datastore redis
```

The admin extension is auto-discovered on the classpath (same jar). Security: the node
runtime executes arbitrary code by design — protect routes with auth and bound egress with
`spec.allow_cidrs`.

## License

Apache-2.0 — Copyright Cloud APIM. See [LICENSE](./LICENSE).
