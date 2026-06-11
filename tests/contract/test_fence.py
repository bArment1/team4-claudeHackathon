"""
Fence contract tests — Anti-Corruption Layer (ACL).

These tests verify that fence.py correctly:
  1. Strips legacy fields on inbound translation (from_legacy)
  2. Emits camelCase legacy fields on outbound translation (to_legacy_shape)
  3. Detects any legacy field names leaking into clean-service responses
     (validate_no_legacy_leak)
  4. The /api/legacy-compat endpoints accept legacy-shaped payloads and
     return legacy-shaped responses
  5. The canonical /api/albums endpoint never exposes legacy fields

Run with:
    pytest tests/contract/test_fence.py -v
"""
from __future__ import annotations

import sys
import os

# Make the service importable without installing it.
SERVICE_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "services", "album-catalog")
sys.path.insert(0, os.path.abspath(SERVICE_DIR))

import pytest
from fastapi.testclient import TestClient

import database as db_module
from main import app
from fence import from_legacy, to_legacy_shape, validate_no_legacy_leak
from models import Album


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture(autouse=True)
def reset_db():
    """Reload seed data before every test for full isolation."""
    db_module.db = db_module._load_seed()
    yield
    db_module.db = db_module._load_seed()


@pytest.fixture
def client():
    return TestClient(app)


# A minimal valid legacy payload (mirrors what Spring Music would send).
LEGACY_PAYLOAD = {
    "id": "12345",
    "albumId": "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",  # 40-char ghost field
    "title": "Kind of Blue",
    "artist": "Miles Davis",
    "genre": "Jazz",
    "trackCount": 6,
    "releaseYear": "1959",
    "_class": "org.cloudfoundry.samples.music.domain.Album",
}

# A minimal clean Album object (as produced by the new service).
CLEAN_ALBUM = Album(
    id="00000000-0000-0000-0000-000000000001",
    title="Kind of Blue",
    artist="Miles Davis",
    genre="Jazz",
    track_count=6,
    price=9.99,
)


# ---------------------------------------------------------------------------
# 1. from_legacy — inbound translation
# ---------------------------------------------------------------------------

def test_legacy_payload_stripped_of_albumId():
    """from_legacy must drop the albumId ghost field."""
    result = from_legacy(LEGACY_PAYLOAD)
    # AlbumCreate has no albumId attribute — verify it's absent from the dict too
    result_dict = result.model_dump()
    assert "albumId" not in result_dict


def test_legacy_trackCount_becomes_track_count():
    """from_legacy must rename camelCase trackCount to snake_case track_count."""
    result = from_legacy(LEGACY_PAYLOAD)
    result_dict = result.model_dump()
    assert "trackCount" not in result_dict
    assert result_dict["track_count"] == 6


def test_from_legacy_drops_releaseYear():
    """from_legacy must drop releaseYear (not part of new public model)."""
    result = from_legacy(LEGACY_PAYLOAD)
    result_dict = result.model_dump()
    assert "releaseYear" not in result_dict


def test_from_legacy_drops_class():
    """from_legacy must drop the Mongo _class field."""
    result = from_legacy(LEGACY_PAYLOAD)
    result_dict = result.model_dump()
    assert "_class" not in result_dict


def test_from_legacy_preserves_core_fields():
    """from_legacy must preserve title, artist, genre."""
    result = from_legacy(LEGACY_PAYLOAD)
    assert result.title == "Kind of Blue"
    assert result.artist == "Miles Davis"
    assert result.genre == "Jazz"


def test_from_legacy_treats_zero_trackCount_as_none():
    """Legacy trackCount=0 means 'not set'; should map to None."""
    payload = {**LEGACY_PAYLOAD, "trackCount": 0}
    result = from_legacy(payload)
    assert result.track_count is None


# ---------------------------------------------------------------------------
# 2. to_legacy_shape — outbound (proxy) translation
# ---------------------------------------------------------------------------

def test_to_legacy_shape_has_albumId_field():
    """Reverse translation must include albumId (even if empty) for legacy consumers."""
    result = to_legacy_shape(CLEAN_ALBUM)
    assert "albumId" in result


def test_to_legacy_shape_has_trackCount_camel_case():
    """Reverse translation must use camelCase trackCount."""
    result = to_legacy_shape(CLEAN_ALBUM)
    assert "trackCount" in result
    assert "track_count" not in result
    assert result["trackCount"] == 6


def test_to_legacy_shape_preserves_core_fields():
    """Reverse translation must carry id, title, artist, genre."""
    result = to_legacy_shape(CLEAN_ALBUM)
    assert result["id"] == CLEAN_ALBUM.id
    assert result["title"] == "Kind of Blue"
    assert result["artist"] == "Miles Davis"
    assert result["genre"] == "Jazz"


def test_to_legacy_shape_none_track_count_becomes_zero():
    """track_count=None in clean model must map to trackCount=0 in legacy shape."""
    album_no_tracks = Album(
        id="00000000-0000-0000-0000-000000000002",
        title="Untitled",
        artist="Unknown",
        genre="Rock",
        track_count=None,
    )
    result = to_legacy_shape(album_no_tracks)
    assert result["trackCount"] == 0


# ---------------------------------------------------------------------------
# 3. validate_no_legacy_leak — guardian function
# ---------------------------------------------------------------------------

