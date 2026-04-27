# Go service playbook

Use this playbook for Go HTTP/RPC services.

## Inventory targets

- router setup: net/http, chi, gorilla/mux, gin, echo, fiber
- handler functions and middleware
- service/use-case packages
- repository/storage packages
- SQL migrations and query files
- protobuf/gRPC/OpenAPI specs
- background goroutines, workers, cron jobs
- message consumers/producers
- tests, fixtures, golden files

## Useful commands when allowed

- `go test ./...` or selected package tests
- router or OpenAPI generation commands if the project has them
- static analysis/search for handler registration and SQL queries

## Extraction hints

- Map handlers/RPC methods to command candidates.
- Map middleware to auth, tenancy, rate-limit, and validation invariants.
- Map explicit SQL writes and transactions to state transitions.
- Map protobuf/OpenAPI schemas to event payloads.
- Map goroutines/workers/retries/timeouts to progress candidates.
- Map table-driven tests to scenario variants.

## Common pitfalls

- routes registered through helper functions
- business logic split across small packages with generic names
- SQL constraints not visible in structs
- context cancellation and timeout behavior affecting liveness
- concurrency races or async side effects not represented in tests
