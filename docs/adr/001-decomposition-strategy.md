# ADR-001: Decomposition Strategy — Strangler Fig Extraction from Spring Music Monolith

**Date:** 2026-06-11
**Status:** accepted

---

## Context

### The Problem

The Spring Music application (`legacy/`) is a Spring Boot Java monolith that manages a music album catalog. It serves a mixed audience: end users browsing and editing albums through an AngularJS 1.2.16 frontend, and operators querying deployment info and simulating failures for platform resilience testing.

The monolith mixes three distinct concerns under one deployable JAR:

1. **Album Catalog** — CRUD for album entities (`AlbumController`, `Album.java`)
2. **App Diagnostics** — Cloud Foundry environment introspection (`InfoController`)
3. **Chaos / Error Simulation** — deliberate crash injection (`ErrorController`)

These concerns have different change rates, different consumers, and different risk profiles. Bundling them into a single deployable unit means every change to any concern requires a full redeploy of the monolith, and a crash in the chaos simulation endpoints (by design) takes down the album catalog as collateral damage.

The goal of this modernization is to extract the Album Catalog into an independently deployable service using a strangler fig approach — intercepting traffic at the routing layer rather than performing a big-bang rewrite. The legacy monolith remains fully operational throughout the migration. Extraction is incremental; the monolith is only decommissioned endpoint-by-endpoint once the replacement has been validated in production traffic.

### The Legacy Codebase

The monolith entry points relevant to this ADR:

- `legacy/src/main/java/org/cloudfoundry/samples/music/web/AlbumController.java` — REST endpoints for album CRUD, backed by `CrudRepository<Album, String>`
- `legacy/src/main/java/org/cloudfoundry/samples/music/domain/Album.java` — JPA entity, 7 fields
- `legacy/src/main/java/org/cloudfoundry/samples/music/web/InfoController.java` — `/appinfo` and `/service` endpoints, CF environment introspection via `CfEnv`
- `legacy/src/main/java/org/cloudfoundry/samples/music/web/ErrorController.java` — `/errors/kill`, `/errors/throw`, `/errors/fill-heap`

The monolith supports multiple persistence backends via Spring profiles: JPA/H2 (default), MongoDB, Redis. The repository is injected as a `CrudRepository<Album, String>` — a Spring Data abstraction — which means the controller itself is storage-agnostic. The data model is defined once in `Album.java` and shared across all persistence profiles.

### Known Legacy Quirks (Locked Behaviors)

Before decomposing, we must document behaviors that cannot be silently "fixed" — they must be pinned by characterization tests in `tests/characterization/` and consciously decided upon in the new service contract.

| ID | Quirk | Source | Impact on Extraction |
|----|-------|--------|---------------------|
| LB-1 | `PUT /albums` creates; `POST /albums` updates — HTTP verbs are swapped from REST convention | `AlbumController.java:28-38` | The new service MUST use conventional verbs (`POST` = create, `PUT` = update). The legacy-to-new proxy layer must translate verb semantics at the boundary. |
| LB-2 | The AngularJS UI never calls `PUT /albums` — both add and update operations issue `POST /albums` via `Albums.save()` | `albums.js:saveAlbum()` | The UI will continue to work unchanged after extraction because it only hits `POST`. The broken `PUT` endpoint is effectively dead code from the UI's perspective. |
| LB-3 | `Album.java` contains two identity fields: `id` (JPA `@Id`, 40-char random string via `RandomIdGenerator`) and `albumId` (nullable `String` column, never populated by any known path) | `Album.java:16,23` | `albumId` is a ghost field. The new service's public API shape must not expose `albumId`. It will be dropped at the anti-corruption layer. |
| LB-4 | `GET /albums` returns the full collection; no server-side filtering exists. Genre filtering is client-side only in `albums.js` | `AlbumController.java:24` | The new service may optionally add server-side filtering, but the contract test must verify the full-collection baseline is preserved. |
| LB-5 | `trackCount` is declared as `int trackCount` in `Album.java` and returned in JSON responses, but the UI form has no `trackCount` input and `albums.json` seed data omits it (defaults to 0) | `Album.java:22`, `albumForm.html`, `albums.json` | `trackCount` will not appear in the new service's public API shape for the current scope. Clients relying on it in the legacy JSON response will need an explicit migration path. |
| LB-6 | Default Angular sort field is `"name"` but the actual field is `title`; initial album order is undefined | `albums.js:$scope.init()` | Frontend concern only; no impact on the API extraction. |
| LB-7 | `GET /albums/{id}` exists on `AlbumController` but is never called by the UI; all reads go through `GET /albums` | `AlbumController.java:40-43` | The endpoint must be preserved in the new service for API completeness and future consumers, but it has no UI characterization test coverage. |
| LB-8 | `POST /errors/fill-heap` exists in `ErrorController` but is not wired in the AngularJS UI (`errors.js`, `errors.html`) | `ErrorController.java:24-29` | Stays in the monolith (ErrorController is not being extracted in this waypoint). No action required. |

