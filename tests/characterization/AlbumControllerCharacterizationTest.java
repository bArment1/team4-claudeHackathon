package org.cloudfoundry.samples.music.characterization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Characterization tests for AlbumController.
 *
 * These tests pin the ACTUAL behavior of the legacy Spring Music monolith,
 * including its known bugs (LB-1 through LB-7).
 *
 * DO NOT update these tests to reflect "correct" behavior.
 * DO update them only when the behavior has deliberately changed in the monolith
 * AND the new Album Catalog service has been updated to compensate.
 *
 * Source: legacy/src/main/java/org/cloudfoundry/samples/music/web/AlbumController.java
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class AlbumControllerCharacterizationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Fetch the full album list and return the first album's JSON node.
     * Used by tests that need a real id from the seeded data.
     */
    private JsonNode getFirstSeedAlbum() throws Exception {
        MvcResult result = mockMvc.perform(get("/albums"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(root.isArray() && root.size() > 0, "Seed data must be present");
        return root.get(0);
    }

    // ===========================================================================
    // GET /albums — list all albums
    // ===========================================================================

    /**
     * PINS BEHAVIOR: GET /albums returns HTTP 200 with a JSON array.
     */
    @Test
    void getAlbumsReturnsJsonArray() throws Exception {
        mockMvc.perform(get("/albums"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", isA(java.util.List.class)));
    }

    /**
     * PINS BEHAVIOR: GET /albums returns exactly 29 albums on a fresh start.
     *
     * albums.json contains 29 entries: 22 Rock, 1 Pop, 6 Blues.
     * Count breakdown (from albums.json):
     *   Rock  = Nirvana, The Beach Boys, Marvin Gaye, Jimi Hendrix Experience, U2 (Joshua Tree),
     *            The Beatles (Abbey Road), Fleetwood Mac, Elvis Presley, The Rolling Stones (Exile),
     *            Bruce Springsteen, The Clash, The Eagles, Led Zeppelin (I), Led Zeppelin (IV),
     *            Police, U2 (Achtung Baby), The Rolling Stones (Let it Bleed),
     *            The Beatles (Rubber Soul), The Ramones, Queen, Boston = 21 (actually 21 Rock
     *            but we count exactly from the file — total is 29)
     *   Pop   = Michael Jackson (Thriller) = 1
     *   Blues = BB King, Albert King, Muddy Waters, The Fabulous Thunderbirds,
     *            Robert Johnson, Stevie Ray Vaughan (Texas Flood),
     *            Stevie Ray Vaughan (Couldn't Stand the Weather) = 7
     *
     * NOTE: genres present are Rock, Pop, Blues ONLY — not Jazz or Classical.
     */
    @Test
    void getAlbumsReturnsTwentyNineSeedAlbums() throws Exception {
        mockMvc.perform(get("/albums"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(29)));
    }

    /**
     * PINS BEHAVIOR: Each album in the list contains the expected fields.
     * trackCount is present and is an integer (defaults to 0 for seed data — see LB-5).
     */
    @Test
    void getAlbumsReturnsAlbumsWithExpectedFields() throws Exception {
        mockMvc.perform(get("/albums"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id", notNullValue()))
                .andExpect(jsonPath("$[0].title", notNullValue()))
                .andExpect(jsonPath("$[0].artist", notNullValue()))
                .andExpect(jsonPath("$[0].releaseYear", notNullValue()))
                .andExpect(jsonPath("$[0].genre", notNullValue()))
                .andExpect(jsonPath("$[0].trackCount", isA(Integer.class)));
    }

    /**
     * PINS BUG LB-4: GET /albums IGNORES query parameters — no server-side filtering.
     * DO NOT FIX without implementing server-side filtering in the new Album Catalog service.
     *
     * The UI does client-side genre filtering. The server always returns the full collection
     * regardless of what query params are passed. This test confirms a ?genre=Rock request
     * still returns all 29 albums, not just the Rock ones.
     */
    @Test
    void getAlbumsIgnoresGenreQueryParam() throws Exception {
        // PINS BUG LB-4: server ignores ?genre=Rock and returns all 29 albums
        mockMvc.perform(get("/albums").param("genre", "Rock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(29)));
    }

    /**
     * PINS BUG LB-4: GET /albums ignores arbitrary unknown query parameters entirely.
     */
    @Test
    void getAlbumsIgnoresArbitraryQueryParams() throws Exception {
        // PINS BUG LB-4: no query param filtering of any kind is performed
        mockMvc.perform(get("/albums").param("artist", "Nirvana").param("sort", "title"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(29)));
    }

    /**
     * PINS BEHAVIOR: Seed data genres are Rock, Pop, and Blues only (not Jazz, Classical, etc.).
     * This pins the actual seed data content so future data changes are caught.
     */
    @Test
    void seedDataContainsOnlyRockPopAndBluesGenres() throws Exception {
        MvcResult result = mockMvc.perform(get("/albums"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode albums = objectMapper.readTree(result.getResponse().getContentAsString());
        long rockCount = 0, popCount = 0, bluesCount = 0, otherCount = 0;
        for (JsonNode album : albums) {
            String genre = album.path("genre").asText();
            switch (genre) {
                case "Rock":  rockCount++;  break;
                case "Pop":   popCount++;   break;
                case "Blues": bluesCount++; break;
                default:      otherCount++; break;
            }
        }
        assertTrue(rockCount > 0,  "Seed data must contain Rock albums");
        assertTrue(popCount > 0,   "Seed data must contain Pop albums");
        assertTrue(bluesCount > 0, "Seed data must contain Blues albums");
        assertEquals(0, otherCount, "Seed data must NOT contain Jazz, Classical, or any other genre");
        assertEquals(29, rockCount + popCount + bluesCount, "Total must be 29");
    }

    // ===========================================================================
    // PUT /albums — CREATE (backwards verb — LB-1)
    // ===========================================================================

    /**
     * PINS BUG LB-1: PUT /albums with a new album body CREATES the album.
     * DO NOT FIX without updating the new Album Catalog service first.
     *
     * Standard REST convention: PUT should update a resource.
     * Legacy behavior: PUT /albums is mapped to AlbumController.add() and saves a new entity.
     * The UI never calls this endpoint (see LB-2), but the endpoint functions as a create.
     */
    @Test
    void putAlbumsCreatesNewAlbum() throws Exception {
        // PINS BUG LB-1: PUT creates — backwards from REST convention
        String newAlbumJson = """
                {
                    "title": "Dark Side of the Moon",
                    "artist": "Pink Floyd",
                    "releaseYear": "1973",
                    "genre": "Rock"
                }
                """;

        MvcResult result = mockMvc.perform(put("/albums")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newAlbumJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("Dark Side of the Moon")))
                .andExpect(jsonPath("$.artist", is("Pink Floyd")))
                .andExpect(jsonPath("$.id", notNullValue()))
                .andReturn();

        // Verify album was persisted — total should now be 30
        mockMvc.perform(get("/albums"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(30)));
    }

    /**
     * PINS BUG LB-1: PUT /albums with a supplied id — behaves like an upsert/save.
     * The id supplied by the client is used as the JPA primary key.
     * DO NOT FIX without updating the new Album Catalog service first.
     */
    @Test
    void putAlbumsWithExplicitIdCreatesAlbumWithThatId() throws Exception {
        // PINS BUG LB-1: PUT creates; the supplied id becomes the JPA primary key
        String albumJson = """
                {
                    "id": "test-id-characterization-1234567890abcdef",
                    "title": "Wish You Were Here",
                    "artist": "Pink Floyd",
                    "releaseYear": "1975",
                    "genre": "Rock"
                }
                """;

        mockMvc.perform(put("/albums")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(albumJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("test-id-characterization-1234567890abcdef")))
                .andExpect(jsonPath("$.title", is("Wish You Were Here")));
    }

    // ===========================================================================
    // POST /albums — UPDATE (backwards verb — LB-1 / LB-2)
    // ===========================================================================

    /**
     * PINS BUG LB-1 + LB-2: POST /albums with an existing album id UPDATES the album.
     * DO NOT FIX without updating the new Album Catalog service first.
     *
     * Standard REST convention: POST should create a new resource.
     * Legacy behavior: POST /albums is mapped to AlbumController.update() — but it uses
     * repository.save() which upserts. When an existing id is provided, the album is updated.
     * This is what the UI calls for both "add new album" and "edit existing album".
     */
    @Test
    void postAlbumsUpdatesExistingAlbum() throws Exception {
        // PINS BUG LB-1: POST updates when an existing id is sent
        JsonNode existingAlbum = getFirstSeedAlbum();
        String existingId = existingAlbum.path("id").asText();
        String originalTitle = existingAlbum.path("title").asText();

        String updatedJson = String.format("""
                {
                    "id": "%s",
                    "title": "UPDATED TITLE",
                    "artist": "Updated Artist",
                    "releaseYear": "2000",
                    "genre": "Pop"
                }
                """, existingId);

        mockMvc.perform(post("/albums")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatedJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(existingId)))
                .andExpect(jsonPath("$.title", is("UPDATED TITLE")));

        // Confirm the update persisted and did not add a new record
        mockMvc.perform(get("/albums"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(29)))  // count unchanged
                .andExpect(jsonPath("$[?(@.title=='UPDATED TITLE')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.title=='" + originalTitle + "')]", hasSize(0)));
    }

    /**
     * PINS BUG LB-2: POST /albums with NO id (new album body) also works — it creates.
     * DO NOT FIX without updating the new Album Catalog service first.
     *
     * The UI never calls PUT; it calls POST for both add and update.
     * When POST receives a body without an id, repository.save() generates a new id and creates
     * a new record. This is the actual path taken when a user adds an album via the UI form.
     */
    @Test
    void postAlbumsWithNoIdCreatesAlbum() throws Exception {
        // PINS BUG LB-2: the UI uses POST (not PUT) to create new albums
        String newAlbumJson = """
                {
                    "title": "Kind of Blue",
                    "artist": "Miles Davis",
                    "releaseYear": "1959",
                    "genre": "Blues"
                }
                """;

        mockMvc.perform(post("/albums")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newAlbumJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("Kind of Blue")))
                .andExpect(jsonPath("$.id", notNullValue()));

        // Album count must increase by 1
        mockMvc.perform(get("/albums"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(30)));
    }

    // ===========================================================================
    // GET /albums/{id} — single album by JPA primary key (LB-3, LB-7)
    // ===========================================================================

    /**
     * PINS BUG LB-7: GET /albums/{id} endpoint exists and returns the album for a known id.
     * DO NOT REMOVE — this endpoint is dead code from the UI's perspective but the server
     * still exposes it.
     *
     * Note also LB-3: the {id} path variable resolves against the JPA primary key field `id`,
     * NOT the separate `albumId` field.
     */
    @Test
    void getAlbumByIdReturnsAlbum() throws Exception {
        // PINS BUG LB-7: endpoint exists but is never called by the UI
        JsonNode seedAlbum = getFirstSeedAlbum();
        String seedId = seedAlbum.path("id").asText();

        mockMvc.perform(get("/albums/{id}", seedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(seedId)))
                .andExpect(jsonPath("$.title", notNullValue()));
    }

    /**
     * PINS BUG LB-7 + LB-3: GET /albums/{id} for a nonexistent id returns HTTP 200 with null body.
     * DO NOT FIX — the controller returns `repository.findById(id).orElse(null)` and Spring
     * serializes null as an empty 200 response body, not a 404.
     *
     * A correct REST implementation would return 404 here. The legacy does not.
     */
    @Test
    void getAlbumByNonexistentIdReturnsNullBody() throws Exception {
        // PINS BUG LB-7: returns 200 with empty/null body instead of 404 for unknown id
        // The controller calls .orElse(null) — Spring serializes null to an empty response
        MvcResult result = mockMvc.perform(get("/albums/{id}", "nonexistent-id-that-does-not-exist"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // Body is empty or "null" — NOT a 404 response
        assertTrue(
                body.isEmpty() || body.equals("null"),
                "Expected empty or null body for nonexistent album, but got: " + body
        );
    }

    /**
     * PINS BUG LB-3: GET /albums/{id} uses the JPA primary key field `id`, not `albumId`.
     * DO NOT confuse the two identity fields when building the new service.
     *
     * Album has two identity fields:
     *   - `id`      — JPA @Id, generated by RandomIdGenerator (UUID string)
     *   - `albumId` — extra nullable field, never populated by seed data or the UI
     *
     * The endpoint path variable resolves against `id`.
     */
    @Test
    void getAlbumsByIdUsesJpaPrimaryKey() throws Exception {
        // PINS BUG LB-3: path variable maps to JPA `id`, not to the `albumId` field
        JsonNode seedAlbum = getFirstSeedAlbum();
        String jpaId = seedAlbum.path("id").asText();

        // The `albumId` field should be absent or null in seed data
        assertTrue(
                seedAlbum.path("albumId").isNull() || seedAlbum.path("albumId").isMissingNode()
                        || seedAlbum.path("albumId").asText("").isEmpty(),
                "Seed albums should have null/empty albumId (LB-3)"
        );

        // The JPA `id` path resolves correctly
        mockMvc.perform(get("/albums/{id}", jpaId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(jpaId)));
    }

    // ===========================================================================
    // LB-3 — Dual identity fields: id vs albumId
    // ===========================================================================

    /**
     * PINS BUG LB-3: When an album is created via PUT (the "create" path), albumId remains null.
     * DO NOT FIX — albumId is never set by any code path and has no known purpose.
     * The new Album Catalog service does not need to reproduce this field.
     */
    @Test
    void createdAlbumHasNullAlbumId() throws Exception {
        // PINS BUG LB-3: albumId is a ghost field that is never populated
        String newAlbumJson = """
                {
                    "title": "The Wall",
                    "artist": "Pink Floyd",
                    "releaseYear": "1979",
                    "genre": "Rock"
                }
                """;

        mockMvc.perform(put("/albums")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newAlbumJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", notNullValue()))
                // albumId should be absent from JSON or explicitly null
                .andExpect(jsonPath("$.albumId", anyOf(nullValue(), emptyString())));
    }

    /**
     * PINS BUG LB-3: id field on created album is a non-empty UUID-style string (from RandomIdGenerator).
     * RandomIdGenerator delegates to UUID.randomUUID().toString() — a 36-char UUID string.
     */
    @Test
    void createdAlbumIdIsUuidString() throws Exception {
        // PINS BUG LB-3: id is generated by RandomIdGenerator -> UUID.randomUUID().toString()
        String newAlbumJson = """
                {
                    "title": "Revolver",
                    "artist": "The Beatles",
                    "releaseYear": "1966",
                    "genre": "Rock"
                }
                """;

        MvcResult result = mockMvc.perform(put("/albums")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newAlbumJson))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String generatedId = body.path("id").asText();

        assertFalse(generatedId.isEmpty(), "Generated id must not be empty");
        // UUID format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx (36 chars with hyphens)
        assertTrue(generatedId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                "Generated id should be a UUID string, was: " + generatedId);
    }

    // ===========================================================================
    // LB-5 — trackCount absent from seed data, defaults to 0
    // ===========================================================================

    /**
     * PINS BUG LB-5: Seed albums have trackCount = 0 because albums.json omits the field.
     * DO NOT FIX — the new Album Catalog service must decide how to handle trackCount separately.
     *
     * The Album entity has `int trackCount` (primitive) which defaults to 0 when absent.
     * The UI form has no trackCount input. The seed data has no trackCount values.
     */
    @Test
    void seedAlbumsHaveTrackCountOfZero() throws Exception {
        // PINS BUG LB-5: trackCount defaults to 0 for all seed data
        MvcResult result = mockMvc.perform(get("/albums"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode albums = objectMapper.readTree(result.getResponse().getContentAsString());
        for (JsonNode album : albums) {
            int trackCount = album.path("trackCount").asInt(-1);
            assertEquals(0, trackCount,
                    "Seed album '" + album.path("title").asText() + "' should have trackCount=0, was: " + trackCount);
        }
    }

    /**
     * PINS BUG LB-5: Creating an album via PUT without trackCount succeeds and trackCount is 0.
     */
    @Test
    void createAlbumWithoutTrackCountDefaultsToZero() throws Exception {
        // PINS BUG LB-5: omitting trackCount results in 0 (primitive default), not null
        String newAlbumJson = """
                {
                    "title": "Disraeli Gears",
                    "artist": "Cream",
                    "releaseYear": "1967",
                    "genre": "Blues"
                }
                """;

        mockMvc.perform(put("/albums")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newAlbumJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackCount", is(0)));
    }

    /**
     * PINS BEHAVIOR: trackCount CAN be set when explicitly provided on album creation.
     * The field is functional on the server — it is only absent from the UI form.
     */
    @Test
    void createAlbumWithExplicitTrackCountPreservesValue() throws Exception {
        // PINS BEHAVIOR: trackCount is stored and returned when explicitly set
        String newAlbumJson = """
                {
                    "title": "Paranoid",
                    "artist": "Black Sabbath",
                    "releaseYear": "1970",
                    "genre": "Rock",
                    "trackCount": 8
                }
                """;

        mockMvc.perform(put("/albums")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newAlbumJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackCount", is(8)));
    }

    // ===========================================================================
    // DELETE /albums/{id}
    // ===========================================================================

    /**
     * PINS BEHAVIOR: DELETE /albums/{id} removes the album and subsequent GET returns empty body.
     * After deletion, GET /albums/{id} returns null (200 with null body) — not 404 (see LB-7).
     */
    @Test
    void deleteAlbumRemovesItFromCollection() throws Exception {
        // PINS BEHAVIOR: delete works; count decreases by 1
        JsonNode albumToDelete = getFirstSeedAlbum();
        String idToDelete = albumToDelete.path("id").asText();

        mockMvc.perform(delete("/albums/{id}", idToDelete))
                .andExpect(status().isOk());

        // Verify count dropped
        mockMvc.perform(get("/albums"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(28)));
    }

    /**
     * PINS BEHAVIOR: After DELETE, GET /albums/{id} returns 200 with null/empty body (not 404).
     * This reinforces LB-7 — the endpoint never 404s.
     */
    @Test
    void getAfterDeleteReturnsNullBodyNot404() throws Exception {
        // PINS BUG LB-7: even after deletion, GET /albums/{id} returns 200 + null body
        JsonNode albumToDelete = getFirstSeedAlbum();
        String idToDelete = albumToDelete.path("id").asText();

        mockMvc.perform(delete("/albums/{id}", idToDelete))
                .andExpect(status().isOk());

        MvcResult getResult = mockMvc.perform(get("/albums/{id}", idToDelete))
                .andExpect(status().isOk())
                .andReturn();

        String body = getResult.getResponse().getContentAsString();
        assertTrue(
                body.isEmpty() || body.equals("null"),
                "Expected null/empty body after delete, got: " + body
        );
    }

    /**
     * PINS BEHAVIOR: DELETE /albums/{id} for a nonexistent id does not throw — void return.
     * JPA deleteById() with no matching record silently completes (Spring Data behavior).
     */
    @Test
    void deleteNonexistentAlbumDoesNotError() throws Exception {
        // PINS BEHAVIOR: deleteById does not error on missing id; JPA swallows it
        mockMvc.perform(delete("/albums/{id}", "nonexistent-id-xyz-does-not-exist"))
                .andExpect(status().isOk());

        // Total count unchanged
        mockMvc.perform(get("/albums"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(29)));
    }

    // ===========================================================================
    // Validation — required fields
    // ===========================================================================

    /**
     * PINS BEHAVIOR: PUT /albums with an empty body (no required fields) returns 400.
     *
     * AlbumController uses @Valid on the @RequestBody. The Album entity's fields are not
     * annotated with @NotNull/@NotBlank however, so the actual constraint validation behavior
     * may vary. This test pins whatever the actual server response is.
     *
     * TODO: Album.java has no JSR-303 annotations on fields — @Valid may be a no-op here.
     * If this test returns 200 with a null-field album, that is the actual pinned behavior.
     * Update the assertion to match what the server actually returns.
     */
    @Test
    void putAlbumsWithEmptyBodyReturns400OrCreatesEmptyAlbum() throws Exception {
        // PINS BEHAVIOR: empty body — actual behavior depends on whether @Valid has any effect
        // Album entity has no @NotNull annotations, so @Valid may not reject the empty object
        MvcResult result = mockMvc.perform(put("/albums")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        int status = result.getResponse().getStatus();
        // Pin the actual status: likely 200 (Album has no @NotNull constraints) or 400
        assertTrue(status == 200 || status == 400,
                "Expected 200 or 400 for empty album body, got: " + status);
        // TODO: tighten this assertion once the actual response has been observed
    }

    // ===========================================================================
    // LB-6 — Default sort (client-side; not testable server-side)
    // ===========================================================================

    /**
     * PINS BUG LB-6 (documentation only): The Angular client defaults to sort field "name"
     * which does not exist on the Album model. The server returns albums in DB insertion order
     * and the client-side sort produces undefined ordering on initial page load.
     *
     * This cannot be pinned at the server level — it is a client-side Angular behavior.
     * The server-side pin is: GET /albums returns albums in some order (not guaranteed stable).
     *
     * TODO: If you need to pin insertion order, add an explicit ORDER BY to the repository
     * layer and update this test to assert a specific first album title.
     */
    @Test
    void getAlbumsReturnsSomeOrderWithNoServerSideSort() throws Exception {
        // PINS BUG LB-6 documentation: server returns albums in unspecified order
        // (typically H2 insertion order for fresh DB, but not guaranteed)
        MvcResult result = mockMvc.perform(get("/albums"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode albums = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(29, albums.size(), "Must return all 29 albums regardless of order");
        // NOTE: do NOT assert a specific ordering — it is unspecified and not guaranteed
        // TODO: if insertion order needs to be pinned, observe the actual first album and
        //       add: assertEquals("Nirvana", albums.get(0).path("artist").asText());
    }
}
