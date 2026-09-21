# Kex Agent AI

**A governed AI agent for Kafka operations.** It watches your integration processes through MCP
tools, explains what it sees, and asks a named human before it acts — every decision carrying its
confidence, its evidence and an audit trail.

Built on Spring Boot 4 / Spring AI 2, Java 25. Ships with a browser console (the **Control Center**)
served by the agent itself, no build step and no CDN.

- **Source, issues and full documentation:** https://github.com/devdownin/Kex-agent-ai
- **Licence:** GPL-3.0-or-later

## Tags

| Tag | What it is |
|---|---|
| `latest` | Most recent stable release |
| `x.y.z` | An exact release — what you pin in production |
| `sha-<short>` | A single commit, kept for traceability |

Pre-releases never move `latest` — a `docker pull` without a tag should not bring back code nobody
has finished judging.

`linux/amd64` only: an arm64 image would mean QEMU and an emulated Maven build, an order of magnitude
more build time. Each image ships an SBOM, and is scanned for vulnerabilities **before** it is
pushed — the scan runs on a locally loaded build, so a vulnerable image is never published first and
judged afterwards. After the push it is pulled back from the registry and started, because a
successful push says the layers left, not that the manifest is servable.

## Quick start

Against a Kafka SQL Explorer you already run:

```bash
docker run --rm -p 8081:8081 \
  -e ANTHROPIC_API_KEY=sk-ant-... \
  -e KEX_AGENT_API_KEY="$(openssl rand -hex 32)" \
  -e KAFKA_EXPLORER_URL=http://host.docker.internal:8080 \
  compagnonsdudev/kex-agent-ai:latest
```

Then open **http://localhost:8081** and paste the same bearer once — it lives in `sessionStorage`
and never leaves the browser. Or talk to the API directly:

```bash
curl -X POST localhost:8081/api/agent/chat \
  -H "Authorization: Bearer $KEX_AGENT_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"message":"List the topics whose name starts with demo. and tell me which ones are empty."}'
```

The whole stack — Kafka 4.3 (KRaft), Explorer with its MCP server on, and this agent wired to it —
is one `docker compose up` away from
[the repository](https://github.com/devdownin/Kex-agent-ai#-quick-start).

## Configuration

| Variable | Role |
|---|---|
| `KEX_AGENT_API_KEY` | Bearer for the agent's own API. **Without it every `/api/**` route answers `503`** — the agent refuses to serve rather than serve unauthenticated |
| `ANTHROPIC_API_KEY` | Model provider key. Absent, the agent starts and reports itself `DEGRADED` rather than pretending to be healthy |
| `KAFKA_EXPLORER_URL` | Base URL of the Kafka SQL Explorer MCP server |
| `EXPLORER_MCP_AUTH_TOKEN` | Bearer for that MCP server, when it requires one |
| `KEX_AGENT_LLM_PROVIDER` | `anthropic` (default) or `openai` — the latter also covers OpenRouter-compatible gateways |

An unreachable MCP server never blocks startup: the agent boots, says so, and keeps serving what it
still can. [Every setting, with its reasons](https://github.com/devdownin/Kex-agent-ai/blob/main/docs/CONFIGURATION.md).

## Health and observability

| Endpoint | Content |
|---|---|
| `/actuator/health` | Liveness and readiness — the only route left open, deliberately |
| `/actuator/prometheus` | Metrics, bearer required |
| `/swagger-ui.html` | The API, documented from the running instance |

The container exposes `8081` and runs as an unprivileged user (`10001:10001`).

## Persistence

`/var/lib/kex` holds everything the agent remembers outside a database: long-term facts, skills a
human approved, the operating charter, and the encrypted MCP connections created from the console.
It is declared as a volume — mount a named one over it, or those survive only as long as the
container does:

```bash
docker run --rm -p 8081:8081 -v kex_state:/var/lib/kex ... compagnonsdudev/kex-agent-ai:latest
```

With the `shared-memory` profile and a PostgreSQL datasource, memory, skills, the supervision audit
and the decision state move to the database instead — which is what you want behind a load
balancer, where a decision approved on one replica has to exist for the others. The encrypted MCP
connections stay on disk either way, so the volume is never pointless.

## What it will not do

The Kafka SQL Explorer MCP server is **read-only** — fifteen tools, none of them mutating. In front
of it the agent observes and recommends; it does not act. Where a capability can act, autonomy is
declared per capability, an execution mode can only narrow it, and anything below the confidence
floor goes back to a human. Nothing here is a default you inherit by accident: an unlisted capability
is forbidden.
