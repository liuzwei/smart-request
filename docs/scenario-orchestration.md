# Scenario Orchestration (Scenarios) — Implementation Plan

Status: P1 (model + validation + engine) implemented; P2 (persistence + steps UI) next.

Confirmed decisions:

- The editor is an **editor tab** (wide canvas, splittable, re-openable), not a tool-window pane and not a floating dialog.
- Delivery in two steps: **engine + steps table first, canvas second**. The steps table stays as a secondary view.
- Failure semantics: **fail fast**. The first failed node ends the run; nodes that are still in flight are cancelled and nodes that have not started are skipped. No failure branches in v1.
- Captured values go to the **run scope only**. A scenario run never writes `.idea/smart-request.xml`. Single-request Extract keeps its current behaviour (it writes the environment); the difference is intentional and is explained in the UI.

## 1. Goal

Run several of the project's HTTP requests as one activity-on-node flow:

```
(Start) → [POST /auth/login] → [GET /cart] → [POST /orders] → (End)
```

- Nodes are existing requests, referenced by `HttpRequestConfig.id`.
- Data flows along the edges: a node captures values from its response with the existing Extract rules (JSONPath), downstream nodes use them as `{{variables}}`.
- One click runs the whole flow, with live per-node status, duration, HTTP status and captured values.
- The flow is a reusable project artifact (save, rename, duplicate, share).

## 2. Non-goals for v1

- Loops, array iteration, data-driven runs.
- Scripting, assertion expressions, conditional branching, failure branches.
- Sub-scenarios, retries, delays, timers.
- CI/headless runner, scheduled runs, run history on disk.
- Writing captured values back to environment variables.
- More than one concurrent run of the same scenario.

These are revisited in §11; the data model leaves room for them without migration (see §3.4).

## 3. Data model

Stored in the project state (`.idea/smart-request.xml`), `SmartRequestState.version` → **3**.

### 3.1 Scenario

```kotlin
class ScenarioConfig(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "New Scenario",
    var notes: String = "",
    var maxParallel: Int = 4,                        // 1 = strictly sequential
    var parameters: MutableList<KeyValue> = mutableListOf(), // run inputs, defaults
    var nodes: MutableList<ScenarioNode> = mutableListOf(),
    var edges: MutableList<ScenarioEdge> = mutableListOf()
)
```

`parameters` are shown in the Run dialog and can be overridden per run; they are not persisted per run.

### 3.2 Node

```kotlin
class ScenarioNode(
    var id: String = UUID.randomUUID().toString(),
    var kind: String = "REQUEST",           // START / REQUEST / END
    var requestId: String? = null,          // REQUEST only
    var name: String = "",                  // canvas title, default = request name
    var x: Int = 0,
    var y: Int = 0,
    var expectStatus: String = "",          // see §5.3
    var timeoutMs: Int? = null,             // null = project default
    var outputs: MutableList<ResponseExtractor> = mutableListOf()
)
```

- `kind` is a string for XML stability (same convention as `HttpRequestConfig.bodyType`).
- Exactly one `START` and one `END` per scenario; both are real nodes with coordinates, which keeps drag/delete/topology code uniform.
- `outputs` are seeded from `request.extractors` when the node is created and are then owned by the node. The inspector offers "Sync from request". Reusing `ResponseExtractor` means the existing variable picker and JSONPath validation apply unchanged.
- `expectStatus` is what makes error-path testing possible under fail-fast: a node that expects `404` passes on 404.

### 3.3 Edge

```kotlin
class ScenarioEdge(
    var id: String = UUID.randomUUID().toString(),
    var from: String = "",
    var to: String = ""
)
```

v1 edges are unconditional dependencies ("`to` runs after `from` passed"). A `condition` field with `SUCCESS / FAILURE / ALWAYS` can be added later without migration: absent values deserialize as `SUCCESS` and older readers ignore unknown fields.

### 3.4 Normalization

`SmartRequestState.normalize()` and `ScenarioConfig.normalize()`:

- create a START and an END if missing; keep only the first if there are several;
- drop edges whose endpoints do not exist; drop self-loops;
- clamp `maxParallel` to `1..10`, `timeoutMs` to the existing project range;
- drop empty parameters;
- never delete a node because its request disappeared — it stays and is reported by validation (§4), so the user can remap it.

### 3.5 Serialization

`XmlSerializer` handles the nested lists as-is; add a round-trip test that writes and reads a scenario with 5 nodes, 2 output rules per node and 4 edges, plus a `version = 2` state without `scenarios` (must load unchanged, `scenarios` empty).

## 4. Validation

