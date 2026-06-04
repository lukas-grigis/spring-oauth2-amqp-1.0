/**
 * Integration tests for the OAuth2-secured AMQP 1.0 demo.
 *
 * Requires the full stack to be running (Keycloak, RabbitMQ, and the three services).
 * Start it with `mise run demo`, then run these tests with `mise run test:integration`.
 *
 * The tests fetch real client_credentials tokens for each service and prove the broker-level authorization
 * model over AMQP 1.0: the HTTP entry point accepts a job, every client may publish what its scopes allow,
 * and the broker refuses every publish a token is not authorized for. Unlike AMQP 0.9.1 (where a refusal is a
 * channel-level 403 ACCESS_REFUSED), an AMQP 1.0 authorization failure ends the session/connection with the
 * OASIS-standard condition `amqp:unauthorized-access`.
 *
 * The final block proves the AMQP 1.0 token lifecycle: the broker *disconnects* a live connection once its
 * token expires (the 0.9.1 broker only refused new operations). This is exactly why the services configure
 * the RabbitMQ AMQP 1.0 client's oauth2() support — it refreshes the token in place on the live connection
 * (HTTP-over-AMQP `PUT /auth/tokens`) at ~80% of its lifetime, so the long-lived service connections never hit
 * this disconnect. We shorten the realm's access-token lifespan to 15s for this block, then restore it.
 *
 * AMQP 1.0 client: rhea (https://github.com/amqp/rhea). The Keycloak JWT is the SASL PLAIN password; the
 * username is cosmetic (the broker authenticates from the token), but rhea only offers PLAIN when a non-empty
 * username is set, so we pass the client id.
 */

import { after, before, describe, it } from "node:test";
import assert from "node:assert/strict";
import rhea from "rhea";

// ── Configuration (matches support/keycloak + the services' application.yml) ──

const KEYCLOAK = process.env.KEYCLOAK_URL ?? "http://localhost/auth";
const DISPATCHER = process.env.DISPATCHER_URL ?? "http://localhost:8080";
const RABBIT_HOST = process.env.RABBITMQ_HOST ?? "localhost";
const RABBIT_PORT = Number(process.env.RABBITMQ_PORT ?? "5672");
const REALM = "amqp-demo";

const SECRETS: Record<string, string> = {
  dispatcher: "dispatcher-secret",
  worker: "worker-secret",
  reporter: "reporter-secret",
};

// Keycloak master-realm admin (demo defaults; the realm provisioner relaxes master-realm SSL over HTTP).
const KC_ADMIN_USER = process.env.KC_ADMIN_USER ?? "admin";
const KC_ADMIN_PASSWORD = process.env.KC_ADMIN_PASSWORD ?? "admin";

// ── Helpers ───────────────────────────────────────────────────────────────

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

type PublishResult = { ok: boolean; condition?: string; error?: string };

/** Normalizes a rhea/AMQP error object ({condition, description}) into a flat {condition, error}. */
function amqpError(source: unknown): { condition?: string; error?: string } {
  const e = (source ?? {}) as { condition?: unknown; description?: unknown; message?: unknown };
  const condition = e.condition == null ? undefined : String(e.condition);
  const error = (e.description ?? e.message ?? condition) as string | undefined;
  return { condition, error };
}

