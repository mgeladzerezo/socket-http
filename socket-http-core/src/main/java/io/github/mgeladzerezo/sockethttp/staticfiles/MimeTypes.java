package io.github.mgeladzerezo.sockethttp.staticfiles;

import java.util.Locale;
import java.util.Map;

/** Media types by file extension, and which of them are worth compressing. */
public final class MimeTypes {

    private static final String DEFAULT = "application/octet-stream";

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("map", "application/json"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("md", "text/markdown; charset=utf-8"),
            Map.entry("csv", "text/csv; charset=utf-8"),
            Map.entry("xml", "application/xml"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("avif", "image/avif"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("zip", "application/zip"),
            Map.entry("gz", "application/gzip"),
            Map.entry("wasm", "application/wasm"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("bin", DEFAULT));

    private MimeTypes() {
    }

    /** The media type for a file name; {@code application/octet-stream} when the extension is unknown. */
    public static String forFileName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return DEFAULT;
        }
        return BY_EXTENSION.getOrDefault(fileName.substring(dot + 1).toLowerCase(Locale.ROOT), DEFAULT);
    }

    /**
     * Whether content of this type shrinks under gzip. Text formats do; images, video, fonts
     * and archives are already compressed, and compressing them again costs CPU for nothing.
     */
    public static boolean isCompressible(String mediaType) {
        return mediaType.startsWith("text/")
                || mediaType.startsWith("application/json")
                || mediaType.startsWith("application/xml")
                || mediaType.startsWith("image/svg+xml")
                || mediaType.startsWith("application/wasm");
    }
}
