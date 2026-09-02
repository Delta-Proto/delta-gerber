package com.deltaproto.deltagerber.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The page the viewer server serves. The version badge is the one part of it assembled at
 * runtime — Maven filters the build's version into {@code version.properties} and the server
 * substitutes it as it serves — so it is the part worth pinning: a placeholder that reaches the
 * browser would be shown to the user as if it were a version.
 */
class ViewerPageTest {

    @Test
    void versionPlaceholderIsNeverServedRaw() {
        String html = GerberViewerServer.getIndexHtml();

        assertTrue(html.contains("<html") || html.contains("<!DOCTYPE"), "the viewer page is served");
        assertFalse(html.contains("__APP_VERSION__"), "the placeholder must be substituted away");
        assertTrue(html.contains("id=\"app-version\""), "the header carries a version badge");
    }

    /**
     * Under Maven the filtered resource is on the classpath, so the running build's version is
     * known and belongs in the page. Elsewhere — an IDE run against unfiltered resources — it is
     * {@code null}, and the badge is left empty for the page to hide rather than filled with a
     * guess.
     */
    @Test
    void theBuildsVersionReachesThePage() {
        String version = GerberViewerServer.getVersion();
        assertNotNull(version, "Maven filters version.properties, so the version is known here");
        assertFalse(version.startsWith("${"), "an unfiltered placeholder is not a version");
        assertTrue(GerberViewerServer.getIndexHtml().contains(">" + version + "<"),
            "the header shows " + version);
    }
}