---

## Seam Analysis

A "seam" in this codebase is a point where one concern can be cut from another with minimal surgery to the remaining system. We evaluated three candidate services corresponding to the three `@RestController` classes.

### Candidate Services

**1. Album Catalog** (`AlbumController` + `Album.java`)

Endpoints: `GET /albums`, `PUT /albums` (create), `POST /albums` (update), `GET /albums/{id}`, `DELETE /albums/{id}`

`AlbumController` is injected with a `CrudRepository<Album, String>`. It has no dependencies on `InfoController` or `ErrorController`. The `Album` entity is the sole domain object — it is not referenced by either other controller. Data ownership is unambiguous: Album Catalog owns all album state.

**2. App Info** (`InfoController`)

Endpoints: `GET /appinfo`, `GET /service`

`InfoController` has a hard runtime dependency on `CfEnv` (Cloud Foundry environment SDK) and `Environment` (Spring context). It has no album data dependency. However, it is tightly coupled to the deployment platform: it only produces meaningful output when running inside CF with services bound. Extracting it as a standalone service would require either bundling the CF SDK into the new service or mocking it — both options add complexity with no business value. The `/appinfo` response is consumed by the AngularJS header dropdown (`header.html`); changing the host/port would require a frontend config change.

**3. Error Simulation** (`ErrorController`)

Endpoints: `GET /errors/kill`, `GET /errors/throw`, `GET /errors/fill-heap`

`ErrorController` is self-contained — no Spring data dependency, no album references. However, its behavior is fundamentally tied to the JVM process it runs in. `kill()` calls `System.exit(1)`, which terminates the containing process. Extracting it to an independent service would mean the kill endpoint kills the chaos service, not the monolith — defeating its purpose entirely. `fillHeap()` similarly only makes sense inside the monolith's JVM. This service must stay in the monolith.

### Risk Matrix

| Candidate | Coupling | Data Ownership | Test Coverage | Behavior Stability | Risk Rating | Recommended Order |
|-----------|----------|---------------|--------------|-------------------|-------------|------------------|
| Album Catalog (`AlbumController`) | Low — no cross-controller calls; `CrudRepository` is the only dependency | Sole owner of `Album` entity; no shared state with other controllers | High surface — all 5 endpoints reachable and testable; 6 user stories directly exercising CRUD paths | Stable — CRUD semantics well-understood; quirks documented and pinnable | **Low** | **1st** |
| App Info (`InfoController`) | Medium — runtime dependency on `CfEnv` and Spring `Environment`; outputs change with deployment platform | No domain data ownership; reads CF VCAP_SERVICES at runtime | Low — requires CF environment to produce meaningful responses; hard to characterize in local test | Unstable outside CF — `/appinfo` returns different values per deployment target | **Medium** | 3rd (do not extract in this project scope) |
| Error Simulation (`ErrorController`) | Low internal coupling but semantically coupled to host JVM | No domain data; side effects target the process it runs inside | Low — `kill` terminates the JVM; not safe to run in automated characterization suites | Fundamentally non-portable — `System.exit(1)` only makes sense in the monolith | **High** | Not extractable |

