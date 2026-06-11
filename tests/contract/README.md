# Contract Tests — Album Catalog

These tests define and enforce the interface contract between the
**Album Catalog microservice** and any proxy/gateway that sits in front of it
during the strangler fig migration.

---

## What "contract" means here

In a strangler fig migration, the proxy must route identical requests to either
the legacy monolith or the new service and the caller must not be able to tell
the difference.  These tests pin the *new* service's public behavior so that:

1. Any regression in the service is caught immediately.
2. When the proxy is built, it can use these same tests to verify it correctly
   forwards and translates requests.
3. The anti-corruption layer (no `albumId` leakage, correct verbs) is
   continuously validated.

---

## What is covered

| Test area | Tests | Contract rule |
|-----------|-------|---------------|
| List all albums | `test_list_albums_*` | 29 seed albums, sorted by title, JSON array |
| Genre filter | `test_list_albums_genre_filter_*` | `?genre=X` returns only matching albums (server-side) |
| Get single | `test_get_album_by_id_*` | Known id → 200 + correct album; unknown → 404 |
| Create | `test_create_album_*` | POST → 201 + album with id; missing fields → 422 |
| Update | `test_update_album_*` | PUT → 200 + updated; unknown id → 404; partial update preserves other fields |
| Delete | `test_delete_album_*` | DELETE → 204; subsequent GET → 404; unknown id → 404 |
| Response shape | `test_album_response_*`, `test_*_no_albumId` | Must have id/title/artist/genre; must NOT have albumId |
| Health | `test_health_*` | GET /health → 200 `{"status":"ok","service":"album-catalog"}` |

---

## How to run

```bash
# From repo root
pip install -r services/album-catalog/requirements.txt
pytest tests/contract/test_album_catalog_contract.py -v
```

Tests run entirely in-process via FastAPI `TestClient` — no running server
or legacy monolith needed.

---

## Legacy quirks addressed by this contract

| Quirk | Legacy behavior | Contract enforces |
|-------|----------------|-------------------|
| LB-1 | PUT=create, POST=update | POST=create (201), PUT=update (200) |
| LB-3 | Two identity fields: id + albumId | `albumId` absent from all responses |
| LB-4 | No server-side genre filter | `?genre=X` filter works and returns correct subset |
| LB-6 | Default sort by non-existent "name" field | Albums sorted by `title` ascending |
