# Characterization Tests — Spring Music Monolith

## Purpose

These tests **pin the existing behavior** of the Spring Music monolith, including its known bugs.
They are NOT correctness tests. If the legacy code does something wrong, the test captures that
wrong behavior — intentionally.

The rule: **a characterization test must turn red if you change the legacy behavior**, even if that
change is a fix. That's what makes them a safe net for the strangler-fig extraction.

Before fixing any pinned bug in the new Album Catalog service, confirm:
1. The new service reproduces the correct behavior
2. The anti-corruption layer (The Fence) handles the difference
3. The relevant characterization test is updated or retired with an ADR note

---

## Known Bugs Being Intentionally Pinned

| ID | Bug | Class | Method |
|----|-----|-------|--------|
| LB-1 | `PUT /albums` creates; `POST /albums` updates — HTTP verbs are backwards | `AlbumControllerCharacterizationTest` | `putAlbumsCreatesNewAlbum`, `postAlbumsUpdatesExistingAlbum` |
| LB-2 | UI never calls `PUT /albums`; both add and update use POST | `AlbumControllerCharacterizationTest` | `postAlbumsWithNoIdCreatesAlbum` |
| LB-3 | Two identity fields: `id` (JPA PK, UUID-style) and `albumId` (nullable extra field) | `AlbumControllerCharacterizationTest` | `getAlbumsByIdUsesJpaPrimaryKey`, `createdAlbumHasNullAlbumId` |
| LB-4 | No server-side genre filtering — `GET /albums` ignores query params | `AlbumControllerCharacterizationTest` | `getAlbumsIgnoresGenreQueryParam` |
| LB-5 | `trackCount` absent from UI/seed data; defaults to 0 | `AlbumControllerCharacterizationTest` | `createAlbumWithoutTrackCountDefaultsToZero` |
| LB-6 | Default sort field in Angular is `"name"` which doesn't exist on Album | (client-side only; documented but not testable server-side) | N/A |
| LB-7 | `GET /albums/{id}` exists but is never called by the UI | `AlbumControllerCharacterizationTest` | `getAlbumByIdReturnsAlbum`, `getAlbumByNonexistentIdReturnsNull` |
| LB-8 | `GET /errors/fill-heap` exists on server but has no UI button | `ErrorControllerCharacterizationTest` | `fillHeapEndpointIsReachable` |

---

## Test Files

| File | Covers |
|------|--------|
| `AlbumControllerCharacterizationTest.java` | All `/albums` endpoints — CRUD, verb swap bugs, seed data, identity fields |
| `InfoControllerCharacterizationTest.java` | `/appinfo` and `/service` endpoints |
| `ErrorControllerCharacterizationTest.java` | `/errors/kill`, `/errors/throw`, `/errors/fill-heap` |

---

## How to Run

These tests live outside the `legacy/` source tree intentionally (they are observer tests, not part
of the application). To run them you need to copy or symlink them into the legacy test source root,
or configure Gradle to pick up an additional test source set.

Quick path — from `legacy/`:

```bash
# Copy tests into the legacy test tree (one-time setup)
cp -r ../tests/characterization/src legacy/src/test/java/org/cloudfoundry/samples/music/characterization

# Run only characterization tests
./gradlew test --tests "org.cloudfoundry.samples.music.characterization.*"
```

The tests use `@SpringBootTest` + `@AutoConfigureMockMvc` and run against the in-memory H2 database.
No external services are required.

---

## Conventions

- Every method that pins a bug carries a `// PINS BUG LB-N` comment block at the top.
- Methods that document CORRECT (non-bug) behavior use `// PINS BEHAVIOR` instead.
- `TODO` comments flag behaviors that are difficult to characterize without a running server or
  that have non-deterministic output (e.g., insertion order of seed data).