**Conclusion:** Album Catalog is the correct first extraction target. It has the lowest coupling, unambiguous data ownership, the richest behavioral surface area for characterization tests, and the highest business visibility (it is the primary user-facing feature of the application).

---

## Decision

We will extract the Album Catalog as the first independent service.

### New Service: Album Catalog

**Technology stack:** FastAPI (Python 3.11+). This choice is deliberate and contrasts with the legacy Java/Spring stack — demonstrating that the strangler fig pattern is stack-agnostic. The new service will expose a clean REST API using conventional HTTP verb semantics, correcting LB-1. It will have its own persistence layer (PostgreSQL or SQLite depending on environment), decoupled from the monolith's multi-profile Spring Data setup.

**Public API contract (new service):**

| Verb | Path | Semantics | Legacy equivalent |
|------|------|-----------|------------------|
| `GET` | `/albums` | List all albums | `GET /albums` |
| `POST` | `/albums` | Create a new album | `PUT /albums` (LB-1 fix) |
| `GET` | `/albums/{id}` | Fetch single album by ID | `GET /albums/{id}` |
| `PUT` | `/albums/{id}` | Full update of an existing album | `POST /albums` (LB-1 fix) |
| `DELETE` | `/albums/{id}` | Delete album by ID | `DELETE /albums/{id}` |

Note: `PUT /albums/{id}` (with ID in the path) is the correct REST pattern for updates. The legacy `POST /albums` passes the ID inside the request body. The new service will accept either form during the transition period via a compatibility shim in the proxy layer.

**Public API shape (new service response body):**

```json
{
  "id":          "string (server-generated, opaque)",
  "title":       "string",
  "artist":      "string",
  "release_year": "string",
  "genre":       "string"
}
```

Fields intentionally omitted from the public API:
- `albumId` — ghost field (LB-3); dropped entirely. The anti-corruption layer (ADR-002) enforces this at the boundary.
- `trackCount` — absent from UI and seed data (LB-5); dropped from initial public contract. Can be added in a future iteration with a dedicated migration.

Field name change: `releaseYear` (Java camelCase) becomes `release_year` (snake_case) to follow Python/JSON API conventions. The proxy layer translates field names at the boundary.

### Strangler Fig Topology

The strangler fig pattern works by intercepting a subset of traffic at the routing layer and redirecting it to the new service. The legacy monolith remains running and serves all other traffic.

```
                         ┌─────────────────────────────────────┐
Browser / API client     │  Reverse Proxy / API Gateway         │
    ──────────────────►  │                                      │
                         │  /api/albums/*  ──►  Album Catalog   │
                         │                      (FastAPI)        │
                         │  /albums/*      ──►  Monolith         │
                         │  /appinfo       ──►  Monolith         │
                         │  /errors/*      ──►  Monolith         │
                         └─────────────────────────────────────┘
```

During the transition:
- The legacy monolith continues to serve `/albums` unmodified. Existing integrations do not break.
- The new service is reachable at `/api/albums`. New integrations and the modernized AngularJS frontend (or replacement) use this path.
- Once validation is complete, the proxy is reconfigured to route `/albums` to the new service and the legacy album endpoints are decommissioned.

---

## Consequences

### What We Gain

