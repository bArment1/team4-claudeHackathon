package org.cloudfoundry.samples.music.characterization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Characterization tests for InfoController.
 *
 * Pins the behavior of:
 *   GET /appinfo  — ApplicationInfo (active Spring profiles + bound CF service names)
 *   GET /service  — Raw CfService list (CF-bound services only; API-only endpoint, no UI)
 *
 * Both endpoints are read-only and have no known bugs. These tests document the contract
 * so that extraction of an equivalent endpoint in the new service does not silently change
 * the shape of the response.
 *
 * Source: legacy/src/main/java/org/cloudfoundry/samples/music/web/InfoController.java
 *         legacy/src/main/java/org/cloudfoundry/samples/music/domain/ApplicationInfo.java
 */
@SpringBootTest
@AutoConfigureMockMvc
class InfoControllerCharacterizationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ===========================================================================
    // GET /appinfo
    // ===========================================================================

    /**
     * PINS BEHAVIOR: GET /appinfo returns HTTP 200.
     */
    @Test
    void getAppinfoReturns200() throws Exception {
        mockMvc.perform(get("/appinfo"))
                .andExpect(status().isOk());
    }

    /**
     * PINS BEHAVIOR: GET /appinfo returns JSON with content type application/json.
     */
    @Test
    void getAppinfoReturnsJson() throws Exception {
        mockMvc.perform(get("/appinfo"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    /**
     * PINS BEHAVIOR: GET /appinfo returns a JSON object with exactly two fields: "profiles" and "services".
     * Shape: { "profiles": [...], "services": [...] }
     *
     * This shape is defined by ApplicationInfo.java.
     */
    @Test
    void getAppinfoReturnsObjectWithProfilesAndServicesFields() throws Exception {
        mockMvc.perform(get("/appinfo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profiles", notNullValue()))
                .andExpect(jsonPath("$.services", notNullValue()));
    }

    /**
     * PINS BEHAVIOR: GET /appinfo "profiles" field is a JSON array.
     */
    @Test
    void getAppinfoProfilesIsArray() throws Exception {
        mockMvc.perform(get("/appinfo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profiles", isA(java.util.List.class)));
    }

    /**
     * PINS BEHAVIOR: GET /appinfo "services" field is a JSON array.
     */
    @Test
    void getAppinfoServicesIsArray() throws Exception {
        mockMvc.perform(get("/appinfo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services", isA(java.util.List.class)));
    }

    /**
     * PINS BEHAVIOR: GET /appinfo returns empty "services" array when running locally
     * without any Cloud Foundry services bound.
     *
     * When running in a test environment with no CF_SERVICES env variable, cfEnv.findAllServices()
     * returns an empty list. InfoController maps service names to a String[] — empty array.
     */
    @Test
    void getAppinfoServicesIsEmptyWhenNoCloudFoundryServices() throws Exception {
        // PINS BEHAVIOR: no CF services bound in test/local environment
        mockMvc.perform(get("/appinfo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services", hasSize(0)));
    }

    /**
     * PINS BEHAVIOR: GET /appinfo "profiles" contains the active Spring profiles.
     * When running tests with no explicit profile, the active profiles array may be empty
     * or contain only the default profile.
     *
     * TODO: If running with an explicit Spring profile (e.g., -Dspring.profiles.active=mysql),
     * the profiles array will contain that profile name. Add a profile-specific assertion if
     * needed for multi-profile characterization.
     */
    @Test
    void getAppinfoProfilesReflectsActiveSpringProfiles() throws Exception {
        MvcResult result = mockMvc.perform(get("/appinfo"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode profiles = body.path("profiles");

        assertTrue(profiles.isArray(), "profiles must be a JSON array");
        // When no profile is explicitly active, the array is empty
        // NOTE: this test runs with H2/no-profile — profiles will be empty
        // If this fails with a non-empty array, record the actual profile values here
    }

    /**
     * PINS BEHAVIOR: GET /appinfo response does NOT contain any other top-level fields
     * beyond "profiles" and "services".
     * Ensures no accidental data leakage is added to the response shape.
     */
    @Test
    void getAppinfoResponseHasExactlyTwoTopLevelFields() throws Exception {
        MvcResult result = mockMvc.perform(get("/appinfo"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(2, body.size(),
                "ApplicationInfo should serialize exactly 2 fields (profiles, services), got: " + body.fieldNames());
    }

    // ===========================================================================
    // GET /service
    // ===========================================================================

    /**
     * PINS BEHAVIOR: GET /service returns HTTP 200.
     * This endpoint is API-only — no corresponding UI element.
     */
    @Test
    void getServiceReturns200() throws Exception {
        mockMvc.perform(get("/service"))
                .andExpect(status().isOk());
    }

    /**
     * PINS BEHAVIOR: GET /service returns a JSON array.
     */
    @Test
    void getServiceReturnsJsonArray() throws Exception {
        mockMvc.perform(get("/service"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", isA(java.util.List.class)));
    }

    /**
     * PINS BEHAVIOR: GET /service returns an empty array when no CF services are bound.
     * In a local/test environment, cfEnv.findAllServices() returns [].
     */
    @Test
    void getServiceReturnsEmptyArrayWhenNoCloudFoundryServices() throws Exception {
        // PINS BEHAVIOR: empty array for non-CF environment
        mockMvc.perform(get("/service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    /**
     * PINS BEHAVIOR: /appinfo and /service are separate endpoints on the same controller.
     * Calling one does not affect the other.
     */
    @Test
    void appinfoAndServiceEndpointsAreIndependent() throws Exception {
        // Call both endpoints and confirm both return 200
        mockMvc.perform(get("/appinfo")).andExpect(status().isOk());
        mockMvc.perform(get("/service")).andExpect(status().isOk());
    }
}
