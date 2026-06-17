# Otoroshi smolvm FaaS extension — Spécification

> Statut : draft v0.1 — 2026-06-17
> Auteur : Mathieu Ancelin (Cloud APIM)
> Objet : extension Otoroshi orchestrant un FaaS basé sur smolvm (micro-VMs).

---

## 1. Objectif

Permettre à Otoroshi d'exposer des **fonctions serverless** exécutées dans des
micro-VMs **smolvm**. Une route Otoroshi est associée à une « fonction » ; à
chaque requête, l'extension :

1. choisit un hôte smolvm (round-robin sur une liste statique ou tirée d'une URL) ;
2. provisionne / sélectionne une micro-VM correspondant à la config de la fonction ;
3. exécute la fonction selon l'un des deux **contrats d'exécution** (voir §4) ;
4. renvoie la réponse au client via Otoroshi.

Le point d'extension est un plugin **`NgBackendCall`** (il *remplace* l'appel
backend et fabrique la réponse).

### Non-objectifs (v1)
- Pas de pool de VMs chaudes (cf. roadmap v2). v1 = **éphémère par requête**.
- Pas de cible « cloud API » (`api.smolmachines.com`). v1 = **self-hosted** uniquement.
- Pas d'exécution de code inline (`/code`). Hors périmètre v1.
- Pas d'UI d'admin dédiée (AdminExtension) au-delà du formulaire de config du plugin.

---

## 2. smolvm — faits techniques de référence

Sources : <https://smolmachines.com/docs/>, repo `smol-machines/smolvm`.
Apache-2.0. Distribué en binaire unique (`curl -sSL https://smolmachines.com/install.sh | bash`).

### 2.1 Serveur local & API HTTP

Démarrage du serveur : `smolvm serve start --listen 127.0.0.1:8080`
(ou socket unix `--listen $XDG_RUNTIME_DIR/smolvm.sock`).
Spec OpenAPI disponible via `smolvm serve openapi`.

Base path : `/api/v1`. **Pas d'auth obligatoire** en self-hosted (réseau de
confiance ; à protéger côté infra).

| Méthode | Path | Rôle |
|---|---|---|
| POST | `/api/v1/machines` | Créer une machine |
| GET | `/api/v1/machines` | Lister les machines |
| GET | `/api/v1/machines/:name` | Détails d'une machine |
| POST | `/api/v1/machines/:name/start` | Démarrer |
| POST | `/api/v1/machines/:name/stop` | Stopper (redémarrable) |
| DELETE | `/api/v1/machines/:name` | Supprimer |
| POST | `/api/v1/machines/:name/exec` | Exécuter une commande |
| POST | `/api/v1/machines/:name/exec/stream` | Exec en streaming (SSE) |
| PUT | `/api/v1/machines/:name/files/*path` | Upload fichier |
| GET | `/api/v1/machines/:name/files/*path` | Download fichier |
| GET | `/api/v1/machines/:name/logs` | Logs (SSE) |
| POST | `/api/v1/machines/:name/images/pull` | Pull image OCI |

### 2.2 Corps de création de machine (POST /machines — `CreateMachineRequest`)

> ✅ **Schéma validé contre l'OpenAPI réel de smolvm 1.0.4** (`smolvm serve openapi`)
> et contre le serveur en marche. La doc `AGENTS.md` publiée était inexacte sur
> plusieurs noms de champs — ce sont les noms ci-dessous qui font foi.

```json
{
  "name": "myvm",
  "image": "python:3.12-alpine",
  "from": "./app.smolmachine",
  "cpus": 2,
  "memoryMb": 1024,
  "storageGb": 40,
  "overlayGb": 4,
  "network": true,
  "networkBackend": "virtio-net",
  "gpu": false,
  "allowedCidrs": ["10.0.0.0/8"],
  "mounts": [{ "source": "/host/src", "target": "/app", "readonly": false }],
  "ports": [{ "host": 18080, "guest": 8080 }],
  "registryRef": "...", "registryIdentityToken": "...",
  "secrets": { "DB": { "from_env": "PROD_DB_URL" } },
  "restart": { }
}
```

