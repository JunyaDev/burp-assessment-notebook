package com.assessmentnotebook;

import com.assessmentnotebook.analyze.ResourceUrls;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResourceUrlsTest {

    @Test void dropsFragmentAndLowercasesHost() {
        assertEquals("https://portal.acme.test/js/app.js",
                ResourceUrls.canonical("https://PORTAL.Acme.Test/js/app.js#v2"));
    }

    @Test void dropsDefaultPortsButKeepsCustom() {
        assertEquals("https://a.test/x", ResourceUrls.canonical("https://a.test:443/x"));
        assertEquals("http://a.test/x", ResourceUrls.canonical("http://a.test:80/x"));
        assertEquals("https://a.test:8443/x", ResourceUrls.canonical("https://a.test:8443/x"));
    }

    @Test void staticAssetsIgnoreCacheBusterQuery() {
        assertTrue(ResourceUrls.sameResource(
                "https://a.test/static/app.js?v=9f3a",
                "https://a.test/static/app.js?v=beef"));
        assertTrue(ResourceUrls.sameResource(
                "https://a.test/app.css",
                "https://a.test/app.css?123"));
    }

    @Test void endpointsKeepDistinguishingQuery() {
        assertFalse(ResourceUrls.sameResource(
                "https://a.test/api/users?role=admin",
                "https://a.test/api/users?role=guest"));
    }

    @Test void collapsesTrailingSlashOnNonRoot() {
        assertTrue(ResourceUrls.sameResource("https://a.test/api/", "https://a.test/api"));
        assertEquals("https://a.test/", ResourceUrls.canonical("https://a.test/"));
    }

    @Test void unparseableUrlSurvivesTrimmedWithoutFragment() {
        assertEquals("not a url", ResourceUrls.canonical("  not a url#frag "));
    }
}