async function token(clientId: string): Promise<string> {
  const res = await fetch(`${KEYCLOAK}/realms/${REALM}/protocol/openid-connect/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "client_credentials",
      client_id: clientId,
      client_secret: SECRETS[clientId],
    }),
  });
  const body = await res.json();
  assert.equal(res.status, 200, `token request for ${clientId} failed: ${JSON.stringify(body)}`);
  return body.access_token as string;
}

/** The JWT is the AMQP 1.0 SASL password; the username is cosmetic but must be non-empty for rhea to use PLAIN. */
function connect(clientId: string, accessToken: string) {
  return rhea.create_container().connect({
    host: RABBIT_HOST,
    port: RABBIT_PORT,
    username: clientId,
    password: accessToken,
    reconnect: false, // tests want failures to surface, not be retried away
  });
}

/** Opens a connection and resolves once it is established (or rejects if the broker refuses it). */
function openConnection(clientId: string, accessToken: string): Promise<rhea.Connection> {
  return new Promise((resolve, reject) => {
    const connection = connect(clientId, accessToken);
    connection.once("connection_open", () => resolve(connection));
    connection.once("connection_error", (ctx) => reject(new Error(amqpError(ctx.connection?.error).error ?? "connection_error")));
    connection.once("disconnected", () => reject(new Error("disconnected before open")));
  });
}

/**
 * Publishes once to {exchange}/{routingKey} on an already-open connection (target address `/exchanges/{e}/{k}`),
 * resolving {ok:true} when the broker settles the message as accepted, or {ok:false, ...} on any refusal.
 *
 * Over AMQP 1.0, an exchange-level write refusal fails the *attach*, and a routing-key (topic) refusal fails the
 * *transfer* — both surface as a connection/session error with condition `amqp:unauthorized-access`, not a clean
 * link detach or a per-message reject, so we listen on the connection as well as the sender.
 */
function publishOn(connection: rhea.Connection, exchange: string, routingKey: string): Promise<PublishResult> {
  return new Promise((resolve) => {
    let done = false;
    // Track every listener so finish() can detach them — important because the expiry test reuses one
    // connection across multiple publishOn() calls, and leaked handlers would re-fire on the later close.
    const registered: Array<[{ on: Function; removeListener: Function }, string, (ctx: any) => void]> = [];
    const on = (emitter: { on: Function; removeListener: Function }, event: string, handler: (ctx: any) => void) => {
      emitter.on(event, handler);
      registered.push([emitter, event, handler]);
    };
    const finish = (result: PublishResult) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      for (const [emitter, event, handler] of registered) {
        try { emitter.removeListener(event, handler); } catch { /* best effort */ }
      }
      resolve(result);
    };
    const timer = setTimeout(() => finish({ ok: false, error: "timeout waiting for broker outcome" }), 10_000);

    on(connection, "connection_error", (ctx) => finish({ ok: false, ...amqpError(ctx.connection?.error) }));
    on(connection, "disconnected", () => finish({ ok: false, error: "disconnected" }));

    const sender = connection.open_sender({ target: { address: `/exchanges/${exchange}/${routingKey}` } });

    // The crux of AMQP 1.0 authorization: a refusal is NOT a link detach, a connection error, or a per-message
    // reject — the broker ends the SESSION with condition `amqp:unauthorized-access`. An exchange-level write
    // refusal ends it at link *attach* (reporter/dispatcher → results); a routing-key/topic refusal ends it on
    // the first message *transfer* (worker → internal.audit). Listen on the session or rhea throws on the
    // unhandled error event.
    on(sender.session, "session_error", (ctx) => finish({ ok: false, ...amqpError(ctx.session?.error) }));
    on(sender.session, "session_close", (ctx) => finish({ ok: false, ...amqpError(ctx.session?.error) }));
    on(sender, "sender_error", (ctx) => finish({ ok: false, ...amqpError(ctx.sender?.error) }));
    on(sender, "accepted", () => finish({ ok: true }));
    on(sender, "rejected", (ctx) => finish({ ok: false, ...amqpError(ctx.delivery?.remote_state?.error), error: "rejected" }));
    on(sender, "released", () => finish({ ok: false, error: "released" }));
    on(sender, "sendable", (ctx) => { ctx.sender.send({ body: "{}" }); });
  });
}

/** Connects with a fresh token, attempts one publish, then closes — returns {ok} or {ok:false, ...} on refusal. */
async function tryPublish(clientId: string, exchange: string, routingKey: string): Promise<PublishResult> {
  const accessToken = await token(clientId);
  const connection = connect(clientId, accessToken);
  try {
    await new Promise<void>((resolve, reject) => {
      connection.once("connection_open", () => resolve());
      connection.once("connection_error", (ctx) => reject(new Error(amqpError(ctx.connection?.error).error ?? "connection_error")));
      connection.once("disconnected", () => reject(new Error("disconnected before open")));
    });
    return await publishOn(connection, exchange, routingKey);
  } catch (error) {
    return { ok: false, error: error instanceof Error ? error.message : String(error) };
  } finally {
    try { connection.close(); } catch { /* server may have closed it already */ }
  }
}

// ── Keycloak Admin API (used only to shorten the token lifespan for the expiry test) ──

async function adminToken(): Promise<string> {
  const res = await fetch(`${KEYCLOAK}/realms/master/protocol/openid-connect/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "password",
      client_id: "admin-cli",
      username: KC_ADMIN_USER,
      password: KC_ADMIN_PASSWORD,
    }),
  });
  const body = await res.json();
  assert.equal(res.status, 200, `admin token request failed: ${JSON.stringify(body)}`);
  return body.access_token as string;
}