- **Independent deployability.** The Album Catalog service can be deployed, scaled, and updated without touching the monolith. A failure in the monolith's ErrorController endpoints no longer takes down album CRUD.
- **Clean data model.** The new service's public contract is free of ghost fields (`albumId`) and unused fields (`trackCount`). Consumers get a predictable, documented shape.
- **Correct REST semantics (LB-1 fix).** `POST` creates, `PUT` updates. New consumers do not need to learn the inverted verb convention. The existing AngularJS UI continues to work because it only ever called `POST` (LB-2 — it never hit `PUT`).
- **Technology diversity validation.** A Python/FastAPI service alongside a Java/Spring monolith proves the architecture is not language-locked.
- **Testability.** The new service can be tested with a standard HTTP test suite without needing a full Spring Boot context, multiple DB profiles, or a CF environment.
- **Characterization test baseline.** Pinning legacy behaviors before extraction gives us a regression safety net and documents the quirks for any future consumer of the monolith.

### What We Give Up / What Gets Harder

- **Dual running systems.** During the transition period both the monolith and the new service serve album data. This requires synchronization discipline: writes that reach the monolith must not diverge from writes that reach the new service.
- **Data sync risk.** Until traffic is fully cut over, two persistence stores exist for album data. A synchronization bug (a write going to the wrong store, or a read cache serving stale data from the monolith) can cause user-visible inconsistency. This risk is mitigated by routing all writes to one store during transition and treating the other as read-only.
- **Proxy operational complexity.** The strangler fig requires a routing layer. That layer is new infrastructure to configure, monitor, and maintain.
- **Field name translation overhead.** The `releaseYear` → `release_year` rename and verb inversion (LB-1) require a translation layer in the proxy. This must be implemented carefully to avoid silent data corruption.
- **Loss of multi-DB profile flexibility.** The legacy monolith supports H2/JPA, MongoDB, and Redis via Spring profiles. The new service will initially support a single database backend. Operators who were relying on the H2 in-memory profile for local development will need an alternative setup.

---

## What We Chose Not To Do

### Big-Bang Rewrite

Rewriting the entire monolith from scratch in a single effort was rejected. A big-bang rewrite:
- Produces no deployable value until the entire rewrite is complete
- Carries high risk of behavioral regression — the legacy quirks (LB-1 through LB-8) are exactly the kind of behavior that gets accidentally "fixed" and then causes downstream breakage
- Requires maintaining two full codebases in parallel until the cutover, rather than shrinking the monolith incrementally

### Extracting Info or Errors First

`InfoController` was rejected as a first extraction target because it has a hard dependency on Cloud Foundry runtime bindings. Extracting it would require mocking or bundling CF infrastructure, adding complexity with no business user value.

`ErrorController` was rejected as a first extraction target because its core behaviors (`System.exit(1)`, heap exhaustion) are semantically coupled to the JVM process they run inside. An "extracted" error simulation service that kills itself rather than the application under test is not useful.

### Keeping the Same Java/Spring Stack for the New Service

We chose FastAPI (Python) deliberately to demonstrate stack independence. Using Spring Boot for the extracted service would have been less complex operationally, but it would have obscured an important architectural point: the strangler fig works regardless of the replacement technology. Future extractions may use different stacks based on the domain's requirements (e.g., a Node.js BFF for the frontend, a Go service for high-throughput paths).

### Exposing Legacy Field Names in the New API

