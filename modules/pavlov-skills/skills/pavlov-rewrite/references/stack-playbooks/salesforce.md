# Salesforce rewrite playbook

Use this playbook when extracting behavior from a Salesforce org or Salesforce DX source tree for a Pavlov-guided rewrite. Salesforce behavior is spread across declarative metadata, Apex, UI bundles, permissions, and runtime automation; do not treat Apex source alone as the system of record.

## Start with org and source inventory

Prefer a Salesforce DX source tree when available. Common roots and metadata types:

- `sfdx-project.json` and package directories such as `force-app/main/default/`
- Apex: `classes/*.cls`, `triggers/*.trigger`, and `*-meta.xml`
- Data model: `objects/**`, custom fields, record types, validation rules, duplicate rules, compact/layout metadata
- Automation: `flows/*.flow-meta.xml`, workflow/process metadata in older orgs, approval processes, assignment/escalation rules
- UI: `lwc/**`, `aura/**`, `pages/*.page`, `components/*.component`, Lightning pages and app metadata
- Integration/security metadata: connected apps, named credentials, remote site settings, platform events, profiles, permission sets, sharing rules

If source may be incomplete, ask for or produce a manifest-driven Metadata API retrieval (`package.xml`) rather than relying only on local folders. Record the org alias, API version, package directories, retrieval command, and manifest as evidence.

Official references:

- Salesforce DX source format: <https://developer.salesforce.com/docs/platform/code-builder/guide/codebuilder-source-format.html>
- Metadata API deploy/retrieve: <https://developer.salesforce.com/docs/atlas.en-us.api_meta.meta/api_meta/>
- Metadata coverage report: <https://developer.salesforce.com/docs/success/metadata-coverage-report/references/metadata-types>
- VS Code retrieve/deploy source: <https://developer.salesforce.com/docs/platform/sfvscode-extensions/guide/retrieve-source.html>

## Behavior surfaces to mine

### Apex classes and triggers

Extract candidate events and invariants from:

- trigger object, timing, and operation: `before/after insert/update/delete/undelete`
- trigger handlers and service classes reached from triggers
- DML side effects, SOQL/SOSL reads, callouts, platform-event publishes, email sends, approval actions, task/case creation, and async work
- annotations: `@AuraEnabled`, `@InvocableMethod`, `@RestResource`, `@future`, queueable/schedulable/batch classes, test methods
- sharing mode: `with sharing`, `without sharing`, `inherited sharing`
- exception handling and `addError` calls, which often encode business rejection outcomes

Model trigger-driven behavior by Salesforce order of execution, not by source file order. Include flows, validation rules, duplicate rules, assignment rules, workflow/process automation, rollups, and after-commit actions when they can observe or modify the same record lifecycle.

Official references:

- Apex classes/triggers overview: <https://developer.salesforce.com/docs/platform/webconsole/guide/work-with-code.html>
- Salesforce order of execution: <https://developer.salesforce.com/docs/platform/data-models/guide/order-of-execution.html>
- Apex tests in VS Code: <https://developer.salesforce.com/docs/platform/sfvscode-extensions/guide/apex-testing.html>
- SOQL/SOSL reference: <https://developer.salesforce.com/docs/atlas.en-us.252.0.soql_sosl.meta/soql_sosl/>

### Declarative automation

Treat Flow, Process Builder, workflow rules, approval processes, assignment rules, and validation rules as first-class behavior. For each active flow or automation artifact, record:

- trigger: record change, schedule, platform event, screen action, invocable call, or subflow
- entry criteria and decision branches
- reads/writes, created records, deletes, outbound messages, callouts, Apex actions, subflows, emails, and platform events
- scheduled paths, retries, fault paths, and user-facing screens
- active/inactive versions and migration status from legacy Process Builder/workflow

Candidate Pavlov scenarios usually come from business outcomes of automation, not from individual flow elements.

Official references:

- Migrate to Flow: <https://help.salesforce.com/s/articleView?id=platform.flow_migrate_to_flow.htm&type=5>
- Flow in Lightning Web Components: <https://developer.salesforce.com/docs/platform/lwc/guide/use-flow.html>
- Flow data types: <https://developer.salesforce.com/docs/platform/lwc/guide/use-flow-data-types>

### UI contracts: LWC, Aura, and Visualforce

UI code is evidence for user intent, field-level behavior, validation feedback, and Apex/API contracts. Mine:

- LWC bundle `*.js-meta.xml` targets, public `@api` properties, wire adapters, Lightning Data Service usage, Apex imports, custom events, slots, and Jest tests
- Aura component controllers/helpers/renderers, application/component events, interfaces, and server actions
- Visualforce pages/components, standard/custom controllers, controller extensions, action methods, rerender regions, and JavaScript remoting
- navigation, quick actions, record pages, screen flows, and exposed component targets

Do not turn click paths into scenarios unless they produce or observe domain outcomes.

Official references:

