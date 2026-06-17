# Examples

Two minimal functions, one per execution contract. Each ships a `Dockerfile`, the
function code, and a `route.json` (the plugin block to paste into an Otoroshi route's
`plugins` array).

> The image referenced in `route.json` must be reachable by the smolvm host — push it
> to a registry the host can pull from (Docker Hub, ghcr.io, a local registry…), or use
> a `from` pre-packed `.smolmachine`. `smolvm-*-node:latest` below is a placeholder tag.

## `exec-node/` — stdin → stdout

The function reads the request envelope on **stdin** and writes the response envelope
on **stdout** (classic-watchdog style). The micro-VM stays alive (`sleep infinity` as
PID 1) so the plugin can `exec` the handler per request.

```bash
cd examples/exec-node
docker build -t smolvm-exec-node:latest .
# docker push <registry>/smolvm-exec-node:latest   # make it reachable by the smolvm host
```

Request envelope (stdin): `{"method","path","query","headers","body_base64"}`
Response envelope (stdout): `{"status","headers","body_base64"}` (or `"body"` for text).

## `service-node/` — HTTP server proxied

The image runs a long-running HTTP server; Otoroshi reverse-proxies to the forwarded
port. Supports streaming and binary natively. The plugin waits on `readiness_path`
(`/health`) before forwarding the first request.

```bash
cd examples/service-node
docker build -t smolvm-service-node:latest .
# docker push <registry>/smolvm-service-node:latest
```

## Wiring it up

1. Run a smolvm host: `smolvm serve start --listen 0.0.0.0:8080`
2. Start Otoroshi with `otoroshi-smolvm-extension_2.12-*.jar` on the classpath.
3. Create a route, add the plugin block from the relevant `route.json`, set `hosts` to
   your smolvm host(s) and `image` to your pushed image.
4. Call the route — a micro-VM is booted per request, runs the function, returns the
   response, and is torn down (v1 ephemeral).
