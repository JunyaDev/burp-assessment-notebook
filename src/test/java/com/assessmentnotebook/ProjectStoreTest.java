package com.assessmentnotebook;

import com.assessmentnotebook.model.Page;
import com.assessmentnotebook.model.Project;
import com.assessmentnotebook.model.Technology;
import com.assessmentnotebook.model.EntityType;
import com.assessmentnotebook.store.ProjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProjectStoreTest {

    @Test void createBuildsSkeletonAndPersists(@TempDir Path dir) throws IOException {
        ProjectStore store = new ProjectStore(dir);
        Project p = store.create("Acme assessment");
        assertTrue(store.exists());
        assertTrue(Files.isDirectory(store.layout().pages()));
        assertTrue(Files.isDirectory(store.layout().screenshots()));
        assertEquals("Acme assessment", p.name);
        assertNotNull(p.createdAt);
    }

    @Test void refusesToClobberExistingProject(@TempDir Path dir) throws IOException {
        ProjectStore store = new ProjectStore(dir);
        store.create("First");
        assertThrows(IOException.class, () -> store.create("Second"));
    }

    @Test void roundTripsModel(@TempDir Path dir) throws IOException {
        ProjectStore store = new ProjectStore(dir);
        Project p = store.create("RT");
        p.target.domain = "site.test";

        Page page = new Page();
        page.id = p.nextId(EntityType.PAGE);
        page.url = "https://site.test/login";
        page.method = "POST";
        page.statusCode = 200;
        p.pages.add(page);

        Technology t = new Technology();
        t.id = p.nextId(EntityType.TECHNOLOGY);
        t.category = Technology.Category.WEB_SERVER;
        t.name = "nginx";
        p.technologies.add(t);

        store.save(p);

        Project loaded = new ProjectStore(dir).load();
        assertEquals("site.test", loaded.target.domain);
        assertEquals(1, loaded.pages.size());
        assertEquals("https://site.test/login", loaded.pages.get(0).url);
        assertEquals("POST", loaded.pages.get(0).method);
        assertEquals(Technology.Category.WEB_SERVER, loaded.technologies.get(0).category);
        // Id counters survive the round trip so ids stay unique.
        assertEquals("page-0002", loaded.nextId(EntityType.PAGE));
    }

    @Test void saveWritesBinaryEvidence(@TempDir Path dir) throws IOException {
        ProjectStore store = new ProjectStore(dir);
        store.create("Ev");
        String rel = store.saveScreenshot(new byte[]{1, 2, 3}, "home page!!");
        assertTrue(rel.startsWith("screenshots/"));
        assertTrue(Files.exists(store.layout().root.resolve(rel)));
        // Name is sanitized to a safe, portable base.
        assertFalse(rel.contains(" "));
        assertFalse(rel.contains("!"));
    }
}
