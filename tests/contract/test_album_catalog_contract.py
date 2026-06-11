"""
Contract tests for the Album Catalog microservice.

These tests define the interface contract that the strangler fig proxy must
honor when traffic is cut over from the Spring Music monolith.  They run
entirely in-process via FastAPI TestClient — no running server required.

Contract enforced:
  - Correct REST verbs (POST=create, PUT=update) — fixes legacy LB-1
  - No albumId field in any response — anti-corruption layer, legacy LB-3
  - Server-side genre filtering — improvement over legacy LB-4
  - Sorted by title — fixes legacy LB-6 (legacy sorted by non-existent "name")
  - 29 seed albums loaded from legacy/src/main/resources/albums.json
"""
from __future__ import annotations

import sys
import os

# Make the service importable without installing it.
SERVICE_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "services", "album-catalog")
sys.path.insert(0, os.path.abspath(SERVICE_DIR))

import pytest
from fastapi.testclient import TestClient

# Reset the in-memory database before each test so tests are isolated.
import database as db_module
from main import app

SEED_COUNT = 29


@pytest.fixture(autouse=True)
def reset_db():
    """Reload the seed data before every test for full isolation."""
    db_module.db = db_module._load_seed()
    yield
    db_module.db = db_module._load_seed()


@pytest.fixture
def client():
    return TestClient(app)


# ---------------------------------------------------------------------------
# 1. GET /api/albums — returns all 29 seed albums
# ---------------------------------------------------------------------------

def test_list_albums_returns_all_seed_albums(client):
    response = client.get("/api/albums")
    assert response.status_code == 200
    albums = response.json()
    assert isinstance(albums, list)
    assert len(albums) == SEED_COUNT


# ---------------------------------------------------------------------------
# 2. GET /api/albums?genre=Rock — server-side genre filter (LB-4 improvement)
# ---------------------------------------------------------------------------

def test_list_albums_genre_filter_rock(client):
    response = client.get("/api/albums?genre=Rock")
    assert response.status_code == 200
    albums = response.json()
    assert len(albums) > 0
    assert all(a["genre"] == "Rock" for a in albums)


def test_list_albums_genre_filter_blues(client):
    response = client.get("/api/albums?genre=Blues")
    assert response.status_code == 200
    albums = response.json()
    assert len(albums) > 0
    assert all(a["genre"] == "Blues" for a in albums)


def test_list_albums_genre_filter_nonexistent_returns_empty(client):
    response = client.get("/api/albums?genre=Jazz")
    assert response.status_code == 200
    assert response.json() == []


def test_list_albums_genre_filter_case_sensitive(client):
    # "rock" (lowercase) should not match "Rock"
    response = client.get("/api/albums?genre=rock")
    assert response.status_code == 200
    assert response.json() == []


def test_list_albums_sorted_by_title(client):
    """List must come back sorted by title (fixes LB-6)."""
    response = client.get("/api/albums")
    titles = [a["title"] for a in response.json()]
    assert titles == sorted(titles, key=str.lower)


# ---------------------------------------------------------------------------
# 3. GET /api/albums/{id} — known id returns correct album
# ---------------------------------------------------------------------------

def test_get_album_by_id_returns_correct_album(client):
    # Grab the first id from the list
    all_albums = client.get("/api/albums").json()
    target = all_albums[0]
    response = client.get(f"/api/albums/{target['id']}")
    assert response.status_code == 200
    assert response.json()["id"] == target["id"]
    assert response.json()["title"] == target["title"]


# ---------------------------------------------------------------------------
# 4. GET /api/albums/{nonexistent-id} — returns 404
# ---------------------------------------------------------------------------

def test_get_album_not_found(client):
    response = client.get("/api/albums/does-not-exist-00000000")
    assert response.status_code == 404


# ---------------------------------------------------------------------------
# 5. POST /api/albums — creates album, returns 201, has id field
# ---------------------------------------------------------------------------

def test_create_album_returns_201_with_id(client):
    payload = {"title": "Kind of Blue", "artist": "Miles Davis", "genre": "Jazz"}
    response = client.post("/api/albums", json=payload)
    assert response.status_code == 201
    body = response.json()
    assert "id" in body
    assert body["id"]  # non-empty
    assert body["title"] == "Kind of Blue"
    assert body["artist"] == "Miles Davis"
    assert body["genre"] == "Jazz"


def test_create_album_appears_in_list(client):
    payload = {"title": "Kind of Blue", "artist": "Miles Davis", "genre": "Jazz"}
    created = client.post("/api/albums", json=payload).json()
    all_albums = client.get("/api/albums").json()
    ids = [a["id"] for a in all_albums]
    assert created["id"] in ids
    assert len(all_albums) == SEED_COUNT + 1


def test_create_album_with_optional_fields(client):
    payload = {
        "title": "Kind of Blue",
        "artist": "Miles Davis",
        "genre": "Jazz",
        "track_count": 5,
        "price": 9.99,
    }
    response = client.post("/api/albums", json=payload)
    assert response.status_code == 201
    body = response.json()
    assert body["track_count"] == 5
    assert body["price"] == 9.99


