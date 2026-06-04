/**
 * Keycloak setup script for the spring-oauth2-amqp demo.
 *
 * Creates a realm with three confidential clients (dispatcher, worker, reporter)
 * configured for the client_credentials grant. Each client is assigned a distinct
 * set of scopes that RabbitMQ's OAuth2 plugin translates into broker permissions.
 *
 * Idempotent — every resource is created if missing, then updated to match config.
 * Safe to re-run at any time.
 *
 * Usage:
 *   npm run setup
 */

import KcAdminClient from "@keycloak/keycloak-admin-client";
import { Result } from "typescript-result";
import { configSchema } from "./config.js";
import rawConfig from "./keycloak.config.js";

const config = configSchema.parse(rawConfig);

// Must match auth_oauth2.resource_server_id in support/rabbitmq/rabbitmq.conf. Every token needs this in
// its `aud` claim because RabbitMQ's verify_aud defaults to true; we add it via an audience protocol mapper.
const RESOURCE_SERVER_ID = "rabbitmq";
const AUDIENCE_SCOPE = "rabbitmq-audience";

class ConflictError {
    readonly type = "conflict" as const;
}

class KeycloakError {
    readonly type = "keycloak" as const;

    constructor(readonly cause: unknown) {}
}

function toKeycloakError(e: unknown): ConflictError | KeycloakError {
    const isConflict =
        typeof e === "object" &&
        e !== null &&
        "response" in e &&
        typeof (e as Record<string, unknown>).response === "object" &&
        (e as Record<string, Record<string, unknown>>).response?.status === 409;

    return isConflict ? new ConflictError() : new KeycloakError(e);
}

async function ensureCreated(name: string, createFn: () => Promise<unknown>): Promise<void> {
    const [, error] = (await Result.try(createFn, toKeycloakError)).toTuple();

    if (!error) {
        console.log(`   ${name} — created.`);
    } else if (error.type === "conflict") {
        console.log(`   ${name} — exists.`);
    } else {
        throw error.cause;
    }
}

async function main() {
    const { keycloak, realm, scopes, clients } = config;

    console.log(`Keycloak: ${keycloak.url}`);

    const kc = new KcAdminClient({ baseUrl: keycloak.url, realmName: "master" });
    await kc.auth({
        username: keycloak.adminUser,
        password: keycloak.adminPassword,
        grantType: "password",
        clientId: "admin-cli",
    });

    // 1. Realm — create if missing, then update
    console.log(`\n1. Realm: ${realm}`);
    const realmConfig = {
        realm,
        enabled: true,
        sslRequired: "none" as const,
        accessTokenLifespan: 300,
    };
    await ensureCreated(realm, () => kc.realms.create(realmConfig));
    await kc.realms.update({ realm }, realmConfig);
    kc.setConfig({ realmName: realm });

    // 2. Client scopes — each maps (in RabbitMQ) to one or more native permission scopes
    console.log(`\n2. Client scopes: [${scopes.join(", ")}]`);
    for (const scopeName of scopes) {
        const scopeConfig = {
            name: scopeName,
            protocol: "openid-connect" as const,
            attributes: {
                "include.in.token.scope": "true",
                "display.on.consent.screen": "false",
            },
        };
        await ensureCreated(scopeName, () => kc.clientScopes.create(scopeConfig));
        const allScopes = await kc.clientScopes.find();
        const scope = allScopes.find((s) => s.name === scopeName);
        if (scope) {
            await kc.clientScopes.update({ id: scope.id! }, scopeConfig);
        }
    }

    // 2b. Audience scope — adds `aud: rabbitmq` to every access token (RabbitMQ rejects tokens whose
    //     audience does not include resource_server_id). include.in.token.scope=false keeps "rabbitmq"
    //     out of the `scope` claim, so it never reads as a permission. Assigned as a default to all clients.
    console.log(`\n2b. Audience scope: ${AUDIENCE_SCOPE} (aud=${RESOURCE_SERVER_ID})`);
    const audienceScopeConfig = {
        name: AUDIENCE_SCOPE,
        protocol: "openid-connect" as const,
        attributes: {
            "include.in.token.scope": "false",
            "display.on.consent.screen": "false",
        },
    };
    await ensureCreated(AUDIENCE_SCOPE, () => kc.clientScopes.create(audienceScopeConfig));
    const audienceScope = (await kc.clientScopes.find()).find((s) => s.name === AUDIENCE_SCOPE);
    if (audienceScope?.id) {
        await kc.clientScopes.update({ id: audienceScope.id }, audienceScopeConfig);
        const mappers = await kc.clientScopes.listProtocolMappers({ id: audienceScope.id });
        if (!mappers.some((m) => m.name === AUDIENCE_SCOPE)) {
            await kc.clientScopes.addProtocolMapper(
                { id: audienceScope.id },
                {
                    name: AUDIENCE_SCOPE,
                    protocol: "openid-connect",
                    protocolMapper: "oidc-audience-mapper",
                    config: {
                        "included.custom.audience": RESOURCE_SERVER_ID,
                        "access.token.claim": "true",
                        "id.token.claim": "false",
                    },
                },
            );
        }
        console.log(`   ${AUDIENCE_SCOPE} — synced.`);
    }

    // 3. Clients — confidential, client_credentials only, each with a distinct set of default scopes
    console.log(`\n3. Clients (confidential, client_credentials)`);
    for (const client of clients) {
        console.log(`\n   ${client.clientId} → scopes: [${client.scopes.join(", ")}]`);

        const clientConfig = {
            clientId: client.clientId,
            enabled: true,
            publicClient: false,
            clientAuthenticatorType: "client-secret",
            secret: client.secret,
            standardFlowEnabled: false,
            directAccessGrantsEnabled: false,
            serviceAccountsEnabled: true,
            protocol: "openid-connect" as const,
            fullScopeAllowed: false,
            attributes: {
                "oauth2.device.authorization.grant.enabled": "false",
                "oidc.ciba.grant.enabled": "false",
            },
        };

        await ensureCreated(client.clientId, () => kc.clients.create(clientConfig));
        const [found] = await kc.clients.find({ clientId: client.clientId });
        const clientUuid = found.id!;
        await kc.clients.update({ id: clientUuid }, clientConfig);

        // Reset default scopes to exactly this client's permission scopes plus the shared audience scope.
        const assigned = await kc.clients.listDefaultClientScopes({ id: clientUuid });
        const allScopes = await kc.clientScopes.find();
        const wantedIds = new Set(
            [...client.scopes, AUDIENCE_SCOPE]
                .map((name) => allScopes.find((s) => s.name === name)?.id)
                .filter((id): id is string => !!id),
        );

        for (const existing of assigned) {
            if (existing.id && !wantedIds.has(existing.id)) {
                await kc.clients.delDefaultClientScope({ id: clientUuid, clientScopeId: existing.id }).catch(() => {
                    /* ignore */
                });
            }
        }
        for (const wantedId of wantedIds) {
            await kc.clients.addDefaultClientScope({ id: clientUuid, clientScopeId: wantedId }).catch(() => {
                /* already assigned */
            });
        }

        console.log(`   ${client.clientId} — synced.`);
    }

    // Summary
    console.log(`\n--- Done! ---`);
    console.log(`Realm:   ${realm}`);
    console.log(`Token:   ${keycloak.url}/realms/${realm}/protocol/openid-connect/token`);
    for (const client of clients) {
        console.log(`Client:  ${client.clientId} / ${client.secret} → [${client.scopes.join(", ")}]`);
    }
}

main().catch((err) => {
    console.error("Setup failed:", err);
    process.exit(1);
});
