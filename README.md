<div align="center">

# 🧭 Kex Agent AI

### An AI agent that actually uses your tools — Spring Boot 4, Spring AI 2, Java 25.

[![CI](https://github.com/devdownin/Kex-agent-ai/actions/workflows/ci.yml/badge.svg)](https://github.com/devdownin/Kex-agent-ai/actions/workflows/ci.yml)
[![CodeQL](https://github.com/devdownin/Kex-agent-ai/actions/workflows/codeql.yml/badge.svg)](https://github.com/devdownin/Kex-agent-ai/actions/workflows/codeql.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/devdownin/Kex-agent-ai/badge)](https://scorecard.dev/viewer/?uri=github.com/devdownin/Kex-agent-ai)
[![Java 25](https://img.shields.io/badge/Java-25-orange)](pom.xml)
[![Spring Boot 4.1](https://img.shields.io/badge/Spring_Boot-4.1-6DB33F?logo=springboot&logoColor=white)](pom.xml)
[![Spring AI 2.0](https://img.shields.io/badge/Spring_AI-2.0-6DB33F)](pom.xml)
[![MCP](https://img.shields.io/badge/MCP-stdio_·_SSE_·_streamable--HTTP-5A45FF)](docs/MCP.md)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

[Quick start](#-quick-start) · [What you can ask it](#-what-you-can-ask-it) · [How it works](#-how-it-works) · [Docs](#-documentation) · [🇫🇷 Français](README.fr.md)

</div>

---

**Most "AI agent" demos answer from the model's memory. This one goes and looks.**

Kex Agent AI is a Spring Boot service that connects to [MCP](https://modelcontextprotocol.io) servers,
discovers the tools they expose, and hands them to a Claude model — so a question like *"which topics
received nothing today?"* becomes a real query against a real cluster, not a plausible-sounding guess.

It ships wired to [Kafka SQL Explorer](https://github.com/devdownin/Kafkaexplorer), whose MCP server
exposes 15 read-only tools over Kafka: topic listing, Flink SQL, schema inference, cross-topic key
tracing, cluster audits. One `docker compose up` and you are asking questions of your broker in
plain language.

## ⚡ Quick start

```bash
export ANTHROPIC_API_KEY=sk-ant-...
export EXPLORER_MCP_AUTH_TOKEN="$(openssl rand -hex 32)"
export KEX_AGENT_API_KEY="$(openssl rand -hex 32)"

docker compose up -d
```

Three containers: a Kafka 4.3 broker (KRaft), Kafka SQL Explorer with its MCP server switched on,
and this agent wired to it. Explorer lands on **http://localhost:8080**, the agent on
**http://localhost:8081**.

```bash
curl -X POST localhost:8081/api/agent/chat \
  -H "Authorization: Bearer $KEX_AGENT_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"message":"List the topics whose name starts with demo. and tell me which ones are empty."}'
```

```json
{"conversationId":"3f2b…","content":"Eight topics match demo.*. Three are empty: demo.returns, …"}
```

No Docker? [Run it from source](docs/CONFIGURATION.md#running-from-source) — JDK 25 and `./mvnw spring-boot:run`.

## 💬 What you can ask it

With Kafka SQL Explorer connected, the model has real verbs instead of vague recall:

| You ask | The agent calls | You get |
|---|---|---|
| *"What's in demo.orders?"* | `kex_preview_messages`, `kex_infer_schema` | Real records, plus the structure inferred from them |
| *"Where did order ORD-1042 go?"* | `kex_trace_key` | Its path across topics, hop by hop, with latency |
| *"Why is the DLQ filling up?"* | `kex_run_audit`, `kex_get_audit` | A graded diagnosis, not a metadata dump |
| *"Which consumer groups are behind?"* | `kex_consumer_lag` | Time lag — the age of the oldest unread message |
| *"Count yesterday's orders over 100 €"* | `kex_sql_query` | The Flink SQL it ran, and the rows it got back |

The model picks the tool; the server enforces the guardrails. Explorer is read-only by default, with
a deny-list, rate limiting and an audit trail — **the agent inherits those, it does not replace them.**

## 🧩 How it works

```mermaid
flowchart LR
    U([Client]) -->|Bearer + JSON| A
    subgraph A["Kex Agent AI :8081"]
        C[ChatClient] --- M[(Conversation<br/>memory)]
        C --- T[MCP tool<br/>callbacks]
    end
    C -->|Messages API| AN([Claude])
    T -->|streamable-HTTP<br/>+ Bearer| E
    T -.->|stdio / SSE| O([Any other<br/>MCP server])
    subgraph E["Kafka SQL Explorer :8080"]
        G[Guards: read-only,<br/>deny-list, rate limit, audit]
        K[15 kex_* tools]
    end
    E --> KA([Kafka cluster])
```

One request, end to end:

1. The client posts a message with its bearer token. Anything under `/api/**` is authenticated —
   the agent spends money and executes tools, so it is closed by default.
2. The `ChatClient` replays the conversation window, then asks Claude with **every MCP tool attached**.
   The tool list is re-read from the MCP clients on each request, so a server that publishes a new
   tool is picked up without a restart.
3. Claude answers, or asks for a tool. Spring AI runs the call over MCP, feeds the result back, and
   loops — capped at 20 tool calls per exchange so a confused model cannot run up a bill.
4. The answer comes back, and the exchange is appended to that conversation's memory.

**The details that matter are the boring ones**, and they are written down: why MCP clients are
initialized lazily, why servers are addressed by connection key, why the tool-call endpoint is a
loaded gun. See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## 🔌 Plug in your own MCP server

Three transports, no code:

```yaml
spring:
  ai:
    mcp:
      client:
        stdio:                       # a local process
          connections:
            filesystem:
              command: npx
              args: ["-y", "@modelcontextprotocol/server-filesystem", "/data"]
        streamable-http:             # a remote server
          connections:
            my-tools:
              url: https://mcp.internal.example.com
              endpoint: /mcp

kex:
  mcp:
    bearer-tokens:                   # if it needs authentication
      - url-prefix: https://mcp.internal.example.com
        token: ${MY_MCP_TOKEN}
```

Restart, then `GET /api/agent/mcp/servers` tells you what it found. The whole story — including what
MCP is, if this is your first one — is in [`docs/MCP.md`](docs/MCP.md).

## 🔭 API

| Method | Route | What it does |
|---|---|---|
| `POST` | `/api/agent/chat` | Ask a question, get an answer, the `conversationId`, and the tools it used |
| `POST` | `/api/agent/chat/structured` | Same, answered as JSON matching a schema you supply |
| `POST` | `/api/agent/chat/stream` | Same, streamed as named SSE events: `conversation`, `token`, `tool`, `error` |
| `DELETE` | `/api/agent/conversations/{id}` | Forget a conversation |
| `GET` | `/api/agent/mcp/servers` | Which MCP servers are connected, and what they expose |
| `POST` | `/api/agent/mcp/servers/{connection}/tools/{tool}` | Call a tool directly, no model involved |
| `GET` | `/api/agent/mcp/servers/{connection}/resources` | List a server's resources |
| `GET` | `/api/agent/mcp/servers/{connection}/resource?uri=…` | Read one |
| `POST` `GET` `DELETE` | `/api/agent/knowledge` | Feed, search and prune the knowledge base (when enabled) |

The OpenAPI description is served at `/v3/api-docs`, with Swagger UI at `/swagger-ui.html`. Both are
open: the *shape* of the API is already public in this repository, and hiding it would only make the
UI unusable in a browser. What is protected is everything that acts or costs money.

Every route under `/api/**` requires `Authorization: Bearer $KEX_AGENT_API_KEY`. `/actuator/health`
stays open for container probes.

The stream emits a `tool` event each time one finishes — name, duration, whether it failed — so the
connection is never silent for the minute a tool may take, and a UI can show what the agent is doing.
The blocking route returns the same list in its `tools` field. The stream opens with a `conversation`
event carrying the id — a client that did not supply one can
still follow up and clean up — and a failure arrives as an `error` event rather than a socket that
simply stops, which a client cannot tell apart from a finished answer. Exchanges are capped at
`kex.agent.request-timeout` (120s by default), tool rounds included; the blocking route answers
`504` past it.

## 🔐 Security posture

- **Closed by default.** No `kex.agent.api-key` configured → `/api/**` answers `503`, not `200`.
  An agent that spends tokens and executes tools does not ship open.
- **Loopback by default.** Compose binds every port to `127.0.0.1`; `BIND_ADDR=0.0.0.0` is a decision
  you make, not one you inherit.
- **Tokens stay where they belong.** The MCP bearer is injected only on requests matching the
  declared URL prefix, so one server's credential never reaches another.
- **The direct tool endpoint has no model in the loop.** Authorization is entirely on the caller.
  Read [`docs/ARCHITECTURE.md#the-direct-tool-endpoint`](docs/ARCHITECTURE.md#the-direct-tool-endpoint)
  before exposing it.

## 📚 Documentation

| Document | What's in it |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Components, request lifecycle, and the design decisions with their reasons |
| [`docs/MCP.md`](docs/MCP.md) | What MCP is, the three transports, connecting a server, writing your own |
| [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md) | Every property and environment variable, the profiles, running from source |
| [`docs/OBSERVABILITE.md`](docs/OBSERVABILITE.md) | Cost and tool metrics, scraping them, what to alert on |
| [`docs/CONNAISSANCE.md`](docs/CONNAISSANCE.md) | The knowledge base: why it is off, how to turn it on, how to feed and debug it |

## 🧪 Tests

```bash
./mvnw verify
```

78 tests, no network, no secrets. Including an MCP integration test that stands up a **real**
streamable-HTTP server behind a bearer token and drives the actual client through handshake,
`tools/list`, `tools/call`, `resources/list` and `resources/read` — the transport is exercised, not
mocked.

CI additionally builds the Docker image and smoke-tests it — the container must start with **no MCP
server reachable at all**, refuse an unauthenticated call, and serve an authenticated one — then
brings a real compose stack up (`compose/smoke.yml`: the agent plus a stub MCP server) and checks
the agent discovers the stub, its tool, and calls it through the network with the shared bearer.

## 🗺️ Stack

| Component | Version |
|---|---|
| Java | 25 |
| Spring Boot | 4.1.1 |
| Spring AI | 2.0.1 |
| Model | Anthropic (swap the starter for OpenAI, Ollama, Bedrock…) |
| MCP | `spring-ai-starter-mcp-client` — stdio, SSE, streamable-HTTP |

## 📖 Give it what your team knows

MCP tools say what **is** in the cluster. They do not say what your team **knows**: the topic naming
convention, the runbook for a filling DLQ, why `demo.orders` keeps 7 days. Turn the knowledge base
on and every question is searched against it first, with the relevant passages added to the prompt.

```bash
curl -X POST localhost:8081/api/agent/knowledge \
  -H "Authorization: Bearer $KEX_AGENT_API_KEY" -H 'Content-Type: application/json' \
  -d '[{"text":"demo.orders keeps 7 days — audit requirement, see ticket OPS-412.",
        "metadata":{"source":"runbook"}}]'
```

It ships **off**: retrieval needs an embedding model, which is infrastructure the agent does not
impose to start. [`docs/CONNAISSANCE.md`](docs/CONNAISSANCE.md) has the three ways to provide one,
and the `GET` route that runs the exact same search the model sees — so a disappointing answer is
diagnosed against the base, not guessed at.

## 🛡️ Supply chain

Every release image is built from a tagged commit, published multi-arch to GHCR with provenance and
an SBOM. The build itself emits a CycloneDX SBOM (`target/classes/META-INF/sbom/`), CI enforces the
license headers, the format, and a coverage floor, CodeQL runs per pull request and weekly, OpenSSF
Scorecard weekly, and Dependabot watches Maven, Actions and Docker. Every GitHub Action is pinned by
commit SHA.

## 🤝 Contributing

[`CONTRIBUTING.md`](CONTRIBUTING.md) for the workflow and the house rules, [`SECURITY.md`](SECURITY.md)
for reporting a vulnerability — privately, never as an issue.

## 📄 License

GPL-3.0 — see [LICENSE](LICENSE).
