package io.github.mgeladzerezo.sockethttp.staticfiles;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a request's path segments may be used as file names under a document root.
 *
 * <p>The input is the list of <em>decoded</em> segments from the request parser, so every
 * encoding trick ({@code %2e%2e}, {@code %2f}, {@code %5c}) has already been turned into the
 * characters it stands for and is judged as such. The rule is an allow-list in spirit: a
 * segment must be an ordinary file name on every platform the server may run on, so the
 * Windows hazards are refused on Linux too and behaviour does not depend on the host.
 *
 * <p>This is the first of two independent checks. The second, in {@link StaticFiles}, resolves
 * the path on disk and requires the real path to be exactly the root plus these segments,
 * which also catches symbolic links and file-system aliases this class cannot know about.
 */
final class PathSanitizer {

    /**
     * Names Windows maps to devices in every directory, with or without an extension:
     * opening {@code C:\site\aux.txt} opens the AUX device, not a file.
     */
    private static final Set<String> WINDOWS_DEVICES = Set.of(
            "CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$", "CLOCK$",
            "COM0", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "COM\u00b9", "COM\u00b2", "COM\u00b3",
            "LPT0", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
            "LPT\u00b9", "LPT\u00b2", "LPT\u00b3");

    private PathSanitizer() {
    }

    /**
     * @return {@code null} if every segment is acceptable, otherwise a short description of
     *         the first problem, suitable for a 403 body
     */
    static String rejection(List<String> segments, boolean allowDotFiles) {
        for (String segment : segments) {
            if (segment.isEmpty()) {
                return "empty path segment";
            }
            if (segment.equals(".") || segment.equals("..")) {
                return "dot segment";
            }
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (c == '/' || c == '\\') {
                    // Only reachable percent-encoded; the parser splits on raw slashes and
                    // rejects raw backslashes.
                    return "path separator inside a segment";
                }
                if (c == ':') {
                    // Drive letters ("C:") and NTFS alternate data streams ("file::$DATA").
                    return "colon in a path segment";
                }
                if (c < 0x20 || c == 0x7F) {
                    return "control character in a path segment";
                }
                if (c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') {
                    // Not valid in Windows file names; '<', '>' and '"' act as wildcards in
                    // some Win32 calls.
                    return "reserved character in a path segment";
                }
            }
            char last = segment.charAt(segment.length() - 1);
            if (last == '.' || last == ' ') {
                // Windows silently strips these, so "secret.txt." would alias "secret.txt".
                return "trailing dot or space";
            }
            if (isWindowsDevice(segment)) {
                return "reserved device name";
            }
            if (!allowDotFiles && segment.charAt(0) == '.') {
                return "hidden file";
            }
        }
        return null;
    }

    private static boolean isWindowsDevice(String segment) {
        int dot = segment.indexOf('.');
        String base = (dot < 0 ? segment : segment.substring(0, dot)).stripTrailing().toUpperCase(Locale.ROOT);
        return WINDOWS_DEVICES.contains(base);
    }
}
