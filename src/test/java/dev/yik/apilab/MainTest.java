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
