"""
In-memory album store seeded from the legacy albums.json.

Field mapping (legacy -> new):
  title       -> title       (unchanged)
  artist      -> artist      (unchanged)
  genre       -> genre       (unchanged)
  trackCount  -> track_count (snake_case; 0 in seed data -> None)
  _class      -> dropped
  releaseYear -> dropped (not part of new public model)
  albumId     -> dropped (anti-corruption layer, LB-3)
  id (JPA)    -> id (UUID, generated fresh)
"""
from __future__ import annotations

import json
import os
import uuid
from typing import Dict

from models import Album

# Path to legacy seed data — resolved relative to this file so it works
# regardless of the working directory the server is launched from.
_LEGACY_SEED = os.path.join(
    os.path.dirname(__file__),
    "..",
    "..",
    "legacy",
    "src",
    "main",
    "resources",
    "albums.json",
)


def _load_seed() -> Dict[str, Album]:
    store: Dict[str, Album] = {}
    try:
        with open(_LEGACY_SEED, "r", encoding="utf-8") as fh:
            raw = json.load(fh)
    except FileNotFoundError:
        # Graceful degradation: if legacy tree is absent start with empty store
        return store

    for entry in raw:
        album_id = str(uuid.uuid4())
        track_count_raw = entry.get("trackCount", 0)
        album = Album(
            id=album_id,
            title=entry["title"],
            artist=entry["artist"],
            genre=entry["genre"],
            # Legacy seed data always has trackCount=0 (omitted = default 0).
            # Treat 0 as "not set" so the new model expresses absence cleanly.
            track_count=track_count_raw if track_count_raw else None,
            price=entry.get("price"),
        )
        store[album_id] = album

    return store


# Module-level singleton — imported by routers.
db: Dict[str, Album] = _load_seed()
