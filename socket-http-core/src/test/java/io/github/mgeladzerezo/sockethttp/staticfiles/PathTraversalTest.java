package io.github.mgeladzerezo.sockethttp.staticfiles;

import io.github.mgeladzerezo.sockethttp.server.ConcurrencyModel;
import io.github.mgeladzerezo.sockethttp.support.RawClient;
import io.github.mgeladzerezo.sockethttp.support.RawResponse;
import io.github.mgeladzerezo.sockethttp.support.TestServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Attempts to read a file outside the document root, each sent as raw bytes because any real
 * HTTP client would normalise the path before it left the machine.
 *
 * <p>The layout is {@code base/secret.txt} next to {@code base/public/}, which is the root
 * mounted at {@code /static/*}. Every vector must fail with the stated status and must never
 * return the secret. The status tells which layer stopped it: 400 is the request parser
 * (illegal bytes in the target), 403 is {@link PathSanitizer}, 404 is the real-path check.
 */
@Timeout(30)
class PathTraversalTest {

    private static final String SECRET = "TOP SECRET CONTENT";

    @TempDir
    static Path base;
    private static TestServer server;

    @BeforeAll
    static void startServer() throws IOException {
        Path root = Files.createDirectories(base.resolve("public"));
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(base.resolve("secret.txt"), SECRET);
        Files.writeString(root.resolve("index.html"), "<h1>public</h1>");
        Files.writeString(root.resolve("sub").resolve("page.html"), "<h1>sub</h1>");
        Files.writeString(root.resolve(".env"), "PASSWORD=" + SECRET);
        Files.writeString(root.resolve("report.txt"), "quarterly report");
        server = TestServer.start(ConcurrencyModel.VIRTUAL_THREADS,
                s -> s.get("/static/*", StaticFiles.serve(root)));
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    static Stream<Arguments> vectors() {
        return Stream.of(
                // ---- dot-dot, raw and encoded
                arguments("raw ../", "/static/../secret.txt", 403),
                arguments("raw ../ from a subdirectory", "/static/sub/../../secret.txt", 403),
                arguments("encoded dots %2e%2e", "/static/%2e%2e/secret.txt", 403),
                arguments("upper-case encoding %2E%2E", "/static/%2E%2E/secret.txt", 403),
                arguments("half-encoded .%2e", "/static/.%2e/secret.txt", 403),
                arguments("half-encoded %2e.", "/static/%2e./secret.txt", 403),
                arguments("single dot segment", "/static/./index.html", 403),
                arguments("encoded slash ..%2f", "/static/..%2fsecret.txt", 403),
                arguments("encoded slash, upper case ..%2F", "/static/..%2Fsecret.txt", 403),
                arguments("fully encoded %2e%2e%2f", "/static/%2e%2e%2fsecret.txt", 403),
                arguments("nested encoded ../../", "/static/sub/..%2f..%2fsecret.txt", 403),
                arguments("dot-dot hidden behind a real directory", "/static/sub/%2e%2e/%2e%2e/secret.txt", 403),
                arguments("double-encoded %252e%252e is decoded once only", "/static/%252e%252e/secret.txt", 404),
                arguments("overlong UTF-8 dots %c0%ae", "/static/%c0%ae%c0%ae/secret.txt", 400),
                arguments("overlong UTF-8 slash %c0%af", "/static/..%c0%afsecret.txt", 400),

                // ---- Windows separators
                arguments("raw backslash", "/static/..\\secret.txt", 400),
                arguments("encoded backslash ..%5c", "/static/..%5csecret.txt", 403),
                arguments("encoded backslash, upper case ..%5C", "/static/..%5Csecret.txt", 403),
                arguments("encoded backslash inside a name", "/static/sub%5c..%5c..%5csecret.txt", 403),

                // ---- absolute paths
                arguments("empty segment (double slash)", "/static//etc/passwd", 403),
                arguments("encoded leading slash", "/static/%2Fetc%2Fpasswd", 403),
                arguments("drive letter", "/static/C:/Windows/win.ini", 403),
                arguments("encoded drive letter", "/static/C%3A%5CWindows%5Cwin.ini", 403),
                arguments("encoded colon c%3a", "/static/c%3a/windows/win.ini", 403),
                arguments("UNC path", "/static/%5C%5Clocalhost%5Cc$%5Cwindows%5Cwin.ini", 403),

                // ---- Windows device names and name aliases
                arguments("device CON", "/static/CON", 403),
                arguments("device nul, lower case", "/static/nul", 403),
                arguments("device with extension aux.txt", "/static/aux.txt", 403),
                arguments("device COM1", "/static/COM1", 403),
                arguments("device LPT1 with extension", "/static/LPT1.html", 403),
                arguments("device in a subdirectory", "/static/sub/prn.txt", 403),
                arguments("alternate data stream", "/static/index.html::$DATA", 403),
                arguments("trailing dot", "/static/index.html.", 403),
                arguments("trailing encoded space", "/static/index.html%20", 403),
                arguments("wildcard character", "/static/index%3F.html", 403),
                arguments("different case is a different name", "/static/INDEX.HTML", 404),
                arguments("NTFS short name", "/static/REPORT~1.TXT", 404),

                // ---- NUL and control characters
                arguments("encoded NUL truncation", "/static/index.html%00.png", 400),
                arguments("encoded newline in a name", "/static/index%0a.html", 403),

                // ---- hidden files
                arguments("dot-file", "/static/.env", 403),
                arguments("dot-directory", "/static/.git/config", 403)
        );
    }

    @ParameterizedTest(name = "{0}: {1} -> {2}")
    @MethodSource("vectors")
    void traversalAttemptIsRefused(String name, String target, int expectedStatus) throws Exception {
        try (RawClient client = server.connect()) {
            RawResponse response = client.send("GET " + target + " HTTP/1.1\r\nHost: test\r\n\r\n").readResponse();

            assertThat(response.status()).isEqualTo(expectedStatus);
            assertThat(response.text()).doesNotContain(SECRET);
        }
    }

    @Test
    void theSameFilesAreReachableByTheirProperNames() throws Exception {
        try (RawClient client = server.connect()) {
            assertThat(client.send("GET /static/index.html HTTP/1.1\r\nHost: test\r\n\r\n").readResponse().text())
                    .isEqualTo("<h1>public</h1>");
            assertThat(client.send("GET /static/sub/page.html HTTP/1.1\r\nHost: test\r\n\r\n").readResponse().text())
                    .isEqualTo("<h1>sub</h1>");
            assertThat(client.send("GET /static/report.txt HTTP/1.1\r\nHost: test\r\n\r\n").readResponse().text())
                    .isEqualTo("quarterly report");
        }
    }

    @Test
    void aSymbolicLinkPointingOutOfTheRootIsNotFollowed() throws Exception {
        Path link = base.resolve("public").resolve("escape.txt");
        try {
            Files.createSymbolicLink(link, base.resolve("secret.txt"));
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("this account may not create symbolic links: " + e);
        }
        try (RawClient client = server.connect()) {
            RawResponse response = client.send("GET /static/escape.txt HTTP/1.1\r\nHost: test\r\n\r\n").readResponse();

            assertThat(response.status()).isEqualTo(404);
            assertThat(response.text()).doesNotContain(SECRET);
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    void dotFilesCanBeEnabledExplicitly() throws Exception {
        try (TestServer permissive = TestServer.start(ConcurrencyModel.VIRTUAL_THREADS, s ->
                s.get("/static/*", StaticFiles.from(base.resolve("public")).allowDotFiles(true).build()));
             RawClient client = permissive.connect()) {
            assertThat(client.send("GET /static/.env HTTP/1.1\r\nHost: test\r\n\r\n").readResponse().status()).isEqualTo(200);
            // Allowing dot-files does not allow dot segments.
            assertThat(client.send("GET /static/../secret.txt HTTP/1.1\r\nHost: test\r\n\r\n").readResponse().status())
                    .isEqualTo(403);
        }
    }
}