Run-time blocking checks live in `ScenarioValidator`, are pure, and are reused by the UI banner and by the Run button.

### 4.1 Errors (block the run)

| Check | Message shape |
|---|---|
| no START / no END / more than one | `Scenario needs exactly one Start and one End` |
| cycle | `Cycle: POST /orders → GET /cart → POST /orders` |
| node cannot be reached from START | `POST /orders is not reachable from Start` |
| edge into START / out of END | `Start cannot have incoming edges` |
| START without outgoing edge, END without incoming edge | `End has no incoming edge` (warning when the scenario has no request nodes) |
| REQUEST node without `requestId` | `Node 3 has no request` |
| `requestId` not found in the project | `Request was deleted; choose another request for POST /orders` |
| node needs a variable that no ancestor produces and that is not in the environment or parameters | `POST /orders needs {{token}}; it is not an environment variable and no upstream node captures it` |
| variable produced only by a node that is not upstream | `{{token}} is captured by POST /auth/login, which is not upstream of GET /cart` |
| START/END sized wrong (e.g. START with 2 inputs) | structural messages above cover this |

The variable check runs against the snapshot taken at run start (environment + parameters), so it is exact rather than heuristic; it is the main safety net for data flow.

### 4.2 Warnings (do not block)

| Check | Message shape |
|---|---|
| two nodes capture the same variable and neither is an ancestor of the other | `Both A and B capture {{token}}; with parallel execution the value depends on completion order` |
| a node captures a variable that is also a scenario parameter | `Downstream nodes will see the value captured by A, not the run parameter` |
| node captures a variable that is also an environment variable | informational, shown in the node inspector rather than the banner |
| scenario has no request nodes | `Add at least one request node` |

The banner shows `✓ Valid` / `2 errors, 1 warning` and is clickable to open the diagnostics list; clicking an item selects the offending node or edge.

## 5. Run semantics

### 5.1 States

```
Scenario: IDLE → RUNNING → PASSED | FAILED | CANCELLED
Node:     PENDING → RUNNING → PASSED | FAILED | SKIPPED | CANCELLED
```

- Run status is `PASSED` when END was reached and every executed node passed.
- Run status is `FAILED` when any node failed (END may never be reached).
- Run status is `CANCELLED` when the user cancels; completed node results are kept.

### 5.2 Scheduling

A plain dependency DAG: a node becomes runnable when **all** predecessors are `PASSED`. START is `PASSED` immediately at t=0.

```
ready  = successors of START
loop:
  submit up to maxParallel ready nodes to the worker pool
  on completion:
    PASSED → release its successors whose predecessors are all PASSED
    FAILED → abort: cancel in-flight futures, mark PENDING nodes SKIPPED, run = FAILED
  stop when no node is running and no node is ready
```

- Stable ordering: ready nodes are sorted by `(y, x, id)` before submission, so logs and results are reproducible.
- `maxParallel = 1` gives sequential execution with the same semantics.
- Parallel branches are allowed to finish only until the first failure; after that they are cancelled.

### 5.3 Node outcome

A node is `PASSED` when **all** of the following hold:

1. the request was prepared and sent (no build error, no transport error, no timeout);
2. the status matches `expectStatus`:
   - empty → `200..399`
   - `2xx` / `4xx` / `5xx` → that class
   - `404` → exactly 404
   - `200-299` → inclusive range
   - `200,201,204` → any of the listed codes
3. every enabled output rule evaluated without error.

Otherwise `FAILED` with a one-line reason (`Unresolved variables: tenantId`, `Expected 200, got 500`, `Header 'X-Request-Id' is not present`, `Request timed out`).

Output rules are required in v1: a failed rule fails the node, because downstream data would otherwise be stale or missing. A per-rule "optional" flag is a later addition.

### 5.4 Isolation and snapshots

At start the runner snapshots:

- the scenario (nodes, edges, outputs, `maxParallel`);
- every referenced `HttpRequestConfig` (deep copy);
- environment id/name, `baseUrl`, variables, global headers/params;
- parameters entered in the Run dialog;
- project timeout/followRedirects.

Edits made during a run therefore affect only the next run. Deleting a request mid-run does not break the current run.

### 5.5 Concurrency and cancellation

- One active run per scenario; pressing Run while running focuses the run panel instead of starting a second one.
- Cancel: cancel the in-flight futures, mark them `CANCELLED`, mark `PENDING` nodes `SKIPPED`, dispose the worker pool.
- Transport timeouts are bounded by the node timeout (project default when unset); there is no separate scenario-level timeout in v1.

## 6. Variable scope