async function getRealmTokenLifespan(): Promise<number> {
  const res = await fetch(`${KEYCLOAK}/admin/realms/${REALM}`, {
    headers: { Authorization: `Bearer ${await adminToken()}` },
  });
  assert.equal(res.status, 200, "failed to read realm config");
  return (await res.json()).accessTokenLifespan ?? 300;
}

async function setRealmTokenLifespan(seconds: number): Promise<void> {
  const res = await fetch(`${KEYCLOAK}/admin/realms/${REALM}`, {
    method: "PUT",
    headers: { Authorization: `Bearer ${await adminToken()}`, "Content-Type": "application/json" },
    body: JSON.stringify({ accessTokenLifespan: seconds }),
  });
  assert.ok(res.status === 204 || res.ok, `failed to set realm token lifespan: HTTP ${res.status}`);
}

// ── Tests ─────────────────────────────────────────────────────────────────

describe("OAuth2-secured AMQP 1.0", () => {
  before(async () => {
    const health = await fetch(`${DISPATCHER}/actuator/health`).catch(() => null);
    assert.ok(health?.ok, "Dispatcher is not reachable — is the stack running? (mise run demo)");
  });

  describe("HTTP entry point", () => {
    it("accepts a job and returns 202 with an id", async () => {
      const res = await fetch(`${DISPATCHER}/jobs`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ payload: "hello amqp 1.0" }),
      });
      assert.equal(res.status, 202);
      const body = await res.json();
      assert.ok(body.id, "expected a job id in the response");
      assert.equal(body.status, "accepted");
    });
  });

  describe("broker authorization — allowed", () => {
    it("dispatcher may publish job.* to the jobs exchange", async () => {
      const r = await tryPublish("dispatcher", "jobs", "job.submitted");
      assert.ok(r.ok, `expected success, got: ${r.condition ?? r.error}`);
    });

    it("worker may publish result.* to the results exchange", async () => {
      const r = await tryPublish("worker", "results", "result.ready");
      assert.ok(r.ok, `expected success, got: ${r.condition ?? r.error}`);
    });
  });

  describe("broker authorization — refused (the payoff)", () => {
    it("reporter cannot publish (results_read only)", async () => {
      const r = await tryPublish("reporter", "results", "result.ready");
      assert.equal(r.ok, false, "reporter must not be allowed to publish");
      assert.match(r.condition ?? r.error ?? "", /unauthorized-access|refused|403/i);
    });

    it("dispatcher cannot cross over to the results exchange", async () => {
      const r = await tryPublish("dispatcher", "results", "result.ready");
      assert.equal(r.ok, false, "dispatcher must not be allowed to publish to results");
      assert.match(r.condition ?? r.error ?? "", /unauthorized-access|refused|403/i);
    });

    it("worker cannot publish a routing key outside its result.* scope", async () => {
      const r = await tryPublish("worker", "results", "internal.audit");
      assert.equal(r.ok, false, "worker must not publish a disallowed routing key");
      assert.match(r.condition ?? r.error ?? "", /unauthorized-access|refused|403/i);
    });
  });

  // Proves the AMQP 1.0 token lifecycle on a live connection: once the token expires, the broker disconnects
  // the client (0.9.1 only refused new operations). This is what the services' oauth2() in-place token refresh
  // (PUT /auth/tokens at ~80% of lifetime) exists to prevent. The realm's token lifespan is shortened to 15s
  // for this block, then restored.
  describe("token expiry on a live AMQP 1.0 connection", () => {
    const SHORT_TTL = 15;
    let originalTtl = 300;

    before(async () => {
      originalTtl = await getRealmTokenLifespan();
      await setRealmTokenLifespan(SHORT_TTL);
    });

    after(async () => {
      await setRealmTokenLifespan(originalTtl);
    });

    it("the broker disconnects the client once its token expires", { timeout: 60_000 }, async () => {
      const connection = await openConnection("worker", await token("worker"));
      let closed: string | null = null;
      connection.on("connection_close", (ctx) => { closed = amqpError(ctx.connection?.error).condition ?? "closed"; });
      connection.on("disconnected", () => { closed = closed ?? "disconnected"; });
      try {
        // The fresh token works.
        const fresh = await publishOn(connection, "results", "result.ready");
        assert.ok(fresh.ok, `expected the initial publish to succeed, got: ${fresh.condition ?? fresh.error}`);

        // Let the token expire (valid only SHORT_TTL seconds); the broker proactively closes the connection.
        await sleep((SHORT_TTL + 12) * 1000);

        assert.ok(closed !== null, "the broker must disconnect the connection once the token has expired");
      } finally {
        try { connection.close(); } catch { /* broker may have closed it already */ }
      }
    });
  });
});
