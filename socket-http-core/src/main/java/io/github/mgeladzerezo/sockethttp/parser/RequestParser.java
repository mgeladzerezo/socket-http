package io.github.mgeladzerezo.sockethttp.parser;

import io.github.mgeladzerezo.sockethttp.http.Headers;
import io.github.mgeladzerezo.sockethttp.http.HttpRequest;
import io.github.mgeladzerezo.sockethttp.http.HttpVersion;
import io.github.mgeladzerezo.sockethttp.http.RequestTarget;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * An incremental HTTP/1.1 request parser (RFC 9112) that works on bytes.
 *
 * <p>Bytes are pushed in with {@link #feed(ByteBuffer)} in whatever pieces the network
 * delivers. The parser keeps all of its state between calls, so the result is the same
 * whether a request arrives in one segment or one byte at a time; the test suite checks this
 * by splitting every sample request at every byte offset. {@code feed} consumes exactly the
 * bytes of one request and leaves the rest in the buffer, which is what makes pipelining work:
 * the caller handles the request, calls {@link #reset()}, and feeds the same buffer again.
 *
 * <h2>How it stays split-proof</h2>
 * Everything line-shaped (request line, header fields, chunk-size lines, trailers) is
 * accumulated into one line buffer until its terminating LF arrives and only then
 * interpreted, so no decision is ever taken on a partial line. Limits are checked as bytes
 * are appended, not when the line completes, so an endless line is refused after
 * {@code limit} bytes rather than buffered. Content is copied in bulk with a remaining-bytes
 * counter.
 *
 * <h2>Strictness</h2>
 * Where RFC 9112 lets a recipient choose between tolerating and refusing sloppy framing, this
 * parser refuses: lines must end in CRLF (§2.2 only <em>permits</em> accepting a bare LF),
 * obsolete line folding is an error (§5.2), whitespace before the colon is an error (§5.1),
 * and a message with both {@code Transfer-Encoding} and {@code Content-Length} is rejected
 * rather than repaired (§6.1). Each of these is a point where a lenient front-end and a strict
 * back-end, or the reverse, can disagree on where a message ends; that disagreement is request
 * smuggling. Every rejection carries the section number in {@link HttpParseException#reference()}.
 *
 * <p>Not thread-safe; one parser belongs to one connection.
 */
public final class RequestParser {

    /** Coarse progress of the current request, used by the server to pick a read deadline. */
    public enum Phase {
        /** No byte of the next request has arrived: the connection is idle. */
        IDLE,
        /** Inside the request line or the header section. */
        HEAD,
        /** Header section complete, content or trailers still arriving. */
        BODY,
        /** A whole request is available from {@link #request()}. */
        COMPLETE
    }

    private enum State {
        REQUEST_LINE, HEADERS, BODY_FIXED, CHUNK_SIZE, CHUNK_DATA, CHUNK_DATA_CR, CHUNK_DATA_LF, TRAILERS, COMPLETE
    }

    private static final byte CR = '\r';
    private static final byte LF = '\n';
    private static final byte SP = ' ';
    private static final byte HTAB = '\t';

    /** How many bytes of empty lines before a request line are tolerated (RFC 9112 §2.2). */
    private static final int MAX_LEADING_EMPTY_LINE_BYTES = 8;

    /** RFC 9110 §5.6.2 token characters. */
    private static final boolean[] TCHAR = new boolean[256];

    static {
        for (char c = 'a'; c <= 'z'; c++) {
            TCHAR[c] = true;
        }
        for (char c = 'A'; c <= 'Z'; c++) {
            TCHAR[c] = true;
        }
        for (char c = '0'; c <= '9'; c++) {
            TCHAR[c] = true;
        }
        for (char c : "!#$%&'*+-.^_`|~".toCharArray()) {
            TCHAR[c] = true;
        }
    }

    /**
     * Fields a sender must not put in a trailer section because they affect framing, routing,
     * authentication or content interpretation and would arrive too late to be applied safely
     * (RFC 9110 §6.5.1). They are dropped, not treated as an error.
     */
    private static final Set<String> FORBIDDEN_TRAILERS = Set.of(
            "transfer-encoding", "content-length", "host", "connection", "trailer", "te", "upgrade",
            "cache-control", "expect", "max-forwards", "pragma", "range",
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "content-encoding", "content-type", "content-range");

    private final ParserLimits limits;

    private State state;
    private byte[] line = new byte[256];
    private int lineLen;
    private int lineLimit;
    private int leadingEmptyBytes;

    private String method;
    private RequestTarget target;
    private HttpVersion version;
    private String versionText;
    private Headers headers;
    private int headerBytes;
    private boolean headComplete;
    private boolean expectContinue;

    private byte[] body;
    private int bodyLen;
    private long fixedRemaining;
    private long chunkRemaining;
    private Headers trailers;
    private int trailerBytes;

    private HttpRequest request;

    public RequestParser(ParserLimits limits) {
        this.limits = limits;
        reset();
    }

    public RequestParser() {
        this(ParserLimits.DEFAULT);
    }

    /** Forgets the current request and prepares to parse the next one on the same connection. */
    public void reset() {
        state = State.REQUEST_LINE;
        lineLen = 0;
        lineLimit = limits.maxRequestLineLength();
        leadingEmptyBytes = 0;
        method = null;
        target = null;
        version = null;
        versionText = null;
        headers = null;
        headerBytes = 0;
        headComplete = false;
        expectContinue = false;
        body = null;
        bodyLen = 0;
        fixedRemaining = 0;
        chunkRemaining = 0;
        trailers = null;
        trailerBytes = 0;
        request = null;
    }

    /**
     * Consumes bytes of the current request from {@code in}.
     *
     * @return {@code true} once a complete request is available; bytes after its end are left
     *         in {@code in}. {@code false} means all of {@code in} was consumed and more is needed
     * @throws HttpParseException if the request is malformed or exceeds a limit. The parser is
     *                            then unusable for this connection, which must be closed
     */
    public boolean feed(ByteBuffer in) throws HttpParseException {
        while (true) {
            switch (state) {
                case REQUEST_LINE -> {
                    if (lineLen == 0 && in.hasRemaining()) {
                        // Fail fast on bytes that cannot start a request (a TLS ClientHello sent
                        // to a plaintext port, for instance) instead of waiting for a line end.
                        int first = in.get(in.position()) & 0xFF;
                        if (first != CR && !TCHAR[first]) {
                            throw new HttpParseException(400, "request does not start with a method token", "RFC 9112 §3");
                        }
                    }
                    if (!readLine(in)) {
                        return false;
                    }
                    if (lineLen == 0) {
                        // "A server SHOULD ignore at least one empty line received prior to the
                        // request-line" (RFC 9112 §2.2). Bounded, so CRLF floods are refused.
                        leadingEmptyBytes += 2;
                        if (leadingEmptyBytes > MAX_LEADING_EMPTY_LINE_BYTES) {
                            throw new HttpParseException(400, "too many empty lines before the request line", "RFC 9112 §2.2");
                        }
                    } else {
                        parseRequestLine();
                        headers = new Headers();
                        state = State.HEADERS;
                        lineLimit = limits.maxHeaderSectionSize();
                    }
                    lineLen = 0;
                }
                case HEADERS -> {
                    if (!readLine(in)) {
                        return false;
                    }
                    if (lineLen == 0) {
                        finishHead();
                    } else {
                        if (headers.size() >= limits.maxHeaderCount()) {
                            throw new HttpParseException(431, "too many header fields", "RFC 6585 §5");
                        }
                        parseFieldLine(headers);
                        headerBytes += lineLen + 2;
                        lineLimit = limits.maxHeaderSectionSize() - headerBytes;
                    }
                    lineLen = 0;
                }
                case BODY_FIXED -> {
                    int n = (int) Math.min(fixedRemaining, in.remaining());
                    appendBody(in, n);
                    fixedRemaining -= n;
                    if (fixedRemaining > 0) {
                        return false;
                    }
                    complete();
                }
                case CHUNK_SIZE -> {
                    if (!readLine(in)) {
                        return false;
                    }
                    parseChunkSize();
                    lineLen = 0;
                }
                case CHUNK_DATA -> {
                    int n = (int) Math.min(chunkRemaining, in.remaining());
                    appendBody(in, n);
                    chunkRemaining -= n;
                    if (chunkRemaining > 0) {
                        return false;
                    }
                    state = State.CHUNK_DATA_CR;
                }
                case CHUNK_DATA_CR -> {
                    if (!in.hasRemaining()) {
                        return false;
                    }
                    if (in.get() != CR) {
                        throw new HttpParseException(400, "chunk data is not followed by CRLF", "RFC 9112 §7.1");
                    }
                    state = State.CHUNK_DATA_LF;
                }
                case CHUNK_DATA_LF -> {
                    if (!in.hasRemaining()) {
                        return false;
                    }
                    if (in.get() != LF) {
                        throw new HttpParseException(400, "chunk data is not followed by CRLF", "RFC 9112 §7.1");
                    }
                    state = State.CHUNK_SIZE;
                    lineLimit = limits.maxChunkLineLength();
                }
                case TRAILERS -> {
                    if (!readLine(in)) {
                        return false;
                    }
                    if (lineLen == 0) {
                        complete();
                    } else {
                        Headers field = new Headers();
                        parseFieldLine(field);
                        if (!FORBIDDEN_TRAILERS.contains(field.nameAt(0).toLowerCase(Locale.ROOT))) {
                            if (trailers == null) {
                                trailers = new Headers();
                            }
                            trailers.add(field.nameAt(0), field.valueAt(0));
                        }
                        trailerBytes += lineLen + 2;
                        lineLimit = limits.maxHeaderSectionSize() - trailerBytes;
                    }
                    lineLen = 0;
                }
                case COMPLETE -> {
                    return true;
                }
            }
        }
    }

    /** Where the parser is within the current request. */
    public Phase phase() {
        if (state == State.COMPLETE) {
            return Phase.COMPLETE;
        }
        if (headComplete) {
            return Phase.BODY;
        }
        // A lone CR or an ignored empty line does not count as the start of a request, so a
        // client that sends a stray CRLF after a body still times out as an idle connection.
        boolean started = state != State.REQUEST_LINE || lineLen > 1 || (lineLen == 1 && line[0] != CR);
        return started ? Phase.HEAD : Phase.IDLE;
    }

    public boolean isComplete() {
        return state == State.COMPLETE;
    }

    /**
     * Whether the client sent {@code Expect: 100-continue} and is still waiting to send content.
     * True only between the end of the header section and the end of the request, so the
     * server can send the interim response exactly once by checking it after each feed.
     */
    public boolean awaitingContinue() {
        return expectContinue && headComplete && state != State.COMPLETE && bodyLen == 0;
    }

    /** Marks the interim response as sent. */
    public void continueSent() {
        expectContinue = false;
    }

    /** The parsed request; only valid once {@link #feed} has returned {@code true}. */
    public HttpRequest request() {
        if (state != State.COMPLETE) {
            throw new IllegalStateException("request is not complete");
        }
        return request;
    }

    /**
     * The protocol version of the request being parsed, or {@code null} before the request
     * line is complete. Lets the server answer a malformed HTTP/1.0 request sensibly.
     */
    public HttpVersion versionSoFar() {
        return version;
    }

    // ------------------------------------------------------------------ lines

    /**
     * Appends bytes to the line buffer up to and including the next LF.
     *
     * @return {@code true} when a full line is in {@code line[0..lineLen)} with its CRLF removed
     */
    private boolean readLine(ByteBuffer in) throws HttpParseException {
        while (in.hasRemaining()) {
            byte b = in.get();
            if (b == LF) {
                if (lineLen == 0 || line[lineLen - 1] != CR) {
                    throw new HttpParseException(400, "line terminated by bare LF instead of CRLF", "RFC 9112 §2.2");
                }
                lineLen--;
                return true;
            }
            if (lineLen > 0 && line[lineLen - 1] == CR) {
                throw new HttpParseException(400, "bare CR inside a protocol element", "RFC 9112 §2.2");
            }
            // The buffer may hold lineLimit content bytes plus the CR that precedes the LF.
            if (lineLen > lineLimit || (lineLen == lineLimit && b != CR)) {
                throw lineTooLong();
            }
            if (lineLen == line.length) {
                line = Arrays.copyOf(line, line.length * 2);
            }
            line[lineLen++] = b;
        }
        return false;
    }

    private HttpParseException lineTooLong() {
        return switch (state) {
            case REQUEST_LINE -> {
                boolean inTarget = false;
                for (int i = 0; i < lineLen; i++) {
                    if (line[i] == SP) {
                        inTarget = true;
                        break;
                    }
                }
                yield inTarget
                        ? new HttpParseException(414, "request target is too long", "RFC 9112 §3")
                        : new HttpParseException(501, "method is too long to be one this server implements", "RFC 9112 §3");
            }
            case HEADERS -> new HttpParseException(431, "header section is too large", "RFC 6585 §5");
            case TRAILERS -> new HttpParseException(431, "trailer section is too large", "RFC 6585 §5");
            default -> new HttpParseException(400, "chunk-size line is too long", "RFC 9112 §7.1.1");
        };
    }

    // ----------------------------------------------------------- request line

    private void parseRequestLine() throws HttpParseException {
        int sp1 = indexOf(SP, 0);
        int sp2 = sp1 < 0 ? -1 : indexOf(SP, sp1 + 1);
        if (sp1 <= 0 || sp2 < 0 || sp2 == sp1 + 1 || sp2 == lineLen - 1 || indexOf(SP, sp2 + 1) >= 0) {
            // Exactly "method SP request-target SP HTTP-version". §3 allows lenient parsing on
            // whitespace boundaries; refusing is the option that cannot be misread.
            throw new HttpParseException(400, "malformed request line", "RFC 9112 §3");
        }
        for (int i = 0; i < sp1; i++) {
            if (!TCHAR[line[i] & 0xFF]) {
                throw new HttpParseException(400, "invalid character in method", "RFC 9110 §9.1");
            }
        }
        parseVersion(sp2 + 1);
        method = new String(line, 0, sp1, StandardCharsets.US_ASCII);
        for (int i = sp1 + 1; i < sp2; i++) {
            int c = line[i] & 0xFF;
            if (c <= 0x20 || c >= 0x7F) {
                throw new HttpParseException(400, "illegal character in request target", "RFC 9112 §3.2");
            }
        }
        target = RequestTargetParser.parse(new String(line, sp1 + 1, sp2 - sp1 - 1, StandardCharsets.ISO_8859_1), method);
    }

    private void parseVersion(int from) throws HttpParseException {
        // HTTP-version = "HTTP/" DIGIT "." DIGIT, case-sensitive (RFC 9112 §2.3).
        boolean wellFormed = lineLen - from == 8
                && line[from] == 'H' && line[from + 1] == 'T' && line[from + 2] == 'T' && line[from + 3] == 'P'
                && line[from + 4] == '/' && isDigit(line[from + 5]) && line[from + 6] == '.' && isDigit(line[from + 7]);
        if (!wellFormed) {
            throw new HttpParseException(400, "malformed HTTP version", "RFC 9112 §2.3");
        }
        int major = line[from + 5] - '0';
        int minor = line[from + 7] - '0';
        if (major != 1) {
            throw new HttpParseException(505, "only HTTP/1.x is supported", "RFC 9110 §15.6.6");
        }
        versionText = new String(line, from, 8, StandardCharsets.US_ASCII);
        version = minor == 0 ? HttpVersion.HTTP_1_0 : HttpVersion.HTTP_1_1;
    }

    // ---------------------------------------------------------------- headers

    /** Parses {@code line[0..lineLen)} as one field line and adds it to {@code into}. */
    private void parseFieldLine(Headers into) throws HttpParseException {
        if (line[0] == SP || line[0] == HTAB) {
            // Either an obs-fold continuation, or whitespace between the start line and the
            // first field. Both have been used to hide a header from one parser but not another.
            throw new HttpParseException(400, "obsolete line folding is not accepted", "RFC 9112 §5.2");
        }
        int colon = indexOf((byte) ':', 0);
        if (colon < 0) {
            throw new HttpParseException(400, "header line without a colon", "RFC 9112 §5");
        }
        if (colon == 0) {
            throw new HttpParseException(400, "empty header field name", "RFC 9112 §5");
        }
        for (int i = 0; i < colon; i++) {
            int c = line[i] & 0xFF;
            if (!TCHAR[c]) {
                if (c == SP || c == HTAB) {
                    throw new HttpParseException(400, "whitespace between header field name and colon", "RFC 9112 §5.1");
                }
                throw new HttpParseException(400, "invalid character in header field name", "RFC 9110 §5.1");
            }
        }
        int start = colon + 1;
        int end = lineLen;
        while (start < end && (line[start] == SP || line[start] == HTAB)) {
            start++;
        }
        while (end > start && (line[end - 1] == SP || line[end - 1] == HTAB)) {
            end--;
        }
        for (int i = start; i < end; i++) {
            int c = line[i] & 0xFF;
            if ((c < 0x20 && c != HTAB) || c == 0x7F) {
                // Includes NUL and bare CR, which RFC 9110 §5.5 calls out as dangerous.
                throw new HttpParseException(400, "control character in header field value", "RFC 9110 §5.5");
            }
        }
        into.add(new String(line, 0, colon, StandardCharsets.US_ASCII),
                new String(line, start, end - start, StandardCharsets.ISO_8859_1));
    }

    /**
     * Runs once the empty line after the header section arrives: validates the fields that
     * decide how the message is framed, then chooses the body state.
     */
    private void finishHead() throws HttpParseException {
        checkHost();
        checkExpect();

        List<String> transferEncodings = headers.all("Transfer-Encoding");
        List<String> contentLengths = headers.all("Content-Length");

        if (!transferEncodings.isEmpty()) {
            if (version == HttpVersion.HTTP_1_0) {
                throw new HttpParseException(400, "Transfer-Encoding in an HTTP/1.0 request", "RFC 9112 §6.1");
            }
            if (!contentLengths.isEmpty()) {
                // The classic CL.TE / TE.CL smuggling vector. §6.1 allows processing such a
                // message by ignoring Content-Length and then closing; rejecting outright means
                // no component downstream ever sees the ambiguous message.
                throw new HttpParseException(400, "both Transfer-Encoding and Content-Length present", "RFC 9112 §6.1");
            }
            checkTransferEncoding();
            headComplete = true;
            state = State.CHUNK_SIZE;
            lineLimit = limits.maxChunkLineLength();
            return;
        }

        long contentLength = contentLengths.isEmpty() ? 0 : parseContentLength(contentLengths);
        if (contentLength > limits.maxBodySize()) {
            throw new HttpParseException(413, "request content exceeds the configured limit", "RFC 9110 §15.5.14");
        }
        headComplete = true;
        if (contentLength == 0) {
            complete();
        } else {
            fixedRemaining = contentLength;
            state = State.BODY_FIXED;
        }
    }

    private void checkHost() throws HttpParseException {
        List<String> hosts = headers.all("Host");
        if (hosts.size() > 1) {
            throw new HttpParseException(400, "more than one Host header field", "RFC 9112 §3.2");
        }
        if (hosts.isEmpty()) {
            if (version == HttpVersion.HTTP_1_1) {
                throw new HttpParseException(400, "HTTP/1.1 request without a Host header field", "RFC 9112 §3.2");
            }
            return;
        }
        String host = hosts.getFirst();
        if (host.isEmpty()) {
            if (target.form() == RequestTarget.Form.ORIGIN) {
                // Empty Host is legal only when the target URI has no authority; this server
                // always has one, so an empty value on an origin-form request is invalid.
                throw new HttpParseException(400, "empty Host header field", "RFC 9112 §3.2");
            }
            return;
        }
        try {
            RequestTargetParser.checkAuthority(host);
        } catch (HttpParseException e) {
            throw new HttpParseException(400, "invalid Host header field: " + e.getMessage(), "RFC 9112 §3.2");
        }
    }

    private void checkExpect() throws HttpParseException {
        List<String> expectations = headers.tokens("Expect");
        if (expectations.isEmpty() || version == HttpVersion.HTTP_1_0) {
            // RFC 9110 §10.1.1: a server that receives 100-continue in an HTTP/1.0 request
            // must ignore that expectation.
            return;
        }
        for (String expectation : expectations) {
            if (!expectation.equals("100-continue")) {
                throw new HttpParseException(417, "unsupported expectation", "RFC 9110 §10.1.1");
            }
        }
        expectContinue = true;
    }

    private void checkTransferEncoding() throws HttpParseException {
        List<String> codings = headers.tokens("Transfer-Encoding");
        if (codings.isEmpty()) {
            throw new HttpParseException(400, "empty Transfer-Encoding", "RFC 9112 §6.1");
        }
        for (String coding : codings) {
            if (!coding.equals("chunked")) {
                // gzip, deflate, compress and "identity" included: only chunked is implemented.
                throw new HttpParseException(501, "unsupported transfer coding", "RFC 9112 §6.1");
            }
        }
        if (codings.size() > 1) {
            throw new HttpParseException(400, "chunked transfer coding applied more than once", "RFC 9112 §6.1");
        }
    }

    /**
     * Validates one or more Content-Length values. Several identical values, whether in
     * repeated fields or a comma list, are collapsed as RFC 9112 §6.3 allows; values that
     * differ make the length undecidable and are rejected.
     */
    private static long parseContentLength(List<String> fields) throws HttpParseException {
        long result = -1;
        for (String field : fields) {
            int start = 0;
            while (start <= field.length()) {
                int comma = field.indexOf(',', start);
                int end = comma < 0 ? field.length() : comma;
                String member = field.substring(start, end).trim();
                long value = parseDecimal(member);
                if (result >= 0 && result != value) {
                    throw new HttpParseException(400, "conflicting Content-Length values", "RFC 9112 §6.3");
                }
                result = value;
                if (comma < 0) {
                    break;
                }
                start = comma + 1;
            }
        }
        return result;
    }

    /** {@code 1*DIGIT} only: no sign, no hex, no inner whitespace, nothing {@code Long.parseLong} would forgive. */
    private static long parseDecimal(String s) throws HttpParseException {
        if (s.isEmpty() || s.length() > 18) {
            throw new HttpParseException(400, "invalid Content-Length", "RFC 9110 §8.6");
        }
        long value = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                throw new HttpParseException(400, "invalid Content-Length", "RFC 9110 §8.6");
            }
            value = value * 10 + (c - '0');
        }
        return value;
    }

    // ------------------------------------------------------------------- body

    private void parseChunkSize() throws HttpParseException {
        int i = 0;
        long size = 0;
        while (i < lineLen) {
            int digit = hexValue(line[i]);
            if (digit < 0) {
                break;
            }
            if (i == 15) {
                // 15 hex digits already exceed any body this server would accept; stopping
                // here keeps the accumulator from overflowing into a small positive number.
                throw new HttpParseException(400, "chunk size is too large", "RFC 9112 §7.1");
            }
            size = size << 4 | digit;
            i++;
        }
        if (i == 0) {
            throw new HttpParseException(400, "chunk does not start with a hexadecimal size", "RFC 9112 §7.1");
        }
        int j = i;
        while (j < lineLen && (line[j] == SP || line[j] == HTAB)) {
            j++;
        }
        if (j < lineLen) {
            if (line[j] != ';') {
                throw new HttpParseException(400, "invalid character after chunk size", "RFC 9112 §7.1");
            }
            // Chunk extensions are permitted and ignored (§7.1.1), but not allowed to carry
            // control characters that another parser might take for a line ending.
            for (int k = j + 1; k < lineLen; k++) {
                int c = line[k] & 0xFF;
                if ((c < 0x20 && c != HTAB) || c == 0x7F) {
                    throw new HttpParseException(400, "control character in chunk extension", "RFC 9112 §7.1.1");
                }
            }
        } else if (j != i) {
            throw new HttpParseException(400, "whitespace after chunk size", "RFC 9112 §7.1");
        }
        if (size == 0) {
            state = State.TRAILERS;
            trailerBytes = 0;
            lineLimit = limits.maxHeaderSectionSize();
        } else {
            if (bodyLen + size > limits.maxBodySize()) {
                throw new HttpParseException(413, "request content exceeds the configured limit", "RFC 9110 §15.5.14");
            }
            chunkRemaining = size;
            state = State.CHUNK_DATA;
        }
    }

    private void appendBody(ByteBuffer in, int n) {
        if (n == 0) {
            return;
        }
        if (body == null) {
            // Grow with the bytes that actually arrive rather than trusting Content-Length for
            // the allocation: a header is cheap to send, megabytes of heap are not.
            long expected = state == State.BODY_FIXED ? fixedRemaining : n;
            body = new byte[(int) Math.max(n, Math.min(expected, 64 * 1024))];
        } else if (bodyLen + n > body.length) {
            long needed = (long) bodyLen + n;
            long grown = Math.max(needed, Math.min(body.length * 2L, limits.maxBodySize()));
            body = Arrays.copyOf(body, (int) grown);
        }
        in.get(body, bodyLen, n);
        bodyLen += n;
    }

    private void complete() {
        byte[] content = body == null ? null : (bodyLen == body.length ? body : Arrays.copyOf(body, bodyLen));
        request = new HttpRequest(method, target, version, versionText, headers, content, trailers);
        headComplete = true;
        state = State.COMPLETE;
    }

    // ---------------------------------------------------------------- helpers

    private int indexOf(byte b, int from) {
        for (int i = from; i < lineLen; i++) {
            if (line[i] == b) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isDigit(byte b) {
        return b >= '0' && b <= '9';
    }

    private static int hexValue(byte b) {
        if (b >= '0' && b <= '9') {
            return b - '0';
        }
        if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        }
        return -1;
    }
}
