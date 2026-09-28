package com.assessmentnotebook;

import com.assessmentnotebook.analyze.ParameterAnalysis;
import com.assessmentnotebook.analyze.ProbeGenerator;
import com.assessmentnotebook.core.NotebookController;
import com.assessmentnotebook.model.ParameterTest;
import com.assessmentnotebook.model.Vulnerability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParameterAnalysisTest {

    @Test void defaultProbeSetIsNonDestructiveAndOrdered() {
        List<ProbeGenerator.Probe> probes = ProbeGenerator.generate("apple");
        assertEquals(ProbeGenerator.Kind.ORIGINAL, probes.get(0).kind);
        assertTrue(probes.stream().anyMatch(p -> p.omit), "an omitted probe exists");
        assertTrue(probes.stream().anyMatch(p -> p.kind == ProbeGenerator.Kind.EMPTY
                && p.value.isEmpty()));
        // Canaries are inert markers, not executable payloads.
        ProbeGenerator.Probe markup = probes.stream()
                .filter(p -> p.kind == ProbeGenerator.Kind.MARKUP_CANARY).findFirst().orElseThrow();
        assertFalse(markup.value.toLowerCase().contains("script"));
        assertFalse(markup.value.toLowerCase().contains("alert"));
    }

    @Test void characterizeNeverAutoConfirms() {
        ParameterTest t = new ParameterTest();
        t.probeKind = "MARKUP_CANARY";
        t.baselineStatus = 200; t.baselineLength = 100;
        t.responseStatus = 200; t.responseLength = 140;
        t.reflected = true;
        ParameterAnalysis.characterize(t);
        assertEquals(ParameterTest.Classification.POTENTIAL_ISSUE, t.classification);
        assertTrue(t.observation.contains("reflected"));
        assertNotEquals(ParameterTest.Classification.CONFIRMED_VULNERABILITY, t.classification);
    }

    @Test void summaryDetectsRequiredAndReflected() {
        ParameterTest orig = test("ORIGINAL", 200, 100, false);
        ParameterTest empty = test("EMPTY", 400, 30, false);
        ParameterTest omit = test("OMITTED", 400, 30, false);
        ParameterTest canary = test("MARKUP_CANARY", 200, 150, true);
        ParameterAnalysis.Summary s =
                ParameterAnalysis.summarize(List.of(orig, empty, omit, canary));
        assertTrue(s.required);
        assertFalse(s.acceptsEmpty);
        assertTrue(s.reflected);
        assertTrue(s.changesStatus);
        assertTrue(s.changesLength);
    }

    @Test void recordAndPromoteFlow(@TempDir Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("params");
        ParameterTest t = test("MARKUP_CANARY", 200, 120, true);
        t.parameterName = "q";
        c.recordParameterTest(t);
        assertEquals(1, c.project().parameterTests.size());
        assertEquals(ParameterTest.Classification.POTENTIAL_ISSUE,
                c.project().parameterTests.get(0).classification);

        Vulnerability v = c.promoteTestToFinding(t.id, "Reflected input in q",
                Vulnerability.Severity.MEDIUM);
        assertNotNull(v);
        assertEquals(1, c.project().vulnerabilities.size());
        assertEquals(ParameterTest.Classification.CONFIRMED_VULNERABILITY,
                c.project().parameterTests.get(0).classification);
        assertEquals(v.id, c.project().parameterTests.get(0).promotedVulnId);
    }

    private static ParameterTest test(String kind, int status, int len, boolean reflected) {
        ParameterTest t = new ParameterTest();
        t.probeKind = kind;
        t.probeLabel = kind;
        t.baselineStatus = 200; t.baselineLength = 100;
        t.responseStatus = status; t.responseLength = len;
        t.reflected = reflected;
        return t;
    }
}
