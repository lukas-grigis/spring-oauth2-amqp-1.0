import { z } from "zod";

const clientSchema = z.object({
    clientId: z.string(),
    secret: z.string(),
    scopes: z.array(z.string()).default([]),
});

export const configSchema = z.object({
    keycloak: z
        .object({
            url: z.string().url().default("http://localhost/auth"),
            adminUser: z.string().default("admin"),
            adminPassword: z.string().default("admin"),
        })
        .default({}),

    realm: z.string().default("amqp-demo"),

    scopes: z.array(z.string()).default(["jobs_write", "jobs_read", "results_write", "results_read"]),

    clients: z.array(clientSchema).default([
        {
            clientId: "dispatcher",
            secret: "dispatcher-secret",
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
    ]),
});

export type Config = z.infer<typeof configSchema>;
