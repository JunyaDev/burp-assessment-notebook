package com.assessmentnotebook.burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import com.assessmentnotebook.burp.ui.RegisterPageDialog;
import com.assessmentnotebook.core.NotebookController;
import com.assessmentnotebook.core.PageRegistration;
import com.assessmentnotebook.model.*;

import javax.swing.*;
import java.awt.Component;
import java.awt.Desktop;
import java.util.ArrayList;
import java.util.List;

/**
 * The right-click menu that drives capture from anywhere in Burp: register the
 * selected request as a page or resource, attach an observation or finding, or
 * capture a screenshot of the current screen. Every action targets the single
 * open project and refreshes the tab afterward.
 */
public final class ContextMenuProvider implements ContextMenuItemsProvider {
    private final MontoyaApi api;
    private final NotebookSession session;
    private final RequestExtractor extractor = new RequestExtractor();

    public ContextMenuProvider(MontoyaApi api, NotebookSession session) {
        this.api = api;
        this.session = session;
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        HttpRequestResponse rr = selected(event);
        List<Component> items = new ArrayList<>();
        JMenu menu = new JMenu("Assessment Notebook");

        menu.add(action("Register Page…", () -> registerPage(rr), rr != null));
        menu.add(action("Register Resource", () -> registerResource(rr), rr != null));
        menu.add(action("Add Observation…", () -> addObservation(rr), true));
        menu.add(action("Create Vulnerability…", () -> createVulnerability(rr), true));
        menu.add(action("Capture Screenshot…", () -> captureScreenshot(rr), true));
        menu.addSeparator();
        menu.add(action("Open Project Documentation", this::openDocs, true));

        items.add(menu);
        return items;
    }

    private static JMenuItem action(String label, Runnable r, boolean enabled) {
        JMenuItem item = new JMenuItem(label);
        item.setEnabled(enabled);
        item.addActionListener(e -> r.run());
        return item;
    }

    private static HttpRequestResponse selected(ContextMenuEvent event) {
        if (event.messageEditorRequestResponse().isPresent()) {
            return event.messageEditorRequestResponse().get().requestResponse();
        }
        List<HttpRequestResponse> list = event.selectedRequestResponses();
        return list.isEmpty() ? null : list.get(0);
    }

    // ---- actions ---------------------------------------------------------

    private void registerPage(HttpRequestResponse rr) {
        if (!requireProject() || rr == null) return;
        PageRegistration reg = extractor.toRegistration(rr);
        reg.discoverySource = "Burp: selected request";
        SwingUtilities.invokeLater(() ->
                new RegisterPageDialog(api, session, reg).setVisible(true));
    }

    private void registerResource(HttpRequestResponse rr) {
        if (!requireProject() || rr == null) return;
        String url = rr.request().url();
        Resource.Type type = guessType(url,
                rr.hasResponse() ? rr.response().headerValue("Content-Type") : null);
        run(() -> {
            session.controller().addResource(url, type);
            api.logging().logToOutput("Assessment Notebook: registered resource " + url);
        });
    }

    private void addObservation(HttpRequestResponse rr) {
        if (!requireProject()) return;
        JComboBox<Note.Kind> kind = new JComboBox<>(Note.Kind.values());
        JTextArea text = new JTextArea(4, 30);
        JPanel panel = new JPanel(new java.awt.BorderLayout(6, 6));
        JPanel north = new JPanel();
        north.add(new JLabel("Kind:"));
        north.add(kind);
        panel.add(north, java.awt.BorderLayout.NORTH);
        panel.add(new JScrollPane(text), java.awt.BorderLayout.CENTER);
        int ok = JOptionPane.showConfirmDialog(null, panel, "Add Observation",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION || text.getText().isBlank()) return;

        EntityType type = EntityType.PROJECT;
        String id = "";
        Page page = rr != null ? matchPage(rr) : null;
        if (page != null) { type = EntityType.PAGE; id = page.id; }
        final EntityType ft = type;
        final String fid = id;
        run(() -> session.controller().addNote(ft, fid, (Note.Kind) kind.getSelectedItem(),
                text.getText().trim()));
    }

