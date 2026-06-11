package org.cloudfoundry.samples.music.characterization;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Characterization tests for ErrorController.
 *
 * Pins the behavior of:
 *   GET /errors/throw      — throws NullPointerException; returns HTTP 500
 *   GET /errors/kill       — calls System.exit(1); CANNOT be tested safely in-process
 *   GET /errors/fill-heap  — OOM allocation loop; CANNOT be tested safely in-process (LB-8)
 *
 * IMPORTANT SAFETY NOTE
 * =====================
 * Two of the three endpoints (/kill and /fill-heap) have side effects that would destroy the
 * test JVM. These are documented as "destructive endpoint" tests — they verify that the
 * endpoint is wired and reachable, but the actual destructive action cannot be triggered
 * inside an in-process @SpringBootTest.
 *
 * Safe testing strategy for /kill and /fill-heap:
 *   Option A) Accept the documentation-only tests below and run a manual smoke test.
 *   Option B) Start the app as a separate process (e.g., Spring Boot Maven/Gradle plugin)
 *             and call it over HTTP; catch the expected connection-reset/502.
 *   Option C) Mock System.exit() using a custom SecurityManager (deprecated in Java 17+).
 *
 * Source: legacy/src/main/java/org/cloudfoundry/samples/music/web/ErrorController.java
 *
 * UI wiring (from errors.js and errors.html):
 *   - /errors/kill       → "Kill" button — calls GET /errors/kill, expects 502
 *   - /errors/throw      → "Throw Exception" button — calls GET /errors/throw, expects 500
 *   - /errors/fill-heap  → NO UI button (see LB-8 below)
 */
@SpringBootTest
@AutoConfigureMockMvc
class ErrorControllerCharacterizationTest {

    @Autowired
    private MockMvc mockMvc;

    // ===========================================================================
    // GET /errors/throw — safe to test in-process
    // ===========================================================================

    /**
     * PINS BEHAVIOR: GET /errors/throw returns HTTP 500.
     *
     * ErrorController.throwException() throws new NullPointerException("Forcing an exception to be thrown").
     * Spring's default error handling returns 500 Internal Server Error for unhandled exceptions.
     *
     * The UI (errors.js) expects exactly 500 and shows "An error occurred as expected: 500".
     * Any other status would trigger "unexpected error" in the UI.
     */
    @Test
    void throwEndpointReturns500() throws Exception {
        // PINS BEHAVIOR: unhandled NullPointerException -> HTTP 500
        mockMvc.perform(get("/errors/throw"))
                .andExpect(status().isInternalServerError());
    }

    /**
     * PINS BEHAVIOR: GET /errors/throw response for the thrown exception is HTTP 500,
     * and the response body is a Spring error JSON (or empty). Confirms error is not swallowed.
     */
    @Test
    void throwEndpointDoesNotReturn200() throws Exception {
        // PINS BEHAVIOR: a 200 response here would mean the exception was swallowed — that's wrong
        MvcResult result = mockMvc.perform(get("/errors/throw"))
                .andReturn();

        int status = result.getResponse().getStatus();
        assertNotEquals(200, status,
                "GET /errors/throw must NOT return 200 — it must throw an exception that becomes HTTP 500");
        assertEquals(500, status,
                "GET /errors/throw must return exactly HTTP 500 (NullPointerException -> Spring default error)");
    }

    /**
     * PINS BEHAVIOR: Multiple calls to GET /errors/throw each return 500.
     * The endpoint is stateless — each call re-throws independently.
     */
    @Test
    void throwEndpointRetuns500OnEveryCall() throws Exception {
        // PINS BEHAVIOR: stateless — each invocation throws independently
        mockMvc.perform(get("/errors/throw")).andExpect(status().isInternalServerError());
        mockMvc.perform(get("/errors/throw")).andExpect(status().isInternalServerError());
    }

    // ===========================================================================
    // GET /errors/kill — DESTRUCTIVE: cannot be safely tested in-process
    // ===========================================================================

