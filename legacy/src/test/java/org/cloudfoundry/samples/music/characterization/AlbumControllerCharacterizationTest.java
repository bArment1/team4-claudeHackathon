package org.cloudfoundry.samples.music.characterization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private JsonNode getFirstSeedAlbum() throws Exception {
        MvcResult result = mockMvc.perform(get("/albums"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(root.isArray() && root.size() > 0, "Seed data must be present");
        return root.get(0);
    }

    // ===========================================================================
    // GET /albums
    // ===========================================================================

    @Test
    void getAlbumsReturnsJsonArray() throws Exception {
        mockMvc.perform(get("/albums"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", isA(java.util.List.class)));
    }

    @Test
    void getAlbumsReturnsTwentyNineSeedAlbums() throws Exception {
        mockMvc.perform(get("/albums"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(29)));
    }

    @Test
    void getAlbumsReturnsAlbumsWithExpectedFields() throws Exception {
        mockMvc.perform(get("/albums"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id", notNullValue()))
                .andExpect(jsonPath("$[0].title", notNullValue()))
                .andExpect(jsonPath("$[0].artist", notNullValue()))
                .andExpect(jsonPath("$[0].genre", notNullValue()))
                .andExpect(jsonPath("$[0].trackCount", isA(Integer.class)));
    }

    // PINS BUG LB-4: server ignores ?genre=Rock and returns all 29 albums
    @Test
    void getAlbumsIgnoresGenreQueryParam() throws Exception {
        mockMvc.perform(get("/albums").param("genre", "Rock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(29)));
    }

    @Test
    void getAlbumsIgnoresArbitraryQueryParams() throws Exception {
        mockMvc.perform(get("/albums").param("artist", "Nirvana").param("sort", "title"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(29)));
    }

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

    // PINS BUG LB-1: PUT /albums creates — backwards from REST convention
    // DO NOT FIX without updating the new Album Catalog service first.
    @Test
    void putAlbumsCreatesNewAlbum() throws Exception {
        String newAlbumJson = "{\"title\": \"Dark Side of the Moon\", \"artist\": \"Pink Floyd\", \"releaseYear\": \"1973\", \"genre\": \"Rock\"}";

        mockMvc.perform(put("/albums")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newAlbumJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("Dark Side of the Moon")))
                .andExpect(jsonPath("$.artist", is("Pink Floyd")))
                .andExpect(jsonPath("$.id", notNullValue()));

        mockMvc.perform(get("/albums"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(30)));
    }

    // PINS BUG LB-1: PUT creates; the supplied id becomes the JPA primary key
    @Test
    void putAlbumsWithExplicitIdCreatesAlbumWithThatId() throws Exception {
        String albumJson = "{\"id\": \"test-id-characterization-1234567890abcdef\", \"title\": \"Wish You Were Here\", \"artist\": \"Pink Floyd\", \"releaseYear\": \"1975\", \"genre\": \"Rock\"}";

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

    // PINS BUG LB-1 + LB-2: POST updates when an existing id is sent
    // DO NOT FIX without updating the new Album Catalog service first.
    @Test
    void postAlbumsUpdatesExistingAlbum() throws Exception {
        JsonNode existingAlbum = getFirstSeedAlbum();
        String existingId = existingAlbum.path("id").asText();
        String originalTitle = existingAlbum.path("title").asText();

        String updatedJson = String.format(
                "{\"id\": \"%s\", \"title\": \"UPDATED TITLE\", \"artist\": \"Updated Artist\", \"releaseYear\": \"2000\", \"genre\": \"Pop\"}",
                existingId);

        mockMvc.perform(post("/albums")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatedJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(existingId)))
                .andExpect(jsonPath("$.title", is("UPDATED TITLE")));

        mockMvc.perform(get("/albums"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(29)))
                .andExpect(jsonPath("$[?(@.title=='UPDATED TITLE')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.title=='" + originalTitle + "')]", hasSize(0)));
    }

    // PINS BUG LB-2: the UI uses POST (not PUT) to create new albums
    @Test
    void postAlbumsWithNoIdCreatesAlbum() throws Exception {
        String newAlbumJson = "{\"title\": \"Kind of Blue\", \"artist\": \"Miles Davis\", \"releaseYear\": \"1959\", \"genre\": \"Blues\"}";

        mockMvc.perform(post("/albums")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newAlbumJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("Kind of Blue")))
                .andExpect(jsonPath("$.id", notNullValue()));

        mockMvc.perform(get("/albums"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(30)));
    }

    // ===========================================================================
    // GET /albums/{id} — LB-3, LB-7
    // ===========================================================================

    // PINS BUG LB-7: endpoint exists but is never called by the UI
    @Test
    void getAlbumByIdReturnsAlbum() throws Exception {
        JsonNode seedAlbum = getFirstSeedAlbum();
        String seedId = seedAlbum.path("id").asText();

        mockMvc.perform(get("/albums/{id}", seedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(seedId)))
                .andExpect(jsonPath("$.title", notNullValue()));
    }

    // PINS BUG LB-7: returns 200 with empty/null body instead of 404 for unknown id
    @Test
    void getAlbumByNonexistentIdReturnsNullBody() throws Exception {
        MvcResult result = mockMvc.perform(get("/albums/{id}", "nonexistent-id-that-does-not-exist"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.isEmpty() || body.equals("null"),
                "Expected empty or null body for nonexistent album, but got: " + body);
    }

    // PINS BUG LB-3: path variable maps to JPA `id`, not to the `albumId` field
    @Test
    void getAlbumsByIdUsesJpaPrimaryKey() throws Exception {
        JsonNode seedAlbum = getFirstSeedAlbum();
        String jpaId = seedAlbum.path("id").asText();

        assertTrue(
                seedAlbum.path("albumId").isNull() || seedAlbum.path("albumId").isMissingNode()
                        || seedAlbum.path("albumId").asText("").isEmpty(),
                "Seed albums should have null/empty albumId (LB-3)");

        mockMvc.perform(get("/albums/{id}", jpaId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(jpaId)));
    }

    // ===========================================================================
    // LB-3 — Dual identity fields
    // ===========================================================================

    // PINS BUG LB-3: albumId is a ghost field that is never populated
    @Test
    void createdAlbumHasNullAlbumId() throws Exception {
        String newAlbumJson = "{\"title\": \"The Wall\", \"artist\": \"Pink Floyd\", \"releaseYear\": \"1979\", \"genre\": \"Rock\"}";

        mockMvc.perform(put("/albums")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newAlbumJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.albumId", anyOf(nullValue(), emptyString())));
    }

    // PINS BUG LB-3: id is generated by RandomIdGenerator -> UUID.randomUUID().toString()
    @Test
    void createdAlbumIdIsUuidString() throws Exception {
        String newAlbumJson = "{\"title\": \"Revolver\", \"artist\": \"The Beatles\", \"releaseYear\": \"1966\", \"genre\": \"Rock\"}";

        MvcResult result = mockMvc.perform(put("/albums")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newAlbumJson))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String generatedId = body.path("id").asText();

        assertFalse(generatedId.isEmpty(), "Generated id must not be empty");
        assertTrue(generatedId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                "Generated id should be a UUID string, was: " + generatedId);
    }

    // ===========================================================================
    // LB-5 — trackCount absent from seed data
    // ===========================================================================

    // PINS BUG LB-5: trackCount defaults to 0 for all seed data
    @Test
    void seedAlbumsHaveTrackCountOfZero() throws Exception {
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

    // PINS BUG LB-5: omitting trackCount results in 0 (primitive default), not null
    @Test
    void createAlbumWithoutTrackCountDefaultsToZero() throws Exception {
        String newAlbumJson = "{\"title\": \"Disraeli Gears\", \"artist\": \"Cream\", \"releaseYear\": \"1967\", \"genre\": \"Blues\"}";

        mockMvc.perform(put("/albums")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newAlbumJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackCount", is(0)));
    }

    @Test
    void createAlbumWithExplicitTrackCountPreservesValue() throws Exception {
        String newAlbumJson = "{\"title\": \"Paranoid\", \"artist\": \"Black Sabbath\", \"releaseYear\": \"1970\", \"genre\": \"Rock\", \"trackCount\": 8}";

        mockMvc.perform(put("/albums")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newAlbumJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackCount", is(8)));
    }

    // ===========================================================================
    // DELETE /albums/{id}
    // ===========================================================================

    @Test
    void deleteAlbumRemovesItFromCollection() throws Exception {
        JsonNode albumToDelete = getFirstSeedAlbum();
        String idToDelete = albumToDelete.path("id").asText();

        mockMvc.perform(delete("/albums/{id}", idToDelete))
                .andExpect(status().isOk());

        mockMvc.perform(get("/albums"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(28)));
    }

    // PINS BUG LB-7: even after deletion, GET /albums/{id} returns 200 + null body
    @Test
    void getAfterDeleteReturnsNullBodyNot404() throws Exception {
        JsonNode albumToDelete = getFirstSeedAlbum();
        String idToDelete = albumToDelete.path("id").asText();

        mockMvc.perform(delete("/albums/{id}", idToDelete))
                .andExpect(status().isOk());

        MvcResult getResult = mockMvc.perform(get("/albums/{id}", idToDelete))
                .andExpect(status().isOk())
                .andReturn();

        String body = getResult.getResponse().getContentAsString();
        assertTrue(body.isEmpty() || body.equals("null"),
                "Expected null/empty body after delete, got: " + body);
    }

    @Test
    void deleteNonexistentAlbumDoesNotError() throws Exception {
        mockMvc.perform(delete("/albums/{id}", "nonexistent-id-xyz-does-not-exist"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/albums"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(29)));
    }

    // ===========================================================================
    // LB-6 — Default sort (client-side; not testable server-side)
    // ===========================================================================

    // PINS BUG LB-6: server returns albums in unspecified order (typically H2 insertion order)
    @Test
    void getAlbumsReturnsSomeOrderWithNoServerSideSort() throws Exception {
        MvcResult result = mockMvc.perform(get("/albums"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode albums = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(29, albums.size(), "Must return all 29 albums regardless of order");
    }
}