- LWC introduction: <https://developer.salesforce.com/docs/platform/lwc/guide/get-started-introduction>
- LWC component folder structure: <https://developer.salesforce.com/docs/platform/lwc/guide/create-components-folder>
- Call Apex from LWC: <https://developer.salesforce.com/docs/platform/lwc/guide/apex>
- LWC Jest tests: <https://developer.salesforce.com/docs/platform/lwc/guide/unit-testing-using-jest-run-tests>
- Aura components reference: <https://developer.salesforce.com/docs/platform/lightning-component-reference/guide/aura-components.html>
- LWC or Aura guidance: <https://developer.salesforce.com/docs/platform/lwc/guide/get-started-lwc-or-aura.html>
- Visualforce writing support: <https://developer.salesforce.com/docs/platform/sfvscode-extensions/guide/visualforce-writing.html>

### Integration, APIs, and events

Inventory both inbound and outbound contracts:

- inbound Apex REST resources, SOAP API usage, REST/Bulk API clients, Experience Cloud endpoints, and external middleware contracts
- outbound Apex callouts, named credentials, remote site settings, outbound messages, webhooks, and generated WSDL clients
- Platform Events, Change Data Capture, Streaming/Pub/Sub clients, event-triggered flows/Apex, replay IDs, retention assumptions, and ordering/idempotency rules
- connected apps, OAuth scopes, integration users, and API-version dependencies

Map each integration to domain stimuli and observable outcomes: request accepted/rejected, external system notified, retry/dead-letter behavior, eventual consistency, and duplicate handling.

Official references:

- REST API Developer Guide: <https://developer.salesforce.com/docs/atlas.en-us.api_rest.meta/api_rest/>
- SOAP API Developer Guide: <https://developer.salesforce.com/docs/atlas.en-us.api.meta/api/>
- Pub/Sub API intro: <https://developer.salesforce.com/docs/platform/pub-sub-api/guide/intro.html>
- Pub/Sub supported event types: <https://developer.salesforce.com/docs/platform/pub-sub-api/guide/supported-event-types.html>
- Event durability: <https://developer.salesforce.com/docs/platform/pub-sub-api/guide/event-message-durability.html>

### Sharing, permissions, and tenancy

Security behavior is often a rewrite-critical policy projection. Capture:

- organization-wide defaults, roles, territories, teams, sharing rules, manual shares, restriction/scoping rules
- profiles, permission sets, permission set groups, CRUD/FLS, custom permissions, and login/session policies
- Apex sharing declarations and explicit CRUD/FLS enforcement (`WITH USER_MODE`, `stripInaccessible`, describe checks)
- guest/community user access and Experience Cloud sharing behavior
- record ownership changes and implicit shares produced by business processes

Represent cross-cutting access rules as policies/invariants unless a specific authorization path has a domain outcome worth modeling as a scenario.

Official references:

- Secure Apex classes: <https://developer.salesforce.com/docs/platform/lwc/guide/apex-security>
- Lightning component security: <https://developer.salesforce.com/docs/platform/lightning-components-security/guide/intro.html>

## Evidence collection checklist

1. List package directories, manifests, retrieved metadata types, API version, and gaps in Metadata Coverage.
2. Run and archive Apex test results. Link failures and covered classes/triggers to scenario candidates.
3. Run LWC Jest tests if present and capture UI/API contracts they assert.
4. Export or cite relevant debug logs, trace flags, Event Monitoring records, integration logs, and platform-event/CDC samples.
5. Build a data-model inventory: objects, fields, relationships, record types, validation/duplicate rules, picklist values, and ownership/sharing-sensitive fields.
6. Build an automation graph: object lifecycle -> validation -> triggers -> flows/process/workflow -> approvals/assignment/escalation -> async/after-commit work.
7. Mark inferred behavior clearly until validated by tests, logs, metadata, or SME review.

Useful official references:

- Debug Apex code: <https://developer.salesforce.com/docs/platform/code-builder/guide/codebuilder-apex-debug.html>
- Execute anonymous and debug logs: <https://developer.salesforce.com/docs/platform/webconsole/guide/exec-anon-apex.html>
- LWC debug mode: <https://developer.salesforce.com/docs/platform/lwc/guide/debug-debug-mode.html>
- Lightning Logger and Event Monitoring: <https://developer.salesforce.com/docs/platform/lightning-component-reference/guide/lightning-logger.html>

## Salesforce-specific scenario candidates

Good scenario families often come from:

- record lifecycle workflows: lead conversion, opportunity stage changes, quote/order generation, case escalation, entitlement milestones
- approval and assignment workflows
- external-system synchronization: ERP/accounting/customer portal updates, retries, dedupe, conflict resolution
- event-driven processes: platform event received, CDC update consumed, async job completes/fails
- security-sensitive behaviors: user with role/profile/permission can or cannot perform a domain action
- data quality gates: validation/duplicate rules that reject a business action with a meaningful message

For each scenario, record the initiating stimulus, initial record state, acting user/profile/permission context, active automation versions, domain event sequence, terminal state/response, and evidence IDs.

## Red flags and pitfalls

- Assuming Apex is complete while active flows or validation rules change the same lifecycle.
- Ignoring Salesforce order of execution when explaining trigger/flow interactions.
- Treating generated metadata diffs as behavior without linking them to user/system outcomes.
- Missing inactive-but-deployed flow versions, legacy Process Builder/workflow artifacts, or managed-package behavior.
- Losing security semantics by testing only as a system administrator.
- Ignoring governor limits, bulk behavior, partial-success DML, retries, and async eventual consistency.
- Over-modeling UI click paths instead of extracting the domain outcome behind the UI.
