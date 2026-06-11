# Project Planning

## Open Decisions

### 1. Legacy Flavor
Pick one — determines what Claude generates for "The Patient":

| Option | Notes |
|--------|-------|
| PHP 5 monolith | `index.php`, inline SQL, sessions in globals |
| Early Node callback hell | Express 3, callbacks 6 deep, Mongoose pre-save hooks |
| Enterprise Java 2010 | Spring XML config, god classes, WAR on WebLogic |
| Stored-proc architecture | 40 T-SQL procs, thin shell app |

**Decision:** _pending_

### 2. Business Domain
- Option A: Factory inventory (orders, warehouses, SKUs, demand/backlog)
- Option B: Northwind Logistics (as described in hackathon brief)

**Decision:** _pending_

### 3. Target Architecture
- Strangler fig (incremental extraction around the edges)
- API facade (put a clean HTTP layer in front, extract behind it)
- Event-driven (domain events as the seam)

**Decision:** _pending_

---

## Waypoints

From the hackathon brief — mark as in-progress or done as we go:

| # | Waypoint | Owner | Status |
|---|----------|-------|--------|
| 1 | The Stories — user stories + acceptance criteria | | pending |
| 2 | The Patient — generate the legacy monolith | | pending |
| 3 | The Map — decomposition ADR, seam ranking | | pending |
| 4 | The Pin — characterization tests | | pending |
| 5 | The Cut — extract first service | | pending |
| 6 | The Fence — anti-corruption layer + test | | pending |
| 7 | The Scorecard — eval harness for LLM refactoring | | pending |
| 8 | The Weekend — cutover runbook | stretch | pending |
| 9 | The Scouts — fan-out subagent risk analysis | stretch | pending |

---

## Suggested Work Order

1. Resolve the 3 open decisions above
2. Generate The Patient (Waypoint 2) — needs legacy flavor + domain
3. Write The Stories (Waypoint 1) — needs domain
4. Write The Map ADR (Waypoint 3) — needs The Patient to exist
5. Write characterization tests — The Pin (Waypoint 4)
6. Extract first service — The Cut (Waypoint 5)
7. Add anti-corruption layer — The Fence (Waypoint 6)
