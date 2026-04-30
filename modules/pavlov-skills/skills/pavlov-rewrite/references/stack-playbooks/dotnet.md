# .NET playbook

Use this playbook for ASP.NET Core and related .NET services.

## Inventory targets

- controllers, minimal APIs, endpoint route builders
- application services / command handlers / MediatR handlers
- Entity Framework DbContexts, entities, migrations, configurations
- validation: data annotations, FluentValidation, custom guards
- authorization policies, attributes, filters, middleware
- hosted services, background workers, Hangfire/Quartz jobs
- message consumers/producers
- OpenAPI/Swagger definitions
- tests and fixtures

## Useful tools

- `dotnet test` for selected projects
- Swagger/OpenAPI generation if configured
- CodeQL or IDE symbol search for attributes and handlers

## Extraction hints

- Map endpoints/controllers/command handlers to command events.
- Map EF configurations and migrations to invariants.
- Map FluentValidation and guard clauses to safety candidates.
- Map authorization attributes/policies to auth invariants.
- Map hosted services and retry policies to progress/liveness candidates.
- Map domain events or integration events to Pavlov domain/side-effect events.

## Common pitfalls

- behavior hidden in filters/middleware/pipeline behaviors
- validation duplicated across DTOs, domain objects, and DB
- implicit transactions in EF unit-of-work patterns
- MediatR pipelines obscuring actual call flow
