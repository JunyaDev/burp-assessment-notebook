package com.assessmentnotebook;

import com.assessmentnotebook.core.NotebookController;
import com.assessmentnotebook.model.InterestingString.Category;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WordlistTest {

    @Test void capturesDedupesAndExportsByCategory(@TempDir Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("wl");

        c.addInterestingString("admin", Category.USERNAMES, null, "login form", "", "");
        c.addInterestingString("administrator", Category.USERNAMES, null, "", "", "");
        c.addInterestingString("admin", Category.USERNAMES, null, "", "", ""); // dup
        c.addInterestingString("/internal/api", Category.DIRECTORIES, null, "", "", "");

        assertEquals(3, c.project().interestingStrings.size(), "duplicate not stored twice");
        assertEquals(List.of("admin", "administrator"), c.wordlist(Category.USERNAMES));

        List<String> files = c.exportWordlists();
        assertEquals(2, files.size(), "one file per non-empty category");
        Path usernames = dir.resolve("wordlists/usernames.txt");
        assertTrue(Files.exists(usernames));
        assertEquals(List.of("admin", "administrator"), Files.readAllLines(usernames));

        // The wordlists overview page is generated once strings exist.
        assertTrue(Files.exists(dir.resolve("wordlists/index.html")));
        assertTrue(Files.readString(dir.resolve("index.html")).contains("WORDLISTS"));
    }
}
