package dev.yik.apilab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class WebServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer mock;
    private String mockBase;
    private WebServer web;
    private String webBase;
    private HttpClient client;
    private final AtomicInteger countedHits = new AtomicInteger();

    @BeforeEach
    void startServers() throws IOException {
        mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mock.createContext("/ok", exchange -> respond(exchange, 200));
        mock.createContext("/text", exchange -> respond(exchange, 200, "hello world"));
        mock.createContext("/body", exchange -> respond(exchange, 200, "{\"weather\":\"多云\"}"));
        mock.createContext("/body-wrong", exchange -> respond(exchange, 200, "{\"weather\":\"晴\"}"));
        mock.createContext("/missing", exchange -> respond(exchange, 404, "{\"weather\":\"多云\"}"));
        mock.createContext("/header-echo", exchange -> respond(exchange, 200,
                "token=" + exchange.getRequestHeaders().getFirst("X-Token")));
        mock.createContext("/query-decoded", exchange -> respond(exchange, 200,
                "decoded=" + exchange.getRequestURI().getQuery()));
        mock.createContext("/post-echo", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(exchange, 200, "method=" + exchange.getRequestMethod()
                    + ";contentType=" + exchange.getRequestHeaders().getFirst("Content-Type")
                    + ";body=" + body
                    + ";authorization=" + exchange.getRequestHeaders().getFirst("Authorization"));
        });
        mock.createContext("/post-created", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 201, "{\"id\":1}");
        });
        mock.createContext("/counted", exchange -> {
            countedHits.incrementAndGet();
            respond(exchange, 200);
        });
        mock.createContext("/large", exchange -> respond(exchange, 200, "x".repeat(100_000)));
        mock.createContext("/large-chinese", exchange -> respond(exchange, 200, "云".repeat(30_000)));
        mock.start();
        mockBase = "http://127.0.0.1:" + mock.getAddress().getPort();

        web = new WebServer(0);
        web.start();
        webBase = "http://127.0.0.1:" + web.port();

        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void stopServers() {
        if (web != null) {
            web.stop();
        }
        if (mock != null) {
            mock.stop(0);
        }
    }

    @Test
    void servesIndexPage() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(webBase + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("")
                .startsWith("text/html"));
        assertTrue(response.body().contains("API Regression Lab"), "page must render the title");
        assertTrue(response.body().contains("/api/run"), "page must call the execution endpoint");
        assertTrue(response.body().contains("id=\"single-workspace\""));
        assertTrue(response.body().contains("id=\"batch-workspace\""));
        assertTrue(response.body().contains("id=\"case-assertion-review\""));
        assertTrue(response.body().contains("id=\"batch-download-html\""));
        assertTrue(response.body().contains("function buildHtmlReport()"));
        assertTrue(response.body().contains("function htmlReportExplanation(c)"),
                "HTML report must have a privacy-safe explanation function");
        assertTrue(response.body().contains("htmlReportExplanation(c)")
                        && !response.body().contains("c.friendlyMessage"),
                "HTML report explanation must not echo friendlyMessage (which may contain raw expectContains)");
        assertTrue(response.body().contains("function responseSuggestionText(meta)"),
                "V2-03.1 must conditionally suggest response text as assertion baseline");
        assertTrue(response.body().contains("function clearSingleResultIfStale()"),
                "V2-03.1 must invalidate result snapshot when form changes");
        assertTrue(response.body().contains("function checkUrlSwitch()"),
                "V2-03.1 must detect URL switches and avoid assertion pollution");
        assertTrue(response.body().contains("function caseStatusLabel(c)"),
                "V2-03.1 must distinguish PASS·仅状态码 from PASS");
        // Bug-fix coverage (V2-03.1 follow-up): the stale banner DOM element
        // and the in-request stale check must be wired up.
        assertTrue(response.body().contains("id=\"result-stale-banner\""),
                "Stale-result banner must exist in the DOM");
    }

    @Test
    void submitTestChecksFormStalenessAfterFetch() throws Exception {
        String body = pageBody();

        int start = body.indexOf("async function submitTest(event)");
        assertTrue(start >= 0, "submitTest must exist");
        int end = body.indexOf("\n  function ", start);
        assertTrue(end > start, "submitTest must close properly");
        String submitFunc = body.substring(start, end);

        // After await fetch returns, formSnapshot() must be compared against
        // the pre-request requestHash before trusting the result.
        assertTrue(submitFunc.contains("var requestHash = formSnapshot()"),
                "submitTest must capture requestHash before fetch");
        assertTrue(submitFunc.contains("formSnapshot() !== requestHash"),
                "submitTest must detect form changes that happened during fetch");
        assertTrue(submitFunc.contains("setResultStale(true)"),
                "stale results must flip the banner and invalidate baseline state");
    }

    @Test
    void submitTestNeverReenablesCreateCaseForStaleOrErrorResults() throws Exception {
        String submitFunc = functionBody(pageBody(), "async function submitTest(event)");

        // 请求期间改动后又改回：hash 重新相等，但 payload 已被作废，
        // 必须同时检查 payload 为 null，否则会重新启用无响应的按钮。
        assertTrue(submitFunc.contains("|| singleResultPayload === null"),
                "stale detection must also treat an invalidated payload as stale");

        int errorBranch = submitFunc.indexOf("if (data.status === \"ERROR\")");
        int enable = submitFunc.indexOf("g(\"single-add-case\").disabled = false;");
        assertTrue(errorBranch >= 0, "submitTest must special-case ERROR results");
        assertTrue(enable >= 0, "successful results must enable the create-case entry");
        assertTrue(errorBranch < enable,
                "the ERROR branch must run before the button is enabled, so ERROR never exposes a usable entry");
        assertEquals(1, countOccurrences(submitFunc, "g(\"single-add-case\").disabled = false;"),
                "the create-case button must be enabled from exactly one guarded place");
    }

    @Test
    void checkUrlSwitchRebindsAnchorAfterClearing() throws Exception {
        String func = functionBody(pageBody(), "function checkUrlSwitch()");

        // URL 未变化时必须直接返回，绝不能清空用户已填写的断言。
        assertTrue(func.contains("if (url === lastSentUrl) return;"),
                "unchanged URL must short-circuit before any clearing");

        // 清空必须受“当前断言非空”约束，而不是无条件执行。
        int clearGuard = func.indexOf("if (el.expectContains.value.trim())");
        int clear = func.indexOf("el.expectContains.value = \"\";");
        assertTrue(clearGuard >= 0 && clear > clearGuard,
                "clearing must be guarded by a non-empty assertion check");

        // 清空之后必须把锚点更新为新 URL，后续无变化的 blur 才不会重复清除。
        int rebind = func.indexOf("lastSentUrl = url;", clear);
        assertTrue(rebind > clear,
                "anchor must be rebound to the new URL after clearing");
        assertEquals(1, countOccurrences(func.substring(clear), "lastSentUrl = url;"),
                "anchor rebinding must be a single statement after the clearing block");
    }

    @Test
    void draftRoundTripPreservesAssertionAnchor() throws Exception {
        String body = pageBody();
        String capture = functionBody(body, "function captureSingleDraft()");
        String restore = functionBody(body, "function restoreSingleDraft()");

        assertTrue(capture.contains("lastSentUrl: lastSentUrl"),
                "single draft must persist the assertion anchor");
        assertTrue(restore.contains("lastSentUrl = singleDraft.lastSentUrl"),
                "restoring the draft must restore its assertion anchor");
    }

    private static String functionBody(String page, String signature) {
        int start = page.indexOf(signature);
        assertTrue(start >= 0, signature + " must exist");
        int end = page.indexOf("\n  function ", start + signature.length());
        assertTrue(end > start, signature + " must close properly");
        return page.substring(start, end);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = haystack.indexOf(needle);
        while (idx >= 0) {
            count++;
            idx = haystack.indexOf(needle, idx + needle.length());
        }
        return count;
    }

    @Test
    void loadExamplePlacesInitialAnchorUrl() throws Exception {
        String body = pageBody();

        int start = body.indexOf("function loadExample(focusUrl)");
        assertTrue(start >= 0, "loadExample must exist");
        int end = body.indexOf("\n  function ", start);
        assertTrue(end > start, "loadExample must close properly");
        String func = body.substring(start, end);

        // Bug fix: the example itself must be registered as the lastSentUrl
        // anchor so that switching URL without ever sending a request still
        // clears the inherited expectContains.
        assertTrue(func.contains("lastSentUrl = normalizeUrl(EXAMPLE.url)"),
                "loadExample must register EXAMPLE.url as the anchor for the initial assertion");
    }

    @Test
    void pairRowMutationsAlsoTriggerStaleGuard() throws Exception {
        String body = pageBody();

        // addPair's remove button must hook clearSingleResultIfStale so that
        // deleting a param/header row invalidates any existing result snapshot.
        int addPairStart = body.indexOf("function addPair(container, kind)");
        assertTrue(addPairStart >= 0, "addPair must exist");
        int addPairEnd = body.indexOf("\n  function ", addPairStart);
        assertTrue(addPairEnd > addPairStart, "addPair must close properly");
        String addPair = body.substring(addPairStart, addPairEnd);

        // Check that pair-remove click handler calls clearSingleResultIfStale —
        // use a substring match rather than relying on exact formatting.
        int removeClick = addPair.indexOf("remove.addEventListener(\"click\"");
        assertTrue(removeClick >= 0, "remove button must have a click handler");
        assertTrue(addPair.indexOf("clearSingleResultIfStale()", removeClick) >= removeClick,
                "remove click must call clearSingleResultIfStale");

        // data-add click handler must also trigger stale guard.
        int closeIdx = body.indexOf("[data-add]");
        assertTrue(closeIdx >= 0);
        int staleIdx = body.indexOf("clearSingleResultIfStale()", closeIdx);
        assertTrue(staleIdx >= closeIdx && staleIdx < closeIdx + 500,
                "data-add click handler must call clearSingleResultIfStale");
    }

    @Test
    void htmlReportExplanationDoesNotEchoRawAssertionText() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(webBase + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String body = response.body();

        int start = body.indexOf("function htmlReportExplanation(c)");
        assertTrue(start >= 0, "privacy-safe explanation function must be present");

        // Walk forward to the next top-level function declaration at the
        // same indentation level. Anything between start and that boundary
        // belongs to htmlReportExplanation.
        int end = body.indexOf("\n  function ", start);
        assertTrue(end > start, "htmlReportExplanation function must close properly");
        String explanationFunc = body.substring(start, end);

        assertFalse(explanationFunc.contains("\" + c.expectContains + \""),
                "htmlReportExplanation must not concatenate raw expectContains text");
        assertTrue(explanationFunc.contains("包含文本断言"),
                "explanation should still communicate that a text assertion was expected");
    }

    @Test
    void unknownPathReturns404() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(webBase + "/missing-page")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(404, response.statusCode());
    }

    @Test
    void getRequestPasses() throws Exception {
        ObjectNode payload = casePayload("/ok", 200);

        JsonNode result = send(payload);

        assertEquals("PASS", result.get("status").asText());
        assertEquals(200, result.get("expectedStatus").asInt());
        assertEquals(200, result.get("actualStatus").asInt());
        assertTrue(result.get("reasonCode").isNull());
        assertTrue(result.get("message").isNull());
        assertTrue(result.get("durationMillis").asLong() >= 0);
    }

    @Test
    void statusMismatchFails() throws Exception {
        ObjectNode payload = casePayload("/missing", 200);

        JsonNode result = send(payload);

        assertEquals("FAIL", result.get("status").asText());
        assertEquals(200, result.get("expectedStatus").asInt());
        assertEquals(404, result.get("actualStatus").asInt());
        assertEquals("STATUS_MISMATCH", result.get("reasonCode").asText());
        assertTrue(result.get("message").asText().contains("404"), result.toString());
    }

    @Test
    void bodyMismatchFails() throws Exception {
        ObjectNode payload = casePayload("/body-wrong", 200);
        payload.put("expectContains", "多云");

        JsonNode result = send(payload);

        assertEquals("FAIL", result.get("status").asText());
        assertEquals("BODY_MISMATCH", result.get("reasonCode").asText());
        assertTrue(result.get("message").asText().contains("预期文本"), result.toString());
    }

    @Test
    void chineseContainsPasses() throws Exception {
        ObjectNode payload = casePayload("/body", 200);
        payload.put("expectContains", "多云");

        JsonNode result = send(payload);

        assertEquals("PASS", result.get("status").asText());
        assertEquals(200, result.get("actualStatus").asInt());
    }

    @Test
    void postJsonPasses() throws Exception {
        ObjectNode payload = casePayload("/post-created", 201);
        payload.put("method", "POST");
        payload.put("body", "{\"name\":\"测试\"}");

        JsonNode result = send(payload);

        assertEquals("PASS", result.get("status").asText());
        assertEquals(201, result.get("actualStatus").asInt());
    }

    @Test
    void postUsesDefaultJsonContentType() throws Exception {
        ObjectNode payload = casePayload("/post-echo", 200);
        payload.put("method", "POST");
        payload.put("body", "{\"name\":\"测试\"}");
        payload.put("expectContains", "contentType=application/json; charset=utf-8");

        JsonNode result = send(payload);

        assertEquals("PASS", result.get("status").asText(), result.toString());
    }

    @Test
    void queryParametersAreEncoded() throws Exception {
        ObjectNode payload = casePayload("/query-decoded", 200);
        payload.putArray("query").add("city=北京");
        payload.put("expectContains", "decoded=city=北京");

        JsonNode result = send(payload);

        assertEquals("PASS", result.get("status").asText(), result.toString());
    }

    @Test
    void headersAreSent() throws Exception {
        ObjectNode payload = casePayload("/header-echo", 200);
        payload.putArray("headers").add("X-Token: abc");
        payload.put("expectContains", "token=abc");

        JsonNode result = send(payload);

        assertEquals("PASS", result.get("status").asText(), result.toString());
    }

    @Test
    void foreignOriginIsRejectedBeforeExecuting() throws Exception {
        ObjectNode payload = casePayload("/counted", 200);

        HttpResponse<String> response = post(JSON.writeValueAsString(payload),
                "http://evil.example");

        assertEquals(403, response.statusCode());
        assertEquals(0, countedHits.get(), "target must not be called for a foreign origin");
        JsonNode body = JSON.readTree(response.body());
        assertEquals("ERROR", body.get("status").asText());
    }

    @Test
    void missingOriginIsRejected() throws Exception {
        ObjectNode payload = casePayload("/counted", 200);

        HttpResponse<String> response = post(JSON.writeValueAsString(payload), null);

        assertEquals(403, response.statusCode());
        assertEquals(0, countedHits.get());
    }

    @Test
    void invalidPayloadReturns400() throws Exception {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("url", mockBase + "/ok");
        payload.put("method", "GET");

        HttpResponse<String> response = post(JSON.writeValueAsString(payload), webBase);

        assertEquals(400, response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("ERROR", body.get("status").asText());
        assertEquals("INVALID_REQUEST", body.get("reasonCode").asText());
        assertTrue(body.get("message").asText().contains("expectStatus"), body.toString());
    }

    @Test
    void invalidBodyJsonReturns400() throws Exception {
        ObjectNode payload = casePayload("/post-created", 201);
        payload.put("method", "POST");
        payload.put("body", "not json");

        HttpResponse<String> response = post(JSON.writeValueAsString(payload), webBase);

        assertEquals(400, response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("ERROR", body.get("status").asText());
        assertTrue(body.get("message").asText().contains("JSON"), body.toString());
    }

    @Test
    void sequentialRunsUseLatestConfiguration() throws Exception {
        // The web test set runs every case through /api/run one by one; each call must be fully
        // independent so an edited case is executed with its latest URL and assertion.
        ObjectNode first = casePayload("/ok", 200);
        assertEquals("PASS", send(first).get("status").asText());

        ObjectNode edited = casePayload("/text", 200);
        assertEquals("PASS", send(edited).get("status").asText());

        ObjectNode changed = casePayload("/ok", 201);
        JsonNode result = send(changed);
        assertEquals("FAIL", result.get("status").asText());
        assertEquals("STATUS_MISMATCH", result.get("reasonCode").asText());
        assertEquals(201, result.get("expectedStatus").asInt());
        assertEquals(200, result.get("actualStatus").asInt());
    }

    @Test
    void batchEndpointIsRemoved() throws Exception {
        // V2-02 batch execution now goes through /api/run one case at a time, so the old
        // duplicate engine endpoint must no longer exist.
        HttpRequest request = HttpRequest.newBuilder(URI.create(webBase + "/api/batch"))
                .header("Origin", webBase)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"version\":1,\"cases\":[]}"))
                .build();

        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(405, response.statusCode(), "no batch route may remain");
    }

    @Test
    void arrayPayloadReturns400() throws Exception {
        HttpResponse<String> response = post("[]", webBase);

        assertEquals(400, response.statusCode());
        assertEquals("ERROR", JSON.readTree(response.body()).get("status").asText());
    }

    @Test
    void postWithoutBodyReturns400() throws Exception {
        ObjectNode payload = casePayload("/post-created", 201);
        payload.put("method", "POST");

        HttpResponse<String> response = post(JSON.writeValueAsString(payload), webBase);

        assertEquals(400, response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("INVALID_REQUEST", body.get("reasonCode").asText());
        assertFalse(body.get("message").asText().contains("Exception"));
    }

    @Test
    void getWithBodyReturns400() throws Exception {
        ObjectNode payload = casePayload("/ok", 200);
        payload.put("body", "{\"name\":\"测试\"}");

        HttpResponse<String> response = post(JSON.writeValueAsString(payload), webBase);

        assertEquals(400, response.statusCode());
        assertEquals("ERROR", JSON.readTree(response.body()).get("status").asText());
    }

    @Test
    void statusOutOfRangeReturns400() throws Exception {
        ObjectNode payload = casePayload("/ok", 999);

        HttpResponse<String> response = post(JSON.writeValueAsString(payload), webBase);

        assertEquals(400, response.statusCode());
        assertTrue(JSON.readTree(response.body()).get("message").asText()
                .contains("between 100 and 599"));
    }

    @Test
    void responsePreviewShowsTargetBody() throws Exception {
        ObjectNode payload = casePayload("/body", 200);

        JsonNode result = send(payload);

        assertEquals("PASS", result.get("status").asText());
        assertTrue(result.get("responseBody").asText().contains("多云"), result.toString());
        assertFalse(result.get("responseTruncated").asBoolean());
        assertTrue(result.get("responseBytes").asInt() > 0);
    }

    @Test
    void jsonResponsePreviewIsReturnedAsPlainText() throws Exception {
        ObjectNode payload = casePayload("/post-created", 201);
        payload.put("method", "POST");
        payload.put("body", "{\"name\":\"测试\"}");

        JsonNode result = send(payload);

        assertEquals("PASS", result.get("status").asText());
        assertEquals("{\"id\":1}", result.get("responseBody").asText());
    }

    @Test
    void longResponseIsTruncated() throws Exception {
        ObjectNode payload = casePayload("/large", 200);

        JsonNode result = send(payload);

        assertEquals("PASS", result.get("status").asText());
        assertTrue(result.get("responseTruncated").asBoolean());
        assertEquals(100_000, result.get("responseBytes").asInt());
        assertEquals(64 * 1024, result.get("responseBody").asText().length());
    }

    @Test
    void truncationKeepsMultiByteCharactersIntact() throws Exception {
        ObjectNode payload = casePayload("/large-chinese", 200);

        JsonNode result = send(payload);

        String preview = result.get("responseBody").asText();
        assertTrue(result.get("responseTruncated").asBoolean());
        assertEquals(30_000 * 3, result.get("responseBytes").asInt());
        assertEquals(64 * 1024 / 3, preview.length());
        assertEquals("云".repeat(preview.length()), preview, "no character may be split");
    }

    @Test
    void unreachableTargetReturnsSafeError() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        ObjectNode payload = JSON.createObjectNode();
        payload.put("method", "GET");
        payload.put("url", "http://127.0.0.1:" + unusedPort + "/");
        payload.put("expectStatus", 200);

        JsonNode result = send(payload);

        assertEquals("ERROR", result.get("status").asText());
        assertEquals("REQUEST_FAILED", result.get("reasonCode").asText());
        assertTrue(result.get("actualStatus").isNull());
        assertTrue(result.get("expectedStatus").asInt() == 200);
        String message = result.get("message").asText();
        assertTrue(message.contains("无法连接"), message);
        assertFalse(message.contains("Exception"), "stack details must not leak: " + message);
        assertFalse(message.contains("\tat "), "stack details must not leak: " + message);
        assertFalse(message.contains("PrintStream"), "log objects must not leak: " + message);
        assertTrue(result.get("responseBody").isNull(), "no response means no preview");
        assertFalse(result.get("responseTruncated").asBoolean());
        assertEquals(0, result.get("responseBytes").asInt());
    }

    @Test
    void requestHeadersAndBodyAreNotAddedToResult() throws Exception {
        ObjectNode payload = casePayload("/post-created", 201);
        payload.put("method", "POST");
        payload.put("body", "{\"password\":\"hunter2\"}");
        payload.putArray("headers").add("Authorization: Bearer supersecret");

        HttpResponse<String> response = post(JSON.writeValueAsString(payload), webBase);

        assertEquals(200, response.statusCode());
        assertEquals("PASS", JSON.readTree(response.body()).get("status").asText());
        assertFalse(response.body().contains("supersecret"), "authorization value must not leak");
        assertFalse(response.body().contains("hunter2"), "request body must not leak");
    }

    @Test
    void rejectsGetOnExecutionEndpoint() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(webBase + "/api/run")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(405, response.statusCode());
    }

    @Test
    void bindsToLoopbackOnly() {
        assertTrue(web.bindAddress().getAddress().isLoopbackAddress());
        assertEquals("127.0.0.1", web.bindAddress().getAddress().getHostAddress());
        assertTrue(web.port() > 0);
    }

    @Test
    void webModeRejectsExtraArguments() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = Main.execute(new String[] {"--web", "--url", "http://127.0.0.1/"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(2, exitCode);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("--web cannot be combined"));
    }

    @Test
    void duplicateWebFlagIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> WebServer.run(
                new String[] {"--web", "--web"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));
    }

    private String pageBody() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(webBase + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode());
        return response.body();
    }

    private ObjectNode casePayload(String path, int expectedStatus) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("method", "GET");
        payload.put("url", mockBase + path);
        payload.put("expectStatus", expectedStatus);
        return payload;
    }

    private JsonNode send(ObjectNode payload) throws Exception {
        HttpResponse<String> response = post(JSON.writeValueAsString(payload), webBase);
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> post(String body, String origin) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(webBase + "/api/run"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (origin != null) {
            builder.header("Origin", origin);
        }
        return client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        respond(exchange, status, "");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