We chose not to preserve `albumId` and `trackCount` in the new public API. Preserving them would:
- Carry the ghost field semantics into the new service with no business justification
- Make it harder to reason about the data model going forward
- Violate the anti-corruption layer principle (the new service's contract should reflect the new bounded context, not the legacy persistence model)

Consumers that depend on `trackCount` (none are known) will need an explicit migration path. This is preferable to silently inheriting technical debt.

---

## Migration Path

The extraction proceeds in four phases. Each phase is independently deployable; the system is functional and safe to operate at the end of each phase.

### Phase 1 — Extract Album Catalog (Current Waypoint)

**Goal:** Stand up the FastAPI Album Catalog service and validate it against pinned legacy behavior.

Steps:
1. Write characterization tests in `tests/characterization/` that pin the current behavior of all five `AlbumController` endpoints, including the LB-1 verb inversion and LB-3/LB-5 ghost field presence in responses.
2. Implement the FastAPI service in `services/album-catalog/`. Endpoints: `GET /albums`, `POST /albums` (create), `GET /albums/{id}`, `PUT /albums/{id}` (update), `DELETE /albums/{id}`.
3. Write contract tests in `tests/contract/` that verify the new service's response shapes against the agreed public API (snake_case fields, no `albumId`, no `trackCount`).
4. The new service runs with its own persistence store. No connection to the monolith's database.
5. The monolith continues to serve all `/albums` traffic. The new service is reachable at a separate port/path for testing only.

**Exit criteria:** All characterization tests green against the monolith. All contract tests green against the new service.

### Phase 2 — Anti-Corruption Layer / The Fence

**Goal:** Enforce that the legacy data model does not leak into the new service's public API shape.

Steps:
1. Implement the Fence: a translation layer (can be a Pydantic schema with explicit field exclusions, or a dedicated mapper module) that prevents `albumId`, `trackCount`, and camelCase field names from appearing in any response the new service emits.
2. Add Fence tests that assert: if a legacy-shaped object is passed through the Fence, the output conforms to the new API shape and contains none of the excluded fields.
3. Document the Fence rules in `docs/adr/002-anti-corruption-layer.md`.

**Exit criteria:** Fence tests green. Code review confirms no legacy field name appears in any public API response schema in `services/album-catalog/`.

### Phase 3 — Traffic Cutover (The Pin)

**Goal:** Route production `/albums` traffic to the new service while keeping the monolith running as a fallback.

Steps:
1. Configure the reverse proxy to route `/albums` requests to the new service. The monolith's `/albums` endpoints remain active but receive no traffic.
2. Implement a compatibility shim in the proxy to handle the LB-1 verb inversion: incoming `POST /albums` (create) from legacy clients is translated to `POST /albums` (new service, create). Incoming `PUT /albums` (legacy, create) is blocked or redirected with a deprecation header.
3. Implement field name translation in the proxy for any client that sends camelCase field names (`releaseYear` → `release_year`).
4. Run both systems in shadow mode: the proxy sends writes to the new service and asynchronously mirrors them to the monolith. Compare responses; alert on divergence.
5. When shadow mode shows zero divergence over a validation window, disable the mirror and cut over fully.

**Exit criteria:** All `/albums` traffic served by the new service. Monolith album endpoints return 200 but receive no live traffic. No consumer-visible regressions (verified by re-running characterization test suite against the new service).

### Phase 4 — Decommission Legacy Album Endpoints

**Goal:** Remove album CRUD from the monolith, completing the strangler fig extraction for this domain.

Steps:
1. Remove `AlbumController.java`, `Album.java`, `RandomIdGenerator.java`, and related repository wiring from `legacy/`.
2. Update the monolith's Spring Data configuration to remove the album entity scan.
3. Remove album-related database migration scripts and seed data from the monolith.
4. Update `docs/stories.md` to mark Album Catalog stories as "served by `services/album-catalog/`".
5. Archive the characterization tests for the monolith's album endpoints (they no longer apply) and promote the contract tests to the primary regression suite.

**Exit criteria:** `legacy/` no longer contains any album domain code. The monolith still serves `/appinfo`, `/service`, `/errors/*` unchanged. The new service serves all album traffic. All contract tests green.

---

## References

- Legacy source: `legacy/src/main/java/org/cloudfoundry/samples/music/web/AlbumController.java`
- Legacy source: `legacy/src/main/java/org/cloudfoundry/samples/music/domain/Album.java`
- Legacy source: `legacy/src/main/java/org/cloudfoundry/samples/music/web/InfoController.java`
- Legacy source: `legacy/src/main/java/org/cloudfoundry/samples/music/web/ErrorController.java`
- Behavioral inventory: `docs/stories.md`
- ADR template: `docs/adr/000-template.md`
- Hackathon challenge: https://github.com/carmatthews/claude-code-hackathon/blob/main/01-code-modernization.md
