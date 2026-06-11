"""
Album Catalog router — all /api/albums routes.

Verb conventions (correcting LB-1 from the legacy monolith):
  POST   /api/albums        -> create  (legacy had PUT for create)
  PUT    /api/albums/{id}   -> update  (legacy had POST for update)
  GET    /api/albums        -> list all, optional ?genre= filter (LB-4 improvement)
  GET    /api/albums/{id}   -> get single
  DELETE /api/albums/{id}   -> delete
"""
from __future__ import annotations

import uuid
from typing import List, Optional

from fastapi import APIRouter, HTTPException, Query, status
from fastapi.responses import Response

import database
from models import Album, AlbumCreate, AlbumUpdate

router = APIRouter(prefix="/api/albums", tags=["albums"])


@router.get("", response_model=List[Album])
def list_albums(genre: Optional[str] = Query(default=None)) -> List[Album]:
    """
    Return all albums, sorted by title (fixing LB-6 — legacy sorted by non-existent 'name').
    Pass ?genre=Rock to filter server-side (improvement over legacy LB-4 client-side filter).
    """
    albums = list(database.db.values())
    if genre is not None:
        albums = [a for a in albums if a.genre == genre]
    albums.sort(key=lambda a: a.title.lower())
    return albums


@router.get("/{album_id}", response_model=Album)
def get_album(album_id: str) -> Album:
    """Return a single album by id, or 404 if not found."""
    album = database.db.get(album_id)
    if album is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Album not found")
    return album


@router.post("", response_model=Album, status_code=status.HTTP_201_CREATED)
def create_album(payload: AlbumCreate) -> Album:
    """
    Create a new album.
    Corrects LB-1: legacy used PUT for creation; new service uses POST.
    Returns 201 with the created album including its generated id.
    """
    album_id = str(uuid.uuid4())
    album = Album(id=album_id, **payload.model_dump())
    database.db[album_id] = album
    return album


@router.put("/{album_id}", response_model=Album)
def update_album(album_id: str, payload: AlbumUpdate) -> Album:
    """
    Update an existing album (partial update — only supplied fields change).
    Corrects LB-1: legacy used POST for updates; new service uses PUT.
    Returns 404 if the album does not exist.
    """
    existing = database.db.get(album_id)
    if existing is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Album not found")

    updated_data = existing.model_dump()
    for field, value in payload.model_dump(exclude_unset=True).items():
        updated_data[field] = value

    updated = Album(**updated_data)
    database.db[album_id] = updated
    return updated


@router.delete("/{album_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_album(album_id: str) -> Response:
    """Delete an album. Returns 204 No Content on success, 404 if not found."""
    if album_id not in database.db:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Album not found")
    del database.db[album_id]
    return Response(status_code=status.HTTP_204_NO_CONTENT)
