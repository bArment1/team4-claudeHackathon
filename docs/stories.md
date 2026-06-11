# The Stories — Spring Music Album Catalog

User stories for the 4 main capabilities of the Spring Music legacy app.
Grounded in the actual `AlbumController.java` and `Album.java` domain model.

---

## Notable Legacy Behaviors (pin these — do not "fix" in characterization tests)

| Quirk | Where | Notes |
|-------|-------|-------|
| `PUT /albums` creates, `POST /albums` updates | `AlbumController.java:29-38` | HTTP verbs are swapped from REST convention — this is a bug being intentionally pinned |
| `albumId` field exists alongside `id` | `Album.java:23` | Two identity fields — `id` is the JPA primary key; `albumId` is an extra field with no clear purpose |
| No server-side genre filtering | `AlbumController.java:24` | `GET /albums` returns all albums; genre filtering is done client-side in JS |

---

## User Stories

| # | Role | Goal | Acceptance Criteria | Tester Verifies |
|---|------|------|--------------------|--------------------|
| US-1 | Music catalog user | Browse the full list of albums so I can see what's in the collection | Given the catalog has albums, when I load the page, then all albums are displayed with title, artist, release year, genre, and track count | `GET /albums` returns HTTP 200 with a JSON array; each album object contains `title`, `artist`, `releaseYear`, `genre`, `trackCount`; empty catalog returns `[]` not an error |
| US-2 | Music catalog user | Filter albums by genre so I can find albums in a specific style | Given albums of multiple genres exist, when I select a genre filter, then only albums matching that genre are shown | Client-side filter reduces displayed albums to the selected genre; selecting "All" restores the full list; genre values seen in data: Rock, Pop, Jazz, Classical |
| US-3 | Catalog manager | Add a new album so the collection stays current | Given I submit a valid album (title, artist, releaseYear, genre, trackCount), when the form is saved, then the album appears in the list with a system-generated ID | `PUT /albums` with a valid body returns HTTP 200 and the saved album with a non-empty `id`; the album is retrievable via `GET /albums/{id}`; submitting without required fields returns a 4xx error |
| US-4 | Catalog manager | Edit an existing album so I can correct mistakes or update details | Given an album exists, when I change one or more fields and save, then the updated values are reflected in the list | `POST /albums` with an existing `id` returns HTTP 200 with updated fields; `GET /albums/{id}` returns the new values; the album count does not change |
| US-5 | Catalog manager | Delete an album so the collection doesn't contain outdated entries | Given an album exists, when I delete it, then it no longer appears in the list | `DELETE /albums/{id}` returns HTTP 200 (or 204); subsequent `GET /albums/{id}` returns null or 404; total album count decreases by 1 |

---

## Out of Scope (for this modernization exercise)

- User authentication / access control (any user can add/edit/delete)
- Album artwork / media files
- Search by title or artist (only genre filter exists)
- Pagination (full list always returned)
- Audit trail / change history
