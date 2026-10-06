package io.github.mgeladzerezo.sockethttp.demo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The static server serves a directory (it uses {@code FileChannel.transferTo}, which needs a
 * real file), but the site ships inside the jar. This copies it out at startup, using the
 * {@code site/files.txt} manifest because a jar cannot be listed portably.
 */
final class SiteResources {

    private static final String ROOT = "/site/";

    private SiteResources() {
    }

    static Path extractToTempDirectory() {
        try {
            Path target = Files.createTempDirectory("socket-http-site");
            target.toFile().deleteOnExit();
            extractTo(target);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot unpack the bundled site", e);
        }
    }

    static void extractTo(Path target) throws IOException {
        String manifest;
        try (InputStream in = SiteResources.class.getResourceAsStream(ROOT + "files.txt")) {
            if (in == null) {
                throw new IOException("site manifest missing from the jar");
            }
            manifest = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String line : manifest.split("\\R")) {
            String name = line.strip();
            if (name.isEmpty()) {
                continue;
            }
            Path file = target.resolve(name).normalize();
            if (!file.startsWith(target)) {
                throw new IOException("manifest entry escapes the site directory: " + name);
            }
            Files.createDirectories(file.getParent());
            try (InputStream in = SiteResources.class.getResourceAsStream(ROOT + name)) {
                if (in == null) {
                    throw new IOException("listed in the manifest but missing: " + name);
                }
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            }
            file.toFile().deleteOnExit();
        }
    }
}
