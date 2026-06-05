<p align="center">
  <img src="https://img.shields.io/badge/Spring_Boot-4.0-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 4">
  <img src="https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white" alt="Java 25">
  <img src="https://img.shields.io/badge/AMQP-1.0-FF6600" alt="AMQP 1.0">
  <img src="https://img.shields.io/badge/RabbitMQ-4.2-FF6600?logo=rabbitmq&logoColor=white" alt="RabbitMQ 4.2">
  <img src="https://img.shields.io/badge/Keycloak-26-4D4D4D?logo=keycloak&logoColor=white" alt="Keycloak 26">
  <img src="https://img.shields.io/badge/license-MIT-blue" alt="MIT License">
</p>

<h1 align="center">OAuth2-secured AMQP 1.0 messaging</h1>

<p align="center">
  A Spring Boot demo that secures <strong>both</strong> broker connections and per-message permissions with OAuth2,<br>
  over the <strong>AMQP 1.0</strong> protocol.<br>
  No <code>guest/guest</code>. No application-level ACLs. Just tokens, scopes, and a broker that refuses to lie.
</p>

<p align="center">
  <a href="https://lukasgrigis.dev/blog/rabbitmq-oauth2-amqp-1-0/"><strong>Read the companion blog post &rarr;</strong></a>
</p>

---

