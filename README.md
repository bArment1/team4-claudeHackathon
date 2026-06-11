# Team 4 — Claude Code Hackathon

**Track:** Code Modernization (Scenario 1 — "The Monolith")

Northwind Logistics runs on something old. It works, mostly, but the people who built it are gone, the docs are a folder of outdated Word files, and the board just approved "modernization" without defining what that means. We prove it can be evolved safely without a big-bang rewrite.

Hackathon brief: https://github.com/carmatthews/claude-code-hackathon/blob/main/01-code-modernization.md

## Team

| Name | GitHub | Role |
|------|--------|------|
| barment@deloitte.com | | Lead |

## Our Approach

- **Legacy:** Spring Music — Spring Boot Java monolith with JPA/MongoDB/Redis profiles and an AngularJS 1.2.16 frontend
- **Domain:** Music album catalog (title, artist, genre, track count, price) — CRUD via REST
- **Target architecture:** Strangler fig + API facade — Album Catalog extracted as a standalone FastAPI Python service running alongside the monolith

We documented 8 legacy behavioral quirks before touching anything (LB-1 through LB-8), pinned them as characterization tests, then extracted the Album Catalog service with corrected REST semantics and an explicit anti-corruption layer preventing legacy field shapes from leaking into the new API.

## Waypoints Completed

| # | Waypoint | Artifact |
|---|----------|---------|
| 1 | The Stories | [docs/stories.md](docs/stories.md) — 13 user stories, 8 legacy quirks |
| 2 | The Patient | [legacy/](legacy/) — Spring Music monolith (read-only) |
| 3 | The Map | [docs/adr/001-decomposition-strategy.md](docs/adr/001-decomposition-strategy.md) — seam ranking, migration phases |
| 4 | The Pin | [tests/characterization/](tests/characterization/) — 39 MockMvc tests pinning all bugs |
| 5 | The Cut | [services/album-catalog/](services/album-catalog/) — FastAPI service, 30 contract tests |
| 6 | The Fence *(stretch)* | [services/album-catalog/fence.py](services/album-catalog/fence.py) — ACL + 24 fence tests, [docs/adr/002-anti-corruption-layer.md](docs/adr/002-anti-corruption-layer.md) |

**54 tests total, all passing.**

## Key Findings in the Legacy Code

| ID | Quirk | Impact |
|----|-------|--------|
| LB-1 | `PUT /albums` creates; `POST /albums` updates — verbs are swapped | Fixed in new service |
| LB-2 | UI never calls `PUT` — both add and update go through `POST` | Documented, not replicated |
| LB-3 | Two identity fields: `id` (JPA key) and `albumId` (ghost 40-char string) | `albumId` dropped in new API |
| LB-4 | Genre filtering is entirely client-side — server ignores `?genre=` | Server-side filtering added |
| LB-5 | `trackCount` in model, absent from UI form and all seed data | Made optional (`track_count`) |
| LB-6 | Default sort field is `"name"` but no such field exists on `Album` | New service sorts by `title` |
| LB-7 | `GET /albums/{id}` exists but is never called by the UI | Endpoint retained, behaviour pinned |
| LB-8 | `POST /errors/fill-heap` exists in Java but not exposed in the UI | Documented, not extracted |

## Repo Structure

```
docs/
  adr/                          Architecture Decision Records
    001-decomposition-strategy  Strangler fig seam analysis + migration plan
    002-anti-corruption-layer   The Fence — ACL design and rationale
  planning.md                   Locked decisions, work order, copy-paste prompts
  stories.md                    13 user stories + legacy quirks inventory

legacy/                         Spring Music monolith (read-only after characterization tests)

services/
  album-catalog/                Extracted FastAPI service
    fence.py                    Anti-corruption layer (legacy ↔ clean shape translation)
    routers/albums.py           Clean REST endpoints (POST=create, PUT=update)
    database.py                 In-memory store seeded from legacy albums.json

tests/
  characterization/             MockMvc tests pinning legacy behavior (bugs and all)
  contract/                     Contract + fence tests for the new service
```

## Running the New Service

```bash
cd services/album-catalog
pip install -r requirements.txt
uvicorn main:app --reload
# API available at http://localhost:8000/api/albums
```

## Running the Contract Tests

```bash
cd services/album-catalog
pip install -r requirements.txt
pytest ../../tests/contract/ -v
```

## Running the Characterization Tests

```bash
cd legacy
./gradlew test --tests "org.cloudfoundry.samples.music.characterization.*"
# Requires Java 11+. Tests live in legacy/src/test/java/org/cloudfoundry/samples/music/characterization/
# To run the full test suite (including the original ApplicationTests):
./gradlew test
```

## Key Decisions

See [docs/planning.md](docs/planning.md) and [docs/adr/](docs/adr/) for the full rationale. Check [CLAUDE.md](CLAUDE.md) for project conventions and the legacy write-guard hook.
