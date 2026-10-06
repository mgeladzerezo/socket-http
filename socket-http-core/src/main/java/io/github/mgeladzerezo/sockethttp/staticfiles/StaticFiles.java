package io.github.mgeladzerezo.sockethttp.staticfiles;

import io.github.mgeladzerezo.sockethttp.http.HttpDates;
import io.github.mgeladzerezo.sockethttp.http.HttpException;
import io.github.mgeladzerezo.sockethttp.http.HttpRequest;
import io.github.mgeladzerezo.sockethttp.http.HttpStatus;
import io.github.mgeladzerezo.sockethttp.http.Response;
import io.github.mgeladzerezo.sockethttp.http.ResponseBody;
import io.github.mgeladzerezo.sockethttp.routing.Context;
import io.github.mgeladzerezo.sockethttp.routing.Handler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.GZIPOutputStream;

/**
 * Serves files from a directory. Mount it on a wildcard route:
 *
 * <pre>{@code
 * server.get("/assets/*", StaticFiles.from(Path.of("public")).cacheControl("public, max-age=86400").build());
 * }</pre>
 *
 * <h2>What it implements</h2>
 * <ul>
 *   <li>Validators: a strong {@code ETag} derived from size and modification time, and
 *       {@code Last-Modified}; conditional requests evaluated in the order of RFC 9110 §13.2.2
 *       ({@code If-Match}, {@code If-Unmodified-Since}, {@code If-None-Match},
 *       {@code If-Modified-Since}) giving 304 or 412.</li>
 *   <li>Range requests (§14): single ranges as 206 with {@code Content-Range}, several ranges
 *       as {@code multipart/byteranges}, 416 when nothing is satisfiable, and {@code If-Range}.</li>
 *   <li>Zero-copy: file content goes to the socket with {@code FileChannel.transferTo}.</li>
 *   <li>gzip for compressible types when the client accepts it, with {@code Vary:
 *       Accept-Encoding} on both variants and a distinct ETag for the compressed one, so a
 *       cache never mixes them up. Compressed bytes are kept in a small LRU cache.</li>
 *   <li>Directories: {@code /dir} redirects to {@code /dir/}, which serves the index file.</li>
 * </ul>
 *
 * <h2>Path traversal</h2>
 * Two independent checks, either of which is sufficient for the classic attacks:
 * <ol>
 *   <li>{@link PathSanitizer} refuses (403) any decoded segment that is not a plain file
 *       name: {@code ..}, embedded separators, colons, control characters, Windows device
 *       names, trailing dots and spaces, and by default dot-files.</li>
 *   <li>The resolved file's <em>real path</em> must be textually equal to the real document
 *       root followed by exactly the requested segments. A symbolic link, an NTFS short name
 *       or a differently-cased alias has a different real path and is answered with 404. As
 *       a consequence symbolic links are never followed, even to targets inside the root.</li>
 * </ol>
 */
public final class StaticFiles implements Handler {

    private static final int MULTIPART_OVERHEAD_HINT = 128;

    private final Path root;
    private final String rootPrefix;
    private final String indexFile;
    private final String cacheControl;
    private final boolean gzip;
    private final long gzipMinSize;
    private final long gzipMaxSize;
    private final boolean allowDotFiles;
    private final GzipCache gzipCache;

    private StaticFiles(Builder builder) {
        try {
            this.root = builder.root.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("static file root does not exist: " + builder.root, e);
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("static file root is not a directory: " + root);
        }
        this.rootPrefix = root.toString();
        this.indexFile = builder.indexFile;
        this.cacheControl = builder.cacheControl;
        this.gzip = builder.gzip;
        this.gzipMinSize = builder.gzipMinSize;
        this.gzipMaxSize = builder.gzipMaxSize;
        this.allowDotFiles = builder.allowDotFiles;
        this.gzipCache = new GzipCache(builder.gzipCacheEntries);
    }

    /** Starts configuring a handler that serves the directory {@code root}. */
    public static Builder from(Path root) {
        return new Builder(root);
    }

    /** A handler with default settings for the directory {@code root}. */
    public static StaticFiles serve(Path root) {
        return new Builder(root).build();
    }

    /** Configuration for {@link StaticFiles}. */
    public static final class Builder {
        private final Path root;
        private String indexFile = "index.html";
        private String cacheControl = "public, max-age=3600";
        private boolean gzip = true;
        private long gzipMinSize = 1024;
        private long gzipMaxSize = 2 * 1024 * 1024;
        private int gzipCacheEntries = 128;
        private boolean allowDotFiles;

        private Builder(Path root) {
            this.root = Objects.requireNonNull(root, "root");
        }

        /** File served for a directory request; default {@code index.html}. */
        public Builder indexFile(String name) {
            this.indexFile = Objects.requireNonNull(name);
            return this;
        }

