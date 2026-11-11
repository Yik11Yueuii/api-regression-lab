package dev.yik.apilab;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class MainTest {

    private HttpServer server;
    private int port;

    @TempDir
    Path tempDir;

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
        server.createContext("/request-echo", exchange -> {
            String token = exchange.getRequestHeaders().getFirst("X-Token");
            respond(exchange, 200, "token=" + token + ";raw=" + exchange.getRequestURI().getRawQuery());
        });
        server.createContext("/post-echo", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            List<String> contentTypes = exchange.getRequestHeaders().get("Content-Type");
            respond(exchange, 200, "method=" + exchange.getRequestMethod()
                    + ";contentType=" + exchange.getRequestHeaders().getFirst("Content-Type")
                    + ";contentTypeCount=" + (contentTypes == null ? 0 : contentTypes.size())
                    + ";body=" + body
                    + ";rawQuery=" + exchange.getRequestURI().getRawQuery()
                    + ";token=" + exchange.getRequestHeaders().getFirst("X-Token"));
        });
        server.createContext("/post-created", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 201, "{\"id\":1}");
        });
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

    @Test
    void cliProcessReturnsExitCodeZeroAndPassOutput() throws Exception {
        CliResult result = runCli("--url", url("/request-echo"), "--expect-status", "200",
                "--header", "X-Token: abc123", "--query", "city=北京",
                "--expect-contains", "token=abc123;raw=city=%E5%8C%97%E4%BA%AC");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("PASS expected=200 actual=200"), result.output());
    }

    @Test
    void cliProcessReturnsExitCodeOneOnStatusMismatch() throws Exception {
        CliResult result = runCli("--url", url("/missing"), "--expect-status", "200");

        assertEquals(1, result.exitCode());
        assertTrue(result.output().contains("FAIL expected=200 actual=404"), result.output());
    }

    @Test
    void cliProcessReturnsExitCodeTwoOnInvalidArgument() throws Exception {
        CliResult result = runCli("--url", url("/ok"), "--expect-status", "200",
                "--header", "NoColonHere");

        assertEquals(2, result.exitCode());
        assertTrue(result.output().contains("ERROR invalid --header value"), result.output());
    }

    @Test
    void postJsonBodyPassesAndServerReceivesPost() {
        Result result = run("--url", url("/post-echo"), "--expect-status", "200",
                "--method", "POST", "--body-json", "{\"name\":\"demo\"}",
                "--expect-contains", "method=POST;");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("PASS expected=200 actual=200"), result.out());
        assertEquals("", result.err());
    }

    @Test
    void postSendsExactJsonBody() {
        Result result = run("--url", url("/post-echo"), "--expect-status", "200",
                "--method", "POST", "--body-json", "{\"id\":42,\"active\":true}",
                "--expect-contains", ";body={\"id\":42,\"active\":true}");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("PASS"), result.out());
    }

    @Test
    void postSendsChineseJsonBodyAsUtf8() {
        Result result = run("--url", url("/post-echo"), "--expect-status", "200",
                "--method", "POST", "--body-json", "{\"name\":\"张三\",\"city\":\"北京\"}",
                "--expect-contains", ";body={\"name\":\"张三\",\"city\":\"北京\"}");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("PASS"), result.out());
    }

    @Test
    void postSendsDefaultJsonContentType() {
        Result result = run("--url", url("/post-echo"), "--expect-status", "200",
                "--method", "POST", "--body-json", "{}",
                "--expect-contains", "contentType=application/json; charset=utf-8;contentTypeCount=1");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("PASS"), result.out());
    }

    @Test
    void explicitContentTypeWinsWithoutDuplicate() {
        Result result = run("--url", url("/post-echo"), "--expect-status", "200",
                "--method", "POST", "--body-json", "{}",
                "--header", "Content-Type: application/vnd.api+json",
                "--expect-contains", "contentType=application/vnd.api+json;contentTypeCount=1");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("PASS"), result.out());
    }

    @Test
    void rejectsDuplicateContentTypeIgnoringNameCase() {
        Result result = run("--url", url("/post-echo"), "--expect-status", "200",
                "--method", "POST", "--body-json", "{}",
                "--header", "Content-Type: application/json",
                "--header", "content-type: text/plain");

        assertEquals(2, result.exitCode());
        assertFalse(result.out().contains("PASS"), result.out());
        assertTrue(result.err().contains("duplicate Content-Type header"), result.err());
        assertFalse(result.err().contains("text/plain"), result.err());
        assertFalse(result.err().contains("application/json"), result.err());
    }

    @Test
    void rejectsEmptyContentTypeForPost() {
        Result result = run("--url", url("/post-echo"), "--expect-status", "200",
                "--method", "POST", "--body-json", "{}",
                "--header", "Content-Type:");

        assertEquals(2, result.exitCode());
        assertFalse(result.out().contains("PASS"), result.out());
        assertTrue(result.err().contains("Content-Type header must not be empty"), result.err());
    }

    @Test
    void postSupportsHeadersAndQueryParameters() {
        Result result = run("--url", url("/post-echo"), "--expect-status", "200",
                "--method", "POST", "--body-json", "{}",
                "--header", "X-Token: abc123", "--query", "city=北京",
                "--expect-contains", "rawQuery=city=%E5%8C%97%E4%BA%AC;token=abc123");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("PASS"), result.out());
    }

    @Test
    void postFailsWhenStatusDoesNotMatch() {
        Result result = run("--url", url("/post-created"), "--expect-status", "200",
                "--method", "POST", "--body-json", "{}");

        assertEquals(1, result.exitCode());
        assertTrue(result.out().contains("FAIL expected=200 actual=201"), result.out());
        assertEquals("", result.err());
    }

    @Test
    void postFailsWhenResponseBodyAssertionFails() {
        Result result = run("--url", url("/post-echo"), "--expect-status", "200",
                "--method", "POST", "--body-json", "{}",
                "--expect-contains", "not-present");

        assertEquals(1, result.exitCode());
        assertTrue(result.out().contains("FAIL"), result.out());
        assertTrue(result.out().contains("reason: response body does not contain expected text"),
                result.out());
    }

    @Test
    void getKeepsOriginalBehaviorWithoutNewOptions() {
        Result implicit = run("--url", url("/text"), "--expect-status", "200",
                "--expect-contains", "hello world");
        Result explicit = run("--url", url("/text"), "--expect-status", "200",
                "--method", "GET", "--expect-contains", "hello world");

        assertEquals(0, implicit.exitCode());
        assertTrue(implicit.out().contains("PASS"), implicit.out());
        assertEquals(0, explicit.exitCode());
        assertTrue(explicit.out().contains("PASS"), explicit.out());
        assertEquals("", implicit.err());
        assertEquals("", explicit.err());
    }

    @Test
    void rejectsBodyJsonWhenMethodIsGet() {
        Result result = run("--url", url("/text"), "--expect-status", "200",
                "--body-json", "{\"a\":1}");

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("--body-json is only supported with --method POST"), result.err());
    }

    @Test
    void rejectsPostWithoutBody() {
        Result result = run("--url", url("/post-echo"), "--expect-status", "200", "--method", "POST");

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("POST requires --body-json"), result.err());
    }

    @Test
    void rejectsPostWithEmptyBody() {
        Result result = run("--url", url("/post-echo"), "--expect-status", "200",
                "--method", "POST", "--body-json", "   ");

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("--body-json must not be empty"), result.err());
    }

    @Test
    void rejectsUnsupportedMethod() {
        Result result = run("--url", url("/text"), "--expect-status", "200", "--method", "PUT");

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("--method must be GET or POST"), result.err());
    }

    @Test
    void cliProcessSendsPostJsonBody() throws Exception {
        CliResult result = runCli("--url", url("/post-echo"), "--expect-status", "200",
                "--method", "POST", "--body-json", "{\"name\":\"张三\"}",
                "--expect-contains", "method=POST;contentType=application/json; charset=utf-8;"
                        + "contentTypeCount=1;body={\"name\":\"张三\"}");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("PASS expected=200 actual=200"), result.output());
    }

    @Test
    void fileModeGetCasePasses() throws Exception {
        Path file = writeCase("""
                {
                  "url": "$URL",
                  "method": "GET",
                  "query": ["city=北京"],
                  "expectStatus": 200,
                  "expectContains": "raw=city=%E5%8C%97%E4%BA%AC"
                }
                """.replace("$URL", url("/query-echo")));

        Result result = run("--file", file.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("PASS expected=200 actual=200"), result.out());
        assertEquals("", result.err());
    }

    @Test
    void fileModePostCaseSendsJsonBody() throws Exception {
        Path file = writeCase("""
                {
                  "url": "$URL",
                  "method": "POST",
                  "headers": ["X-Token: abc123"],
                  "bodyJson": {"name": "张三", "city": "北京"},
                  "expectStatus": 200,
                  "expectContains": "method=POST;contentType=application/json; charset=utf-8;contentTypeCount=1;body={\\"name\\":\\"张三\\",\\"city\\":\\"北京\\"};rawQuery=null;token=abc123"
                }
                """.replace("$URL", url("/post-echo")));

        Result result = run("--file", file.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("PASS expected=200 actual=200"), result.out());
        assertEquals("", result.err());
    }

    @Test
    void fileModeAcceptsJsonArrayBody() throws Exception {
        Path file = writeCase("""
                {
                  "url": "$URL",
                  "method": "POST",
                  "bodyJson": [1, 2, 3],
                  "expectStatus": 200,
                  "expectContains": "body=[1,2,3]"
                }
                """.replace("$URL", url("/post-echo")));

        Result result = run("--file", file.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("PASS"), result.out());
    }

    @Test
    void fileModeKeepsHeaderAndRepeatedQueryOrder() throws Exception {
        Path file = writeCase("""
                {
                  "url": "$URL",
                  "headers": ["X-Token: abc123"],
                  "query": ["city=北京", "city=上海", "empty="],
                  "expectStatus": 200,
                  "expectContains": "rawQuery=city=%E5%8C%97%E4%BA%AC&city=%E4%B8%8A%E6%B5%B7&empty=;token=abc123"
                }
                """.replace("$URL", url("/post-echo")));

        Result result = run("--file", file.toString());

        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("PASS"), result.out());
    }

    @Test
    void fileModeRejectsMissingRequiredField() throws Exception {
        Path file = writeCase("""
                {
                  "expectStatus": 200
                }
                """);

        Result result = run("--file", file.toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("missing required field in test case file: url"), result.err());
    }

    @Test
    void fileModeRejectsWrongFieldType() throws Exception {
        Path file = writeCase("""
                {
                  "url": "$URL",
                  "expectStatus": "200"
                }
                """.replace("$URL", url("/ok")));

        Result result = run("--file", file.toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("field 'expectStatus' must be an integer"), result.err());
    }

    @Test
    void fileModeRejectsUnknownField() throws Exception {
        Path file = writeCase("""
                {
                  "url": "$URL",
                  "expectStatus": 200,
                  "expectContain": "hello"
                }
                """.replace("$URL", url("/ok")));

        Result result = run("--file", file.toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("unknown field in test case file: expectContain"), result.err());
    }

    @Test
    void fileModeRejectsInvalidJsonWithoutEchoingContent() throws Exception {
        Path file = writeCase("""
                { "url": "$URL", "expectStatus": 200,
                """.replace("$URL", url("/ok")));

        Result result = run("--file", file.toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("invalid JSON in test case file"), result.err());
        assertFalse(result.err().contains("127.0.0.1"), result.err());
    }

    @Test
    void fileModeRejectsMissingFile() {
        Path missing = tempDir.resolve("does-not-exist.json");

        Result result = run("--file", missing.toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("cannot read test case file"), result.err());
    }

    @Test
    void fileModeRejectsDuplicateFileOption() {
        Result result = run("--file", "a.json", "--file", "b.json");

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("duplicate option: --file"), result.err());
    }

    @Test
    void fileModeRejectsCliOptions() {
        Result result = run("--file", "a.json", "--url", url("/ok"));

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("--file cannot be combined with other options"), result.err());
    }

    @Test
    void fileModeValidatesRequestConfiguration() throws Exception {
        Path file = writeCase("""
                {
                  "url": "$URL",
                  "bodyJson": {"a": 1},
                  "expectStatus": 200
                }
                """.replace("$URL", url("/ok")));

        Result result = run("--file", file.toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("--body-json is only supported with --method POST"), result.err());
    }

    @Test
    void fileModePostRequiresBody() throws Exception {
        Path file = writeCase("""
                {
                  "url": "$URL",
                  "method": "POST",
                  "expectStatus": 200
                }
                """.replace("$URL", url("/post-echo")));

        Result result = run("--file", file.toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("POST requires --body-json"), result.err());
    }

    @Test
    void fileModeRejectsNonContainerBodyJson() throws Exception {
        Path file = writeCase("""
                {
                  "url": "$URL",
                  "method": "POST",
                  "bodyJson": "plain text",
                  "expectStatus": 200
                }
                """.replace("$URL", url("/post-echo")));

        Result result = run("--file", file.toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("field 'bodyJson' must be a JSON object or array"), result.err());
    }

    @Test
    void fileModeProcessReturnsRealExitCodes() throws Exception {
        Path passFile = writeCase("""
                { "url": "$URL", "expectStatus": 200, "expectContains": "hello" }
                """.replace("$URL", url("/text")));
        Path failFile = writeCase("""
                { "url": "$URL", "expectStatus": 201 }
                """.replace("$URL", url("/text")));
        Path errorFile = writeCase("""
                { "url": "$URL", "expectStatus": 200, "unknownField": true }
                """.replace("$URL", url("/text")));

        assertEquals(0, runCli("--file", passFile.toString()).exitCode());
        assertEquals(1, runCli("--file", failFile.toString()).exitCode());
        assertEquals(2, runCli("--file", errorFile.toString()).exitCode());
    }

    @Test
    void suiteRunsAllCasesAndSummarizesPasses() throws Exception {
        Path dir = tempDir.resolve("suite-pass");
        writeFile(dir, "a.json", caseJson(url("/text"), 200, "hello world"));
        writeFile(dir, "b.json", caseJson(url("/text"), 200, "hello world"));
        Path suite = writeFile(dir, "suite.json", suiteJson("a.json", "b.json"));

        Result result = run("--suite", suite.toString());

        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("CASE a.json"), result.out());
        assertTrue(result.out().contains("CASE b.json"), result.out());
        assertTrue(result.out().contains("SUMMARY total=2 pass=2 fail=0 error=0"), result.out());
    }

    @Test
    void suiteSummarizesFailuresAndExitsOne() throws Exception {
        Path dir = tempDir.resolve("suite-fail");
        writeFile(dir, "a.json", caseJson(url("/text"), 200, "hello world"));
        writeFile(dir, "b.json", caseJson(url("/text"), 201, "hello world"));
        Path suite = writeFile(dir, "suite.json", suiteJson("a.json", "b.json"));

        Result result = run("--suite", suite.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.out().contains("CASE b.json"), result.out());
        assertTrue(result.out().contains("SUMMARY total=2 pass=1 fail=1 error=0"), result.out());
    }

    @Test
    void suiteSummarizesErrorsAndExitsTwo() throws Exception {
        Path dir = tempDir.resolve("suite-error");
        writeFile(dir, "a.json", caseJson(url("/text"), 200, "hello world"));
        writeFile(dir, "b.json", "{\"url\":\"$URL\",\"bodyJson\":{\"a\":1},\"expectStatus\":200}"
                .replace("$URL", url("/ok")));
        Path suite = writeFile(dir, "suite.json", suiteJson("a.json", "b.json"));

        Result result = run("--suite", suite.toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.out().contains("SUMMARY total=2 pass=1 fail=0 error=1"), result.out());
        assertTrue(result.err().contains("ERROR"), result.err());
    }

    @Test
    void suiteKeepsOrderAndContinuesAfterFailuresAndErrors() throws Exception {
        Path dir = tempDir.resolve("suite-mixed");
        writeFile(dir, "c1.json", caseJson(url("/text"), 201, "hello world"));
        writeFile(dir, "c2.json", caseJson(url("/text"), 200, "hello world"));
        writeFile(dir, "c3.json", "{\"url\":\"$URL\",\"expectStatus\":200,\"unknownField\":1}"
                .replace("$URL", url("/ok")));
        Path suite = writeFile(dir, "suite.json", suiteJson("c1.json", "c2.json", "c3.json"));

        Result result = run("--suite", suite.toString());

        assertEquals(2, result.exitCode());
        String out = result.out();
        assertTrue(out.indexOf("CASE c1.json") < out.indexOf("CASE c2.json"), out);
        assertTrue(out.indexOf("CASE c2.json") < out.indexOf("CASE c3.json"), out);
        assertTrue(out.contains("SUMMARY total=3 pass=1 fail=1 error=1"), out);
    }

    @Test
    void suiteResolvesRelativeEntriesFromSuiteDirectory() throws Exception {
        Path dir = tempDir.resolve("nested").resolve("cases");
        writeFile(dir, "only.json", caseJson(url("/text"), 200, "hello world"));
        Path suite = writeFile(dir, "suite.json", suiteJson("only.json"));

        Result result = run("--suite", suite.toString());

        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("SUMMARY total=1 pass=1 fail=0 error=0"), result.out());
    }

    @Test
    void suiteSupportsAbsoluteEntryPaths() throws Exception {
        Path caseFile = writeFile(tempDir.resolve("absolute"), "case.json",
                caseJson(url("/text"), 200, "hello world"));
        Path suite = writeFile(tempDir.resolve("absolute-suite"), "suite.json",
                suiteJson(caseFile.toAbsolutePath().toString()));

        Result result = run("--suite", suite.toString());

        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("SUMMARY total=1 pass=1 fail=0 error=0"), result.out());
    }

    @Test
    void suiteRejectsInvalidJson() throws Exception {
        Path suite = writeFile(tempDir.resolve("bad-suite"), "suite.json", "{\"cases\":[ ");

        Result result = run("--suite", suite.toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("invalid JSON in suite file"), result.err());
        assertFalse(result.err().contains("cases"), result.err());
    }

    @Test
    void suiteRejectsEmptyCases() throws Exception {
        Path suite = writeFile(tempDir.resolve("empty-suite"), "suite.json", "{\"cases\":[]}");

        Result result = run("--suite", suite.toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("field 'cases' must not be empty"), result.err());
    }

    @Test
    void suiteRejectsWrongCasesType() throws Exception {
        Path suite = writeFile(tempDir.resolve("wrong-type-suite"), "suite.json",
                "{\"cases\":\"a.json\"}");

        Result result = run("--suite", suite.toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("field 'cases' must be an array of strings"), result.err());
    }

    @Test
    void suiteRejectsUnknownField() throws Exception {
        Path suite = writeFile(tempDir.resolve("unknown-suite"), "suite.json",
                "{\"cases\":[\"a.json\"],\"stopOnFail\":true}");

        Result result = run("--suite", suite.toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("unknown field in suite file: stopOnFail"), result.err());
    }

    @Test
    void suiteTreatsMissingCaseFileAsError() throws Exception {
        Path suite = writeFile(tempDir.resolve("missing-case"), "suite.json",
                suiteJson("does-not-exist.json"));

        Result result = run("--suite", suite.toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("cannot read test case file"), result.err());
        assertTrue(result.out().contains("SUMMARY total=1 pass=0 fail=0 error=1"), result.out());
    }

    @Test
    void suiteRejectsOtherOptionsAndDuplicates() {
        Result combined = run("--suite", "suite.json", "--url", url("/ok"));
        Result duplicated = run("--suite", "a.json", "--suite", "b.json");

        assertEquals(2, combined.exitCode());
        assertTrue(combined.err().contains("--suite cannot be combined with other options"),
                combined.err());
        assertEquals(2, duplicated.exitCode());
        assertTrue(duplicated.err().contains("duplicate option: --suite"), duplicated.err());
    }

    @Test
    void utf8BomIsAcceptedForCaseAndSuiteFiles() throws Exception {
        Path dir = tempDir.resolve("bom");
        writeFile(dir, "case.json", "\uFEFF" + caseJson(url("/text"), 200, "hello world"));
        Path suite = writeFile(dir, "suite.json", "\uFEFF" + suiteJson("case.json"));

        Result single = run("--file", dir.resolve("case.json").toString());
        Result batch = run("--suite", suite.toString());

        assertEquals(0, single.exitCode(), single.err());
        assertTrue(single.out().contains("PASS"), single.out());
        assertEquals(0, batch.exitCode(), batch.err());
        assertTrue(batch.out().contains("SUMMARY total=1 pass=1 fail=0 error=0"), batch.out());
    }

    @Test
    void suiteProcessReturnsRealExitCodes() throws Exception {
        Path passDir = tempDir.resolve("proc-pass");
        writeFile(passDir, "a.json", caseJson(url("/text"), 200, "hello world"));
        Path passSuite = writeFile(passDir, "suite.json", suiteJson("a.json"));

        Path failDir = tempDir.resolve("proc-fail");
        writeFile(failDir, "a.json", caseJson(url("/text"), 201, "hello world"));
        Path failSuite = writeFile(failDir, "suite.json", suiteJson("a.json"));

        Path errorDir = tempDir.resolve("proc-error");
        writeFile(errorDir, "a.json", "{\"url\":\"$URL\",\"expectStatus\":\"200\"}"
                .replace("$URL", url("/ok")));
        Path errorSuite = writeFile(errorDir, "suite.json", suiteJson("a.json"));

        CliResult pass = runCli("--suite", passSuite.toString());
        CliResult fail = runCli("--suite", failSuite.toString());
        CliResult error = runCli("--suite", errorSuite.toString());

        assertEquals(0, pass.exitCode(), pass.output());
        assertTrue(pass.output().contains("SUMMARY total=1 pass=1 fail=0 error=0"), pass.output());
        assertEquals(1, fail.exitCode(), fail.output());
        assertTrue(fail.output().contains("SUMMARY total=1 pass=0 fail=1 error=0"), fail.output());
        assertEquals(2, error.exitCode(), error.output());
        assertTrue(error.output().contains("SUMMARY total=1 pass=0 fail=0 error=1"), error.output());
    }

    private Path writeFile(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static String caseJson(String url, int expectStatus, String expectContains) {
        return "{\"url\":\"" + url + "\",\"expectStatus\":" + expectStatus
                + ",\"expectContains\":\"" + expectContains + "\"}";
    }

    private static String suiteJson(String... entries) {
        StringBuilder json = new StringBuilder("{\"cases\":[");
        for (int i = 0; i < entries.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"')
                    .append(entries[i].replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return json.append("]}").toString();
    }

    private Path writeCase(String json) throws IOException {
        Path file = Files.createTempFile(tempDir, "case-", ".json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private static CliResult runCli(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(subprocessClasspath());
        command.add("dev.yik.apilab.Main");
        command.addAll(List.of(args));
        Path outputFile = Files.createTempFile("apilab-cli-", ".log");
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new AssertionError("CLI process did not finish within 10s and was terminated");
            }
            return new CliResult(process.exitValue(), Files.readString(outputFile, StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(outputFile);
        }
    }

    /**
     * Surefire publishes the full test classpath (including the Jackson runtime jars) in this
     * property, so a subprocess can load the same classes as the test JVM.
     */
    private static String subprocessClasspath() {
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) {
            classpath = Path.of("target", "classes").toAbsolutePath().toString();
        }
        return classpath;
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

    private record CliResult(int exitCode, String output) {
    }
}
