# Game of Life Refactor Plan

## Goal

- Refactor the Game of Life example so that board rules, game status, client initialization, DOM projection, and timing are separate Pavlov behaviors instead of one large bthread.
- Keep the current browser contract as stable as possible: browser clicks still become semantic `:game-of-life/...clicked` events, and the frontend still receives `:pavlov.web.dom/op` or `:pavlov.web.dom/ops` events to apply.

## Event Model

### Browser input events

- `:game-of-life/cell-clicked`
  - Properties: `:pavlov-row`, `:pavlov-col`
  - Meaning: the user clicked one visible cell in the board.
- `:game-of-life/start-clicked`
  - Properties: none
  - Meaning: the user wants the game to begin advancing on ticks.
- `:game-of-life/pause-clicked`
  - Properties: none
  - Meaning: the user wants the game to stop advancing on ticks.
- `:game-of-life/reset-clicked`
  - Properties: none
  - Meaning: the user wants the board cleared and the game paused.

### Runtime and client lifecycle events

- `:game-of-life/client-opened`
  - Properties: `:client-id`
  - Meaning: a new browser websocket connection joined the shared game.
- `:game-of-life/client-closed`
  - Properties: `:client-id`
  - Meaning: a browser websocket connection left the shared game.
- `:game-of-life/tick`
  - Properties: none
  - Meaning: one timer interval elapsed; this is only a clock pulse and does not itself imply that the board should advance.

### Domain coordination events

- `:game-of-life/advance-generation`
  - Properties: none
  - Meaning: the board should evolve forward exactly one generation.
- `:game-of-life/get-board-snapshot`
  - Properties: `:client-id`
  - Meaning: request the board's current full state so a late joiner can be initialized.
- `:game-of-life/get-status-snapshot`
  - Properties: `:client-id`
  - Meaning: request the current running/paused state so a late joiner can receive the current controls and status view.

### Domain fact events

- `:game-of-life/board-changed`
  - Properties:
    - `:created-cells` - set of `[row col]` coords that became alive
    - `:killed-cells` - set of `[row col]` coords that became dead
    - `:generation` - resulting generation number after the change
    - `:live-count` - total live cells after the change
  - Meaning: the authoritative board state changed, either due to a click, reset, or one generation advance.
- `:game-of-life/board-snapshot`
  - Properties:
    - `:client-id` - target client to initialize
    - `:live-cells` - set of all live `[row col]` coords
    - `:generation` - current generation number
    - `:live-count` - total live cells
    - optional `:height` and `:width` if helpful to downstream renderers
  - Meaning: the board emitted its full current state for a specific client.
- `:game-of-life/status-changed`
  - Properties: `:running?`
  - Meaning: the game transitioned between running and paused.
- `:game-of-life/status-snapshot`
  - Properties: `:client-id`, `:running?`
  - Meaning: the current running/paused state for a specific client.

### Projection and transport events

- `:game-of-life/broadcast`
  - Properties: `:event`
  - Meaning: send the contained frontend event to all connected clients.
- `:game-of-life/send-to-client`
  - Properties: `:client-id`, `:event`
  - Meaning: send the contained frontend event to one specific client identified by a runtime-managed client id.
- `:pavlov.web.dom/op`
  - Properties: existing DOM op properties
  - Meaning: one DOM mutation for the browser.
- `:pavlov.web.dom/ops`
  - Properties: `:ops`
  - Meaning: a batch of DOM mutations for the browser.

## Bthreads

### Board bthread

- Constructor: `make-board-bthread`
- Inputs:
  - board dimensions: `height`, `width`
  - events: `:game-of-life/cell-clicked`, `:game-of-life/reset-clicked`, `:game-of-life/advance-generation`, `:game-of-life/get-board-snapshot`
- State:
  - `:live-cells`
  - `:generation`
- Outputs:
  - `:game-of-life/board-changed`
  - `:game-of-life/board-snapshot`
- Responsibilities:
  - own the authoritative Game of Life board state
  - toggle a clicked cell
  - reset the board to empty and reset generation to `0`
  - compute one generation step from the current board
  - answer snapshot requests for late joiners
- Notes:
  - this bthread should know nothing about running/paused state
  - this bthread should know nothing about DOM selectors, CSS classes, or websocket side effects

### Game-status bthread

- Constructor: `make-game-status-bthread`
- Inputs:
  - `:game-of-life/start-clicked`
  - `:game-of-life/pause-clicked`
  - `:game-of-life/reset-clicked`
  - `:game-of-life/tick`
  - `:game-of-life/get-status-snapshot`
- State:
  - `:running?`
- Outputs:
  - `:game-of-life/status-changed`
  - `:game-of-life/status-snapshot`
  - `:game-of-life/advance-generation` when a tick arrives while `:running?` is true
- Responsibilities:
  - own whether the simulation is running or paused
  - start on `start-clicked`
  - pause on `pause-clicked`
  - pause on `reset-clicked`
  - convert raw timer ticks into `advance-generation` only while active
  - answer status snapshot requests for late joiners
- Notes:
  - `generation` stays in the board bthread, not here
  - repeated start while already running should be idempotent

### Client-manager bthread

