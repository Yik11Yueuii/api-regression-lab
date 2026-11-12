package dev.yik.apilab;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class Main {

    private static final int EXIT_PASS = 0;
    private static final int EXIT_FAIL = 1;
    private static final int EXIT_ERROR = 2;

    /**
     * Header names rejected by the JDK HttpClient unless explicitly allowed through the
     * {@code jdk.httpclient.allowRestrictedHeaders} system property.
     */
    private static final Set<String> RESTRICTED_HEADERS =
            Set.of("connection", "content-length", "expect", "host", "upgrade");

    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<String> CASE_FIELDS = Set.of(
            "url", "method", "headers", "query", "bodyJson", "expectStatus", "expectContains");

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(execute(args, System.out, System.err));
    }

    static int execute(String[] args, PrintStream out, PrintStream err) {
        if (webRequested(args)) {
            try {
                return WebServer.run(args, out);
            } catch (IllegalArgumentException e) {
                err.println("ERROR " + printable(e.getMessage()));
                return EXIT_ERROR;
            }
        }
        try {
            SuiteCommand suite = suiteCommandFrom(args);
            if (suite != null) {
                return runSuite(suite, out, err);
            }
            return runCase(Options.load(args), newClient(), out, err).exitCode();
        } catch (IllegalArgumentException e) {
            err.println("ERROR " + printable(e.getMessage()));
            return EXIT_ERROR;
        }
    }

    /**
     * Detects the web mode flag before any CLI option is parsed so {@code --web} is never reported
     * as an unknown argument and can never be mixed silently with another mode.
     */
    private static boolean webRequested(String[] args) {
        for (String argument : args) {
            if (argument.equals("--web")) {
                return true;
            }
        }
        return false;
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Parses {@code --suite} and its optional {@code --report-json} companion. Returns null when
     * neither option is present (single case mode). Suite mode accepts no other options so a run
     * never mixes modes.
     */
    private static SuiteCommand suiteCommandFrom(String[] args) {
        boolean hasSuite = false;
        boolean hasReport = false;
        for (String argument : args) {
            if (argument.equals("--suite")) {
                hasSuite = true;
            } else if (argument.equals("--report-json")) {
                hasReport = true;
            }
        }
        if (!hasSuite && !hasReport) {
            return null;
        }
        if (!hasSuite) {
            throw new IllegalArgumentException("--report-json requires --suite");
        }
        String suitePath = null;
        String reportPath = null;
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (argument.equals("--suite")) {
                if (suitePath != null) {
                    throw new IllegalArgumentException("duplicate option: --suite");
                }
                suitePath = Options.valueOf(args, ++i, "--suite");
            } else if (argument.equals("--report-json")) {
                if (reportPath != null) {
                    throw new IllegalArgumentException("duplicate option: --report-json");
                }
                reportPath = Options.valueOf(args, ++i, "--report-json");
            } else {
                throw new IllegalArgumentException(
                        "--suite cannot be combined with other options: " + argument);
            }
        }
        return new SuiteCommand(Path.of(suitePath),
                reportPath == null ? null : Path.of(reportPath));
    }

    /**
     * Runs every case of a suite in declaration order, keeps going after FAIL or ERROR, and prints
     * one line per case plus the final summary. ERROR dominates FAIL in the exit code.
     */
    private static int runSuite(SuiteCommand command, PrintStream out, PrintStream err) {
        List<SuiteCase> cases = SuiteCase.load(command.suite());
        HttpClient client = newClient();
        List<CaseResult> results = new ArrayList<>();
        for (SuiteCase suiteCase : cases) {
            out.println("CASE " + suiteCase.displayName());
            CaseResult result;
            if (suiteCase.path() == null) {
                err.println("ERROR invalid case path");
                result = CaseResult.error(suiteCase.displayName(), null, "INVALID_CASE");
                results.add(result);
                continue;
            }
            try {
                result = runCase(Options.fromFile(suiteCase.path()), client, out, err)
                        .named(suiteCase.displayName());
            } catch (IllegalArgumentException e) {
                err.println("ERROR " + printable(e.getMessage()));
                result = CaseResult.error(suiteCase.displayName(), null, "INVALID_CASE");
            }
            results.add(result);
        }
        int pass = 0;
        int fail = 0;
        int error = 0;
        for (CaseResult result : results) {
            switch (result.status()) {
                case "PASS" -> pass++;
                case "FAIL" -> fail++;
                default -> error++;
            }
        }
        out.printf("SUMMARY total=%d pass=%d fail=%d error=%d%n",
                results.size(), pass, fail, error);
        if (command.report() != null) {
            writeReport(command.report(), results);
        }
        if (error > 0) {
            return EXIT_ERROR;
        }
        if (fail > 0) {
            return EXIT_FAIL;
        }
        return EXIT_PASS;
    }

    /**
     * Executes one already validated case for the local web UI. The same request and assertion
     * logic as the CLI is used; only the output streams are redirected.
     */
    static CaseResult runForWeb(Options options, HttpClient client, PrintStream sink) {
        return runCase(options, client, sink, sink);
    }

    private static CaseResult runCase(Options options, HttpClient client,
                                      PrintStream out, PrintStream err) {
        int expectedStatus = options.expectedStatus();
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(options.requestUri())
                    .timeout(Duration.ofSeconds(30));
            if (options.method() == Method.POST) {
                requestBuilder.POST(HttpRequest.BodyPublishers
                        .ofString(options.bodyJson(), StandardCharsets.UTF_8));
            } else {
                requestBuilder.GET();
            }
            for (Header header : options.effectiveHeaders()) {
                try {
                    requestBuilder.header(header.name(), header.value());
                } catch (IllegalArgumentException e) {
                    // The JDK exception message may embed the raw value, so it is never propagated.
                    throw new IllegalArgumentException(
                            "header value rejected by JDK HttpClient: " + header.name());
                }
            }
            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int actualStatus = response.statusCode();
            String body = response.body();

            boolean statusMatches = actualStatus == expectedStatus;
            boolean bodyMatches = options.expectedContains() == null
                    || body.contains(options.expectedContains());

            if (statusMatches && bodyMatches) {
                out.printf("PASS expected=%d actual=%d%n", expectedStatus, actualStatus);
                return new CaseResult(null, "PASS", expectedStatus, actualStatus, null);
            }
            out.printf("FAIL expected=%d actual=%d%n", expectedStatus, actualStatus);
            if (!bodyMatches) {
                out.printf("reason: response body does not contain expected text: %s%n",
                        printable(options.expectedContains()));
            }
            String reasonCode = statusMatches ? "BODY_MISMATCH" : "STATUS_MISMATCH";
            return new CaseResult(null, "FAIL", expectedStatus, actualStatus, reasonCode);
        } catch (IllegalArgumentException e) {
            err.println("ERROR " + printable(e.getMessage()));
            return CaseResult.error(null, expectedStatus, "INVALID_REQUEST");
        } catch (IOException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            err.println("ERROR request failed: " + printable(reason));
            return CaseResult.error(null, expectedStatus, "REQUEST_FAILED");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("ERROR request interrupted");
            return CaseResult.error(null, expectedStatus, "INTERRUPTED");
        }
    }

    /** Replaces control characters so a case name cannot break the console layout. */
    static String printable(String value) {
        StringBuilder sanitized = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            sanitized.append(c < 0x20 || c == 0x7F ? '?' : c);
        }
        return sanitized.toString();
    }

    /**
     * Writes the JSON report through a temporary file in the target directory, then moves it into
     * place, so a failed write never leaves a partially written report. The move is atomic where
     * the file system supports it and degrades to a plain replace move otherwise (the temporary
     * file is already complete at that point). Failure to write is reported as ERROR/2 by the
     * caller.
     */
    private static void writeReport(Path reportPath, List<CaseResult> results) {
        Path target = reportPath.toAbsolutePath();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("cannot write JSON report: " + target);
        }

        ArrayNode caseNodes = JSON.createArrayNode();
        int pass = 0;
        int fail = 0;
        int error = 0;
        for (CaseResult result : results) {
            switch (result.status()) {
                case "PASS" -> pass++;
                case "FAIL" -> fail++;
                default -> error++;
            }
            ObjectNode node = caseNodes.addObject();
            node.put("name", result.name());
            node.put("status", result.status());
            if (result.expectedStatus() == null) {
                node.putNull("expectedStatus");
            } else {
                node.put("expectedStatus", result.expectedStatus());
            }
            if (result.actualStatus() == null) {
                node.putNull("actualStatus");
            } else {
                node.put("actualStatus", result.actualStatus());
            }
            if (result.reasonCode() == null) {
                node.putNull("reasonCode");
            } else {
                node.put("reasonCode", result.reasonCode());
            }
        }

        ObjectNode root = JSON.createObjectNode();
        root.put("version", 1);
        root.put("generatedAt", Instant.now().toString());
        ObjectNode summary = root.putObject("summary");
        summary.put("total", results.size());
        summary.put("pass", pass);
        summary.put("fail", fail);
        summary.put("error", error);
        root.set("cases", caseNodes);

        Path temp = null;
        try {
            temp = Files.createTempFile(parent, "apilab-report-", ".tmp");
            Files.writeString(temp,
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root)
                            + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot write JSON report: " + target);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort cleanup of the temporary file
                }
            }
        }
    }

    /**
     * Reads a JSON object from a UTF-8 file and tolerates a leading UTF-8 BOM (written by some
     * Windows editors); no other encoding is guessed. Parser messages are never forwarded because
     * they quote the source text.
     */
    private static JsonNode readJsonObject(Path path, String what) {
        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read " + what + ": " + path);
        }
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
            content = content.substring(1);
        }
        JsonNode root;
        try {
            root = JSON.readTree(content);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("invalid JSON in " + what + ": " + path);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException(what + " must contain a JSON object: " + path);
        }
        return root;
    }

    private record SuiteCommand(Path suite, Path report) {
    }

    record CaseResult(String name, String status, Integer expectedStatus,
                      Integer actualStatus, String reasonCode) {

        static CaseResult error(String name, Integer expectedStatus, String reasonCode) {
            return new CaseResult(name, "ERROR", expectedStatus, null, reasonCode);
        }

        CaseResult named(String caseName) {
            return new CaseResult(caseName, status, expectedStatus, actualStatus, reasonCode);
        }

        int exitCode() {
            return switch (status) {
                case "PASS" -> EXIT_PASS;
                case "FAIL" -> EXIT_FAIL;
                default -> EXIT_ERROR;
            };
        }
    }

    private record SuiteCase(String displayName, Path path) {

        /**
         * Loads the suite manifest. Relative entries are resolved against the suite file directory,
         * not the process working directory, and each entry must be a non-empty string.
         */
        static List<SuiteCase> load(Path suiteFile) {
            JsonNode root = readJsonObject(suiteFile, "suite file");
            root.fieldNames().forEachRemaining(field -> {
                if (!field.equals("cases")) {
                    throw new IllegalArgumentException("unknown field in suite file: " + field);
                }
            });
            JsonNode casesNode = root.get("cases");
            if (casesNode == null) {
                throw new IllegalArgumentException("missing required field in suite file: cases");
            }
            if (!casesNode.isArray()) {
                throw new IllegalArgumentException("field 'cases' must be an array of strings");
            }
            if (casesNode.isEmpty()) {
                throw new IllegalArgumentException("field 'cases' must not be empty");
            }
            Path base = suiteFile.toAbsolutePath().getParent();
            List<SuiteCase> cases = new ArrayList<>();
            for (JsonNode item : casesNode) {
                if (!item.isTextual()) {
                    throw new IllegalArgumentException("field 'cases' must be an array of strings");
                }
                String entry = item.textValue();
                if (entry.isBlank()) {
                    throw new IllegalArgumentException("field 'cases' must not contain empty entries");
                }
                cases.add(new SuiteCase(displayName(entry), resolveCasePath(base, entry)));
            }
            return List.copyOf(cases);
        }

        /**
         * Builds the name shared by the console output and the JSON report. An absolute entry is
         * reduced to its file name so local directory layout never leaks into logs or reports;
         * control characters are replaced so one entry cannot break the output format. Reading the
         * case file still uses the original entry.
         */
        private static String displayName(String entry) {
            String name = entry;
            try {
                Path raw = Path.of(entry);
                if (raw.isAbsolute() && raw.getFileName() != null) {
                    name = raw.getFileName().toString();
                }
            } catch (InvalidPathException e) {
                int separator = Math.max(entry.lastIndexOf('/'), entry.lastIndexOf('\\'));
                if (separator >= 0) {
                    name = entry.substring(separator + 1);
                }
            }
            return printable(name);
        }

        /**
         * Returns null when the entry cannot be turned into a path (for example because it
         * contains control characters); the caller reports such an entry as a single ERROR case
         * instead of aborting the whole suite.
         */
        private static Path resolveCasePath(Path base, String entry) {
            try {
                return base.resolve(entry).normalize();
            } catch (InvalidPathException e) {
                return null;
            }
        }
    }

    record Options(URI url, Method method, String bodyJson, int expectedStatus,
                   String expectedContains, List<Header> headers, List<QueryParam> queries) {

        static Options parse(String[] args) {
            URI url = null;
            Method method = Method.GET;
            boolean methodProvided = false;
            String bodyJson = null;
            Integer expectedStatus = null;
            String expectedContains = null;
            List<Header> headers = new ArrayList<>();
            List<QueryParam> queries = new ArrayList<>();

            for (int i = 0; i < args.length; i++) {
                String argument = args[i];
                if (argument.equals("--url")) {
                    if (url != null) {
                        throw new IllegalArgumentException("duplicate option: --url");
                    }
                    url = URI.create(valueOf(args, ++i, "--url"));
                } else if (argument.equals("--method")) {
                    if (methodProvided) {
                        throw new IllegalArgumentException("duplicate option: --method");
                    }
                    methodProvided = true;
                    method = parseMethod(valueOf(args, ++i, "--method"));
                } else if (argument.equals("--body-json")) {
                    if (bodyJson != null) {
                        throw new IllegalArgumentException("duplicate option: --body-json");
                    }
                    bodyJson = valueOf(args, ++i, "--body-json");
                } else if (argument.equals("--expect-status")) {
                    if (expectedStatus != null) {
                        throw new IllegalArgumentException("duplicate option: --expect-status");
                    }
                    expectedStatus = parseStatus(valueOf(args, ++i, "--expect-status"));
                } else if (argument.equals("--expect-contains")) {
                    if (expectedContains != null) {
                        throw new IllegalArgumentException("duplicate option: --expect-contains");
                    }
                    String value = valueOf(args, ++i, "--expect-contains");
                    if (value.isEmpty()) {
                        throw new IllegalArgumentException("--expect-contains must not be empty");
                    }
                    expectedContains = value;
                } else if (argument.equals("--header")) {
                    headers.add(parseHeader(valueOf(args, ++i, "--header")));
                } else if (argument.equals("--query")) {
                    queries.add(parseQuery(valueOf(args, ++i, "--query")));
                } else {
                    throw new IllegalArgumentException("unknown argument: " + argument);
                }
            }

            if (url == null || expectedStatus == null) {
                throw new IllegalArgumentException(
                        "usage: --url <http-or-https-url> --expect-status <code>"
                                + " [--method GET|POST] [--body-json <json>]"
                                + " [--expect-contains <text>]"
                                + " [--header \"Name: Value\"]... [--query \"name=value\"]..."
                                + " | --file <test-case.json>"
                                + " | --suite <suite.json> [--report-json <report.json>]");
            }
            return of(url, method, bodyJson, expectedStatus, expectedContains, headers, queries);
        }

        /**
         * Chooses between CLI arguments and a JSON case file. The two modes are mutually
         * exclusive so that neither silently overrides the other.
         */
        static Options load(String[] args) {
            int fileOptionIndex = -1;
            int fileOptionCount = 0;
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("--file")) {
                    fileOptionIndex = i;
                    fileOptionCount++;
                }
            }
            if (fileOptionCount == 0) {
                return parse(args);
            }
            if (fileOptionCount > 1) {
                throw new IllegalArgumentException("duplicate option: --file");
            }
            if (args.length == 1) {
                throw new IllegalArgumentException("missing value for --file");
            }
            if (args.length != 2 || fileOptionIndex != 0) {
                throw new IllegalArgumentException("--file cannot be combined with other options");
            }
            return fromFile(Path.of(args[1]));
        }

        /**
         * Reads one test case from a JSON file. Field names, types and required fields are
         * validated explicitly; unknown fields are reported so a typo cannot silently disable part
         * of a test case. Parsing failures never echo the file content or the underlying parser
         * message, which could quote it.
         */
        static Options fromFile(Path path) {
            return fromJson(readJsonObject(path, "test case file"), "test case file");
        }

        /**
         * Validates one case object. Accepting already-parsed JSON lets the local web UI reuse the
         * exact same field rules as a test case file instead of duplicating request validation.
         */
        static Options fromJson(JsonNode root, String what) {
            root.fieldNames().forEachRemaining(field -> {
                if (!CASE_FIELDS.contains(field)) {
                    throw new IllegalArgumentException("unknown field in " + what + ": " + field);
                }
            });

            JsonNode urlNode = require(root, "url", what);
            if (!urlNode.isTextual()) {
                throw new IllegalArgumentException("field 'url' must be a string");
            }
            String rawUrl = urlNode.textValue();
            if (rawUrl.isBlank()) {
                throw new IllegalArgumentException("field 'url' must not be empty");
            }
            URI url;
            try {
                url = URI.create(rawUrl);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("field 'url' is not a valid URI");
            }

            Method method = Method.GET;
            JsonNode methodNode = root.get("method");
            if (methodNode != null) {
                if (!methodNode.isTextual()) {
                    throw new IllegalArgumentException("field 'method' must be a string");
                }
                method = parseMethod(methodNode.textValue());
            }

            List<Header> headers = new ArrayList<>();
            JsonNode headersNode = root.get("headers");
            if (headersNode != null) {
                if (!headersNode.isArray()) {
                    throw new IllegalArgumentException("field 'headers' must be an array of strings");
                }
                for (JsonNode item : headersNode) {
                    if (!item.isTextual()) {
                        throw new IllegalArgumentException("field 'headers' must be an array of strings");
                    }
                    headers.add(parseHeader(item.textValue()));
                }
            }

            List<QueryParam> queries = new ArrayList<>();
            JsonNode queryNode = root.get("query");
            if (queryNode != null) {
                if (!queryNode.isArray()) {
                    throw new IllegalArgumentException("field 'query' must be an array of strings");
                }
                for (JsonNode item : queryNode) {
                    if (!item.isTextual()) {
                        throw new IllegalArgumentException("field 'query' must be an array of strings");
                    }
                    queries.add(parseQuery(item.textValue()));
                }
            }

            String bodyJson = null;
            JsonNode bodyNode = root.get("bodyJson");
            if (bodyNode != null) {
                if (!bodyNode.isObject() && !bodyNode.isArray()) {
                    throw new IllegalArgumentException("field 'bodyJson' must be a JSON object or array");
                }
                try {
                    bodyJson = JSON.writeValueAsString(bodyNode);
                } catch (JacksonException e) {
                    throw new IllegalArgumentException("field 'bodyJson' could not be serialized");
                }
            }

            JsonNode statusNode = require(root, "expectStatus", what);
            if (!statusNode.isIntegralNumber() || !statusNode.canConvertToInt()) {
                throw new IllegalArgumentException("field 'expectStatus' must be an integer");
            }

            String expectedContains = null;
            JsonNode containsNode = root.get("expectContains");
            if (containsNode != null) {
                if (!containsNode.isTextual()) {
                    throw new IllegalArgumentException("field 'expectContains' must be a string");
                }
                expectedContains = containsNode.textValue();
                if (expectedContains.isEmpty()) {
                    throw new IllegalArgumentException("field 'expectContains' must not be empty");
                }
            }

            return of(url, method, bodyJson, statusNode.intValue(), expectedContains, headers, queries);
        }

        private static JsonNode require(JsonNode root, String field, String what) {
            JsonNode node = root.get(field);
            if (node == null) {
                throw new IllegalArgumentException("missing required field in " + what + ": " + field);
            }
            return node;
        }

        private static Options of(URI url, Method method, String bodyJson, int expectedStatus,
                                  String expectedContains, List<Header> headers,
                                  List<QueryParam> queries) {
            if (!url.isAbsolute() || url.getHost() == null
                    || !(url.getScheme().equalsIgnoreCase("http") || url.getScheme().equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("--url must be an absolute http or https URL: " + url);
            }
            if (expectedStatus < 100 || expectedStatus > 599) {
                throw new IllegalArgumentException(
                        "--expect-status must be between 100 and 599: " + expectedStatus);
            }
            if (method == Method.POST) {
                if (bodyJson == null) {
                    throw new IllegalArgumentException("POST requires --body-json");
                }
                if (bodyJson.isBlank()) {
                    throw new IllegalArgumentException("--body-json must not be empty for POST");
                }
                validateContentTypeHeader(headers);
            } else if (bodyJson != null) {
                throw new IllegalArgumentException("--body-json is only supported with --method POST");
            }
            return new Options(url, method, bodyJson, expectedStatus, expectedContains,
                    List.copyOf(headers), List.copyOf(queries));
        }

        /**
         * Returns the headers actually sent: for POST a default JSON content type is added unless
         * the caller supplied one explicitly, in which case the caller's value wins and no
         * duplicate is added.
         */
        List<Header> effectiveHeaders() {
            if (method != Method.POST || hasHeader("Content-Type")) {
                return headers;
            }
            List<Header> effective = new ArrayList<>(headers);
            effective.add(new Header("Content-Type", "application/json; charset=utf-8"));
            return effective;
        }

        private boolean hasHeader(String name) {
            for (Header header : headers) {
                if (header.name().equalsIgnoreCase(name)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * A POST request carries at most one explicit Content-Type header, otherwise the default
         * JSON content type and the user supplied one could conflict. Header names are compared
         * case-insensitively and values are never echoed back.
         */
        private static void validateContentTypeHeader(List<Header> headers) {
            int count = 0;
            String value = null;
            for (Header header : headers) {
                if (header.name().equalsIgnoreCase("Content-Type")) {
                    count++;
                    value = header.value();
                }
            }
            if (count > 1) {
                throw new IllegalArgumentException(
                        "duplicate Content-Type header, provide it at most once");
            }
            if (count == 1 && value.isEmpty()) {
                throw new IllegalArgumentException("Content-Type header must not be empty");
            }
        }

        private static Method parseMethod(String value) {
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            if (normalized.equals("GET")) {
                return Method.GET;
            }
            if (normalized.equals("POST")) {
                return Method.POST;
            }
            throw new IllegalArgumentException("--method must be GET or POST: " + value);
        }

        /**
         * Builds the request URI by appending encoded query parameters to the raw query already
         * present in {@link #url()}. Existing percent-encoding in the URL or in a supplied value
         * is kept as-is so that nothing is encoded twice.
         */
        URI requestUri() {
            if (queries.isEmpty()) {
                return url;
            }
            StringBuilder uri = new StringBuilder();
            uri.append(url.getScheme()).append("://").append(url.getRawAuthority());
            String path = url.getRawPath();
            if (path != null && !path.isEmpty()) {
                uri.append(path);
            }
            String existingQuery = url.getRawQuery();
            boolean hasExistingQuery = existingQuery != null && !existingQuery.isEmpty();
            if (hasExistingQuery) {
                uri.append('?').append(existingQuery);
            }
            uri.append(hasExistingQuery ? '&' : '?').append(joinQueries());
            String fragment = url.getRawFragment();
            if (fragment != null && !fragment.isEmpty()) {
                uri.append('#').append(fragment);
            }
            return URI.create(uri.toString());
        }

        private String joinQueries() {
            StringBuilder joined = new StringBuilder();
            for (QueryParam query : queries) {
                if (!joined.isEmpty()) {
                    joined.append('&');
                }
                joined.append(encode(query.name())).append('=').append(encode(query.value()));
            }
            return joined.toString();
        }

        private static Header parseHeader(String raw) {
            int colon = raw.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("invalid --header value, expected \"Name: Value\"");
            }
            String name = raw.substring(0, colon).trim();
            String value = raw.substring(colon + 1).trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("invalid --header value, header name must not be empty");
            }
            if (!HEADER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid --header value, illegal header name: " + name);
            }
            if (containsControlCharacters(value)) {
                throw new IllegalArgumentException(
                        "invalid --header value, header value must not contain control characters");
            }
            if (RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("header not allowed by JDK HttpClient: " + name
                        + " (restricted headers: connection, content-length, expect, host, upgrade)");
            }
            return new Header(name, value);
        }

        private static QueryParam parseQuery(String raw) {
            int equals = raw.indexOf('=');
            if (equals < 0) {
                throw new IllegalArgumentException("invalid --query value, expected \"name=value\"");
            }
            String name = raw.substring(0, equals).trim();
            String value = raw.substring(equals + 1);
            if (name.isEmpty()) {
                throw new IllegalArgumentException("invalid --query value, parameter name must not be empty");
            }
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException(
                        "invalid --query value, parameter value must not contain line breaks");
            }
            return new QueryParam(name, value);
        }

        private static String encode(String value) {
            StringBuilder encoded = new StringBuilder();
            for (int i = 0; i < value.length(); ) {
                if (value.charAt(i) == '%' && i + 2 < value.length()
                        && isHexDigit(value.charAt(i + 1)) && isHexDigit(value.charAt(i + 2))) {
                    encoded.append(value, i, i + 3);
                    i += 3;
                    continue;
                }
                int codePoint = value.codePointAt(i);
                if (codePoint < 0x80 && isUnreserved(codePoint)) {
                    encoded.append((char) codePoint);
                    i += 1;
                    continue;
                }
                for (byte b : new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8)) {
                    encoded.append('%')
                            .append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                            .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
                }
                i += Character.charCount(codePoint);
            }
            return encoded.toString();
        }

        private static boolean isHexDigit(char c) {
            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
        }

        private static boolean isUnreserved(int codePoint) {
            return (codePoint >= 'A' && codePoint <= 'Z')
                    || (codePoint >= 'a' && codePoint <= 'z')
                    || (codePoint >= '0' && codePoint <= '9')
                    || codePoint == '-' || codePoint == '.' || codePoint == '_' || codePoint == '~';
        }

        private static boolean containsControlCharacters(String value) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c < 0x20 || c == 0x7F) {
                    return true;
                }
            }
            return false;
        }

        private static String valueOf(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("missing value for " + option);
            }
            return args[index];
        }

        private static int parseStatus(String value) {
            int status;
            try {
                status = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--expect-status must be an integer: " + value);
            }
            if (status < 100 || status > 599) {
                throw new IllegalArgumentException("--expect-status must be between 100 and 599: " + value);
            }
            return status;
        }
    }

    enum Method {
        GET, POST
    }

    record Header(String name, String value) {
    }

    record QueryParam(String name, String value) {
    }
}
