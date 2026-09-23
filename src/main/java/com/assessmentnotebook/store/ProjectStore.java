package com.assessmentnotebook.store;

import com.assessmentnotebook.model.Project;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Reads and writes a {@link Project} to its directory, and stores the binary
 * evidence (screenshots, saved source, raw traffic) that the JSON references.
 *
 * <p>The JSON is the source of truth; HTML is regenerated from it. Saving is
 * atomic per file (write to a temp file, then move) so an interrupted save
 * never corrupts an existing project.
 */
public final class ProjectStore {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    private final ProjectLayout layout;

    public ProjectStore(Path root) {
        this.layout = new ProjectLayout(root);
    }

    public ProjectLayout layout() { return layout; }

    /** Serialize the project model to {@code project.json} atomically. */
    public void save(Project project) throws IOException {
        layout.ensureDirectories();
        project.updatedAt = Timestamps.now();
        String json = GSON.toJson(project);
        writeAtomic(layout.projectJson(), json.getBytes(StandardCharsets.UTF_8));
    }

    /** Load the project model, or throw if {@code project.json} is missing. */
    public Project load() throws IOException {
        Path json = layout.projectJson();
        if (!Files.exists(json)) {
            throw new IOException("No project.json in " + layout.root);
        }
        String text = Files.readString(json, StandardCharsets.UTF_8);
        Project p = GSON.fromJson(text, Project.class);
        return p != null ? p : new Project();
    }

    public boolean exists() {
        return Files.exists(layout.projectJson());
    }

    /**
     * Create a new, empty project on disk with the directory skeleton and an
     * initial {@code project.json}. Refuses to clobber an existing project.
     */
    public Project create(String name) throws IOException {
        if (exists()) {
            throw new IOException("A project already exists at " + layout.root);
        }
        layout.ensureDirectories();
        Project p = new Project();
        p.name = name;
        p.createdAt = Timestamps.now();
        save(p);
        return p;
    }

    // ---- binary evidence -------------------------------------------------

    /** Save an image into {@code screenshots/} and return its project-relative path. */
    public String saveScreenshot(byte[] png, String baseName) throws IOException {
        Path target = uniquePath(layout.screenshots(), sanitize(baseName), ".png");
        Files.write(target, png);
        return layout.relative(target);
    }

    /** Save captured source text into {@code source/} and return its relative path. */
    public String saveSource(String content, String fileName) throws IOException {
        Path target = uniquePath(layout.source(), sanitize(stripExt(fileName)), ext(fileName));
        Files.writeString(target, content, StandardCharsets.UTF_8);
        return layout.relative(target);
    }

    /** Save raw bytes (e.g. a full request/response) under {@code source/}. */
    public String saveRaw(byte[] bytes, String baseName, String extension) throws IOException {
        Path target = uniquePath(layout.source(), sanitize(baseName), extension);
        Files.write(target, bytes);
        return layout.relative(target);
    }

    /** Copy an external file into {@code files/} and return its relative path. */
    public String importFile(Path external) throws IOException {
        String name = external.getFileName().toString();
        Path target = uniquePath(layout.files(), sanitize(stripExt(name)), ext(name));
        Files.copy(external, target, StandardCopyOption.REPLACE_EXISTING);
        return layout.relative(target);
    }

    // ---- helpers ---------------------------------------------------------

    private static void writeAtomic(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Pick a non-colliding path like base.ext, base-1.ext, base-2.ext ... */
    private static Path uniquePath(Path dir, String base, String ext) throws IOException {
        Files.createDirectories(dir);
        if (base.isEmpty()) base = "item";
        Path candidate = dir.resolve(base + ext);
        int n = 1;
        while (Files.exists(candidate)) {
            candidate = dir.resolve(base + "-" + n + ext);
            n++;
        }
        return candidate;
    }

    /** Reduce an arbitrary string to a safe, portable file base name. */
    static String sanitize(String s) {
        if (s == null) return "";
        String cleaned = s.trim().toLowerCase()
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }

    private static String ext(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot) : "";
    }

    private static String stripExt(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(0, dot) : fileName;
    }
}