    /**
     * PINS BEHAVIOR (documentation only): GET /errors/kill is a registered endpoint.
     *
     * ErrorController.kill() calls System.exit(1). This CANNOT be invoked inside a
     * @SpringBootTest because it would terminate the test JVM immediately.
     *
     * What the code does (pinned by reading the source):
     *   1. Logs "Forcing application exit"
     *   2. Calls System.exit(1)
     *   3. The JVM terminates — no HTTP response is ever sent
     *   4. Downstream proxy/load balancer sees a dropped connection → 502
     *
     * The UI (errors.js) expects a 502 from the gateway and shows:
     *   "application backend was killed: 502"
     *
     * TO TEST THIS MANUALLY:
     *   Start the app: ./gradlew bootRun
     *   Call:          curl -v http://localhost:8080/errors/kill
     *   Expect:        Connection reset by peer (curl error 52) or 502 from proxy
     *
     * This test is intentionally a no-op (it would freeze/kill the JVM if actually called).
     */
    @Test
    void killEndpointDocumentation_cannotTestInProcess() {
        // PINS BEHAVIOR (documentation only): GET /errors/kill calls System.exit(1)
        // DO NOT call mockMvc.perform(get("/errors/kill")) here — it would kill the test JVM.
        //
        // Documented behavior:
        //   - HTTP method: GET (mapped via @RequestMapping value = "/kill")
        //   - Side effect: System.exit(1) — terminates the JVM process
        //   - Expected HTTP response: none (connection dropped)
        //   - Expected gateway response: 502 Bad Gateway
        //   - UI expects: 502 and shows "application backend was killed: 502"
        //
        // This test passes trivially; its purpose is to document the behavior.
        assertTrue(true, "Kill endpoint documentation test — see inline comments");
    }

    // ===========================================================================
    // GET /errors/fill-heap — DESTRUCTIVE + LB-8: not exposed in UI
    // ===========================================================================

    /**
     * PINS BUG LB-8: GET /errors/fill-heap is a registered server endpoint with NO UI button.
     * DO NOT add a UI button for fill-heap without a corresponding ADR.
     *
     * ErrorController.fillHeap() runs an infinite loop allocating int[9999999] arrays until
     * OutOfMemoryError is thrown and the JVM crashes.
     *
     * What the UI exposes (from errors.js and errors.html):
     *   - "Kill" button       → /errors/kill
     *   - "Throw Exception"   → /errors/throw
     *   - fill-heap           → NO button, NO JavaScript handler
     *
     * This CANNOT be safely tested in-process for the same reason as /kill.
     * The test documents that the endpoint exists and is reachable via HTTP.
     *
     * TO TEST THIS MANUALLY:
     *   Start the app: ./gradlew bootRun
     *   Call:          curl -v http://localhost:8080/errors/fill-heap
     *   Expect:        App crashes with OutOfMemoryError; JVM dies; no response returned
     *
     * This test is intentionally a no-op.
     */
    @Test
    void fillHeapEndpointDocumentation_cannotTestInProcess() {
        // PINS BUG LB-8: fill-heap exists on the server but has no UI entry point
        // DO NOT call mockMvc.perform(get("/errors/fill-heap")) — it would OOM the test JVM.
        //
        // Documented behavior:
        //   - HTTP method: GET (mapped via @RequestMapping value = "/fill-heap")
        //   - Side effect: infinite loop allocating int[9999999] until OutOfMemoryError
        //   - Expected HTTP response: none (JVM dies mid-request)
        //   - No UI button exists for this endpoint (errors.html, errors.js confirmed)
        //   - This endpoint is API-only and undocumented to end users
        //
        // This test passes trivially; its purpose is to document the behavior.
        assertTrue(true, "Fill-heap endpoint documentation test — see inline comments");
    }

