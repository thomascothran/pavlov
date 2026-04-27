# Node Express/Nest playbook

Use this playbook for Node.js web/server applications, including Express, Fastify, NestJS, and similar frameworks.

## Inventory targets

- route registration files and routers
- controllers/resolvers/handlers
- service/use-case modules
- middleware, guards, interceptors
- ORM models/migrations: Prisma, TypeORM, Sequelize, Knex, Mongoose
- queues/jobs: Bull/BullMQ, Agenda, workers
- validation: zod, joi, class-validator, yup, custom validators
- tests, fixtures, pact/contract tests
- OpenAPI/GraphQL schemas when available

## Useful commands when allowed

- framework route printers if present
- `npm test` / targeted package test commands
- schema generation commands such as Prisma introspection/generation

## Extraction hints

- Map route handlers and GraphQL mutations to command candidates.
- Map middleware/guards/interceptors to auth, tenancy, rate-limit, and validation invariants.
- Map service functions that perform writes to domain event candidates.
- Map ORM schema constraints and migrations to safety candidates.
- Map queue processors and retries to liveness/progress candidates.
- Map emitted events/webhooks to side-effect events.

## Common pitfalls

- dynamically registered routes
- middleware order affecting behavior
- validation split across frontend, middleware, handlers, and database
- untyped JavaScript payloads requiring examples/tests/traces for schemas
- async behavior hidden behind event emitters or queues
