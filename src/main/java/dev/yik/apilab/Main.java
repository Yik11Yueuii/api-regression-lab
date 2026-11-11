package dev.yik.apilab;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(execute(args, System.out, System.err));
    }

    static int execute(String[] args, PrintStream out, PrintStream err) {
        try {
            Options options = Options.parse(args);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
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

            boolean statusMatches = actualStatus == options.expectedStatus();
            boolean bodyMatches = options.expectedContains() == null
                    || body.contains(options.expectedContains());

            if (statusMatches && bodyMatches) {
                out.printf("PASS expected=%d actual=%d%n", options.expectedStatus(), actualStatus);
                return EXIT_PASS;
            }
            out.printf("FAIL expected=%d actual=%d%n", options.expectedStatus(), actualStatus);
            if (!bodyMatches) {
                out.printf("reason: response body does not contain expected text: %s%n",
                        options.expectedContains());
            }
            return EXIT_FAIL;
        } catch (IllegalArgumentException e) {
            err.println("ERROR " + e.getMessage());
            return EXIT_ERROR;
        } catch (IOException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            err.println("ERROR request failed: " + reason);
            return EXIT_ERROR;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("ERROR request interrupted");
            return EXIT_ERROR;
        }
    }

    private record Options(URI url, Method method, String bodyJson, int expectedStatus,
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
                                + " [--header \"Name: Value\"]... [--query \"name=value\"]...");
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
            if (!url.isAbsolute() || url.getHost() == null
                    || !(url.getScheme().equalsIgnoreCase("http") || url.getScheme().equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("--url must be an absolute http or https URL: " + url);
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

    private enum Method {
        GET, POST
    }

    private record Header(String name, String value) {
    }

    private record QueryParam(String name, String value) {
    }
}
