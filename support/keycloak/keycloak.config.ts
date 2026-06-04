import type { Config } from "./config.js";

const config: Config = {
    keycloak: {
        // Keycloak lives under /auth behind Traefik (HTTP).
        url: "http://localhost/auth",
        adminUser: "admin",
        adminPassword: "admin", // demo only — use environment variables or a secrets manager in production
    },

    realm: "amqp-demo",

    // Keycloak scope names — aliases that RabbitMQ's OAuth2 plugin maps to
    // native {permission}:{vhost}/{resource} scopes via auth_oauth2.scope_aliases.
    scopes: ["jobs_write", "jobs_read", "results_write", "results_read"],

    clients: [
        {
            clientId: "dispatcher",
            secret: "dispatcher-secret", // demo only — use environment variables or a secrets manager in production
            scopes: ["jobs_write"],
        },
        {
            clientId: "worker",
            secret: "worker-secret",
            scopes: ["jobs_read", "results_write"],
        },
        {
            clientId: "reporter",
            secret: "reporter-secret",
            scopes: ["results_read"],
        },
    ],
};

export default config;