- Constructor: `make-client-manager-bthread`
- Inputs:
  - `:game-of-life/client-opened`
  - `:game-of-life/client-closed`
- State:
  - optional client bookkeeping if we want explicit lifecycle state in the program
- Outputs:
  - `:game-of-life/get-board-snapshot`
  - `:game-of-life/get-status-snapshot`
- Responsibilities:
  - treat `client-opened` as the semantic initialization point for a browser client
  - request both board and status snapshots for that client
  - optionally observe closed clients if later logic needs it
- Notes:
  - the actual websocket objects remain runtime-owned in the websocket namespace
  - bthreads communicate only with pure `:client-id` values, not websocket objects

### DOM-ops projector bthread

- Constructor: `make-dom-ops-bthread`
- Inputs:
  - `:game-of-life/board-changed`
  - `:game-of-life/board-snapshot`
  - `:game-of-life/status-changed`
  - `:game-of-life/status-snapshot`
- State:
  - optional cached `:running?`, `:generation`, and `:live-count` if that makes status rendering easier
- Outputs:
  - `:game-of-life/broadcast`
  - `:game-of-life/send-to-client`
  - payloads should contain `:pavlov.web.dom/op` or `:pavlov.web.dom/ops`
- Responsibilities:
  - translate domain facts into DOM operations
  - broadcast board changes to all connected clients
  - send snapshot DOM operations only to the newly opened client
  - project running/paused state into status text and button enabled/disabled state
- Notes:
  - this is the only bthread that should know DOM selectors, CSS classes, status text, or button state
  - the board bthread should emit domain facts, not DOM-specific updates

## Namespace Layout

### Backend behavior namespaces

- `modules/pavlov-web/src/pavlov_web_example/game_of_life/board.cljc`
  - `make-board-bthread`
  - pure board helpers: neighbor lookup, generation stepping, diffing previous and next boards
- `modules/pavlov-web/src/pavlov_web_example/game_of_life/game_status.cljc`
  - `make-game-status-bthread`
- `modules/pavlov-web/src/pavlov_web_example/game_of_life/client_manager.cljc`
  - `make-client-manager-bthread`
- `modules/pavlov-web/src/pavlov_web_example/game_of_life/dom_ops.cljc`
  - `make-dom-ops-bthread`
  - helpers for status DOM ops, cell DOM ops, full-board snapshot DOM ops
- `modules/pavlov-web/src/pavlov_web_example/game_of_life/websocket.clj`
  - compose the bprogram
- own websocket runtime wiring and subscribers
- own the `client-id -> websocket` and `websocket -> client-id` mappings
- start the shared timer loop

### Example app namespaces

- `examples/web/src/pavlov_web_example/game_of_life/client.cljs`
  - keep browser forwarding behavior for `cell-clicked`, `start-clicked`, `pause-clicked`, and `reset-clicked`
- `examples/web/src/pavlov_web_example/game_of_life/handlers.clj`
  - stop slurping a static shell string
  - render the page through Chassis
- `examples/web/src/pavlov_web_example/game_of_life/page.clj`
  - new namespace that builds the shell markup with Hiccup/Chassis
  - generate the board markup from `height` and `width`
- `examples/web/src/pavlov_web_example/game_of_life/routes.clj`
  - route wiring stays the same unless the handler API changes

## Timer Design

- Use one shared clock loop per shared runtime.
- The clock can be simple:
  - create a delayed starter after the bprogram exists
  - when forced, start a `future` that loops forever
  - each loop does `Thread/sleep 1000` and then `bp/submit-event!` with `{:type :game-of-life/tick}`
- The clock should not inspect game state.
- The clock should not try to decide whether the board advances.
- `game-status` is the only place that interprets ticks.
- This keeps the timer dumb and the behavior semantic.
- Tests can continue to submit `:game-of-life/tick` manually so they do not depend on real sleeping.
- If we need better test control, the websocket namespace can keep the current pattern of rebinding the scheduler/timer entry point with a dynamic var.

## Chassis and Visual Shell

- Replace the current static `examples/web/resources/pavlov_web_example/game_of_life/shell.html` shell with a Chassis-generated page so the board dimensions and DOM structure come from one source of truth.
- Use Hiccup in `examples/web/src/pavlov_web_example/game_of_life/page.clj` to generate:
  - the header and control panel
  - the board grid with one button per cell
  - the same `pavlov-*` attributes the current client runtime already expects
- The visual target should follow `examples/web/stitch/pavlov_neural_evolution_v2/code.html`.
- That reference should guide the atmosphere, layout, and styling direction for the Game of Life shell while still preserving the Game of Life controls and selectors already used by the example.
- The plan does not require the exact static HTML file to be copied literally; it is the design reference for the Chassis-generated version.

## Implementation Notes

- Keep the public browser-side event names stable first; internal refactoring should not require a client rewrite.
- Prefer domain facts like `board-changed` and `status-changed` over direct DOM requests from board or status logic.
- A late joiner needs both board state and status state, so initialization should request both snapshots.
- `reset-clicked` should clear the board and pause the game.
- The board diff event should be atomic: one event contains both `created-cells` and `killed-cells`.