Points clés (différences vs l'ancienne doc) :
- `memoryMb` (pas `memory`), `storageGb`, `overlayGb` (pas `storage`/`overlay`).
- `network` est un **booléen** (egress on/off), pas un objet ; pas de `net`.
- `mounts` = tableau d'**objets** `{source,target,readonly}` (pas `["h:g"]`).
- `ports` = tableau d'**objets** `{host:int, guest:int}` (pas `["h:g"]`).
- **Pas d'egress par host** : seul `allowedCidrs` existe (CIDR). Le « valid host /
  allow_hosts » demandé **n'est pas supporté par l'API** → on ne l'expose pas.
- **Pas de `env` / `workdir` / `entrypoint` / `cmd` à la création.** Les variables
  d'env se passent par `exec`/`run`. ⇒ en mode `service`, on **ne peut pas injecter
  d'env au niveau machine** (les baker dans l'image).
- `networkBackend` ∈ {`tsi` (défaut, sortie seule), `virtio-net`}. **Les ports
  publiés exigent `virtio-net`** (TSI n'a pas de chemin entrant) → indispensable au
  mode `service`. (notre `buildSpec` force `virtio-net` dès qu'un port est mappé.)
- `secrets` via corps HTTP : **refusés** (scope « untrusted ») → passer par `env` (exec).

**Images** : réf OCI, archive locale, rootfs déballé, ou `from` (pack `.smolmachine`).

### 2.3 Exec (POST /machines/:name/exec)

```json
{ "command": ["cat"], "stdin": "...", "env": [{"name":"K","value":"V"}],
  "workdir": "/app", "timeoutSecs": 30 }
```
Réponse (`ExecResponse`) : `{ "exitCode": 0, "stdout": "...", "stderr": "..." }`
(stdout/stderr en UTF-8 ; pour du binaire, base64 dans la commande). Variante SSE :
`POST .../exec/stream`.

> ✅ **`stdin` est confirmé supporté** par le serveur local (`stdin: string|null` dans
> l'OpenAPI 1.0.4) — le mode `exec` repose dessus. Champs réels : `command` (requis),
> `stdin`, `env: [{name,value}]`, `workdir`, `timeoutSecs`, `secrets`.

### 2.4 Cycle de vie & cold-start (observé en live)
- Lifecycle : `create → start → exec×N → stop → delete`. Réutiliser une machine est
  recommandé (« une nouvelle machine par commande = lent »).
- 🔍 **L'image est pull/matérialisée paresseusement à l'`exec`/`run`, pas au
  `create`/`start`** (vérifié : `start` renvoie `state:running` + `pid` sans réseau ;
  l'`exec` déclenche le `pull`). ⇒ le `boot_timeout` doit couvrir le pull, qui a lieu
  au premier exec/proxy. ⇒ **conséquence forte pour le mode `service`** : `create+start`
  ne lance pas tout seul le workload de l'image ; voir §4.1 et §10.
- **Aucun snapshot / fast-boot / pooling intégré** → warm pool à notre charge (v2).
- Endpoints additionnels utiles : `GET /capacity`, `POST /machines/:id/run`
  (image+command en un coup), `POST /machines/:name/resize`, `GET/PUT .../files/*`.

---

## 3. Architecture

### 3.1 Vue d'ensemble

```
                         ┌─────────────────────────────────────────┐
   requête client  ─────▶│ Otoroshi route                          │
                         │  └─ plugin SmolVmFunctionBackend         │
                         │       (NgBackendCall, par requête)       │
                         └───────────────┬─────────────────────────┘
                                         │ demande une VM + exécute
                                         ▼
                         ┌─────────────────────────────────────────┐
                         │ SmolVmEngine  (singleton, start(env))    │
                         │  - HostRegistry (liste statique/URL+cache)│
                         │  - round-robin placement + health        │
                         │  - placement map machine→host (affinité) │
                         │  - v1: provision éphémère / v2: warm pool │
                         │  - SmolVmClient (env.Ws → /api/v1/...)    │
                         └───────────────┬─────────────────────────┘
                                         │ HTTP /api/v1
                         ┌───────────────▼─────────┐   ┌───────────┐
                         │ smolvm serve (hôte A)    │   │ hôte B... │
                         └──────────────────────────┘   └───────────┘
```

### 3.2 Composants

- **`SmolVmFunctionBackend` (plugin `NgBackendCall`)** — façade par requête.
  - `steps = Seq(NgStep.CallBackend)`, `useDelegates = false`,
    `categories = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Integrations)`,
    `visibility = NgUserLand`, `multiInstance = true`.
  - lit la config via `ctx.cachedConfig(internalName)(SmolVmFunctionConfig.format)`.
  - délègue tout au `SmolVmEngine`, puis fabrique la réponse via
    `inMemoryBodyResponse` / `sourceBodyResponse`.

- **`SmolVmEngine` (singleton)** — état durable, initialisé dans `start(env)`
  (pattern du template dynamic-js-modules qui enregistre son runtime dans `start`).
  Responsabilités :
  - **HostRegistry** : liste d'hôtes statique (config) ou tirée d'une URL (cache
    Scaffeine + TTL, comme `getCode` du template), avec health-check basique
    (`GET /api/v1/machines` ou endpoint santé) pour écarter les hôtes morts.
  - **Placement** : round-robin **au moment de créer une VM** (pas par requête).
  - **Affinité** : table `machineName → host`. Tous les appels suivants
    (`exec`/`stop`/`delete`/proxy) retapent le **même** hôte (une VM vit sur un seul hôte).
  - **Provisioning v1 (éphémère)** : `create → start → (exec|proxy) → delete` par requête.
  - **Provisioning v2 (pool)** : VMs chaudes indexées par
    `hash(image+network+resources+ports+...)`, idle-timeout + GC (job Otoroshi).

- **`SmolVmClient`** — wrapper HTTP sur l'API smolvm via `env.Ws`
  (méthodes : `createMachine`, `start`, `stop`, `delete`, `exec`, `execStream`,
  `proxy`, `pullImage`, `putFile`, `health`). Timeouts configurables.

### 3.3 Où vit l'état
Le pool / la table d'affinité / le registre d'hôtes **ne peuvent pas** vivre dans
le plugin (instancié par requête). Ils vivent dans `SmolVmEngine` (singleton
process-wide). Le GC v2 sera un `Job` Otoroshi (`NgStep.Job`) ou un scheduler interne.

---

## 4. Contrats d'exécution

Une fonction déclare son **mode**. Les deux modes partagent le moteur (registre,
affinité, provisioning, isolation) ; seul le « dernier mètre » diffère.

### 4.1 Mode `service` (HTTP-in-VM + proxy) — chemin perf/streaming

L'image OCI lance un serveur HTTP (son `CMD`) qui écoute sur un port ; on le forward
via `ports:[{host:<auto>,guest:<service_port>}]` + `networkBackend:virtio-net`.
Otoroshi **reverse-proxifie**. ✅ Validé en réel (whoami, cf. §10).

Flux par requête (v1 éphémère) :
1. `create` (image + resources + `ports` + virtio-net) → **pré-pull best-effort** →
   `start` sur l'hôte choisi (le `CMD` de l'image = le serveur, lancé au boot).
2. **Sonde de readiness** : poll TCP/HTTP sur `http://<host>:<hostPort><readinessPath>`
   jusqu'à succès ou timeout (pattern startup-probe Cloud Run / queue-proxy Knative).
3. **Proxy** : `env.Ws.url("http://<host>:<hostPort><path>?<query>")` avec méthode,
   headers (filtrés), body **streamé** (`ctx.request.body`), réponse renvoyée en
   `sourceBodyResponse(status, headers, body)` (streaming natif, binaire OK).
4. `delete` la VM en `andThen` (best-effort, hors chemin critique de réponse).

Avantages : streaming, binaire, keep-alive, codes HTTP natifs, concurrence réglable.
Coût v1 : boot+readiness+teardown par requête (assumé ; pool en v2).

### 4.2 Mode `exec` (stdin JSON → stdout JSON) — chemin simple/one-shot

= classic-watchdog OpenFaaS. L'image contient un exécutable/script qui lit la
requête sérialisée sur **stdin** et écrit la réponse sur **stdout**.

Sérialisation requête (proposition, à figer) :
```json
{ "method": "POST", "path": "/x", "query": {"a":"b"},
  "headers": {"content-type":"application/json"},
  "body_base64": "..." }
```
Réponse attendue sur stdout :
```json
{ "status": 200, "headers": {"content-type":"application/json"},
  "body_base64": "..." }   // ou "body": "..." pour du texte
```

Flux par requête (v1 éphémère) :
1. `create → start` (souvent `net:false`, image minimale).
2. `POST /exec` avec `command = [entrypoint]` et **`stdin = <requête JSON>`**.
3. parser `stdout` → construire `inMemoryBodyResponse(status, headers, body)`.
   `exitCode != 0` ou `stdout` invalide → 502 + `stderr` en diagnostic.
4. `delete` en `andThen`.

> 🔴 Dépend du support `stdin` par l'exec **local** (cf. §2.3). Plan de repli si absent :
> `PUT /files/request.json` puis `exec` d'un wrapper qui lit le fichier et écrit la
> réponse sur stdout (ou dans un fichier relu via `GET /files`).

Limites assumées : process/VM par requête, pas de streaming (sauf `/exec/stream`),
binaire/gros corps en base64 (coût mémoire + ~33 %).

---

## 5. Modèle de configuration du plugin

Case class `SmolVmFunctionConfig extends NgPluginConfig` (+ `Format`, `configFlow`,
`configSchema`). Champs (noms JSON, snake_case façon Otoroshi) :

Case class `SmolVmFunctionConfig extends NgPluginConfig`. Durées en **millisecondes** dans le JSON.

| Champ (JSON) | Type | Défaut | Mapping smolvm 1.0.4 |
|---|---|---|---|
| `mode` | `"service" \| "exec"` | `"service"` | contrat d'exécution (§4) |
| `hosts` | `[string]` | `[]` | liste statique d'hôtes `http://h:8080` |
| `hosts_url` | `string?` | – | URL renvoyant un tableau JSON d'hôtes |
| `hosts_refresh` | ms | `60000` | TTL cache de `hosts_url` |
| `image` | `string` | – | `image` (réf OCI / archive / rootfs) |
| `network_enabled` | bool | `false` | `network` (egress on/off) |
| `allow_cidrs` | `[string]` | `[]` | `allowedCidrs` (egress par CIDR — **pas d'egress par host dans l'API**) |
| `cpus` | int | `2` | `cpus` |
| `memory_mb` | int | `512` | `memoryMb` |
| `storage_gb` | int? | – | `storageGb` |
| `overlay_gb` | int? | – | `overlayGb` |
| `gpu` | bool | `false` | `gpu` |
| `env` | `{k:v}` | `{}` | **exec only** → `env:[{name,value}]` de l'exec (pas d'env à la création) |
| `volumes` | `[string]` | `[]` | `mounts:[{source,target,readonly}]` (parse `host:guest[:ro]`) |
| `workdir` | `string?` | – | `workdir` de l'exec (mode exec) |
| `service_port` | int | `8080` | (service) port **guest** → `ports:[{host:auto,guest}]` |
| `readiness_path` | string | `/` | (service) sonde de readiness |
| `readiness_timeout` | ms | `10000` | (service) attente max readiness |
| `exec_command` | `[string]?` | – | (exec) commande exécutée (lit stdin) |
| `request_timeout` | ms | `30000` | timeout d'invocation (exec/proxy) |
| `boot_timeout` | ms | `60000` | timeout create+**pull**+start |
| `isolation` | `"ephemeral" \| "reuse"` | `"ephemeral"` | §6 (v1 : seul `ephemeral` honoré) |

Notes : `networkBackend` n'est pas exposé — l'Engine force `virtio-net` dès qu'un port
est mappé (mode service), `tsi` sinon. `defaultConfigObject`/`configFlow`/`configSchema`
fournis pour le formulaire back-office.

---

## 6. Isolation (configurable par fonction)

- `isolation = "ephemeral"` : VM neuve par requête, détruite après → isolation max
  (le vrai intérêt microVM). **Seul mode réellement actif en v1.**
- `isolation = "reuse"` : réutilisation de VMs chaudes **au sein d'une même fonction**
  → perf, mais risque de fuite d'état entre invocations (à documenter). **v2** (dépend du pool).
- **Jamais de partage de VM entre fonctions/tenants**, quelle que soit la valeur.

---

## 7. Round-robin, hôtes & santé

- Source d'hôtes : `hosts` (statique) ∪ `hosts_url` (tirée, cachée `hosts_refresh`).
- Round-robin **sur création de VM**, pas par requête (affinité ensuite, §3.2).
- Health-check léger par hôte (`GET /api/v1/machines`) ; un hôte KO sort du roulement.
- v2 : remplacer/compléter par *least-loaded* (capacité CPU/mém) si besoin.

---

## 8. Gestion d'erreurs & timeouts

- `boot_timeout` dépassé / `create`/`start` KO → `502 Bad Gateway` + diagnostic.
- readiness non atteinte → `504 Gateway Timeout`.
- exec `exitCode != 0` ou stdout illisible → `502` + `stderr` (tronqué) en JSON.
- proxy upstream KO → relayer le statut/erreur upstream.
- `delete` best-effort en `andThen` : un échec de teardown ne doit pas casser la réponse,
  mais doit être loggué + alimenter un nettoyeur de VMs orphelines (v2 GC).
- toutes les erreurs passent par `Errors.craftResponseResult` (style Otoroshi).

---

## 9. Packaging & intégration Otoroshi

(Calqué sur `otoroshi-plugin-dynamic-js-modules`.)
- `build.sbt` : Scala `2.12.13`, sbt `1.9.8`, `organization := "com.cloud-apim"`,
  `name := "otoroshi-smolvm-extension"`, dépendance
  `"fr.maif" %% "otoroshi" % "<version>" % "provided"` (version à figer ; le template
  utilise 17.16.0). Pas de wasm4s ici (inutile).
- Découverte : le plugin est référencé sur une route par
  `"plugin": "cp:otoroshi_plugins.com.cloud.apim.plugins.smolvm.SmolVmFunctionBackend"`
  (le préfixe de package `otoroshi_plugins.*` permet aussi l'auto-référencement UI).
- `start(env)` : log de disponibilité + instanciation du `SmolVmEngine`.
- Distribution : JAR `otoroshi-smolvm-extension_2.12-<version>.jar` à déposer sur le
  classpath Otoroshi. CI GitHub Actions (comme le template).

Arborescence (réalisée) :
```
build.sbt, version.sbt, project/{build.properties,Dependencies.scala}
src/main/scala/otoroshi_plugins/com/cloud/apim/plugins/smolvm/
  plugin.scala        # SmolVmFunctionConfig + SmolVmFunctionBackend (NgBackendCall)
  engine.scala        # SmolVmEngine (hosts, round-robin, affinité, provisioning)
  client.scala        # SmolVmClient (HTTP /api/v1 via env.Ws)
  models.scala        # case classes machine/exec + JSON
src/test/scala/com/cloud/apim/plugins/smolvm/ModelsSpec.scala
examples/{exec-node,service-node}/ ; readme.md, LICENSE, NOTICE
```

---

## 10. Risques & points ouverts

État de validation (smolvm 1.0.4, serveur lancé en local) :
- ✅ **`stdin` confirmé** dans l'OpenAPI exec — mode exec viable.
- ✅ **Mode service validé end-to-end** : `create+start` de `traefik/whoami` (ports +
  `virtio-net`) → `curl http://127.0.0.1:18080/` renvoie la réponse du serveur. Le
  proxy vers `host:hostPort` fonctionne.
- ✅ Noms de champs confirmés par le serveur (`memoryMb`, `ports:[{host,guest}]`, …).

Points ouverts :
1. 🔍 **Pull paresseux** — l'image est matérialisée au premier `exec`/`run` (ou au
   `start` si déjà en cache). Mitigation : pré-pull best-effort (create→pull→start) ;
   recommander de **pré-puller les images sur les hôtes**. `boot_timeout` couvre le pull.
2. ⚠️ **Pas d'env au niveau machine** → en mode `service`, les variables d'env doivent
   être **bakées dans l'image** (l'API create n'a ni `env` ni `cmd`). L'env du config
   ne s'applique qu'au mode `exec`.
3. ⚠️ **Pas d'egress par host** dans smolvm — seul `allowedCidrs` (CIDR). Le « valid
   host » initialement souhaité n'existe pas dans l'API ; non exposé.
4. ⚠️ **Latence v1** — éphémère par requête (boot+pull+start). Assumé ; pool = v2.
5. ⚠️ **Allocation de port hôte** (service) — plage gérée par l'Engine ; risque de
   collision sous forte concurrence sur un même hôte (à durcir : pool de ports/retry).
6. ⚠️ **Sécurité de l'API smolvm** (pas d'auth en local) — protéger réseau ; mTLS via
   `env.MtlsWs` envisageable.
7. ⚠️ **VMs orphelines** si crash Otoroshi entre create et delete — reaper (v2).
8. ❓ **Filtrage des headers** hop-by-hop lors du proxy (seul `Host` est retiré en v1).
9. ❓ **`start` pull-t-il une image non cachée pour un service ?** (whoami était en
   cache lors du test) — le pré-pull explicite couvre ce cas.

---

## 11. Roadmap

- **v1** : self-hosted ; modes `service` + `exec` ; éphémère par requête ;
  round-robin + affinité + health basique ; mapping config complet ; gestion d'erreurs.
- **v2** : warm pool (signature de config) + `isolation = reuse` ; GC/reaper (Job) ;
  least-loaded LB ; métriques.
- **v3** (idées) : cible cloud `api.smolmachines.com` ; mode `code` inline (`/code`) ;
  exec/proxy en streaming SSE de bout en bout ; AdminExtension (UI machines).
