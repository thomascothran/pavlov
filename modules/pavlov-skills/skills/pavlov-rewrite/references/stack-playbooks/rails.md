# Rails playbook

Use this playbook for Ruby on Rails applications.

## Inventory targets

- `config/routes.rb` and mounted engines
- controllers under `app/controllers`
- models under `app/models`
- service objects, interactors, commands, policies, form objects
- jobs under `app/jobs`
- mailers under `app/mailers`
- serializers/views/responders
- migrations and `db/schema.rb` or `db/structure.sql`
- specs/tests, factories, fixtures, system tests

## Useful commands when allowed

- `bin/rails routes`
- `bin/rails runner` for small introspection snippets
- test commands for the selected bounded context/projection

## Extraction hints

- Map non-GET routes to command candidates.
- Map ActiveRecord validations and DB constraints to safety candidates.
- Map callbacks carefully; they often hide side effects and state transitions.
- Map state-machine gems or enum columns to lifecycle events.
- Map Pundit/CanCan policies to authorization invariants.
- Map ActiveJob retries and mailers to progress/side-effect events.

## Common pitfalls

- business behavior hidden in callbacks
- scopes that implement authorization implicitly
- service objects with generic names like `Processor` or `Manager`
- model validations duplicated or contradicted by database constraints
- tests that depend on global time, jobs, or transactional fixtures