def test_validate_no_legacy_leak_raises_on_albumId():
    """Validator must raise ValueError when albumId appears in a clean-service dict."""
    dirty = {"id": "x", "title": "T", "artist": "A", "genre": "G", "albumId": "ghost"}
    with pytest.raises(ValueError, match="albumId"):
        validate_no_legacy_leak(dirty)


def test_validate_no_legacy_leak_raises_on_trackCount():
    """Validator must raise ValueError when camelCase trackCount appears."""
    dirty = {"id": "x", "title": "T", "artist": "A", "genre": "G", "trackCount": 5}
    with pytest.raises(ValueError, match="trackCount"):
        validate_no_legacy_leak(dirty)


def test_validate_no_legacy_leak_raises_on_releaseYear():
    """Validator must raise ValueError when releaseYear appears."""
    dirty = {"id": "x", "title": "T", "artist": "A", "genre": "G", "releaseYear": "1959"}
    with pytest.raises(ValueError, match="releaseYear"):
        validate_no_legacy_leak(dirty)


def test_validate_no_legacy_leak_raises_on_class():
    """Validator must raise ValueError when _class appears."""
    dirty = {"id": "x", "title": "T", "artist": "A", "genre": "G", "_class": "org.foo.Bar"}
    with pytest.raises(ValueError, match="_class"):
        validate_no_legacy_leak(dirty)


def test_validate_no_legacy_leak_passes_clean_shape():
    """A fully clean response dict must pass without raising."""
    clean = {
        "id": "00000000-0000-0000-0000-000000000001",
        "title": "Kind of Blue",
        "artist": "Miles Davis",
        "genre": "Jazz",
        "track_count": 6,
        "price": 9.99,
    }
    # Must not raise
    validate_no_legacy_leak(clean)


# ---------------------------------------------------------------------------
# 4. /api/legacy-compat endpoint integration tests
# ---------------------------------------------------------------------------

def test_legacy_compat_endpoint_accepts_legacy_payload(client):
    """POST /api/legacy-compat/albums must accept a legacy-shaped payload."""
    response = client.post("/api/legacy-compat/albums", json=LEGACY_PAYLOAD)
    assert response.status_code == 201
    body = response.json()
    # Response is in legacy shape — must include albumId
    assert "albumId" in body
    assert body["title"] == "Kind of Blue"
    assert body["artist"] == "Miles Davis"
    assert body["genre"] == "Jazz"


def test_legacy_compat_endpoint_album_stored_in_clean_db(client):
    """Album posted via legacy-compat must be retrievable via the clean /api/albums."""
    client.post("/api/legacy-compat/albums", json=LEGACY_PAYLOAD)
    albums = client.get("/api/albums").json()
    titles = [a["title"] for a in albums]
    assert "Kind of Blue" in titles


def test_legacy_compat_get_returns_legacy_shape(client):
    """GET /api/legacy-compat/albums must return albums with albumId field."""
    response = client.get("/api/legacy-compat/albums")
    assert response.status_code == 200
    albums = response.json()
    assert isinstance(albums, list)
    assert len(albums) > 0
    for album in albums:
        assert "albumId" in album, f"albumId missing from legacy-compat response: {album}"


def test_legacy_compat_get_returns_trackCount_camel_case(client):
    """GET /api/legacy-compat/albums must use camelCase trackCount."""
    response = client.get("/api/legacy-compat/albums")
    albums = response.json()
    for album in albums:
        assert "trackCount" in album, f"trackCount missing from legacy-compat response: {album}"
        assert "track_count" not in album, f"snake_case track_count leaked into legacy-compat response: {album}"


def test_legacy_compat_missing_required_field_returns_422(client):
    """POST /api/legacy-compat/albums with missing title must return 422."""
    bad_payload = {"artist": "Miles Davis", "genre": "Jazz", "albumId": "abc"}
    response = client.post("/api/legacy-compat/albums", json=bad_payload)
    assert response.status_code == 422


# ---------------------------------------------------------------------------
# 5. Clean /api/albums endpoint must never expose legacy fields
# ---------------------------------------------------------------------------

def test_clean_api_response_never_has_albumId(client):
    """GET /api/albums must never include albumId in any record."""
    response = client.get("/api/albums")
    assert response.status_code == 200
    albums = response.json()
    for album in albums:
        assert "albumId" not in album, (
            f"ACL breach: albumId leaked into clean API response for id={album.get('id')}"
        )


def test_clean_api_response_never_has_trackCount(client):
    """GET /api/albums must use snake_case track_count, never camelCase trackCount."""
    response = client.get("/api/albums")
    albums = response.json()
    for album in albums:
        assert "trackCount" not in album, (
            f"ACL breach: trackCount leaked into clean API response for id={album.get('id')}"
        )


def test_clean_api_create_response_never_has_albumId(client):
    """POST /api/albums response must never include albumId."""
    payload = {"title": "Giant Steps", "artist": "John Coltrane", "genre": "Jazz"}
    response = client.post("/api/albums", json=payload)
    assert response.status_code == 201
    body = response.json()
    assert "albumId" not in body
    assert "trackCount" not in body


def test_validate_no_legacy_leak_applied_to_seed_data(client):
    """All seed albums returned by the clean API must pass the fence validator."""
    albums = client.get("/api/albums").json()
    for album in albums:
        validate_no_legacy_leak(album)  # raises ValueError on any ACL breach
