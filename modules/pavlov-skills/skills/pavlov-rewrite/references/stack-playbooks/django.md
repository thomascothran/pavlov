# Django playbook

Use this playbook for Django and Django REST Framework applications.

## Inventory targets

- URLconf files: `urls.py`
- views, viewsets, serializers, forms
- models and managers
- migrations
- Celery/RQ/background tasks
- signals
- permissions and authentication classes
- admin actions
- tests, factories, fixtures

## Useful commands when allowed

- `python manage.py show_urls` if installed
- `python manage.py sqlmigrate <app> <migration>`
- targeted test commands for the selected bounded context/projection

If Python commands are not allowed in the current environment, inspect files directly or use project-approved tooling.

## Extraction hints

- Map DRF viewset actions and serializers to commands and payload schemas.
- Map serializer/model validation to safety candidates.
- Map permissions/queryset filtering to authorization and tenancy invariants.
- Map model `choices` fields to state variables.
- Treat signals as hidden side-effect/state-transition sources.
- Map Celery retries and scheduled tasks to liveness/progress candidates.

## Common pitfalls

- behavior split across serializers, models, managers, and signals
- implicit permissions in `get_queryset`
- model choices that do not encode legal transitions
- migrations differing from current model definitions
