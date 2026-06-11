# Project Planning

## Decisions (locked)

| Decision | Choice |
|----------|--------|
| Legacy flavor | **Spring Music** — Spring Boot Java BYO monolith (https://github.com/rishikeshradhakrishnan/spring-music). Already committed to `legacy/`. |
| Business domain | **Music albums** — title, artist, genre, track count, price. CRUD via REST API. |
| Target architecture | **Strangler fig + API facade** — extract Album Catalog as a standalone service; legacy stays running alongside |
| Waypoints in scope | #1 Stories, #3 Map, #4 Pin, #5 Cut — stretch: #6 Fence |

---

## Waypoints

| # | Waypoint | Owner | Status |
|---|----------|-------|--------|
| 1 | The Stories — user stories + acceptance criteria | | pending |
| 2 | The Patient — Spring Music in `legacy/` | | **done** |
| 3 | The Map — decomposition ADR, seam ranking | | pending |
| 4 | The Pin — characterization tests | | pending |
| 5 | The Cut — extract Album Catalog service | | pending |
| 6 | The Fence — anti-corruption layer + test | | stretch |
| 7 | The Scorecard — eval harness for LLM refactoring | | out of scope |
| 8 | The Weekend — cutover runbook | | out of scope |
| 9 | The Scouts — fan-out subagent risk analysis | | out of scope |

---

## Team Roles

| Role | Waypoints | Person |
|------|-----------|--------|
| PM / BA | The Stories (#1) | |
| Architect | The Map (#3) + ADR | |
| Dev Lead | The Pin (#4) — characterization tests | |
| Dev | The Cut (#5) — extract Album service | |
| Floater / Demo | Narrative, README update, demo prep | |

---

## Work Order (2-hour hackathon)

- **T+0:00** All: clone repo, run `cd legacy && ./mvnw spring-boot:run`, assign roles (15 min)
- **T+0:15** Parallel:
  - Track A (PM): write `docs/stories.md`
  - Track A (Architect): write `docs/adr/001-decomposition-strategy.md`
  - Track B (Dev Lead): write characterization tests → `tests/characterization/`
  - Track B (Dev): extract Album Catalog → `services/album-catalog/`
- **T+1:15** All: sync checkpoint — merge branches, check what's green
- **T+1:25** Stretch: The Fence → `tests/contract/fence_test.*`
- **T+1:45** All: demo prep — update this file, push everything

---

## Claude Prompts (copy-paste ready)

**The Stories (PM):**
> "Write user stories for a Spring Boot music catalog app. Capabilities: browse albums by genre, add a new album, edit an album, delete an album. Each story needs: role, goal, acceptance criteria (testable), and a note on what a tester would verify. Format as a markdown table. Save to docs/stories.md."

**The Map (Architect):**
> "Write an ADR for decomposing Spring Music (Spring Boot monolith) using the strangler fig pattern. Spring Music stores album data (title, artist, album, genre, track count, price) and supports CRUD via a REST API backed by multiple DB profiles (MySQL, MongoDB, Redis). Identify the main seams, rank 3 candidate services by extraction risk (coupling, data ownership, test coverage), include a 'what we chose not to do' section. Use the template at docs/adr/000-template.md. Save to docs/adr/001-decomposition-strategy.md."

**The Pin (Dev Lead):**
> "Write characterization tests for the Spring Boot REST API in legacy/. Read the AlbumController and Album domain class first. Tests should pin existing behavior including edge cases. Use Spring Boot's MockMvc. Goal is behavior pinning, not correctness — note any bugs being pinned intentionally. Save to tests/characterization/."

**The Cut (Dev):**
> "Extract an Album Catalog microservice from the Spring Music monolith in legacy/. The new service should own album CRUD (GET /api/albums, POST /api/albums, PUT /api/albums/{id}, DELETE /api/albums/{id}), use its own clean data model (no leaking of Spring Music internal field names), and include one contract test. Use FastAPI (Python) to contrast with the legacy Java stack. Save to services/album-catalog/."
