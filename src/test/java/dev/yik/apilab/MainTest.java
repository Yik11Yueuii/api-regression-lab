package dev.yik.apilab;

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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class MainTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> respond(exchange, 200));
        server.createContext("/text", exchange -> respond(exchange, 200, "hello world"));
        server.createContext("/body", exchange -> respond(exchange, 200, "{\"weather\":\"多云\"}"));
        server.createContext("/body-wrong", exchange -> respond(exchange, 200, "{\"weather\":\"晴\"}"));
        server.createContext("/missing", exchange -> respond(exchange, 404, "{\"weather\":\"多云\"}"));
        server.createContext("/missing-wrong", exchange -> respond(exchange, 404, "{\"weather\":\"晴\"}"));
        server.createContext("/header-echo", exchange -> {
            String token = exchange.getRequestHeaders().getFirst("X-Token");
            String trace = exchange.getRequestHeaders().getFirst("X-Trace");
            respond(exchange, 200, "token=" + token + ";trace=" + trace);
        });
        server.createContext("/authorization-echo", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            respond(exchange, 200, "authorization=" + authorization);
        });
        server.createContext("/query-echo", exchange -> respond(exchange, 200,
                "raw=" + exchange.getRequestURI().getRawQuery()));
        server.createContext("/query-decoded", exchange -> respond(exchange, 200,
                "decoded=" + exchange.getRequestURI().getQuery()));
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void returnsPassWhenStatusMatches() {
        Result result = run("--url", url("/ok"), "--expect-status", "200");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("PASS"), result.out());
        assertTrue(result.out().contains("expected=200"), result.out());
        assertTrue(result.out().contains("actual=200"), result.out());
        assertEquals("", result.err());
    }

    @Test
    void returnsFailWhenStatusDoesNotMatch() {
        Result result = run("--url", url("/missing"), "--expect-status", "200");

        assertEquals(1, result.exitCode());
        assertTrue(result.out().contains("FAIL"), result.out());
        assertTrue(result.out().contains("expected=200"), result.out());
        assertTrue(result.out().contains("actual=404"), result.out());
    }

    @Test
    void returnsErrorWhenExpectedStatusIsMissing() {
        Result result = run("--url", url("/ok"));

        assertEquals(2, result.exitCode());
        assertFalse(result.out().contains("PASS"), result.out());
        assertTrue(result.err().contains("ERROR"), result.err());
    }

    @Test
    void returnsErrorWhenExpectedStatusIsNotAnInteger() {
        Result result = run("--url", url("/ok"), "--expect-status", "abc");

        assertEquals(2, result.exitCode());
        assertFalse(result.out().contains("PASS"), result.out());
        assertTrue(result.err().contains("ERROR"), result.err());
    }

    @Test
    void returnsErrorWhenUrlIsInvalid() {
        Result result = run("--url", "not-a-url", "--expect-status", "200");

        assertEquals(2, result.exitCode());
        assertFalse(result.out().contains("PASS"), result.out());
        assertTrue(result.err().contains("ERROR"), result.err());
    }

    @Test
    void returnsErrorWhenServerIsUnreachable() throws IOException {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }

        Result result = run("--url", "http://127.0.0.1:" + unusedPort + "/", "--expect-status", "200");

        assertEquals(2, result.exitCode());
        assertFalse(result.out().contains("PASS"), result.out());
        assertTrue(result.err().contains("ERROR"), result.err());
    }

    @Test
    void returnsPassWhenStatusAndBodyMatch() {
        Result result = run("--url", url("/text"), "--expect-status", "200", "--expect-contains", "hello");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("PASS"), result.out());
        assertTrue(result.out().contains("expected=200"), result.out());
        assertTrue(result.out().contains("actual=200"), result.out());
        assertEquals("", result.err());
    }

    @Test
    void returnsFailWhenBodyDoesNotContainExpectedText() {
        Result result = run("--url", url("/body-wrong"), "--expect-status", "200",
                "--expect-contains", "多云");

        assertEquals(1, result.exitCode());
        assertTrue(result.out().contains("FAIL"), result.out());
        assertTrue(result.out().contains("expected=200"), result.out());
        assertTrue(result.out().contains("actual=200"), result.out());
        assertTrue(result.out().contains("does not contain expected text: 多云"), result.out());
        assertFalse(result.out().contains("{\"weather\""), result.out());
    }

    @Test
    void returnsFailWhenStatusDiffersButBodyMatches() {
        Result result = run("--url", url("/missing"), "--expect-status", "200",
                "--expect-contains", "多云");

        assertEquals(1, result.exitCode());
        assertTrue(result.out().contains("FAIL"), result.out());
        assertTrue(result.out().contains("expected=200"), result.out());
        assertTrue(result.out().contains("actual=404"), result.out());
        assertFalse(result.out().contains("does not contain expected text"), result.out());
    }

    @Test
    void returnsFailWithBothReasons() {
        Result result = run("--url", url("/missing-wrong"), "--expect-status", "200",
                "--expect-contains", "多云");

        assertEquals(1, result.exitCode());
        assertTrue(result.out().contains("FAIL"), result.out());
        assertTrue(result.out().contains("expected=200"), result.out());
        assertTrue(result.out().contains("actual=404"), result.out());
        assertTrue(result.out().contains("does not contain expected text: 多云"), result.out());
    }

    @Test
    void keepsOriginalBehaviorWithoutExpectContains() {
        Result result = run("--url", url("/body"), "--expect-status", "200");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("PASS"), result.out());
        assertFalse(result.out().contains("does not contain expected text"), result.out());
        assertEquals("", result.err());
    }

    @Test
    void matchesChineseUtf8Body() {
        Result result = run("--url", url("/body"), "--expect-status", "200",
                "--expect-contains", "多云");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("PASS"), result.out());
        assertEquals("", result.err());
    }

    @Test
    void returnsErrorWhenContainsIsEmpty() {
        Result result = run("--url", url("/ok"), "--expect-status", "200",
                "--expect-contains", "");

        assertEquals(2, result.exitCode());
        assertFalse(result.out().contains("PASS"), result.out());
        assertTrue(result.err().contains("--expect-contains must not be empty"), result.err());
    }

    @Test
    void returnsErrorWhenContainsValueIsMissing() {
        Result result = run("--url", url("/ok"), "--expect-status", "200", "--expect-contains");

        assertEquals(2, result.exitCode());
        assertFalse(result.out().contains("PASS"), result.out());
        assertTrue(result.err().contains("missing value for --expect-contains"), result.err());
    }

    @Test
    void returnsErrorWhenContainsIsDuplicated() {
        Result result = run("--url", url("/ok"), "--expect-status", "200",
                "--expect-contains", "a", "--expect-contains", "b");

        assertEquals(2, result.exitCode());
        assertFalse(result.out().contains("PASS"), result.out());
        assertTrue(result.err().contains("duplicate option: --expect-contains"), result.err());
    }

    @Test
    void sendsSingleCustomHeader() {
        Result result = run("--url", url("/header-echo"), "--expect-status", "200",
                "--header", "X-Token: abc123", "--expect-contains", "token=abc123");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("PASS"), result.out());
        assertEquals("", result.err());
    }

    @Test
    void sendsMultipleCustomHeaders() {
        Result result = run("--url", url("/header-echo"), "--expect-status", "200",
                "--header", "X-Token: abc123", "--header", "X-Trace: trace-1",
                "--expect-contains", "token=abc123;trace=trace-1");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("PASS"), result.out());
        assertEquals("", result.err());
    }

    @Test
    void keepsColonInsideHeaderValue() {
        Result result = run("--url", url("/header-echo"), "--expect-status", "200",
                "--header", "X-Token: a:b:c", "--expect-contains", "token=a:b:c");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("PASS"), result.out());
    }

    @Test
    void rejectsHeaderWithoutColon() {
        Result result = run("--url", url("/ok"), "--expect-status", "200", "--header", "NoColonHere");

        assertEquals(2, result.exitCode());
        assertFalse(result.out().contains("PASS"), result.out());
        assertTrue(result.err().contains("invalid --header value"), result.err());
    }

    @Test
    void rejectsHeaderWithEmptyName() {
        Result result = run("--url", url("/ok"), "--expect-status", "200", "--header", ": value");

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("header name must not be empty"), result.err());
    }

    @Test
    void rejectsHeaderNameWithIllegalCharacters() {
        Result result = run("--url", url("/ok"), "--expect-status", "200", "--header", "Bad Name: value");

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("illegal header name"), result.err());
    }

    @Test
    void rejectsRestrictedHeaderWithClearError() {
        Result result = run("--url", url("/ok"), "--expect-status", "200",
                "--header", "Host: example.com");

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("header not allowed by JDK HttpClient: Host"), result.err());
        assertFalse(result.err().contains("example.com"), result.err());
    }

    @Test
    void doesNotPrintSensitiveHeaderValue() {
        Result result = run("--url", url("/authorization-echo"), "--expect-status", "200",
                "--header", "Authorization: Bearer s3cr3t-token",
                "--expect-contains", "authorization=Bearer s3cr3t-token");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("PASS"), result.out());
        assertFalse(result.out().contains("s3cr3t-token"), result.out());
        assertFalse(result.err().contains("s3cr3t-token"), result.err());
    }

    @Test
    void returnsErrorWithoutLeakingSensitiveHeaderWithControlCharacters() {
        String sensitiveValue = "Bearer s3cr3t" + (char) 1 + "token";

        Result result = run("--url", url("/ok"), "--expect-status", "200",
                "--header", "Authorization: " + sensitiveValue);

        assertEquals(2, result.exitCode());
        assertFalse(result.out().contains("PASS"), result.out());
        assertTrue(result.err().contains("ERROR"), result.err());
        assertTrue(result.err().contains("control characters"), result.err());
        assertFalse(result.out().contains("s3cr3t"), result.out());
        assertFalse(result.err().contains("s3cr3t"), result.err());
    }

    @Test
    void sendsSingleQueryParameter() {
        Result result = run("--url", url("/query-echo"), "--expect-status", "200",
                "--query", "city=Beijing", "--expect-contains", "raw=city=Beijing");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("PASS"), result.out());
        assertEquals("", result.err());
    }

    @Test
    void sendsMultipleQueryParameters() {
        Result result = run("--url", url("/query-echo"), "--expect-status", "200",
                "--query", "city=Beijing", "--query", "unit=c",
                "--expect-contains", "raw=city=Beijing&unit=c");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("PASS"), result.out());
    }

    @Test
    void encodesChineseSpaceAndSpecialCharactersInQuery() {
        Result result = run("--url", url("/query-echo"), "--expect-status", "200",
                "--query", "city=北京", "--query", "q=hello world", "--query", "sym=a&b",
                "--expect-contains", "raw=city=%E5%8C%97%E4%BA%AC&q=hello%20world&sym=a%26b");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("PASS"), result.out());
    }

    @Test
    void decodesUtf8QueryParameterOnServerSide() {
        Result result = run("--url", url("/query-decoded"), "--expect-status", "200",
                "--query", "city=北京", "--query", "q=hello world",
                "--expect-contains", "decoded=city=北京&q=hello world");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("PASS"), result.out());
    }

    @Test
    void preservesExistingQueryParameters() {
        Result result = run("--url", url("/query-echo?existing=1&keep=a%20b"), "--expect-status", "200",
                "--query", "x=2", "--expect-contains", "raw=existing=1&keep=a%20b&x=2");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("PASS"), result.out());
    }

    @Test
    void doesNotDoubleEncodeExistingPercentEncoding() {
        Result result = run("--url", url("/query-echo"), "--expect-status", "200",
                "--query", "pre=%E5%8C%97", "--expect-contains", "raw=pre=%E5%8C%97");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("PASS"), result.out());
        assertFalse(result.out().contains("%25E5"), result.out());
    }

    @Test
    void allowsEqualsAndEmptyValueInQuery() {
        Result result = run("--url", url("/query-decoded"), "--expect-status", "200",
                "--query", "a=b=c", "--query", "empty=",
                "--expect-contains", "decoded=a=b=c&empty=");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("PASS"), result.out());
    }

    @Test
    void rejectsQueryWithoutEqualsSign() {
        Result result = run("--url", url("/ok"), "--expect-status", "200", "--query", "city");

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("invalid --query value"), result.err());
    }

    @Test
    void rejectsQueryWithEmptyName() {
        Result result = run("--url", url("/ok"), "--expect-status", "200", "--query", "=Beijing");

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("parameter name must not be empty"), result.err());
    }

    @Test
    void keepsOriginalBehaviorWithoutHeaderAndQuery() {
        Result headers = run("--url", url("/header-echo"), "--expect-status", "200",
                "--expect-contains", "token=null;trace=null");
        Result query = run("--url", url("/query-echo"), "--expect-status", "200",
                "--expect-contains", "raw=null");

        assertEquals(0, headers.exitCode());
        assertTrue(headers.out().contains("PASS"), headers.out());
        assertEquals(0, query.exitCode());
        assertTrue(query.out().contains("PASS"), query.out());
        assertEquals("", headers.err());
        assertEquals("", query.err());
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(bytes);
        }
    }

    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            exitCode = Main.execute(args, outStream, errStream);
        }
        return new Result(exitCode, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exitCode, String out, String err) {
    }
}
