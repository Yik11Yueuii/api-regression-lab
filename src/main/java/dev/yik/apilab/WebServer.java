package dev.yik.apilab;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local web UI for single case tests. The server binds to the loopback interface only and reuses
 * the CLI request and assertion logic through {@link Main}, so the browser never gets a second
 * test engine. Requests to the execution endpoint are rejected unless they come from the page
 * served by this very server (Origin and Host checks), which keeps other websites from driving the
 * local test service.
 */
final class WebServer implements AutoCloseable {

    static final String DEFAULT_HOST = "127.0.0.1";
    static final int DEFAULT_PORT = 8765;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
    private static final int MAX_MESSAGE_LENGTH = 300;
    /** Upper bound of the response preview; longer bodies are cut on a UTF-8 boundary. */
    private static final int MAX_PREVIEW_BYTES = 64 * 1024;
    private static final int MAX_BATCH_CASES = 20;
    private static final Set<String> PAYLOAD_FIELDS = Set.of(
            "url", "method", "headers", "query", "body", "expectStatus", "expectContains");
    private static final Set<String> SUITE_CASE_FIELDS = Set.of(
            "name", "url", "method", "headers", "query", "bodyJson",
            "expectStatus", "expectContains", "body");
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "cookie", "set-cookie", "proxy-authorization");
    private static final byte[] PAGE = loadPage();

    private final HttpServer server;
    private final ExecutorService executor;
    private final HttpClient client;
    private final int port;

    WebServer(int port) throws IOException {
        this.server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName(DEFAULT_HOST), port), 0);
        this.port = server.getAddress().getPort();
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        server.createContext("/api/run", exchange -> safeHandle(exchange, this::handleRun));
        server.createContext("/api/batch", exchange -> safeHandle(exchange, this::handleBatch));
        server.createContext("/", exchange -> safeHandle(exchange, this::handlePage));
        server.setExecutor(executor);
    }

    /**
     * Starts the web mode for {@code --web}. The method blocks until the process is stopped; any
     * option combination other than a single {@code --web} is rejected before a socket is opened.
     */
    static int run(String[] args, PrintStream out) {
        for (int i = 0; i < args.length; i++) {
            if (!args[i].equals("--web")) {
                throw new IllegalArgumentException(
                        "--web cannot be combined with other options: " + args[i]);
            }
            if (i > 0) {
                throw new IllegalArgumentException("duplicate option: --web");
            }
        }
        WebServer server;
        try {
            server = new WebServer(DEFAULT_PORT);
        } catch (IOException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new IllegalArgumentException("cannot start web server on http://" + DEFAULT_HOST
                    + ":" + DEFAULT_PORT + "/: " + Main.printable(reason));
        }
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> {
                    server.stop();
                    stopped.countDown();
                }, "apilab-web-shutdown"));
        server.start();
        out.println("WEB listening on http://" + DEFAULT_HOST + ":" + server.port()
                + "/ (loopback only, press Ctrl+C to stop)");
        out.flush();
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            server.stop();
        }
        return 0;
    }

    void start() {
        server.start();
    }

    int port() {
        return port;
    }

    /** The actual bind address, exposed so tests can assert the loopback-only policy. */
    InetSocketAddress bindAddress() {
        return server.getAddress();
    }

    @Override
    public void close() {
        stop();
    }

    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    /** Keeps an unexpected runtime failure from surfacing a stack trace to the browser. */
    private static void safeHandle(HttpExchange exchange, ExchangeHandler handler) {
        try {
            handler.handle(exchange);
        } catch (Exception e) {
            try {
                if (exchange.getResponseCode() < 0) {
                    sendJson(exchange, 500, errorNode("本地测试服务执行失败"));
                }
            } catch (IOException ignored) {
                // the connection is already gone
            } finally {
                exchange.close();
            }
        }
    }

    private void handlePage(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendText(exchange, 405, "method not allowed");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (!path.equals("/") && !path.equals("/index.html")) {
                sendText(exchange, 404, "not found");
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, PAGE.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(PAGE);
            }
        }
    }

    private void handleRun(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, errorNode("执行接口只接受 POST 请求"));
                return;
            }
            if (!isTrustedRequest(exchange)) {
                sendJson(exchange, 403, errorNode("拒绝请求：来源不是本地测试页面"));
                return;
            }
            byte[] raw;
            try {
                raw = readLimited(exchange.getRequestBody());
            } catch (PayloadTooLargeException e) {
                sendJson(exchange, 413, errorNode("请求内容过大"));
                return;
            }
            JsonNode payload;
            try {
                payload = JSON.readTree(raw);
            } catch (JacksonException e) {
                sendJson(exchange, 400, errorNode("请求参数不是合法的 JSON"));
                return;
            }
            Main.Options options;
            try {
                options = toOptions(payload);
            } catch (IllegalArgumentException e) {
                sendJson(exchange, 400, errorNode(
                        "请求参数不合法：" + Main.printable(e.getMessage())));
                return;
            }

            ByteArrayOutputStream log = new ByteArrayOutputStream();
            PrintStream sink = new PrintStream(log, true, StandardCharsets.UTF_8);
            long started = System.nanoTime();
            Main.CaseResult result = Main.runForWeb(options, client, sink);
            long durationMillis = (System.nanoTime() - started) / 1_000_000;
            sendJson(exchange, 200,
                    resultNode(result, durationMillis, log.toString(StandardCharsets.UTF_8)));
        }
    }

    /**
     * Runs a named list of cases in order. The input already lives in memory, so this endpoint has
     * no file-system reads and no path handling whatsoever; browser-driven files go through the
     * {@code /api/import} route instead. Per-case failures never abort the rest of the batch.
     */
    private void handleBatch(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, errorNode("批量执行接口只接受 POST 请求"));
                return;
            }
            if (!isTrustedRequest(exchange)) {
                sendJson(exchange, 403, errorNode("拒绝请求：来源不是本地测试页面"));
                return;
            }
            byte[] raw;
            try {
                raw = readLimited(exchange.getRequestBody());
            } catch (PayloadTooLargeException e) {
                sendJson(exchange, 413, errorNode("测试集过大，最多 " + MAX_BATCH_CASES + " 个用例"));
                return;
            }
            JsonNode payload;
            try {
                payload = JSON.readTree(raw);
            } catch (JacksonException e) {
                sendJson(exchange, 400, errorNode("请求参数不是合法的 JSON"));
                return;
            }
            List<Main.WebSuiteCase> cases;
            try {
                cases = toSuite(payload);
            } catch (IllegalArgumentException e) {
                sendJson(exchange, 400, errorNode("测试集不合法：" + Main.printable(e.getMessage())));
                return;
            }
            if (cases.isEmpty()) {
                sendJson(exchange, 400, errorNode("测试集不能为空"));
                return;
            }

            long totalStarted = System.nanoTime();
            ByteArrayOutputStream log = new ByteArrayOutputStream();
            PrintStream sink = new PrintStream(log, true, StandardCharsets.UTF_8);
            ArrayNode casesNode = JSON.createArrayNode();
            int pass = 0;
            int fail = 0;
            int error = 0;
            ArrayNode reportCases = JSON.createArrayNode();

            for (Main.WebSuiteCase entry : cases) {
                sink.println("CASE " + entry.displayName());
                long caseStarted = System.nanoTime();
                Main.CaseResult result;
                try {
                    result = Main.runForWeb(entry.options(), client, sink)
                            .named(entry.displayName());
                } catch (IllegalArgumentException e) {
                    sink.println("ERROR " + Main.printable(e.getMessage()));
                    result = Main.CaseResult.error(entry.displayName(), null, "INVALID_CASE");
                }
                long caseDuration = (System.nanoTime() - caseStarted) / 1_000_000;
                switch (result.status()) {
                    case "PASS" -> pass++;
                    case "FAIL" -> fail++;
                    default -> error++;
                }
                String lineAfter = lastLine(sink.toString());
                casesNode.add(resultNode(result, caseDuration, lineAfter));
                reportCases.add(reportCaseNode(result));
            }
            long totalDuration = (System.nanoTime() - totalStarted) / 1_000_000;

            ObjectNode root = JSON.createObjectNode();
            root.put("totalDurationMillis", totalDuration);
            root.put("startedAt", Instant.now().toString());
            ObjectNode summary = root.putObject("summary");
            summary.put("total", cases.size());
            summary.put("pass", pass);
            summary.put("fail", fail);
            summary.put("error", error);
            root.set("cases", casesNode);

            ObjectNode reportNode = JSON.createObjectNode();
            reportNode.put("version", 1);
            reportNode.put("generatedAt", Instant.now().toString());
            ObjectNode reportSummary = reportNode.putObject("summary");
            reportSummary.put("total", cases.size());
            reportSummary.put("pass", pass);
            reportSummary.put("fail", fail);
            reportSummary.put("error", error);
            reportNode.set("cases", reportCases);
            root.set("report", reportNode);

            sendJson(exchange, 200, root);
        }
    }

    /** The engine emits exactly one status line per case; this returns the last line so failure
     *  hints (for example a STATUS_MISMATCH message) surface quickly on the browser side. */
    private static String lastLine(String log) {
        String[] lines = log.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].strip();
            if (!line.isEmpty() && !line.startsWith("CASE ") && !line.startsWith("SUMMARY ")) {
                return line;
            }
        }
        return "";
    }

    private static ObjectNode reportCaseNode(Main.CaseResult r) {
        ObjectNode node = JSON.createObjectNode();
        node.put("name", r.name());
        node.put("status", r.status());
        putNullable(node, "expectedStatus", r.expectedStatus());
        putNullable(node, "actualStatus", r.actualStatus());
        putNullable(node, "reasonCode", r.reasonCode());
        return node;
    }

    /**
     * Only the page served on this port may call the execution endpoint. Browsers always send an
     * Origin header on POST, so a missing Origin is rejected as well; the Host check additionally
     * guards against DNS rebinding.
     */
    private boolean isTrustedRequest(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || !allowedOrigins().contains(origin)) {
            return false;
        }
        String host = exchange.getRequestHeaders().getFirst("Host");
        return host != null && allowedHosts().contains(host.toLowerCase(Locale.ROOT));
    }

    private Set<String> allowedOrigins() {
        return Set.of("http://" + DEFAULT_HOST + ":" + port,
                "http://localhost:" + port);
    }

    private Set<String> allowedHosts() {
        return Set.of(DEFAULT_HOST + ":" + port, "localhost:" + port);
    }

    /**
     * Converts the browser payload into the same JSON case shape used by test case files, then
     * delegates all field validation and request building to {@link Main.Options#fromJson}.
     */
    private static Main.Options toOptions(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("请求参数必须是一个 JSON 对象");
        }
        payload.fieldNames().forEachRemaining(field -> {
            if (!PAYLOAD_FIELDS.contains(field)) {
                throw new IllegalArgumentException("不支持的字段: " + field);
            }
        });
        ObjectNode node = JSON.createObjectNode();
        copyField(payload, node, "url");
        copyField(payload, node, "method");
        copyField(payload, node, "headers");
        copyField(payload, node, "query");
        copyField(payload, node, "expectStatus");
        copyField(payload, node, "expectContains");
        JsonNode body = payload.get("body");
        if (body != null && !body.isNull()) {
            if (!body.isTextual()) {
                throw new IllegalArgumentException("body 必须是字符串");
            }
            String text = body.textValue();
            if (!text.isBlank()) {
                JsonNode parsed;
                try {
                    parsed = JSON.readTree(text);
                } catch (JacksonException e) {
                    throw new IllegalArgumentException("请求体不是合法的 JSON");
                }
                if (parsed == null || (!parsed.isObject() && !parsed.isArray())) {
                    throw new IllegalArgumentException("请求体必须是 JSON 对象或数组");
                }
                node.set("bodyJson", parsed);
            }
        }
        return Main.Options.fromJson(node, "web request");
    }

    private static void copyField(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null) {
            target.set(field, value);
        }
    }

    /**
     * Accepts both an array of case objects (the shape the web UI uses in memory) and a full v1
     * suite object with {@code version} and {@code cases} fields so the browser's own test set
     * format is importable as well. Per-case validation is delegated to {@link Main.Options}.
     */
    private static List<Main.WebSuiteCase> toSuite(JsonNode payload) {
        JsonNode casesNode;
        if (payload.isArray()) {
            casesNode = payload;
        } else if (payload.isObject()) {
            payload.fieldNames().forEachRemaining(field -> {
                if (!field.equals("version") && !field.equals("cases")) {
                    throw new IllegalArgumentException("不支持的字段: " + field);
                }
            });
            JsonNode versionNode = payload.get("version");
            if (versionNode != null && (!versionNode.isIntegralNumber() || versionNode.asInt() != 1)) {
                throw new IllegalArgumentException("只支持 version=1");
            }
            casesNode = payload.get("cases");
        } else {
            throw new IllegalArgumentException("请求参数必须是一个 JSON 对象或数组");
        }
        if (casesNode == null || !casesNode.isArray()) {
            throw new IllegalArgumentException("缺少 cases 字段");
        }
        if (casesNode.isEmpty()) {
            throw new IllegalArgumentException("测试集不能为空");
        }
        if (casesNode.size() > MAX_BATCH_CASES) {
            throw new IllegalArgumentException("测试集最多 " + MAX_BATCH_CASES + " 个用例");
        }

        List<Main.WebSuiteCase> out = new ArrayList<>();
        int index = 0;
        for (JsonNode item : casesNode) {
            index++;
            String tag = "第 " + index + " 个用例";
            if (!item.isObject()) {
                throw new IllegalArgumentException(tag + " 必须是 JSON 对象");
            }
            String name = null;
            JsonNode nameNode = item.get("name");
            if (nameNode != null) {
                if (!nameNode.isTextual()) {
                    throw new IllegalArgumentException(tag + " 的 name 必须是字符串");
                }
                name = nameNode.textValue();
            }
            for (String field : SUITE_CASE_FIELDS) {
                if (item.has(field) && field.equals("body")) {
                    // body is the raw string the web UI stores; translate to bodyJson below.
                }
            }

            ObjectNode translated = JSON.createObjectNode();
            item.fieldNames().forEachRemaining(field -> {
                if (!SUITE_CASE_FIELDS.contains(field)) {
                    throw new IllegalArgumentException(tag + " 不支持的字段: " + field);
                }
            });
            copyField(item, translated, "url");
            copyField(item, translated, "method");
            copyField(item, translated, "headers");
            copyField(item, translated, "query");
            copyField(item, translated, "expectStatus");
            copyField(item, translated, "expectContains");

            JsonNode body = item.get("body");
            if (body != null && !body.isNull()) {
                if (body.isTextual()) {
                    String text = body.textValue();
                    if (!text.isBlank()) {
                        JsonNode parsed;
                        try {
                            parsed = JSON.readTree(text);
                        } catch (JacksonException e) {
                            throw new IllegalArgumentException(tag + " 的 body 不是合法的 JSON");
                        }
                        if (parsed == null || (!parsed.isObject() && !parsed.isArray())) {
                            throw new IllegalArgumentException(tag + " 的 body 必须是 JSON 对象或数组");
                        }
                        translated.set("bodyJson", parsed);
                    }
                } else if (body.isObject() || body.isArray()) {
                    translated.set("bodyJson", body);
                } else {
                    throw new IllegalArgumentException(tag + " 的 body 必须是 JSON 对象、数组或字符串");
                }
            }
            copyField(item, translated, "bodyJson");

            Main.Options options;
            try {
                options = Main.Options.fromJson(translated, tag);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(tag + "：" + e.getMessage());
            }
            out.add(Main.WebSuiteCase.of(name, options));
        }
        return List.copyOf(out);
    }

    private static ObjectNode resultNode(Main.CaseResult result, long durationMillis, String log) {
        ObjectNode node = JSON.createObjectNode();
        node.put("status", result.status());
        putNullable(node, "expectedStatus", result.expectedStatus());
        putNullable(node, "actualStatus", result.actualStatus());
        if (result.reasonCode() == null) {
            node.putNull("reasonCode");
        } else {
            node.put("reasonCode", result.reasonCode());
        }
        String message = message(result, log);
        if (message == null) {
            node.putNull("message");
        } else {
            node.put("message", message);
        }
        node.put("durationMillis", durationMillis);
        Preview preview = preview(result.responseBody());
        if (preview.text() == null) {
            node.putNull("responseBody");
        } else {
            node.put("responseBody", preview.text());
        }
        node.put("responseTruncated", preview.truncated());
        node.put("responseBytes", preview.size());
        return node;
    }

    /**
     * Limits the response preview without splitting a multi-byte character. A null body means the
     * request produced no response at all, which is different from an empty response body.
     */
    private static Preview preview(String body) {
        if (body == null) {
            return new Preview(null, false, 0);
        }
        int totalBytes = body.getBytes(StandardCharsets.UTF_8).length;
        if (totalBytes <= MAX_PREVIEW_BYTES) {
            return new Preview(body, false, totalBytes);
        }
        int usedBytes = 0;
        int end = 0;
        while (end < body.length()) {
            int codePoint = body.codePointAt(end);
            int codePointBytes = utf8Length(codePoint);
            if (usedBytes + codePointBytes > MAX_PREVIEW_BYTES) {
                break;
            }
            usedBytes += codePointBytes;
            end += Character.charCount(codePoint);
        }
        return new Preview(body.substring(0, end), true, totalBytes);
    }

    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < 0x10000 ? 3 : 4;
    }

    private static void putNullable(ObjectNode node, String field, Integer value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    /**
     * Builds a short, safe explanation for the browser. Only the reason code and the engine's own
     * one-line message are used, so request bodies, headers and stack traces never leak.
     */
    private static String message(Main.CaseResult result, String log) {
        String code = result.reasonCode();
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "STATUS_MISMATCH" -> "实际状态码 " + result.actualStatus()
                    + " 与预期 " + result.expectedStatus() + " 不一致";
            case "BODY_MISMATCH" -> "响应内容未包含预期文本";
            case "REQUEST_FAILED" -> requestFailedMessage(log);
            case "INTERRUPTED" -> "请求被中断，请重试";
            default -> {
                String detail = detail(log);
                yield detail.isEmpty() ? "请求未能完成" : "请求执行失败：" + detail;
            }
        };
    }

    /** Turns the engine's IO failure into a short Chinese hint instead of a Java class name. */
    private static String requestFailedMessage(String log) {
        String detail = detail(log);
        String lower = detail.toLowerCase(Locale.ROOT);
        if (lower.contains("refused") || lower.contains("connectexception")) {
            return "无法连接到目标接口，请确认地址和端口是否可用";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "请求超时，目标接口未在限定时间内响应";
        }
        return detail.isEmpty() ? "请求未能完成" : "请求执行失败：" + detail;
    }

    private static String detail(String log) {
        String text = log.strip();
        if (text.startsWith("ERROR ")) {
            text = text.substring("ERROR ".length());
        }
        int lineBreak = text.indexOf('\n');
        if (lineBreak >= 0) {
            text = text.substring(0, lineBreak);
        }
        String clean = Main.printable(text).strip();
        if (clean.length() > MAX_MESSAGE_LENGTH) {
            clean = clean.substring(0, MAX_MESSAGE_LENGTH) + "...";
        }
        return clean;
    }

    private static ObjectNode errorNode(String message) {
        ObjectNode node = JSON.createObjectNode();
        node.put("status", "ERROR");
        node.putNull("expectedStatus");
        node.putNull("actualStatus");
        node.put("reasonCode", "INVALID_REQUEST");
        node.put("message", message);
        node.putNull("durationMillis");
        node.putNull("responseBody");
        node.put("responseTruncated", false);
        node.put("responseBytes", 0);
        return node;
    }

    private static void sendJson(HttpExchange exchange, int status, JsonNode node)
            throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(node);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(bytes);
        }
    }

    private static void sendText(HttpExchange exchange, int status, String text)
            throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(bytes);
        }
    }

    private static byte[] readLimited(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        int read;
        while ((read = stream.read(chunk)) >= 0) {
            total += read;
            if (total > MAX_PAYLOAD_BYTES) {
                throw new PayloadTooLargeException();
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static byte[] loadPage() {
        try (InputStream stream = WebServer.class.getResourceAsStream("/web/index.html")) {
            if (stream == null) {
                throw new IllegalStateException("missing classpath resource: /web/index.html");
            }
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the web page resource", e);
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record Preview(String text, boolean truncated, int size) {
    }

    private static final class PayloadTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
