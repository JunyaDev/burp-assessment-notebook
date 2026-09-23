package com.assessmentnotebook.html;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Copies the shared, self-contained front-end assets (retro theme CSS, the
 * offline syntax highlighter, and the small page-behavior script) out of the
 * jar and into a project's {@code assets/} directory. No CDN or network is
 * used, so a copied project renders fully offline.
 */
public final class Assets {
    /** Asset files shipped on the classpath under {@code /assets/}. */
    public static final String[] NAMES = {"retro.css", "highlight.js", "app.js"};

    private Assets() {}

    public static void copyTo(Path assetsDir) throws IOException {
        Files.createDirectories(assetsDir);
        for (String name : NAMES) {
            byte[] data = read("/assets/" + name);
            Files.write(assetsDir.resolve(name), data);
        }
    }

    private static byte[] read(String resource) throws IOException {
        try (InputStream in = Assets.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Bundled asset missing from classpath: " + resource);
            }
            return in.readAllBytes();
        }
    }

    /** Read a bundled text asset as a string (used by tests and tooling). */
    public static String readText(String name) throws IOException {
        return new String(read("/assets/" + name), StandardCharsets.UTF_8);
    }
}
