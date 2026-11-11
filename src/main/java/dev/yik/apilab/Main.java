package dev.yik.apilab;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class Main {

    private static final int EXIT_PASS = 0;
    private static final int EXIT_FAIL = 1;
    private static final int EXIT_ERROR = 2;

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
            HttpRequest request = HttpRequest.newBuilder(options.url())
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int actualStatus = response.statusCode();

            if (actualStatus == options.expectedStatus()) {
                out.printf("PASS expected=%d actual=%d%n", options.expectedStatus(), actualStatus);
                return EXIT_PASS;
            }
            out.printf("FAIL expected=%d actual=%d%n", options.expectedStatus(), actualStatus);
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

    private record Options(URI url, int expectedStatus) {

        static Options parse(String[] args) {
            URI url = null;
            Integer expectedStatus = null;

            for (int i = 0; i < args.length; i++) {
                String argument = args[i];
                if (argument.equals("--url")) {
                    if (url != null) {
                        throw new IllegalArgumentException("duplicate option: --url");
                    }
                    url = URI.create(valueOf(args, ++i, "--url"));
                } else if (argument.equals("--expect-status")) {
                    if (expectedStatus != null) {
                        throw new IllegalArgumentException("duplicate option: --expect-status");
                    }
                    expectedStatus = parseStatus(valueOf(args, ++i, "--expect-status"));
                } else {
                    throw new IllegalArgumentException("unknown argument: " + argument);
                }
            }

            if (url == null || expectedStatus == null) {
                throw new IllegalArgumentException(
                        "usage: --url <http-or-https-url> --expect-status <code>");
            }
            if (!url.isAbsolute() || url.getHost() == null
                    || !(url.getScheme().equalsIgnoreCase("http") || url.getScheme().equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("--url must be an absolute http or https URL: " + url);
            }
            return new Options(url, expectedStatus);
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
}
