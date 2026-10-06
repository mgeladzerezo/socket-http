package io.github.mgeladzerezo.sockethttp.staticfiles;

import io.github.mgeladzerezo.sockethttp.http.HttpDates;
import io.github.mgeladzerezo.sockethttp.server.ConcurrencyModel;
import io.github.mgeladzerezo.sockethttp.support.RawClient;
import io.github.mgeladzerezo.sockethttp.support.RawResponse;
import io.github.mgeladzerezo.sockethttp.support.TestRoutes;
import io.github.mgeladzerezo.sockethttp.support.TestServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static file serving through raw sockets: representation metadata, conditional requests,
 * ranges, compression and directory handling.
 */
@Timeout(30)
class StaticFilesTest {

    private static final byte[] DATA = TestRoutes.pattern(100_000);
    private static final String SCRIPT = "export const answer = 42;\n".repeat(200);

    @TempDir
    static Path root;
    private static TestServer server;

    @BeforeAll
    static void startServer() throws IOException {
        Files.writeString(root.resolve("index.html"), "<h1>home</h1>");
        Files.writeString(root.resolve("app.js"), SCRIPT);
        Files.writeString(root.resolve("tiny.txt"), "tiny");
        Files.writeString(root.resolve("empty.txt"), "");
        Files.writeString(root.resolve("file with space.txt"), "spaced");
        Files.write(root.resolve("data.bin"), DATA);
        Files.write(root.resolve("logo.png"), TestRoutes.pattern(5_000));
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs").resolve("index.html"), "<h1>docs</h1>");
        Files.createDirectories(root.resolve("bare"));
        server = TestServer.start(ConcurrencyModel.VIRTUAL_THREADS, s -> s.get("/static/*", StaticFiles.serve(root)));
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    private static RawResponse get(String path, String... headers) throws IOException {
        return request("GET", path, headers);
    }

    private static RawResponse request(String method, String path, String... headers) throws IOException {
        try (RawClient client = server.connect()) {
            StringBuilder request = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: test\r\n");
            for (String header : headers) {
                request.append(header).append("\r\n");
            }
            return client.send(request.append("\r\n").toString()).readResponse(method.equals("HEAD"));
        }
    }

    // ------------------------------------------------------------ whole files

    @Test
    void servesAFileWithRepresentationMetadata() throws Exception {
        RawResponse response = get("/static/data.bin");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(DATA);
        assertThat(response.header("Content-Length")).isEqualTo("100000");
        assertThat(response.header("Content-Type")).isEqualTo("application/octet-stream");
        assertThat(response.header("Accept-Ranges")).isEqualTo("bytes");
        assertThat(response.header("Cache-Control")).isEqualTo("public, max-age=3600");
        assertThat(response.header("ETag")).matches("\"[0-9a-f]+-186a0\"");
        Instant lastModified = HttpDates.parse(response.header("Last-Modified"));
        assertThat(lastModified.getEpochSecond())
                .isEqualTo(Files.getLastModifiedTime(root.resolve("data.bin")).toInstant().getEpochSecond());
    }

    @Test
    void mapsExtensionsToMediaTypes() throws Exception {
        assertThat(get("/static/index.html").header("Content-Type")).isEqualTo("text/html; charset=utf-8");
        assertThat(get("/static/app.js").header("Content-Type")).isEqualTo("text/javascript; charset=utf-8");
        assertThat(get("/static/logo.png").header("Content-Type")).isEqualTo("image/png");
        assertThat(get("/static/tiny.txt").header("Content-Type")).isEqualTo("text/plain; charset=utf-8");
    }

    @Test
    void headSendsTheSameHeadersWithoutTheFile() throws Exception {
        RawResponse head = request("HEAD", "/static/data.bin");
        RawResponse get = get("/static/data.bin");

        assertThat(head.status()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        assertThat(head.header("Content-Length")).isEqualTo("100000");
        assertThat(head.header("ETag")).isEqualTo(get.header("ETag"));
        assertThat(head.header("Last-Modified")).isEqualTo(get.header("Last-Modified"));
    }

    @Test
    void servesEmptyFilesAndPercentEncodedNames() throws Exception {
        RawResponse empty = get("/static/empty.txt");
        assertThat(empty.status()).isEqualTo(200);
        assertThat(empty.header("Content-Length")).isEqualTo("0");

        assertThat(get("/static/file%20with%20space.txt").text()).isEqualTo("spaced");
        assertThat(get("/static/missing.txt").status()).isEqualTo(404);
        assertThat(request("POST", "/static/index.html", "Content-Length: 0").status()).isEqualTo(405);
    }

    @ParameterizedTest
    @EnumSource(ConcurrencyModel.class)
    void aMultiMegabyteFileArrivesIntactUnderEveryModel(ConcurrencyModel model) throws Exception {
        byte[] large = TestRoutes.pattern(5_000_000);
        Files.write(root.resolve("large-" + model + ".bin"), large);
        try (TestServer other = TestServer.start(model, s -> s.get("/files/*", StaticFiles.serve(root)));
             RawClient client = other.connect()) {
            // Twice on one connection: the transfer must leave the connection in a usable state.
            for (int i = 0; i < 2; i++) {
                RawResponse response = client.send("GET /files/large-" + model + ".bin HTTP/1.1\r\nHost: test\r\n\r\n").readResponse();
                assertThat(response.status()).isEqualTo(200);
                assertThat(Arrays.equals(response.body(), large)).as("content identical").isTrue();
            }
            RawResponse ranged = client.send("GET /files/large-" + model + ".bin HTTP/1.1\r\nHost: test\r\n"
                    + "Range: bytes=4000000-4999999\r\n\r\n").readResponse();
            assertThat(ranged.status()).isEqualTo(206);
            assertThat(Arrays.equals(ranged.body(), Arrays.copyOfRange(large, 4_000_000, 5_000_000))).isTrue();
        }
    }

    // ------------------------------------------------------------ directories

    @Test
    void servesTheIndexFileForADirectory() throws Exception {
        assertThat(get("/static/").text()).isEqualTo("<h1>home</h1>");
        assertThat(get("/static/docs/").text()).isEqualTo("<h1>docs</h1>");
    }

    @Test
    void redirectsADirectoryWithoutTrailingSlashKeepingTheQuery() throws Exception {
        RawResponse response = get("/static/docs?x=1");

        assertThat(response.status()).isEqualTo(301);
        assertThat(response.header("Location")).isEqualTo("/static/docs/?x=1");

        RawResponse mountPoint = get("/static");
        assertThat(mountPoint.status()).isEqualTo(301);
        assertThat(mountPoint.header("Location")).isEqualTo("/static/");
    }

    @Test
    void aDirectoryWithoutIndexAndAFileWithTrailingSlashAreNotFound() throws Exception {
        assertThat(get("/static/bare/").status()).isEqualTo(404);
        assertThat(get("/static/index.html/").status()).isEqualTo(404);
    }

    // ----------------------------------------------------------- conditionals

    @Test
    void ifNoneMatchWithTheCurrentTagGives304WithoutABody() throws Exception {
        String etag = get("/static/data.bin").header("ETag");

        RawResponse notModified = get("/static/data.bin", "If-None-Match: " + etag);
        assertThat(notModified.status()).isEqualTo(304);
        assertThat(notModified.body()).isEmpty();
        assertThat(notModified.header("ETag")).isEqualTo(etag);
        assertThat(notModified.header("Cache-Control")).isEqualTo("public, max-age=3600");
        assertThat(notModified.header("Content-Length")).isNull();

        assertThat(get("/static/data.bin", "If-None-Match: \"other\", " + etag).status()).isEqualTo(304);
        assertThat(get("/static/data.bin", "If-None-Match: W/" + etag).status()).as("weak comparison").isEqualTo(304);
        assertThat(get("/static/data.bin", "If-None-Match: *").status()).isEqualTo(304);
        assertThat(get("/static/data.bin", "If-None-Match: \"stale\"").status()).isEqualTo(200);
        assertThat(request("HEAD", "/static/data.bin", "If-None-Match: " + etag).status()).isEqualTo(304);
    }

    @Test
    void ifModifiedSinceComparesAtOneSecondResolution() throws Exception {
        String lastModified = get("/static/data.bin").header("Last-Modified");
        Instant instant = HttpDates.parse(lastModified);

        assertThat(get("/static/data.bin", "If-Modified-Since: " + lastModified).status()).isEqualTo(304);
        assertThat(get("/static/data.bin", "If-Modified-Since: " + HttpDates.format(instant.plusSeconds(60))).status()).isEqualTo(304);
        assertThat(get("/static/data.bin", "If-Modified-Since: " + HttpDates.format(instant.minusSeconds(60))).status()).isEqualTo(200);
        assertThat(get("/static/data.bin", "If-Modified-Since: not a date").status()).as("invalid dates are ignored").isEqualTo(200);
    }

    @Test
    void ifNoneMatchTakesPrecedenceOverIfModifiedSince() throws Exception {
        String lastModified = get("/static/data.bin").header("Last-Modified");

        RawResponse response = get("/static/data.bin", "If-None-Match: \"stale\"", "If-Modified-Since: " + lastModified);

        assertThat(response.status()).as("the tag says changed; the date must not be consulted").isEqualTo(200);
    }

    @Test
    void ifMatchAndIfUnmodifiedSinceGive412WhenTheyFail() throws Exception {
        RawResponse current = get("/static/data.bin");
        String etag = current.header("ETag");
        Instant lastModified = HttpDates.parse(current.header("Last-Modified"));

        assertThat(get("/static/data.bin", "If-Match: " + etag).status()).isEqualTo(200);
        assertThat(get("/static/data.bin", "If-Match: *").status()).isEqualTo(200);
        assertThat(get("/static/data.bin", "If-Match: \"stale\"").status()).isEqualTo(412);
        assertThat(get("/static/data.bin", "If-Match: W/" + etag).status()).as("strong comparison").isEqualTo(412);
        assertThat(get("/static/data.bin", "If-Unmodified-Since: " + HttpDates.format(lastModified)).status()).isEqualTo(200);
        assertThat(get("/static/data.bin", "If-Unmodified-Since: " + HttpDates.format(lastModified.minusSeconds(60))).status())
                .isEqualTo(412);
    }

    // ------------------------------------------------------------------ ranges

    @Test
    void servesASingleRange() throws Exception {
        RawResponse response = get("/static/data.bin", "Range: bytes=100-199");

        assertThat(response.status()).isEqualTo(206);
        assertThat(response.header("Content-Range")).isEqualTo("bytes 100-199/100000");
        assertThat(response.header("Content-Length")).isEqualTo("100");
        assertThat(response.body()).isEqualTo(Arrays.copyOfRange(DATA, 100, 200));
    }

    @Test
    void servesOpenEndedSuffixAndClampedRanges() throws Exception {
        RawResponse openEnded = get("/static/data.bin", "Range: bytes=99990-");
        assertThat(openEnded.header("Content-Range")).isEqualTo("bytes 99990-99999/100000");
        assertThat(openEnded.body()).isEqualTo(Arrays.copyOfRange(DATA, 99_990, 100_000));

        RawResponse suffix = get("/static/data.bin", "Range: bytes=-500");
        assertThat(suffix.header("Content-Range")).isEqualTo("bytes 99500-99999/100000");
        assertThat(suffix.body()).isEqualTo(Arrays.copyOfRange(DATA, 99_500, 100_000));

        RawResponse clamped = get("/static/data.bin", "Range: bytes=99000-999999999");
        assertThat(clamped.header("Content-Range")).isEqualTo("bytes 99000-99999/100000");
        assertThat(clamped.body()).hasSize(1000);

        RawResponse oversizedSuffix = get("/static/data.bin", "Range: bytes=-999999999");
        assertThat(oversizedSuffix.header("Content-Range")).isEqualTo("bytes 0-99999/100000");
    }

    @Test
    void anUnsatisfiableRangeGives416WithTheCurrentLength() throws Exception {
        RawResponse response = get("/static/data.bin", "Range: bytes=100000-");

        assertThat(response.status()).isEqualTo(416);
        assertThat(response.header("Content-Range")).isEqualTo("bytes */100000");
        assertThat(get("/static/data.bin", "Range: bytes=-0").status()).isEqualTo(416);
        assertThat(get("/static/empty.txt", "Range: bytes=0-").status()).isEqualTo(416);
    }

    @Test
    void aRangeHeaderThatIsNotAValidByteRangeIsIgnored() throws Exception {
        for (String header : new String[]{"Range: bytes=abc", "Range: bytes=5-2", "Range: items=0-5", "Range: bytes=",
                "Range: bytes=1-2-3", "Range: bytes=0-10,x"}) {
            RawResponse response = get("/static/data.bin", header);
            assertThat(response.status()).as(header).isEqualTo(200);
            assertThat(response.body()).hasSize(100_000);
        }
    }

    @Test
    void servesSeveralRangesAsMultipartByteranges() throws Exception {
        RawResponse response = get("/static/data.bin", "Range: bytes=0-9, 50000-50019, -5");

        assertThat(response.status()).isEqualTo(206);
        String contentType = response.header("Content-Type");
        assertThat(contentType).startsWith("multipart/byteranges; boundary=");
        String boundary = contentType.substring(contentType.indexOf('=') + 1);
        assertThat(response.header("Content-Length")).isEqualTo(Integer.toString(response.body().length));

        String body = new String(response.body(), StandardCharsets.ISO_8859_1);
        String[] parts = body.split("--" + boundary);
        // preamble (empty), three parts, and the closing "--".
        assertThat(parts).hasSize(5);
        assertThat(parts[0]).isEmpty();
        assertThat(parts[4]).isEqualTo("--\r\n");
        assertPart(parts[1], "bytes 0-9/100000", Arrays.copyOfRange(DATA, 0, 10));
        assertPart(parts[2], "bytes 50000-50019/100000", Arrays.copyOfRange(DATA, 50_000, 50_020));
        assertPart(parts[3], "bytes 99995-99999/100000", Arrays.copyOfRange(DATA, 99_995, 100_000));
    }

    private static void assertPart(String part, String contentRange, byte[] expected) {
        int headerEnd = part.indexOf("\r\n\r\n");
        String headers = part.substring(0, headerEnd);
        assertThat(headers).contains("Content-Range: " + contentRange).contains("Content-Type: application/octet-stream");
        String content = part.substring(headerEnd + 4, part.length() - 2);
        assertThat(content.getBytes(StandardCharsets.ISO_8859_1)).isEqualTo(expected);
    }

    @Test
    void overlappingRangesLargerThanTheFileAreServedAsTheWholeFile() throws Exception {
        RawResponse response = get("/static/data.bin", "Range: bytes=0-,0-,0-,0-");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).hasSize(100_000);
    }

    @Test
    void ifRangeAppliesTheRangeOnlyWhileTheValidatorMatches() throws Exception {
        RawResponse current = get("/static/data.bin");
        String etag = current.header("ETag");
        String lastModified = current.header("Last-Modified");

        assertThat(get("/static/data.bin", "Range: bytes=0-9", "If-Range: " + etag).status()).isEqualTo(206);
        assertThat(get("/static/data.bin", "Range: bytes=0-9", "If-Range: " + lastModified).status()).isEqualTo(206);

        RawResponse stale = get("/static/data.bin", "Range: bytes=0-9", "If-Range: \"stale\"");
        assertThat(stale.status()).as("the file changed: send all of the new one").isEqualTo(200);
        assertThat(stale.body()).hasSize(100_000);
        assertThat(get("/static/data.bin", "Range: bytes=0-9", "If-Range: Thu, 01 Jan 2015 00:00:00 GMT").status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------- gzip

    @Test
    void compressesTextForClientsThatAcceptGzip() throws Exception {
        RawResponse identity = get("/static/app.js");
        RawResponse compressed = get("/static/app.js", "Accept-Encoding: br, gzip");

        assertThat(compressed.status()).isEqualTo(200);
        assertThat(compressed.header("Content-Encoding")).isEqualTo("gzip");
        assertThat(compressed.header("Vary")).isEqualTo("Accept-Encoding");
        assertThat(compressed.body().length).isLessThan(SCRIPT.length() / 4);
        assertThat(compressed.header("Content-Length")).isEqualTo(Integer.toString(compressed.body().length));
        assertThat(new String(new GZIPInputStream(new ByteArrayInputStream(compressed.body())).readAllBytes(),
                StandardCharsets.UTF_8)).isEqualTo(SCRIPT);

        // The two variants must be distinguishable by a cache: different validators, same Vary.
        assertThat(identity.header("Content-Encoding")).isNull();
        assertThat(identity.header("Vary")).isEqualTo("Accept-Encoding");
        assertThat(identity.text()).isEqualTo(SCRIPT);
        assertThat(compressed.header("ETag")).isNotEqualTo(identity.header("ETag")).endsWith("-gz\"");

        assertThat(get("/static/app.js", "Accept-Encoding: gzip", "If-None-Match: " + compressed.header("ETag")).status())
                .isEqualTo(304);
    }

    @Test
    void doesNotCompressWhenItShouldNot() throws Exception {
        assertThat(get("/static/app.js", "Accept-Encoding: gzip;q=0").header("Content-Encoding")).as("q=0 forbids gzip").isNull();
        assertThat(get("/static/app.js", "Accept-Encoding: identity").header("Content-Encoding")).isNull();
        assertThat(get("/static/app.js", "Accept-Encoding: *;q=0.5").header("Content-Encoding")).isEqualTo("gzip");
        assertThat(get("/static/tiny.txt", "Accept-Encoding: gzip").header("Content-Encoding")).as("too small to be worth it").isNull();

        RawResponse image = get("/static/logo.png", "Accept-Encoding: gzip");
        assertThat(image.header("Content-Encoding")).as("already compressed format").isNull();
        assertThat(image.header("Vary")).isNull();

        // Byte ranges refer to the identity representation.
        RawResponse ranged = get("/static/app.js", "Accept-Encoding: gzip", "Range: bytes=0-9");
        assertThat(ranged.status()).isEqualTo(206);
        assertThat(ranged.header("Content-Encoding")).isNull();
        assertThat(ranged.text()).isEqualTo(SCRIPT.substring(0, 10));
    }
}