    private void createVulnerability(HttpRequestResponse rr) {
        if (!requireProject()) return;
        JTextField title = new JTextField(28);
        JComboBox<Vulnerability.Severity> sev = new JComboBox<>(Vulnerability.Severity.values());
        sev.setSelectedItem(Vulnerability.Severity.MEDIUM);
        JTextField url = new JTextField(rr != null ? rr.request().url() : "", 28);
        JPanel panel = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
        panel.add(new JLabel("Title:"));
        panel.add(title);
        panel.add(new JLabel("Severity:"));
        panel.add(sev);
        panel.add(new JLabel("Affected URL:"));
        panel.add(url);
        int ok = JOptionPane.showConfirmDialog(null, panel, "Create Vulnerability",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION || title.getText().isBlank()) return;
        run(() -> session.controller().createVulnerability(title.getText().trim(),
                (Vulnerability.Severity) sev.getSelectedItem(), url.getText().trim()));
    }

    private void captureScreenshot(HttpRequestResponse rr) {
        if (!requireProject()) return;
        Page page = rr != null ? matchPage(rr) : null;
        if (page == null) {
            JOptionPane.showMessageDialog(null,
                    "Register the page first, then capture screenshots for it.",
                    "Assessment Notebook", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JTextField state = new JTextField("Default page", 24);
        JSpinner delay = new JSpinner(new SpinnerNumberModel(2, 0, 30, 1));
        JPanel panel = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
        panel.add(new JLabel("State description:"));
        panel.add(state);
        panel.add(new JLabel("Delay before capture (seconds) — arrange the window now:"));
        panel.add(delay);
        int ok = JOptionPane.showConfirmDialog(null, panel, "Capture Screenshot",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        int seconds = (Integer) delay.getValue();
        int seq = (int) session.controller().project().screenshots.stream()
                .filter(s -> page.id.equals(s.pageId)).count();
        run(() -> {
            byte[] png = ScreenshotCapture.captureFullScreen(seconds * 1000);
            session.controller().addScreenshot(page.id, png, state.getText().trim(), seq, null);
            api.logging().logToOutput("Assessment Notebook: captured screenshot for " + page.url);
        });
    }

    private void openDocs() {
        if (!requireProject()) return;
        java.nio.file.Path index = session.controller().layout().indexHtml();
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(index.toUri());
            else JOptionPane.showMessageDialog(null, "Documentation: " + index);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "Documentation: " + index);
        }
    }

    // ---- helpers ---------------------------------------------------------

    private Page matchPage(HttpRequestResponse rr) {
        NotebookController c = session.controller();
        Page byMethod = c.project().findPageByRequest(rr.request().url(), rr.request().method());
        if (byMethod != null) return byMethod;
        for (Page p : c.project().pages) {
            if (p.url.equals(rr.request().url())) return p;
        }
        return null;
    }

    private boolean requireProject() {
        if (session.isOpen()) return true;
        JOptionPane.showMessageDialog(null,
                "Open or create a project in the Assessment Notebook tab first.",
                "Assessment Notebook", JOptionPane.WARNING_MESSAGE);
        return false;
    }

    /** Run a mutating action off the EDT and refresh the tab, reporting errors. */
    private void run(ThrowingRunnable task) {
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception { task.run(); return null; }
            @Override protected void done() {
                try {
                    get();
                    session.fireChanged();
                } catch (Exception ex) {
                    api.logging().logToError("Assessment Notebook action failed", ex);
                    JOptionPane.showMessageDialog(null, "Action failed: " + ex.getMessage(),
                            "Assessment Notebook", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private static Resource.Type guessType(String url, String contentType) {
        String u = url == null ? "" : url.toLowerCase();
        String ct = contentType == null ? "" : contentType.toLowerCase();
        if (ct.contains("javascript") || u.endsWith(".js")) return Resource.Type.SCRIPT;
        if (ct.contains("css") || u.endsWith(".css")) return Resource.Type.STYLESHEET;
        if (ct.contains("image") || u.matches(".*\\.(png|jpg|jpeg|gif|svg|webp)$")) return Resource.Type.IMAGE;
        if (ct.contains("font") || u.matches(".*\\.(woff2?|ttf|otf)$")) return Resource.Type.FONT;
        if (ct.contains("json") || u.contains("/api/")) return Resource.Type.API;
        return Resource.Type.OTHER;
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }
}
