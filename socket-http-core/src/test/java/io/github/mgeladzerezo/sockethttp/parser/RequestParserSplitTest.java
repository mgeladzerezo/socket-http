package io.github.mgeladzerezo.sockethttp.parser;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static io.github.mgeladzerezo.sockethttp.parser.ParserTestSupport.bytes;
import static io.github.mgeladzerezo.sockethttp.parser.ParserTestSupport.parseStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The property the incremental parser exists for: however TCP segments a byte stream, the
 * requests parsed from it are identical.
 *
 * <p>Each sample stream is parsed once from a single buffer to get the reference result, then
 * again for every possible cut into two segments, every cut into three segments (for the
 * shorter samples), one byte at a time, and with random segmentations. The streams include
 * pipelined requests, so the cut also lands on the boundary between two messages and inside
 * the second one.
 */
class RequestParserSplitTest {

    private static final ParserLimits LIMITS = ParserLimits.DEFAULT;

    static Stream<Arguments> samples() {
        String get = "GET /users/42?verbose=1&tag=a%20b HTTP/1.1\r\nHost: example.org\r\nAccept: */*\r\n\r\n";
        String post = "POST /submit HTTP/1.1\r\nHost: example.org\r\nContent-Type: application/json\r\n"
                + "Content-Length: 17\r\n\r\n{\"name\":\"socket\"}";
        String chunked = "POST /upload HTTP/1.1\r\nHost: example.org\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "5\r\nhello\r\n6;ext=1\r\n world\r\n1a\r\nabcdefghijklmnopqrstuvwxyz\r\n"
                + "0\r\nX-Checksum: 9f86d081\r\nX-Other: yes\r\n\r\n";
        String http10 = "GET /legacy HTTP/1.0\r\nConnection: keep-alive\r\n\r\n";
        String absolute = "GET http://example.org:8080/a/b?x=1 HTTP/1.1\r\nHost: example.org:8080\r\n\r\n";
        String expect = "PUT /doc HTTP/1.1\r\nHost: a\r\nExpect: 100-continue\r\nContent-Length: 4\r\n\r\ndata";
        // Content that looks like protocol syntax must stay content wherever the cut falls.
        String bodyLooksLikeARequest = "POST /a HTTP/1.1\r\nHost: a\r\nContent-Length: 35\r\n\r\n"
                + "GET /smuggled HTTP/1.1\r\nHost: b\r\n\r\n";
        String chunkedBodyWithCrlf = "POST /b HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "7\r\n" + "\r\n0\r\n\r\n" + "\r\n"
                + "4\r\n" + "\r\n\r\n" + "\r\n"
                + "0\r\n\r\n";
        String options = "OPTIONS * HTTP/1.1\r\nHost: a\r\n\r\n";

        return Stream.of(
                arguments("GET", get, 1, true),
                arguments("POST with Content-Length", post, 1, true),
                arguments("chunked with extensions and trailers", chunked, 1, true),
                arguments("HTTP/1.0", http10, 1, true),
                arguments("absolute-form", absolute, 1, true),
                arguments("Expect: 100-continue", expect, 1, true),
                arguments("body that looks like a request", bodyLooksLikeARequest, 1, true),
                arguments("chunked body full of CRLFs", chunkedBodyWithCrlf, 1, true),
                arguments("leading empty line", "\r\n" + get, 1, true),
                arguments("pipelined GET, POST, GET", get + post + get, 3, false),
                arguments("pipelined chunked, GET, chunked", chunked + options + chunkedBodyWithCrlf, 3, false),
                arguments("six pipelined requests", get + http10 + post + chunked + absolute + expect, 6, false)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void everySplitPointGivesTheSameResult(String name, String raw, int expectedRequests, boolean alsoThreeSegments)
            throws Exception {
        byte[] data = bytes(raw);
        List<String> reference = parseStream(data, LIMITS);
        assertThat(reference).hasSize(expectedRequests);

        for (int cut = 0; cut <= data.length; cut++) {
            assertThat(parseStream(data, LIMITS, cut)).as("cut at byte %d", cut).isEqualTo(reference);
        }

        if (alsoThreeSegments) {
            for (int first = 0; first <= data.length; first++) {
                for (int second = first; second <= data.length; second++) {
                    assertThat(parseStream(data, LIMITS, first, second))
                            .as("cuts at bytes %d and %d", first, second).isEqualTo(reference);
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void oneByteAtATimeGivesTheSameResult(String name, String raw, int expectedRequests, boolean ignored) throws Exception {
        byte[] data = bytes(raw);
        int[] everyByte = new int[data.length - 1];
        for (int i = 0; i < everyByte.length; i++) {
            everyByte[i] = i + 1;
        }

        assertThat(parseStream(data, LIMITS, everyByte)).isEqualTo(parseStream(data, LIMITS));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void randomSegmentationsGiveTheSameResult(String name, String raw, int expectedRequests, boolean ignored) throws Exception {
        byte[] data = bytes(raw);
        List<String> reference = parseStream(data, LIMITS);
        Random random = new Random(20261006L + data.length);

        for (int round = 0; round < 500; round++) {
            int cuts = 1 + random.nextInt(12);
            int[] offsets = random.ints(cuts, 0, data.length + 1).sorted().toArray();
            assertThat(parseStream(data, LIMITS, offsets))
                    .as("segmentation %s", java.util.Arrays.toString(offsets)).isEqualTo(reference);
        }
    }
}