### 6.1 Precedence

```
environment variables + baseUrl     (lowest)
scenario parameters                  (override the environment)
values captured by upstream nodes    (override both, in completion order)
```

The initial scope is `environment + parameters`; a captured value overwrites it and records its producer node. `baseUrl` stays reserved for the environment host (same rule as the Extract engine).

### 6.2 Not written back

A run never mutates `EnvironmentConfig`. Reasons: parallel branches and repeated runs stay deterministic, and a green run does not silently change the next manual request. The run panel shows the final variable table (name, value, producer) so the user can copy values manually; single-request Extract still writes the environment when the request is sent from the request editor.

### 6.3 Runtime fallback

Preparation uses `RequestPreparer.prepare(..., variables = scope.snapshot())`. If a variable is still unresolved (e.g. it was in the environment of a different profile, or a conditional value is missing), the node fails with the existing `RequestBuildException` message, which already lists the missing names.

## 7. Engine API and threading

`dev.smartrequest.scenario` must not depend on `Project`, the EDT, or the platform:

```kotlin
fun interface ScenarioTransport {
    fun send(request: PreparedRequest): CompletableFuture<HttpResponseData>
}

fun interface RequestSource {
    fun request(id: String): HttpRequestConfig?
}

data class RunEnvironment(          // snapshot
    val id: String?,
    val name: String,
    val baseUrl: String,
    val variables: Map<String, String>,
    val globalHeaders: List<KeyValue>,
    val globalParams: List<KeyValue>,
    val timeoutMs: Int,
    val followRedirects: Boolean
)

class ScenarioRunner(
    private val transport: ScenarioTransport,
    private val requests: RequestSource,
    private val listener: (ScenarioRunEvent) -> Unit
) {
    fun start(
        scenario: ScenarioConfig,     // deep copy taken by the caller
        environment: RunEnvironment,
        parameters: Map<String, String>
    ): ScenarioRunHandle               // future + cancel()
}
```

- Node execution runs on a per-run daemon pool sized `maxParallel`; a single-threaded scheduler owns all state transitions (no locks beyond that thread), so ordering is deterministic and race-free.
- `listener` is called on the scheduler thread; the UI wraps it in `invokeLater`. Events: `RunStarted`, `NodeStarted`, `NodeFinished`, `RunFinished`.
- Results: `ScenarioRunResult(status, startedAt, durationMs, nodes: Map<nodeId, NodeResult>, variables: List<CapturedVariable>)`; `NodeResult` keeps the `HttpResponseData` reference for the current run so a node's response can be inspected later without another request (bodies are already capped at 5 MB by `HttpTransport`).
- Tests use a fake transport with manual completion (see §10).

## 8. UI

### 8.1 Surface: editor tab

- The tool window lists scenarios (new/rename/delete/duplicate/search) and opens a scenario in an editor tab; the same scenario always reuses the same tab.
- Implementation: a custom `FileType` (e.g. `Scenario`) + `FileEditorProvider`, opened with a `LightVirtualFile` cached per scenario id. The tab title is the scenario name, refreshed on rename; closing the tab does not delete the scenario, and a disposed project closes the run.
- The steps table (P2) and the canvas (P3) are two views of the same model, switchable with a segmented control in the editor toolbar, so the feature is usable before the canvas exists and stays usable for long flows.

### 8.2 Editor layout (P2, steps view)

```
┌ Checkout flow ──────────────────────────────── [Steps|Graph]  Env: dev  ▶Run ✔Valid ┐
│ #  Request             Depends on        Status   HTTP  ms   Captured             │
│ 1  POST /auth/login    (Start)           ●pass    200   143  token=eyJ...          │
│ 2  GET  /cart          login             ●pass    200    31                       │
│ 3  POST /orders        cart             ✕fail    500   210  'orderId' not present │
├──────────────────────────────────────────────┬──────────────────────────────────────┤
│ Node: POST /orders                           │ Outputs                              │
│ Request: [Orders/Create ▾]                   │ ✓ orderId   data.order.id            │
│ Runs after: ☑ cart  ☐ login  ☐ …            │ ✓ status    status                   │
│ Expect status: [2xx]   Timeout: [default]    │ [+ Add rule]  [Sync from request]    │
└──────────────────────────────────────────────┴──────────────────────────────────────┘
```

- "Runs after" is a checkbox list of the other request nodes; it is the edge editor in P2 and stays in the inspector in P3.
- New nodes get grid coordinates automatically, so the graph view is presentable as soon as it exists.
- Requests are added from a searchable picker (grouped like the request tree); drag-and-drop from the request tree is a later addition.

