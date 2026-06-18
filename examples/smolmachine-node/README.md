# `smolmachine-node/` — node runtime (RPC) — v2

A `SmolMachine` entity with `runtime: node` exposes a small HTTP → `exec` protocol over a
pool of live `node:22-alpine` micro-VMs (mirrors the `smolvm-sdk` node preset). The pool
is created lazily on the first request (up to `instances`), reused across requests, and
idle instances are reaped after `idle_timeout`. Placement (instance → host) is kept in the
external state (Redis) so it works in a cluster.

## Build the keepalive image

```bash
cd examples/smolmachine-node
docker build -t smolvm-node-keepalive:latest .
# docker push <registry>/smolvm-node-keepalive:latest   # make it reachable by the smolvm host
```

## Wire it up

1. Run a smolvm host: `smolvm serve start --listen 0.0.0.0:8080`.
2. Enable the extension: `CLOUD_APIM_EXTENSIONS_SMOLMACHINE_ENABLED=true` (+ optionally
   `CLOUD_APIM_EXTENSIONS_SMOLMACHINE_STATE_URI=redis://...` for a dedicated cluster state).
3. Create the entity (`smolmachine.json`) via the back-office **SmolVM Machines** page, or:
   ```bash
   curl -X POST 'http://otoroshi-api/apis/smolvm.extensions.cloud-apim.com/v1/smol-machines' \
     -u admin-api-key:secret -H 'content-type: application/json' -d @smolmachine.json
   ```
4. Add `route.json` to an Otoroshi route's `plugins` array.

## Node RPC protocol (request path selects the operation)

```bash
# run code (node -e)
curl -X POST https://route/run        -d '{"code":"console.log(40+2)"}'      # -> {"exitCode":0,"stdout":"42\n","stderr":""}
# evaluate an expression
curl -X POST https://route/eval       -d '{"expression":"1+2+3"}'            # -> {"exitCode":0,"stdout":"6\n",...}
# install packages (needs network=true)
curl -X POST https://route/npm/install -d '{"packages":["lodash"]}'
# run npx
curl -X POST https://route/npx        -d '{"args":["cowsay","hi"]}'
# upload a file then run it
curl -X PUT  https://route/files/app.js --data-binary @app.js
curl -X POST https://route/run-file   -d '{"path":"/app.js"}'
# node version
curl https://route/version
```

> Running arbitrary code / `npm install` is RCE-by-design (isolated in a micro-VM). Protect
> the route with otoroshi auth (api-key / JWT) and bound egress with `spec.allow_cidrs`.
