"""
Legacy compatibility router — /api/legacy-compat/albums

Accepts payloads shaped like the Spring Music monolith and proxies them
through the Anti-Corruption Layer (fence.py) into the clean Album Catalog
service.  Responses are returned in legacy shape so that old consumers need
not change.

This router exists to support the strangler-fig cut-over period.  Once all
consumers have migrated to /api/albums it should be retired.

Legacy verb mapping (corrected in the new service):
  The legacy monolith used:
    PUT  /album  -> create
    POST /album  -> update
  This compat layer accepts both verbs on POST for create/upsert semantics
  (the caller does not need to distinguish, the fence handles it).
"""
from __future__ import annotations

import uuid
from typing import Any, Dict, List

from fastapi import APIRouter, HTTPException, status

import database
from fence import from_legacy, to_legacy_shape
from models import Album

router = APIRouter(prefix="/api/legacy-compat", tags=["legacy-compat"])


@router.post("/albums", status_code=status.HTTP_201_CREATED)
def legacy_create_album(payload: Dict[str, Any]) -> Dict[str, Any]:
    """
    Accept a legacy-shaped album payload, translate it through the fence,
    persist it, and return the result in legacy shape.

    Accepts both PUT-style (legacy create) and POST-style (legacy update)
    payloads — the shape is identical so the fence handles either uniformly.

    Returns 422 if mandatory fields (title, artist, genre) are missing.
    """
    try:
        album_create = from_legacy(payload)
    except KeyError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Missing required field: {exc}",
        )

    album_id = str(uuid.uuid4())
    album = Album(id=album_id, **album_create.model_dump())
    database.db[album_id] = album
    return to_legacy_shape(album)


@router.get("/albums")
def legacy_list_albums() -> List[Dict[str, Any]]:
    """
    Return all albums in legacy shape (camelCase fields, albumId present).

    Albums are sorted by title (same ordering contract as the clean endpoint).
    """
    albums = sorted(database.db.values(), key=lambda a: a.title.lower())
    return [to_legacy_shape(a) for a in albums]
