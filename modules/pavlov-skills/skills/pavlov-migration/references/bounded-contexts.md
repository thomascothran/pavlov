## Identify Bounded Contexts

### 1. Document the Context Boundary

Analyze the legacy code base (along with any extra information provided, such as logs or test cases) to identify a bounded context.
  * The bounded context should be written up in plain English in business terms in doc/rewrite/<bounded-context-name>/DOMAIN.md
  * This file *does not* specify the scenarios or rules. It has no reference to specific technology (like REST, HTTP, databases).
  * Rather, it explains in business terms the business context in such a way that we can determine what behaviors are included or excluded.
  * It should be at most 3 paragraphs.

### 2. Create a resource map for that bounded context

Create a map in a doc/rewrite/<bounded-context-name>/code-map.json file that identifies files and functions or objects in the code base (and logs or other resources if any) and specifies for each whether it:
  * Has Business logic
  * Has Test Scenarios
  * Does persistence (e.g., saving or retrieving from a database)
  * Handles incoming IO