> This is the **AMQP 1.0** port of [`spring-oauth2-amqp-0.9.1`](https://github.com/lukas-grigis/spring-oauth2-amqp-0.9.1)
> (which uses AMQP 0.9.1). Same security model, same topology, same payoff — adapted to AMQP 1.0's native client,
> addressing, and token lifecycle. See [What changed from the AMQP 0.9.1 version](#what-changed-from-the-amqp-091-version).

Most RabbitMQ tutorials authenticate services with `guest/guest` (or some other shared password) and enforce
who-can-do-what inside the application. Both habits are bad: shared credentials are a blast radius, and
application-level ACLs are trust-on-first-read.

This repo shows a cleaner model — every service authenticates to the broker with an OAuth2 access token, and RabbitMQ
authorizes each publish and consume based on the scopes inside that token. When the reporter service (which is only
supposed to read results) tries to publish, the broker rejects the attempt. Not the application. The broker.

## Architecture

```
                          Traefik (reverse proxy)
                                   │  /auth
                                   ▼
Keycloak (HTTP, behind Traefik) ── Postgres
  ├── client: dispatcher  (scopes: jobs_write)
  ├── client: worker      (scopes: jobs_read, results_write)
  └── client: reporter    (scopes: results_read)

RabbitMQ 4.2 (native AMQP 1.0 + OAuth2 plugin → JWKS via Traefik over TLS)
  ├── exchange: jobs      (dispatcher writes, worker consumes via jobs.in)
  └── exchange: results   (worker writes, reporter consumes via results.out)

Services (Spring Boot 4, Spring AMQP 1.0 client)
  ├── dispatcher (:8080)  REST trigger → publish to jobs
  ├── worker              consume jobs → publish to results
  └── reporter            consume results (publish attempts rejected by broker)
```

### How the pieces fit together

Each service connects to the broker over **AMQP 1.0** using the
[RabbitMQ AMQP 1.0 Java client](https://github.com/rabbitmq/rabbitmq-amqp-java-client) (via Spring AMQP's
`spring-rabbitmq-client` module). The client is configured with Keycloak's token endpoint and the service's
`client_credentials`, so it fetches an access token itself and presents it as the AMQP **SASL** credential (the JWT is
the password; AMQP 1.0 always uses SASL). The broker validates the token's signature against Keycloak's JWKS, checks
that its audience is `rabbitmq`, and the scopes inside determine which exchanges and queues the client can touch.

The scope claim carries friendly aliases (`jobs_write`, `results_read`) that the broker's OAuth2 plugin maps to its
native permission format — **including the routing key**: `jobs_write` expands to `rabbitmq.write:*/jobs/job.*`, so the
dispatcher may publish to the `jobs` exchange but only with `job.*` routing keys. Keycloak stays readable; RabbitMQ
stays expressive. See [Routing keys](#routing-keys) for the full convention.

Two details make the validation real:

- **Audience** — every token carries `aud: rabbitmq` (added by a Keycloak audience mapper), because the broker's
  `verify_aud` is on by default.
- **TLS for JWKS** — the OAuth2 plugin *requires* an `https` JWKS URL. Rather than putting a certificate on Keycloak
  (which runs plain HTTP), the broker fetches the keys through **Traefik**, which terminates TLS with its built-in
  self-signed certificate. Nothing is committed; the broker uses `verify_none` for that internal hop.

Each service wires up the AMQP 1.0 stack by hand — there is no Spring Boot auto-configuration for AMQP 1.0, so the
`Environment` (with its OAuth2 settings), the `SingleAmqpConnectionFactory`, the `RabbitAmqpTemplate`, and the
`RabbitAmqpListenerContainerFactory` are all `@Bean`s in an `AmqpConfiguration`. The client mints the token, caches it,
and — crucially — **renews it on the live connection** before it expires (see
[Token expiry and in-place refresh](#token-expiry-and-in-place-refresh)).

> The legacy 0.9.1 `RabbitTemplate` and `Channel` classes are still on the classpath (pulled in transitively by
> `spring-rabbitmq-client` → `spring-rabbit`), but Spring Boot 4 moved `RabbitAutoConfiguration` into the separate
> `spring-boot-amqp` module — which this project deliberately does **not** depend on. That's what keeps Boot from
> auto-creating a stray legacy `CachingConnectionFactory`/`rabbitTemplate` (pointed at `guest/guest`) that would clash
> with the AMQP 1.0 beans. Adding `spring-boot-starter-amqp` would silently reintroduce it.

## Quick start

You need [Docker](https://docs.docker.com/get-docker/) and [mise](https://mise.jdx.dev/). mise handles Java, Maven, and
Node versions automatically, so there's no manual setup.

```bash
git clone https://github.com/lukas-grigis/spring-oauth2-amqp-1.0.git
cd spring-oauth2-amqp-1.0
mise run demo
```

One command. It builds the Maven project, starts the infrastructure (Traefik, Postgres, Keycloak, RabbitMQ),
provisions the Keycloak realm, then boots all three Spring Boot services. First run takes a minute while containers
download.

When it's up, submit a job:

```bash
curl -X POST http://localhost:8080/jobs \
  -H 'Content-Type: application/json' \
  -d '{"payload":"hello amqp 1.0"}'
```

Then watch the logs:

```bash
tail -f .logs/{dispatcher,worker,reporter}.log
```

You'll see the job flow through: dispatcher publishes → worker consumes from `jobs.in` and publishes the result →
reporter consumes from `results.out` and logs it.

Press `Ctrl+C` in the `mise run demo` terminal to stop everything.

## The payoff

The point of the demo is to prove that broker-level authorization actually holds. The reporter service (scope:
`results_read`) can consume results but the moment it tries to *publish*, RabbitMQ refuses — over AMQP 1.0 the broker
ends the session with the OASIS-standard condition `amqp:unauthorized-access`. The rejection comes from the broker, not
from any `if (user.hasPermission(...))` in application code.

You don't have to take that on faith — the [integration tests](#tests) prove it against the live stack: every service
may publish exactly what its scopes allow, and the reporter (publish), the dispatcher (cross-exchange), and the worker
(out-of-scope routing key) are each refused.

## Routing keys

Both exchanges are `topic` exchanges, and the routing key is part of the contract — and part of the authorization.
The convention is `<domain>.<entity>.<event>`, lowercase and dot-delimited, most-stable segment first:

```
jobs (topic)      dispatcher publishes  job.submitted   →  queue jobs.in    binds job.#
results (topic)   worker publishes      result.ready    →  queue results.out binds result.#
```

Producers always publish a **fully-qualified** key (`job.submitted`); consumers bind with **wildcards** (`#` = zero or
more words, `*` = exactly one). Because each exchange owns its own key namespace (`job.*` vs `result.*`), the two stay
visibly distinct, and the OAuth2 write scopes carry the routing key (`rabbitmq.write:*/jobs/job.*`) — so the broker
enforces not just *which exchange* a service may publish to, but *which routing keys*.

Over AMQP 1.0 there are no channels and no `basic.publish`; a producer attaches a link to a **target address** and a
consumer attaches to a **source address**. RabbitMQ's [v2 address format](https://www.rabbitmq.com/docs/amqp#addresses)
maps cleanly onto the same exchange/routing-key/queue model:

```
publish job.submitted to jobs  →  target  /exchanges/jobs/job.submitted
consume from jobs.in           →  source  /queues/jobs.in
```

Spring's `RabbitAmqpTemplate` and `@RabbitListener` take the familiar `exchange` / `routingKey` / `queue` arguments and
build those v2 addresses for you — so the application code reads exactly like the 0.9.1 version.

When to reach for what:

| Change                | Add it when…                                                                     |
|-----------------------|----------------------------------------------------------------------------------|
| **A new routing key** | there's a new *event type* a subset of consumers cares about (`job.failed`)      |
| **A new binding**     | a new consumer wants a subset of existing events (a `jobs.audit` queue, `job.#`) |
| **A new exchange**    | you cross a *domain* boundary (jobs vs. results)                                 |

The integration test exercises this directly: the worker may publish `result.ready` but is refused when it tries
`internal.audit`, because that key falls outside its `result.*` scope. (Note the AMQP 1.0 nuance: the exchange-level
write check happens when the link *attaches*, while the routing-key check happens on the first message *transfer* — so
the worker's link to `results` attaches fine and is refused only when it sends `internal.audit`.)

## How the RabbitMQ OAuth2 plugin is configured

`support/rabbitmq/rabbitmq.conf` enables the OAuth2 plugin alongside the internal backend (kept so the management UI
stays usable with `admin/admin`). **This file is identical to the AMQP 0.9.1 version** — OAuth2 authentication and
authorization are protocol-agnostic, and AMQP 1.0 needs no plugin (it is native in RabbitMQ 4.x):

```properties
auth_backends.1=rabbit_auth_backend_internal
auth_backends.2=rabbit_auth_backend_oauth2
auth_oauth2.resource_server_id=rabbitmq

# The plugin requires an https JWKS URL. Keycloak runs plain HTTP, so the broker fetches the keys through
# Traefik, which terminates TLS with its built-in self-signed cert (verify_none for this internal hop).
auth_oauth2.jwks_uri=https://traefik/auth/realms/amqp-demo/protocol/openid-connect/certs
auth_oauth2.https.peer_verification=verify_none
auth_oauth2.https.hostname_verification=none

# Aliases map clean Keycloak scope names to native permissions. The 3rd segment on write scopes restricts
# the routing key (glob match — "job.*" = routing keys starting with "job.").
auth_oauth2.scope_aliases.jobs_write=rabbitmq.write:*/jobs/job.*
auth_oauth2.scope_aliases.jobs_read=rabbitmq.read:*/jobs.in rabbitmq.configure:*/jobs.in
auth_oauth2.scope_aliases.results_write=rabbitmq.write:*/results/result.*
auth_oauth2.scope_aliases.results_read=rabbitmq.read:*/results.out rabbitmq.configure:*/results.out
```

The scope aliases keep Keycloak scope names clean (`jobs_write`) while the broker operates in its native permission
vocabulary (`rabbitmq.{permission}:{vhost}/{resource}/{routing-key}`).

## How the AMQP 1.0 client uses OAuth2 for the connection

There is no Spring Security `OAuth2AuthorizedClientManager` here. The RabbitMQ AMQP 1.0 client has first-class OAuth2
support, so token acquisition lives on the `Environment`'s connection settings — Boot owns nothing of the AMQP stack,
because there is no AMQP 1.0 auto-configuration to begin with:

```java
Environment environment = new AmqpEnvironmentBuilder()
    .connectionSettings()
        .host("localhost").port(5672).virtualHost("/")
        .oauth2()
            .tokenEndpointUri("http://localhost/auth/realms/amqp-demo/protocol/openid-connect/token")
            .clientId("worker").clientSecret("worker-secret")
            .grantType("client_credentials")
            .parameter("scope", "jobs_read results_write")
            .shared(true)            // one token, refreshed once, shared by all connections
        .connection()
    .environmentBuilder()
    .build();
```

*(Simplified — the real `AmqpConfiguration` binds all of this from validated `app.amqp` properties.)* The client fetches
the token over plain HTTP, and its token requester pins the JDK HTTP client to HTTP/1.1 — so it sidesteps the
cleartext-h2c-through-Traefik deadlock that the 0.9.1 version had to work around with Apache HttpClient 5. The token
becomes the AMQP SASL password; the broker validates it on connect and on every operation.

## Token expiry and in-place refresh

This is the one place where AMQP 1.0 genuinely differs from 0.9.1, and the demo leans into it.

- **On AMQP 0.9.1**, an expired token does not drop the connection — the broker simply refuses every subsequent
  operation with `ACCESS_REFUSED`, and the 0.9.1 demo renews the token in place with the `update-secret` method (driven
  by a `CredentialsRefreshService`).
- **On AMQP 1.0**, when the token on a live connection expires, **the broker disconnects the client.** So a long-lived
  connection *must* refresh proactively. The renewal mechanism is also different: the client sends an HTTP-over-AMQP 1.0
  [`PUT /auth/tokens`](https://www.rabbitmq.com/docs/oauth2#amqp10) request over the existing connection — there is no
  `update-secret` in AMQP 1.0.

The good news: configuring `oauth2()` is all you need. The RabbitMQ AMQP 1.0 client does the whole dance
automatically — it acquires the token, schedules a refresh at **~80% of the token's lifetime** (the same ratio the
0.9.1 demo used), and re-authenticates the live connection via `PUT /auth/tokens`. No reconnect, no dropped messages,
and no application code. The explicit `CredentialsProvider` + `CredentialsRefreshService` wiring from the 0.9.1 version
is simply *gone*, replaced by one `oauth2()` block.

The [integration tests](#tests) prove the broker half of this story directly: with the realm's token lifespan shortened
to 15s, a live AMQP 1.0 connection is **disconnected** once its token expires — which is exactly what the services'
in-place refresh exists to prevent.

## Keycloak runs over plain HTTP

Keycloak runs `start-dev` over plain HTTP behind Traefik (at `/auth`) — no certificate on Keycloak itself. Two small
consequences, both handled automatically by `mise run demo` / `mise run keycloak:setup`:

- Keycloak is backed by **Postgres** (not the in-memory H2) so the next point survives a restart.
- The `master` realm refuses plain-HTTP admin calls by default. The setup flips `ssl_required` to `NONE` on `master`
  (`UPDATE realm SET ssl_required = 'NONE'` via `psql`, then a restart — **dev only**) so the Admin-API provisioner can
  run over HTTP. The `amqp-demo` realm is created with `sslRequired: none` directly.

## Tests

```bash
# 1. start everything (separate terminal, leave it running)
mise run demo

# 2. run the integration tests against the live stack
mise run test:integration
```

- **Per-service context tests** (`*ApplicationTest`, run by `mise run test` / `mvn clean verify`) boot each Spring
  context offline — they verify the beans and the validated `app.amqp` properties wire up. No broker is required: the
  AMQP 1.0 `Environment`, connection factory, and listener containers all connect lazily.
- **Integration tests** (`test/`, Node's built-in test runner + [`rhea`](https://github.com/amqp/rhea), the AMQP 1.0
  client for Node) run against the live stack and are the real proof. They fetch real `client_credentials` tokens for
  each service and assert: the HTTP entry point accepts a job; every service may publish what its scopes allow; and the
  **reporter (publish), the dispatcher (cross-exchange), and the worker (out-of-scope routing key) are each refused** by
  the broker with `amqp:unauthorized-access`. A final test shortens the realm's token lifespan to 15s and proves the
  AMQP 1.0 lifecycle: the broker **disconnects** a live connection once its token expires.

## Running individual services

```bash
mise run infra:up          # start Traefik + Postgres + Keycloak + RabbitMQ
mise run keycloak:setup    # relax master-realm SSL, then provision realm + clients + scopes
mise run dispatcher:start  # start just the dispatcher
mise run worker:start
mise run reporter:start
mise run infra:down        # tear everything down
```

## Project structure

```
.
├── pom.xml                     # parent Maven module (aggregates the three services)
├── mise.toml                   # tool versions + the `mise run …` task runner
├── dispatcher/                 # REST trigger → publishes jobs
├── worker/                     # consumes jobs, publishes results
├── reporter/                   # consumes results (read-only)
├── test/                       # Node + rhea integration tests against the live stack
└── support/
    ├── .env                    # ports + image tags
    ├── docker-compose.yml
    ├── database/               # Postgres .env + init script (creates the keycloak DB)
    ├── keycloak/               # realm + client provisioning (TypeScript Admin API)
    └── rabbitmq/               # rabbitmq.conf, enabled_plugins, topology definitions
```

The `database` and `keycloak` services each own a small `.env` file that docker-compose loads via `env_file:`, so their
configuration lives next to them. (`support/.env` holds the shared host ports and image tags.)

## What changed from the AMQP 0.9.1 version

If you've read the [0.9.1 version](https://github.com/lukas-grigis/spring-oauth2-amqp-0.9.1), here's the full diff in one
table. The broker, the realm, and the topology are untouched; everything that changed is on the client side.

| Area                  | AMQP 0.9.1                                                              | AMQP 1.0 (this repo)                                                                 |
|-----------------------|------------------------------------------------------------------------|--------------------------------------------------------------------------------------|
| Client library        | `spring-boot-starter-amqp` (RabbitMQ Java client 5.x)                  | `spring-rabbitmq-client` 4.0.3 (RabbitMQ AMQP 1.0 client) — wired by hand, no auto-config |
| Send / receive API    | `RabbitTemplate`, `@RabbitListener` (channels)                         | `RabbitAmqpTemplate`, `@RabbitListener` via `RabbitAmqpListenerContainerFactory` (links) |
| Addressing            | exchange + routing key on a channel                                    | v2 target/source addresses (`/exchanges/jobs/job.*`, `/queues/jobs.in`) — Spring builds them |
| Token acquisition     | Spring Security `OAuth2AuthorizedClientManager` + `CredentialsProvider` | the AMQP client's own `oauth2()` settings (no Spring Security in the broker path)     |
| Token refresh         | `CredentialsRefreshService` → `update-secret` at 80%                   | built into the client → `PUT /auth/tokens` at ~80% (automatic)                       |
| Behavior on expiry    | broker refuses operations, connection stays open                       | broker **disconnects** the connection                                                |
| Authorization failure | channel `403 ACCESS_REFUSED`                                           | session/connection `amqp:unauthorized-access`                                        |
| HTTP token client     | needed Apache HttpClient 5 (JDK HttpClient deadlocked on h2c)          | client's token requester is pinned to HTTP/1.1 — no workaround needed                |
| Integration tests     | `amqplib`                                                              | `rhea`                                                                               |
| Broker / realm / topology | —                                                                  | **unchanged** (`rabbitmq.conf`, `definitions.json`, Keycloak all identical; RabbitMQ 4.1 → 4.2 for live-connection refresh) |

## Tech stack

| Layer          | What's running                                                          |
|----------------|-------------------------------------------------------------------------|
| Services       | Spring Boot 4, Spring AMQP 1.0 client (`spring-rabbitmq-client`)         |
| Broker         | RabbitMQ 4.2 — native AMQP 1.0 + `rabbitmq_auth_backend_oauth2`          |
| Auth           | Keycloak 26 (client_credentials grant) + Postgres                       |
| Edge           | Traefik (reverse proxy, TLS termination for JWKS)                       |
| Infrastructure | Docker Compose                                                          |
| Build          | Maven (multi-module), Java 25                                           |

## Security notice

This is a demo. The setup is optimized for clarity, not for production:

- Client secrets are committed in plain text (use a secrets manager in real life; the apps already read them from
  `${*_CLIENT_SECRET}` env vars, defaulting to the demo values)
- Keycloak runs in `start-dev` over plain HTTP, with `ssl_required` disabled on the `master` realm; services fetch
  tokens over plain HTTP, and the broker reaches its JWKS through Traefik with `verify_none` (use real CA-signed certs
  and `verify_peer` end-to-end — and HTTPS token endpoints with `oauth2().tls()` — in production)
- The Postgres and RabbitMQ admin users are `admin/admin`
- The `dispatcher` REST endpoint is unauthenticated

Don't deploy this as-is. Use it as a reference for how broker-level OAuth2 wires together over AMQP 1.0, then harden it
to your own standards.

## Contributing

If you find a bug or have an idea, open an issue or send a pull request.

1. Fork the repo
2. Create a branch (`git checkout -b my-change`)
3. Make your changes
4. Run `mise run test` (and `mise run test:integration` against a running stack) to make sure things work
5. Open a PR

## License

[MIT](LICENSE)