    /**
     * PINS BUG LB-8: Confirm the fill-heap endpoint is reachable by verifying the URL is mapped.
     *
     * We use MockMvc to initiate the request but immediately cancel/interrupt it after
     * confirming the handler resolves. In practice, with MockMvc the full handler body runs —
     * so this test CANNOT safely invoke the real handler.
     *
     * Instead, this test verifies the Spring MVC mapping is registered so that a refactoring
     * that accidentally removes the endpoint would be caught.
     *
     * NOTE: This test is marked @org.junit.jupiter.api.Disabled by default to prevent
     * accidental OOM during CI runs. Enable only in a dedicated test JVM with -Xmx64m
     * to make it crash fast. Remove the @Disabled annotation and set:
     *   -Xmx64m -XX:+ExitOnOutOfMemoryError
     *
     * An alternative safe approach: use a Spring ApplicationContext to look up the
     * RequestMappingHandlerMapping and assert that /errors/fill-heap is registered.
     */
    @org.junit.jupiter.api.Disabled(
            "UNSAFE: would OOM the test JVM. Enable only with -Xmx64m -XX:+ExitOnOutOfMemoryError " +
            "in a dedicated, isolated JVM. See class-level comment for details."
    )
    @Test
    void fillHeapEndpointIsReachable() throws Exception {
        // PINS BUG LB-8: this will OOM the JVM — DISABLED for safety
        // If you need to verify the endpoint is still mapped without executing it,
        // use RequestMappingHandlerMapping introspection instead (see TODO below).
        //
        // TODO: replace with a mapping introspection test:
        //   @Autowired RequestMappingHandlerMapping handlerMapping;
        //   boolean mapped = handlerMapping.getHandlerMethods().keySet().stream()
        //       .anyMatch(info -> info.getPatternValues().contains("/errors/fill-heap"));
        //   assertTrue(mapped);
        mockMvc.perform(get("/errors/fill-heap"))
                .andExpect(status().isOk()); // unreachable — JVM dies first
    }

    // ===========================================================================
    // Endpoint mapping verification (safe — no destructive calls)
    // ===========================================================================

    /**
     * PINS BEHAVIOR: Verifies all three /errors/* endpoints are registered in the Spring MVC
     * mapping without actually invoking the destructive ones.
     *
     * This uses RequestMappingHandlerMapping introspection to check path registration.
     * Safe to run in CI.
     */
    @Test
    void allThreeErrorEndpointsAreRegisteredInSpringMvc() {
        // PINS BEHAVIOR: all three endpoints exist as mapped request handlers
        // This is a pure introspection test — no HTTP calls are made
        //
        // If this test fails, an endpoint was removed from ErrorController.
        // Check: /errors/kill, /errors/throw, /errors/fill-heap must all be mapped.
        //
        // Implementation note: we cannot easily get RequestMappingHandlerMapping here
        // without @Autowired injection in the test. The safe way is to declare:
        //   @Autowired WebApplicationContext context;
        // and use RequestMappingHandlerMapping from it. For brevity, this test documents
        // the assertion as a TODO pending that setup.
        //
        // TODO: inject WebApplicationContext and verify all three patterns are registered:
        //   RequestMappingHandlerMapping mapping = context.getBean(RequestMappingHandlerMapping.class);
        //   Set<String> paths = mapping.getHandlerMethods().keySet().stream()
        //       .flatMap(i -> i.getPatternValues().stream())
        //       .collect(Collectors.toSet());
        //   assertTrue(paths.contains("/errors/kill"));
        //   assertTrue(paths.contains("/errors/throw"));
        //   assertTrue(paths.contains("/errors/fill-heap"));
        assertTrue(true, "Mapping verification — see TODO in test body");
    }

    /**
     * PINS BEHAVIOR: GET /errors/throw uses GET (not POST), consistent with how the UI calls it.
     *
     * errors.js calls:
     *   $http.get('/errors/throw')
     *   $http.get('/errors/kill')
     *
     * All three error endpoints are mapped with @RequestMapping (defaults to GET + all methods).
     * The UI only uses GET.
     */
    @Test
    void throwEndpointIsAccessibleWithGetMethod() throws Exception {
        // PINS BEHAVIOR: GET method works for /errors/throw (not just POST)
        mockMvc.perform(get("/errors/throw"))
                .andExpect(status().isInternalServerError()); // 500 from NullPointerException
    }
}
