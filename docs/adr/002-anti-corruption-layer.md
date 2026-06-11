# ADR-002: Anti-Corruption Layer (The Fence)

**Date:** 2026-06-11  
**Status:** accepted

## Context

The legacy Spring Music monolith (`legacy/`) exposes an Album entity with several
field names and behavioral patterns that must not leak into the new Album Catalog
service's public API contract.  Specific offenders:

| Legacy field / behavior | Problem |
|---|---|
| `albumId` | 40-char random string ghost field — a secondary identifier with no semantic meaning in the new domain |
| `trackCount` | camelCase Java convention — new service uses Python snake_case `track_count` |
| `releaseYear` | Out-of-scope field — Album Catalog does not model release year in its v1 domain |
| `_class` | MongoDB/Spring Data type hint — infrastructure artifact that must not appear in a domain API |
| `PUT` = create, `POST` = update | Reversed HTTP verbs — violates REST conventions and confuses API consumers |

Without an explicit translation layer, any engineer adding a new endpoint or extending
the data model could accidentally copy a legacy field name into the new service (e.g.
by copying a payload directly from the monolith response), causing a silent contract breach.

## Decision

Implement an explicit Anti-Corruption Layer as `services/album-catalog/fence.py` with
three public functions:

1. **`from_legacy(legacy_dict) -> AlbumCreate`** — translates inbound legacy payloads to
   the clean domain model (drops `albumId`, `_class`, `releaseYear`; renames `trackCount`
   to `track_count`).

2. **`to_legacy_shape(album) -> dict`** — translates clean `Album` objects back to the
   legacy wire format (adds `albumId=""`, renames `track_count` to `trackCount`).  Used
   only by the strangler-fig proxy router during the cut-over period.

3. **`validate_no_legacy_leak(album_dict) -> None`** — raises `ValueError` if the
   provided dict contains any of the known legacy field names (`albumId`, `trackCount`,
   `_class`, `releaseYear`).  Call this at test time on any dict that crosses the
   boundary out of the clean service.

A companion router `services/album-catalog/routers/legacy_compat.py` is registered at
`/api/legacy-compat` so that legacy consumers can be cut over gradually without changes
to their clients.  All calls through this router pass through `from_legacy` on the way
in and `to_legacy_shape` on the way out.

The fence's translation contract is enforced by dedicated pytest tests in
`tests/contract/test_fence.py` which cover both unit-level translation and
integration-level endpoint behaviour.

## Consequences

**Easier:**
- Any future engineer can call `validate_no_legacy_leak` on a response dict and
  immediately know whether the ACL has been violated.
- The translation boundary is explicit, readable, and testable — no implicit
  "everyone just knows not to use `albumId`" convention.
- Contract tests provide a regression safety net: a future refactor that accidentally
  reintroduces a legacy field will be caught before it ships.
- The legacy-compat router gives a clear deprecation path; when it is removed, the
  fence module and all its tests remain as documentation of the migration history.

**Harder / constrained:**
- Every call that crosses the legacy boundary incurs a translation step (dict
  construction + Pydantic validation).  For the album catalog domain at current
  scale this is negligible, but it is a cost to acknowledge.
- Adding a new field to `AlbumCreate` requires a conscious decision about whether
  it also needs a legacy-shape mapping — the fence module is the single place to
  make that decision.

## What We Chose Not To Do

**Rely on developer discipline alone.** Naming conventions and code-review comments
cannot be automatically verified.  The fence makes the contract machine-checkable.

**Expose legacy fields in the new API "temporarily".** "Temporary" compatibility shims
have a long history of becoming permanent.  By routing legacy consumers through a
dedicated `/api/legacy-compat` prefix that is explicitly labelled and isolated, the
cut-over timeline remains visible and the clean API is never contaminated.

**Re-use the legacy Java model via a shared DTO library.** Sharing data structures
across the boundary would couple the new service's release cycle to the monolith's
and defeat the strangler-fig strategy.

**Silently ignore unknown fields.** Pydantic by default ignores extra fields, which
would silently absorb `albumId` without error.  The explicit `from_legacy` function
makes the drop intentional and documented rather than incidental.