# ---------------------------------------------------------------------------
# 6. POST /api/albums with missing required fields — returns 422
# ---------------------------------------------------------------------------

def test_create_album_missing_title_returns_422(client):
    response = client.post("/api/albums", json={"artist": "Miles Davis", "genre": "Jazz"})
    assert response.status_code == 422


def test_create_album_missing_artist_returns_422(client):
    response = client.post("/api/albums", json={"title": "Kind of Blue", "genre": "Jazz"})
    assert response.status_code == 422


def test_create_album_missing_genre_returns_422(client):
    response = client.post("/api/albums", json={"title": "Kind of Blue", "artist": "Miles Davis"})
    assert response.status_code == 422


def test_create_album_empty_body_returns_422(client):
    response = client.post("/api/albums", json={})
    assert response.status_code == 422


# ---------------------------------------------------------------------------
# 7. PUT /api/albums/{id} — updates album fields, returns 200
# ---------------------------------------------------------------------------

def test_update_album_returns_200_with_updated_fields(client):
    album_id = client.get("/api/albums").json()[0]["id"]
    response = client.put(f"/api/albums/{album_id}", json={"title": "Updated Title"})
    assert response.status_code == 200
    assert response.json()["title"] == "Updated Title"
    assert response.json()["id"] == album_id


def test_update_album_partial_update_preserves_other_fields(client):
    original = client.get("/api/albums").json()[0]
    album_id = original["id"]
    original_artist = original["artist"]

    client.put(f"/api/albums/{album_id}", json={"title": "New Title"})
    fetched = client.get(f"/api/albums/{album_id}").json()
    assert fetched["title"] == "New Title"
    assert fetched["artist"] == original_artist  # unchanged


def test_update_album_reflected_in_list(client):
    album_id = client.get("/api/albums").json()[0]["id"]
    client.put(f"/api/albums/{album_id}", json={"genre": "Classical"})
    all_albums = client.get("/api/albums").json()
    match = next(a for a in all_albums if a["id"] == album_id)
    assert match["genre"] == "Classical"


# ---------------------------------------------------------------------------
# 8. PUT /api/albums/{nonexistent-id} — returns 404
# ---------------------------------------------------------------------------

def test_update_album_not_found(client):
    response = client.put("/api/albums/does-not-exist-00000000", json={"title": "X"})
    assert response.status_code == 404


# ---------------------------------------------------------------------------
# 9. DELETE /api/albums/{id} — removes album, subsequent GET returns 404
# ---------------------------------------------------------------------------

def test_delete_album_returns_204(client):
    album_id = client.get("/api/albums").json()[0]["id"]
    response = client.delete(f"/api/albums/{album_id}")
    assert response.status_code == 204


def test_delete_album_not_in_list_after_delete(client):
    album_id = client.get("/api/albums").json()[0]["id"]
    client.delete(f"/api/albums/{album_id}")
    all_albums = client.get("/api/albums").json()
    assert len(all_albums) == SEED_COUNT - 1
    ids = [a["id"] for a in all_albums]
    assert album_id not in ids


def test_delete_album_get_returns_404(client):
    album_id = client.get("/api/albums").json()[0]["id"]
    client.delete(f"/api/albums/{album_id}")
    response = client.get(f"/api/albums/{album_id}")
    assert response.status_code == 404


def test_delete_nonexistent_album_returns_404(client):
    response = client.delete("/api/albums/does-not-exist-00000000")
    assert response.status_code == 404


# ---------------------------------------------------------------------------
# 10. Response shape — must have expected fields; must NOT have albumId
# ---------------------------------------------------------------------------

def test_album_response_has_required_fields(client):
    album = client.get("/api/albums").json()[0]
    for field in ("id", "title", "artist", "genre"):
        assert field in album, f"Missing required field: {field}"


def test_album_response_has_no_albumId_field(client):
    """Anti-corruption layer: albumId must never appear in public API responses."""
    album = client.get("/api/albums").json()[0]
    assert "albumId" not in album


def test_album_list_no_albumId_in_any_record(client):
    albums = client.get("/api/albums").json()
    for album in albums:
        assert "albumId" not in album, (
            f"albumId leaked into response for album id={album.get('id')}"
        )


def test_create_response_has_no_albumId(client):
    body = client.post(
        "/api/albums",
        json={"title": "Test", "artist": "Test Artist", "genre": "Rock"},
    ).json()
    assert "albumId" not in body


def test_update_response_has_no_albumId(client):
    album_id = client.get("/api/albums").json()[0]["id"]
    body = client.put(f"/api/albums/{album_id}", json={"title": "New"}).json()
    assert "albumId" not in body


# ---------------------------------------------------------------------------
# 11. GET /health — returns 200 with expected body
# ---------------------------------------------------------------------------

def test_health_returns_200(client):
    response = client.get("/health")
    assert response.status_code == 200


def test_health_body(client):
    body = client.get("/health").json()
    assert body == {"status": "ok", "service": "album-catalog"}
