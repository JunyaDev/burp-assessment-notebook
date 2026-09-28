package com.assessmentnotebook.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The on-disk layout of a project directory. Every path is derived from a
 * single root so a project is portable: copying the root copies everything,
 * and all generated links between documents are relative to it.
 *
 * <pre>
 * root/
 *   project.json        machine-readable model (source of truth)
 *   index.html          generated overview
 *   assets/             shared CSS/JS (retro theme, highlighter)
 *   pages/  forms/  links/  scripts/  files/  vulnerabilities/   generated docs
 *   screenshots/        captured images
 *   source/             saved page source and raw request/response bytes
 * </pre>
 */
public final class ProjectLayout {
    public final Path root;

    public ProjectLayout(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path projectJson() { return root.resolve("project.json"); }
    public Path indexHtml()   { return root.resolve("index.html"); }
    public Path assets()      { return root.resolve("assets"); }
    public Path pages()       { return root.resolve("pages"); }
    public Path forms()       { return root.resolve("forms"); }
    public Path links()       { return root.resolve("links"); }
    public Path scripts()     { return root.resolve("scripts"); }
    public Path files()       { return root.resolve("files"); }
    public Path vulnerabilities() { return root.resolve("vulnerabilities"); }
    public Path screenshots() { return root.resolve("screenshots"); }
    public Path source()      { return root.resolve("source"); }
    public Path wordlists()   { return root.resolve("wordlists"); }

    /** Create the full directory skeleton if it does not already exist. */
    public void ensureDirectories() throws IOException {
        for (Path p : new Path[]{root, assets(), pages(), forms(), links(),
                scripts(), files(), vulnerabilities(), screenshots(), source(), wordlists()}) {
            Files.createDirectories(p);
        }
    }

    /** A relative POSIX-style path from the project root, for use in HTML links. */
    public String relative(Path absolute) {
        return root.relativize(absolute.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }
}