### 8.3 Editor layout (P3, graph view)

```
(Start)──▶[POST /auth/login]──┬──▶[GET /user/profile]──┐
                              └──▶[GET /cart]───────────┴──▶[POST /orders]──▶(End)
```

Parallel branches fork after `login` and join at `POST /orders`, which runs only after both branches passed.

Interactions (deliberately small):

1. drag nodes;
2. drag from the right port of a node to the left port of another to create an edge; self-links, duplicates, edges into START/out of END and cycles are rejected while dragging with an inline hint;
3. click to select, Delete to remove (START/END protected), context menu for add/delete;
4. double-click a node opens its request in the Smart Request tool window (message-bus event, no direct coupling);
5. Ctrl+wheel zoom, drag on empty space to pan, "Fit" and "Auto layout" (longest-path layering + barycenter ordering);
6. Ctrl+Z / Ctrl+Y with snapshot-based undo (max 50 steps);
7. run state is painted on the nodes (grey pending, blue running, green passed, red failed, dashed skipped) and the result table selects the node on click.

Node width is fixed (method badge + title + captured-variable count); text is ellipsized with a tooltip. No groups, marquee selection, orthogonal routing, or minimap in v1.

### 8.4 Run dialog

Shown when the scenario has parameters or when validation found variables that must be supplied:

