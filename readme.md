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
`cp:otoroshi_plugins.com.cloud.apim.plugins.smolvm.SmolVmFunctionBackend`).

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

## License

Apache-2.0 — Copyright Cloud APIM. See [LICENSE](./LICENSE).
