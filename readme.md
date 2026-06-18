# Otoroshi smolvm extension

Run **code, functions and HTTP services inside [smolvm](https://smolmachines.com/) micro-VMs**,
straight from [Otoroshi](https://github.com/MAIF/otoroshi).

You describe a workload as a **`SmolMachine`** (an Otoroshi entity), point a route at it, and
Otoroshi provisions micro-VMs on your smolvm hosts and routes traffic to them — with strong
per-workload isolation.

## What you can do

- **Expose an HTTP service** running inside a micro-VM and reverse-proxy to it.
- **Run one-shot functions** (request in → response out), OpenFaaS-style.
- **Run Node.js** without building an image: execute JS / `npm` / `npx` on demand, or run
  **inline JavaScript defined right on the entity** (with optional npm dependencies).
- **Pick your isolation/perf trade-off**: a warm pool of reused VMs, or a brand-new VM per
  request (maximum isolation, great for running untrusted code).
- **Run in a cluster**: machine placement is shared across all Otoroshi nodes.

## How it works

1. You create a **`SmolMachine`** entity — in the back-office under **SmolVM Machines**, or via
   the admin API. It describes the workload: image, resources, execution mode, runtime, how many
   instances, which smolvm hosts, timeouts…
2. You add the **Smol Machine** plugin to a route. Its only setting is a **reference** to a
   `SmolMachine`; everything else lives on the entity.
3. On a request, Otoroshi provisions (or reuses) a micro-VM for that machine on one of its smolvm
   hosts, runs the workload, and returns the response.

```
   client ──▶ Otoroshi route ──▶ Smol Machine plugin ──▶ SmolMachine entity
                                                              │
                                                              ▼
                                                    smolvm host(s) ── micro-VM(s)
```

## Requirements

- An Otoroshi instance.
- One or more reachable **smolvm** servers: `smolvm serve start --listen 0.0.0.0:8080`.
- (Optional, recommended for clusters) a Redis for shared machine placement.

## Install

Build the jar and drop it on Otoroshi's classpath:

```bash
sbt package
# target/scala-2.12/otoroshi-smolvm-extension_2.12-<version>.jar
```

Enable the extension (config file or environment):

```bash
CLOUD_APIM_EXTENSIONS_SMOLMACHINE_ENABLED=true
# optional: a dedicated Redis for cluster-wide machine placement
# (falls back to Otoroshi's own datastore when unset)
CLOUD_APIM_EXTENSIONS_SMOLMACHINE_STATE_URI=redis://127.0.0.1:6379/0
```

## Create a machine

From the back-office **SmolVM Machines** page, or via the admin API:

```bash
curl -X POST 'https://otoroshi-api/apis/smolvm.extensions.cloud-apim.com/v1/smol-machines' \
  -u <api-key>:<secret> -H 'content-type: application/json' -d '{
    "id": "smol-machine_whoami",
    "name": "whoami",
    "spec": {
      "image": "traefik/whoami:latest",
      "mode": "service",
      "instances": 2,
      "network": true,
      "hosts": ["http://127.0.0.1:8080"],
      "service_port": 80
    }
  }'
```

Then add the plugin to a route's `plugins` array:

```json
{
  "plugin": "cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.smolvm.plugins.SmolMachineBackend",
  "enabled": true,
  "config": { "ref": "smol-machine_whoami" }
}
```

## Execution modes

| Mode | What runs | When to use |
|---|---|---|
| `service` | The image's own HTTP server is started at boot; Otoroshi reverse-proxies to it. | Container images that already run a server (streaming, binary, native HTTP). |
| `exec` | Each request is serialized to JSON on **stdin** of your command; the command writes a JSON response on **stdout**. | Simple functions / handlers. |
| `service-via-exec` | A generic image is kept alive; the plugin **starts your HTTP server with a command**, waits for it, then proxies. | Run a server from a base image (e.g. Node) without baking a custom image. |

`exec` envelope — stdin (request): `{"method","path","query","headers","body_base64"}` ·
stdout (response): `{"status","headers","body_base64"}` (or `"body"` for plain text).

## Node.js runtime

Set `runtime: node` to use a `node:22-alpine` machine.

**On-demand (RPC).** The request path selects the operation:

```bash
curl -X POST https://route/run         -d '{"code":"console.log(40+2)"}'   # node -e  -> {"exitCode":0,"stdout":"42\n"}
curl -X POST https://route/eval        -d '{"expression":"1+2+3"}'
curl -X POST https://route/run-file    -d '{"path":"/app/x.js"}'
curl -X POST https://route/npm/install -d '{"packages":["lodash"]}'
curl -X POST https://route/npx         -d '{"args":["cowsay","hi"]}'
curl -X PUT  https://route/files/app.js --data-binary @app.js
curl        https://route/version
```

**Inline code.** Bake the JavaScript on the entity instead of passing it per request — set
`code` (and optionally `dependencies` to `npm install`, and `code_file`, default `/app/index.js`).
When `code` is set it becomes the workload:

- with `mode: exec` → each request runs your script with the request envelope on stdin;
- with `mode: service-via-exec` → your script **is** the HTTP server, and gets proxied.

```json
{
  "name": "node app",
  "spec": {
    "image": "node:22-alpine",
    "runtime": "node",
    "mode": "service-via-exec",
    "instances": 2,
    "network": true,
    "hosts": ["http://127.0.0.1:8080"],
    "service_port": 3000,
    "dependencies": ["express"],
    "code": "const e=require('express')();e.get('/',(_,r)=>r.send('hi'));e.listen(3000,'0.0.0.0')",
    "launch_command": ["node", "/app/index.js"]
  }
}
```

## Instances & scaling

`spec.instances` controls how a machine is run:

- **`n` ≥ 1** — a **warm pool** of `n` micro-VMs, created lazily on first use, reused across
  requests, and load-balanced. Idle VMs are recycled automatically (`idle_timeout`).
- **`0`** — **ephemeral**: a fresh micro-VM per request, torn down right after. Maximum isolation,
  ideal for one-shot / untrusted code.

Placement (which VM lives on which host) is kept in shared state, so a pool behaves consistently
across an Otoroshi cluster.

## Configuration reference (`spec`)

Durations are in **milliseconds**.

| Field | Default | Description |
|---|---|---|
| `image` | – | OCI image to run (must be pullable by the smolvm host). |
| `from` | – | Alternative to `image`: a pre-packed `.smolmachine` bundle on the host. |
| `instances` | `1` | `0` = ephemeral (VM per request); `n` = warm pool size. |
| `mode` | `service` | `service` · `exec` · `service-via-exec`. |
| `runtime` | `none` | `none` · `node`. |
| `hosts` | `[]` | smolvm hosts for this machine (`http://host:8080`). |
| `hosts_url` | – | URL returning a JSON array of hosts. |
| `network` | `false` | Allow outbound network from the VM. |
| `allow_cidrs` | `[]` | Restrict egress to these CIDRs. |
| `cpus` / `memory_mb` | `2` / `512` | VM resources. |
| `storage_gb` / `overlay_gb` / `gpu` | – | Extra VM resources. |
| `mounts` / `ports` | `[]` | Host mounts `{source,target,readonly}` / port maps `{host,guest}`. |
| `service_port` | `8080` | (service) guest port to forward & proxy. |
| `readiness_path` / `readiness_timeout` | `/` / `10000` | (service) readiness probe. |
| `launch_command` | – | (service-via-exec) command that starts the server. |
| `exec_command` | – | (exec) command that reads stdin / writes stdout. |
| `code` / `code_file` / `dependencies` | – / `/app/index.js` / `[]` | (node) inline JS, where to write it, npm packages. |
| `env` / `workdir` | `{}` / – | Environment variables / working directory for commands. |
| `boot_timeout` / `request_timeout` | `60000` / `30000` | Provisioning / invocation timeouts. |
| `idle_timeout` | `300000` | Pooled VMs idle longer than this are recycled. |

## Examples

See [`examples/`](./examples):

- `smolmachine-node` — Node runtime (on-demand RPC).
- `smolmachine-node-inline` — inline JS on the entity, with an npm dependency.
- `smolmachine-service-via-exec` — start an HTTP server with a command, then proxy.

## Security

Running arbitrary code or `npm install` is powerful by design — it executes inside an isolated
micro-VM. Protect your routes with Otoroshi authentication (API key / JWT) and bound outbound
access with `allow_cidrs`.

## License

Apache-2.0 — Copyright Cloud APIM. See [LICENSE](./LICENSE).
