# Spring playbook

Use this playbook for Java/Kotlin Spring Boot applications.

## Inventory targets

- `@RestController`, `@Controller`, `@RequestMapping`, `@PostMapping`, etc.
- service classes and use-case/application-service layers
- repositories/entities under JPA/Hibernate or JDBC
- validation annotations and custom validators
- Spring Security config, method security, policies
- scheduled jobs: `@Scheduled`
- message listeners: Kafka, Rabbit, JMS
- transactions: `@Transactional`
- Flyway/Liquibase migrations
- tests and test fixtures

## Useful tools

- generated OpenAPI if available through springdoc or similar
- CodeQL or IDE symbol search for annotations
- build/test commands for the selected module

## Extraction hints

- Map controller methods and message listeners to command candidates.
- Map services with transaction boundaries to aggregate mutation boundaries.
- Map validation annotations to safety candidates, but check custom validators and service guards.
- Map `@PreAuthorize`, filters, and security config to authorization invariants.
- Map entity status enums and repository write paths to state transitions.
- Map transactional outbox, Kafka publishes, and events to side-effect/domain events.

## Common pitfalls

- behavior hidden in annotations, AOP, filters, interceptors, entity listeners
- validation split between DTOs and services
- lazy loading and cascading writes obscuring side effects
- generated clients/contracts diverging from runtime behavior