        /** Value of {@code Cache-Control} on successful responses; {@code null} to send none. */
        public Builder cacheControl(String value) {
            this.cacheControl = value;
            return this;
        }

        /** Whether to gzip compressible types for clients that accept it; default on. */
        public Builder gzip(boolean enabled) {
            this.gzip = enabled;
            return this;
        }

        /** Files outside this size range are never compressed; default 1 KiB to 2 MiB. */
        public Builder gzipSizeRange(long minBytes, long maxBytes) {
            this.gzipMinSize = minBytes;
            this.gzipMaxSize = maxBytes;
            return this;
        }

        /** Whether names starting with a dot may be served; default no. */
        public Builder allowDotFiles(boolean allow) {
            this.allowDotFiles = allow;
            return this;
        }

        public StaticFiles build() {
            return new StaticFiles(this);
        }
    }

    @Override
    public void handle(Context ctx) throws IOException {
        HttpRequest request = ctx.request();
        if (!request.method().equals("GET") && !request.method().equals("HEAD")) {
            throw new HttpException(HttpStatus.METHOD_NOT_ALLOWED, "static files are read-only").header("Allow", "GET, HEAD");
        }
        List<String> segments = ctx.wildcardSegments();
        // A trailing slash shows up as a final empty segment. Without one, a directory (the
        // mount point itself included) is redirected so relative links in its index resolve.
        boolean directoryRequest = !segments.isEmpty() && segments.getLast().isEmpty();
        List<String> names = directoryRequest ? segments.subList(0, segments.size() - 1) : segments;

        String problem = PathSanitizer.rejection(names, allowDotFiles);
        if (problem != null) {
            throw new HttpException(HttpStatus.FORBIDDEN, "path refused: " + problem);
        }
        Path file = resolveExactly(names);
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!directoryRequest) {
                String query = request.target().rawQuery();
                ctx.redirect(HttpStatus.MOVED_PERMANENTLY, request.target().rawPath() + "/" + (query == null ? "" : "?" + query));
                return;
            }
            file = resolveExactly(concat(names, indexFile));
        } else if (directoryRequest) {
            throw HttpException.notFound("not a directory");
        }
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw HttpException.notFound("not a regular file");
        }
        serve(ctx, file, attributes);
    }

    /**
     * Maps segments to a file and insists that the file's real path is literally the root
     * plus those segments. 404 if the file does not exist or is reachable only through an alias.
     */
    private Path resolveExactly(List<String> names) {
        String separator = root.getFileSystem().getSeparator();
        StringBuilder expected = new StringBuilder(rootPrefix);
        for (String name : names) {
            // A root such as "/" or "D:\" already ends with the separator.
            if (expected.length() > rootPrefix.length() || !rootPrefix.endsWith(separator)) {
                expected.append(separator);
            }
            expected.append(name);
        }
        Path real;
        try {
            Path candidate = root;
            for (String name : names) {
                candidate = candidate.resolve(name);
            }
            real = candidate.toRealPath();
        } catch (IOException | InvalidPathException e) {
            throw HttpException.notFound("no such file");
        }
        if (!real.toString().equals(expected.toString())) {
            throw HttpException.notFound("no such file");
        }
        return real;
    }

    private static List<String> concat(List<String> names, String last) {
        List<String> result = new ArrayList<>(names);
        result.add(last);
        return result;
    }

    // ---------------------------------------------------------------- serving

    private void serve(Context ctx, Path file, BasicFileAttributes attributes) throws IOException {
        HttpRequest request = ctx.request();
        Response response = ctx.response();
        long size = attributes.size();
        // HTTP dates have one-second resolution; comparing at finer resolution would make
        // If-Modified-Since with our own Last-Modified value fail.
        Instant lastModified = Instant.ofEpochSecond(attributes.lastModifiedTime().toInstant().getEpochSecond());
        String mediaType = MimeTypes.forFileName(file.getFileName().toString());
        boolean compressible = gzip && MimeTypes.isCompressible(mediaType);
        String identityTag = "\"" + Long.toHexString(attributes.lastModifiedTime().toMillis()) + "-" + Long.toHexString(size) + "\"";
        String rangeHeader = request.method().equals("GET") ? request.headers().get("Range") : null;

        byte[] compressed = null;
        if (compressible && rangeHeader == null && size >= gzipMinSize && size <= gzipMaxSize
                && acceptsGzip(request.headers().get("Accept-Encoding"))) {
            compressed = gzipCache.get(file, attributes);
        }
        String etag = compressed == null ? identityTag : identityTag.substring(0, identityTag.length() - 1) + "-gz\"";

        response.header("ETag", etag)
                .header("Last-Modified", HttpDates.format(lastModified))
                .header("Accept-Ranges", "bytes");
        if (cacheControl != null) {
            response.header("Cache-Control", cacheControl);
        }
        if (compressible) {
            // On every variant, compressed or not: a cache must key on Accept-Encoding even
            // when it happens to store the identity form first.
            response.header("Vary", "Accept-Encoding");
        }

        int precondition = evaluatePreconditions(request, etag, lastModified);
        if (precondition == HttpStatus.NOT_MODIFIED) {
            response.status(HttpStatus.NOT_MODIFIED).body(ResponseBody.EMPTY);
            return;
        }
        if (precondition == HttpStatus.PRECONDITION_FAILED) {
            throw new HttpException(HttpStatus.PRECONDITION_FAILED, "the representation has changed");
        }

        if (compressed != null) {
            response.header("Content-Encoding", "gzip").body(compressed, mediaType);
            return;
        }
        response.header("Content-Type", mediaType);

        if (rangeHeader != null && ifRangeAllows(request.headers().get("If-Range"), etag, lastModified)) {
            ByteRanges.Result result = ByteRanges.parse(rangeHeader, size);
            switch (result.kind()) {
                case UNSATISFIABLE -> {
                    response.headers().remove("Content-Type");
                    throw new HttpException(HttpStatus.RANGE_NOT_SATISFIABLE, "no requested range overlaps the " + size + " bytes available")
                            .header("Content-Range", "bytes */" + size);
                }
                case SATISFIABLE -> {
                    response.status(HttpStatus.PARTIAL_CONTENT);
                    if (result.ranges().size() == 1) {
                        ByteRanges.Range range = result.ranges().getFirst();
                        response.header("Content-Range", "bytes " + range.first() + "-" + range.last() + "/" + size);
                        response.body(fileBody(file, range.first(), range.length()));
                    } else {
                        multipart(response, file, mediaType, size, result.ranges());
                    }
                    return;
                }
                case IGNORED -> {
                    // fall through to the complete representation
                }
            }
        }
        response.body(fileBody(file, 0, size));
    }

    private static ResponseBody fileBody(Path file, long position, long length) {
        return new ResponseBody.Stream(length, sink -> {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                sink.transferFrom(channel, position, length);
            }
        });
    }

    /** Several ranges in one {@code multipart/byteranges} body (RFC 9110 §14.6). */
    private static void multipart(Response response, Path file, String mediaType, long size, List<ByteRanges.Range> ranges) {
        String boundary = "sockethttp-" + Long.toHexString(ThreadLocalRandom.current().nextLong());
        byte[][] partHeads = new byte[ranges.size()][];
        long total = 0;
        for (int i = 0; i < ranges.size(); i++) {
            ByteRanges.Range range = ranges.get(i);
            StringBuilder head = new StringBuilder(MULTIPART_OVERHEAD_HINT);
            head.append(i == 0 ? "" : "\r\n").append("--").append(boundary).append("\r\n")
                    .append("Content-Type: ").append(mediaType).append("\r\n")
                    .append("Content-Range: bytes ").append(range.first()).append('-').append(range.last())
                    .append('/').append(size).append("\r\n\r\n");
            partHeads[i] = head.toString().getBytes(StandardCharsets.ISO_8859_1);
            total += partHeads[i].length + range.length();
        }
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
        total += tail.length;

        response.header("Content-Type", "multipart/byteranges; boundary=" + boundary);
        response.body(new ResponseBody.Stream(total, sink -> {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                for (int i = 0; i < ranges.size(); i++) {
                    sink.write(partHeads[i]);
                    sink.transferFrom(channel, ranges.get(i).first(), ranges.get(i).length());
                }
                sink.write(tail);
            }
        }));
    }

    // ----------------------------------------------------------- conditionals

    /**
     * Evaluates the precondition headers in the order RFC 9110 §13.2.2 prescribes.
     *
     * @return 304, 412, or 0 to proceed with the request
     */
    static int evaluatePreconditions(HttpRequest request, String etag, Instant lastModified) {
        String ifMatch = joined(request, "If-Match");
        if (ifMatch != null) {
            if (!ifMatch.trim().equals("*") && !matchesAny(ifMatch, etag, true)) {
                return HttpStatus.PRECONDITION_FAILED;
            }
        } else {
            Instant unmodifiedSince = HttpDates.parse(request.headers().get("If-Unmodified-Since"));
            if (unmodifiedSince != null && lastModified.isAfter(unmodifiedSince)) {
                return HttpStatus.PRECONDITION_FAILED;
            }
        }
        String ifNoneMatch = joined(request, "If-None-Match");
        if (ifNoneMatch != null) {
            // If-None-Match takes precedence: when present, If-Modified-Since is not evaluated.
            if (ifNoneMatch.trim().equals("*") || matchesAny(ifNoneMatch, etag, false)) {
                return HttpStatus.NOT_MODIFIED;
            }
        } else {
            Instant modifiedSince = HttpDates.parse(request.headers().get("If-Modified-Since"));
            if (modifiedSince != null && !lastModified.isAfter(modifiedSince)) {
                return HttpStatus.NOT_MODIFIED;
            }
        }
        return 0;
    }

    /**
     * {@code If-Range} (§13.1.5): the range applies only if the validator still matches;
     * otherwise the client gets the whole, current representation. An entity tag must match
     * strongly; a date must equal {@code Last-Modified} exactly.
     */
    private static boolean ifRangeAllows(String ifRange, String etag, Instant lastModified) {
        if (ifRange == null) {
            return true;
        }
        String value = ifRange.trim();
        if (value.startsWith("\"") || value.startsWith("W/")) {
            return value.equals(etag);
        }
        Instant date = HttpDates.parse(value);
        return date != null && date.equals(lastModified);
    }

    private static String joined(HttpRequest request, String name) {
        List<String> values = request.headers().all(name);
        return values.isEmpty() ? null : String.join(",", values);
    }

    /**
     * Whether {@code etag} is among the entity tags listed in a header value.
     *
     * @param strong strong comparison (§8.8.3.2): weak tags never match. The weak comparison
     *               used by If-None-Match ignores the {@code W/} prefix on either side.
     */
    static boolean matchesAny(String headerValue, String etag, boolean strong) {
        int i = 0;
        int n = headerValue.length();
        while (i < n) {
            char c = headerValue.charAt(i);
            if (c == ' ' || c == '\t' || c == ',') {
                i++;
                continue;
            }
            boolean weak = headerValue.startsWith("W/", i);
            int open = weak ? i + 2 : i;
            if (open >= n || headerValue.charAt(open) != '"') {
                return false;
            }
            int close = headerValue.indexOf('"', open + 1);
            if (close < 0) {
                return false;
            }
            String candidate = headerValue.substring(open, close + 1);
            if (candidate.equals(etag) && !(strong && weak)) {
                return true;
            }
            i = close + 1;
        }
        return false;
    }

    /** Whether an {@code Accept-Encoding} value permits gzip (RFC 9110 §12.5.3), honouring {@code q=0}. */
    static boolean acceptsGzip(String acceptEncoding) {
        if (acceptEncoding == null) {
            return false;
        }
        Boolean star = null;
        for (String part : acceptEncoding.split(",")) {
            String[] pieces = part.trim().split(";");
            String coding = pieces[0].trim().toLowerCase(Locale.ROOT);
            boolean acceptable = true;
            for (int i = 1; i < pieces.length; i++) {
                String parameter = pieces[i].trim().toLowerCase(Locale.ROOT);
                if (parameter.startsWith("q=")) {
                    try {
                        acceptable = Double.parseDouble(parameter.substring(2)) > 0;
                    } catch (NumberFormatException e) {
                        acceptable = false;
                    }
                }
            }
            if (coding.equals("gzip") || coding.equals("x-gzip")) {
                return acceptable;
            }
            if (coding.equals("*")) {
                star = acceptable;
            }
        }
        return star != null && star;
    }

    // ------------------------------------------------------------------- gzip

    /**
     * Compressed copies of recently served files, keyed by path and invalidated by size and
     * modification time. Bounded by entry count; each entry is at most the configured gzip
     * size limit. A file that does not shrink is remembered as such and served uncompressed.
     */
    private static final class GzipCache {

        private record Cached(long size, long modifiedMillis, byte[] compressed) {
        }

        private final Map<String, Cached> entries;

        GzipCache(int maxEntries) {
            this.entries = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                    return size() > maxEntries;
                }
            };
        }

        /** The gzip form of the file, or {@code null} if compressing it does not make it smaller. */
        byte[] get(Path file, BasicFileAttributes attributes) throws IOException {
            String key = file.toString();
            long size = attributes.size();
            long modified = attributes.lastModifiedTime().toMillis();
            synchronized (entries) {
                Cached entry = entries.get(key);
                if (entry != null && entry.size == size && entry.modifiedMillis == modified) {
                    return entry.compressed;
                }
            }
            // Compress outside the lock; two threads may race to compress the same file once.
            byte[] original = Files.readAllBytes(file);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(original.length / 3 + 64);
            try (GZIPOutputStream out = new GZIPOutputStream(buffer)) {
                out.write(original);
            }
            byte[] compressed = buffer.size() < original.length ? buffer.toByteArray() : null;
            synchronized (entries) {
                entries.put(key, new Cached(size, modified, compressed));
            }
            return compressed;
        }
    }
}