- environment selector (defaults to the project's active environment; changing it changes the project selection);
- parameters table (name/value, prefilled with defaults);
- detected missing variables, editable for this run only;
- `Run` is disabled while validation errors exist, with the first error shown inline.

### 8.5 Run panel

- Header: `5 passed, 1 failed, 1 skipped · 1.8s` + Cancel while running.
- Table: node, status, HTTP status, duration, captured values (red for failures, dashed for skipped).
- Footer: final variable table (name, value, producer node), copyable.
- The panel keeps the last run in memory; it is cleared when a new run starts or the editor is closed.

## 9. Integration changes

| File | Change |
|---|---|
| `model/Models.kt` | `ScenarioConfig` / `ScenarioNode` / `ScenarioEdge`; `SmartRequestState.scenarios`, `normalize()`, version 3 |
| `http/RequestPreparer.kt` | new `variables: Map<String, String> = emptyMap()` parameter, merged over the environment variables; existing call sites unchanged |
| `http/HttpTransport.kt` | optional shared `HttpClient` per run (`send(prepared, client)`), so 10 nodes do not pay 10 TLS handshakes; the caller owns the client |
| `service/SmartRequestProjectService.kt` | `scenario(id)`, `addScenario`, `removeScenario`, `duplicateScenario`, `scenarioOf(nodeId)` helpers |
| `scenario/ScenarioValidator.kt` | pure validation (§4) |
| `scenario/ScenarioRunner.kt` | scheduler, run scope, results (§5–§7) |
| `ui/ScenarioListPanel.kt` | tool window list |
| `ui/ScenarioEditorPanel.kt` | toolbar, steps/graph switch, inspector, run panel |
| `ui/ScenarioStepsTable.kt` | P2 table view + dependency checkboxes |
| `ui/ScenarioGraphCanvas.kt` | P3 canvas |
| `ui/ScenarioInspectorPanel.kt` | node/edge/run properties, outputs table |
| `ui/ScenarioRunPanel.kt` | results and final variables |
| `ui/dialogs/ScenarioRunDialog.kt` | environment + parameters + missing variables |
| `ui/ScenarioEditorProvider.kt` | `FileType` / `FileEditorProvider` / `LightVirtualFile` plumbing |
| `ui/SmartRequestPanel.kt` | subscribe to the "open request" topic; toolbar entry "Scenarios" |
| `META-INF/plugin.xml` | `Scenarios` tool window, file type/editor provider, description bullet |
| `README.md`, `CHANGELOG.md` | feature docs, design notes, test counts |

## 10. Testing

Engine tests need no network and no sleeping:

- `ScenarioValidatorTest`: every error/warning in §4, including cycles, unreachable nodes, duplicate producers, missing variables, dangling request ids.
- `ScenarioRunnerTest` with a `ScenarioTransport` whose futures are completed by the test (or delayed by latches):
  - linear chain passes values (`token` from node 1 resolves in node 2);
  - a join waits for both predecessors;
  - `maxParallel` is respected (three slow nodes, `maxParallel = 2`, assert only two started);
  - independent branches run concurrently (both started before either completes);
  - fail-fast: a failing node cancels in-flight siblings and skips the rest; run = FAILED;
  - `expectStatus` variants (2xx, 404, range) flip the outcome;
  - a failed output rule fails the node and does not leak a value;
  - cancel marks in-flight `CANCELLED` and pending `SKIPPED`;
  - parameters override the environment, captured values override parameters, producers are recorded;
  - deterministic ordering with equal-level nodes.
- `ScenarioSerializationTest`: round-trip, old state without scenarios, normalization of broken edges.
  The round-trip currently lives in `SmartRequestPanelUiTest.testScenarioRoundTripsThroughXmlSerializer`,
  next to the request/environment round-trip test.
- `RequestPreparerTest`: run-scope precedence and unresolved reporting.
- UI tests (`BasePlatformTestCase`, like the existing table tests):
  - `ScenarioStepsTableTest`: add/remove/dependency checkboxes write the model; editing outputs updates the node;
  - `ScenarioGraphCanvasTest`: model coordinates, hit testing, edge creation by programmatic mouse events, invalid edge rejection, delete, undo/redo, auto layout bounds;
  - `ScenarioEditorUiTest`: open from the list, tab reuse, run panel rows, node status colors (via a test hook `nodeState(nodeId)`);
  - `ScenarioRunDialogTest`: parameter prefilling, blocking on validation errors.

Run: `$env:JAVA_HOME = "C:\Program Files\JetBrains\IntelliJ IDEA OSS\jbr"; .\gradlew.bat test -PlocalIdePath="C:\Program Files\JetBrains\IntelliJ IDEA OSS"`.

## 11. Phases and acceptance

### P1 — model, validation, engine (headless)

- [x] `ScenarioConfig`/`ScenarioNode`/`ScenarioEdge` + normalization + XML round-trip (version 2 → 3 compatible).
- [x] `ScenarioValidator` with all §4 rules.
- [x] `ScenarioRunner` with fail-fast, `maxParallel`, per-node timeout, `expectStatus`, run scope, cancel.
- [x] `RequestPreparer` variable injection + tests.
- [x] Fake-transport suite covering §10 engine cases.
- Acceptance: a 6-node, 2-branch scenario passes end-to-end with fabricated responses; a failure cancels siblings and skips the remainder; all tests green.

### P2 — persistence, list, steps table, run panel

- [ ] Scenarios tool window (new/rename/delete/duplicate), editor tab reuse.
- [ ] Steps table with dependency editing, request picker, outputs/expect-status/timeout inspector.
- [ ] Validation banner, Run dialog, run panel with live statuses, final variables.
- [ ] `HttpTransport` shared client for runs.
- Acceptance: from a clean project, create a 3-node scenario, pass a token between nodes, run it against a local server, see per-node results; delete a request and confirm the run is blocked with a clear message.

### P3 — canvas

- [ ] Painting, drag, ports, edge creation with rejection hints, delete, zoom/pan, fit, auto layout, undo/redo.
- [ ] Run-state painting and selection sync with the result table.
- [ ] Graph/steps switch preserving selection.
- Acceptance: build the same scenario entirely on the canvas; invalid operations are refused inline; the graph survives restart with positions.

### P4 — polish

- [ ] Node response viewer (read-only, from the retained response).
- [ ] Export/import scenario as JSON (sharing outside `.idea`).
- [ ] Drag requests from the request tree into the canvas.
- [ ] Optional per-output "required = false" and retry count.
- [ ] README/CHANGELOG/plugin description updates.

## 12. Risks and later work

- **Canvas interaction** is the largest unknown: keep the interaction set at the seven actions above; cover geometry (ports, hit testing, edge picking, layout) with unit tests that do not depend on rendering.
- **Semantics edge cases** concentrate in joins/fail-fast/cancel; model them as a table of tests, one per rule.
- **No failure branches in v1** means error-path flows cannot be expressed as branches; `expectStatus` covers "this 404 is expected". If needed, add `EdgeCondition` later without migration.
- **`.idea` sharing**: scenarios live next to request definitions and are not shared by default; P4 adds export/import.
- **Deleted requests**: nodes keep the id and are reported; no silent data loss.
- **Parallel duplicate writes**: warned at validation, last write wins at run time (producer is recorded).
- **Memory**: the last run keeps one `HttpResponseData` per node (bodies already capped at 5 MB); cleared on the next run or when the editor closes.
- **Threads**: a run owns two daemon thread pools and only releases them when it finishes or is cancelled; the editor must call `cancel()` on dispose (P2), otherwise an abandoned run keeps its pools alive.
- **Later candidates**: failure branches, assertion nodes, `foreach` sub-flows, retries/delays, JSON export, JUnit export, run history, CI runner.
