"""
Album Catalog microservice — FastAPI entry point.

Run with:
    uvicorn main:app --reload
"""
from fastapi import FastAPI

from routers.albums import router as albums_router
from routers.legacy_compat import router as legacy_compat_router

app = FastAPI(
    title="Album Catalog Service",
    description=(
        "Extracted Album Catalog microservice. "
        "Part of the strangler fig migration away from the Spring Music monolith. "
        "Uses conventional REST verbs (corrects LB-1) and a clean data model "
        "(no albumId leakage — corrects LB-3). "
        "Provides a legacy-compat layer (/api/legacy-compat) for strangler-fig cut-over."
    ),
    version="1.0.0",
)

app.include_router(albums_router)
app.include_router(legacy_compat_router)


@app.get("/health", tags=["ops"])
def health() -> dict:
    """Liveness probe."""
    return {"status": "ok", "service": "album-catalog"}
