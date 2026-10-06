package com.assessmentnotebook.burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import com.assessmentnotebook.burp.ui.BurpUi;
import com.assessmentnotebook.burp.ui.RegisterPageDialog;
import com.assessmentnotebook.burp.ui.JsScanDialog;
import com.assessmentnotebook.burp.ui.RegisterResourceDialog;
import com.assessmentnotebook.burp.ui.ScreenshotAnnotator;
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
        String selectedText = selectedText(event);
        List<Component> items = new ArrayList<>();
        JMenu menu = new JMenu("Assessment Notebook");

        // Keyboard support is via MNEMONICS only: open Burp's context menu, then
        // press the underlined letter to run an action. We deliberately do NOT
        // set menu accelerators — on a context menu Burp rebuilds each time they
        // never actually fire, and global chords like Ctrl+Shift+P belong to Burp
        // itself (Proxy tab). Montoya exposes no global extension hotkey, so this
        // in-menu letter is the only keyboard path that works and is collision-free.
        menu.setMnemonic('A');
        menu.add(action("Register Page…", 'P', () -> registerPage(rr, null, false), rr != null));
        menu.add(action("Register API Endpoint…", 'I',
                () -> registerPage(rr, Page.Kind.API, false), rr != null));
        menu.add(action("Register Resource…", 'R', () -> registerResource(rr), rr != null));
        menu.add(action("Register Form…", 'F', () -> registerForm(rr), rr != null));
        menu.add(action("Register as Page Variant…", 'B', () -> registerVariant(rr), rr != null));
        menu.add(action("Describe Parameters (purpose / notes)…", 'O',
                () -> describeParameters(rr), true));
        menu.add(action("Add Note / Observation…", 'N', () -> addObservation(rr), true));
        menu.add(action("Mark Interesting String…", 'M',
                () -> markInterestingString(rr, selectedText), true));
        menu.add(action("Register Element from selection…", 'E',
                () -> registerElement(rr, selectedText), true));
        menu.add(action("Test Parameter (quick probes)…", 'T', () -> testParameter(rr), rr != null));
        menu.add(action("Scan JS for interesting data…", 'J', () -> scanJs(rr),
                rr != null && rr.hasResponse()));
        menu.add(action("Create Vulnerability…", 'V', () -> createVulnerability(rr), true));
        menu.add(action("Capture Screenshot…", 'S', () -> captureScreenshot(rr), true));
        menu.addSeparator();
        menu.add(action("Auto-Capture Rule from this Request…", 'U',
                () -> ruleFromRequest(rr), rr != null));
        menu.add(action("Export Wordlists", 'W', this::exportWordlists, true));
        menu.add(action("Open Project Documentation", 'D', this::openDocs, true));

        items.add(menu);
        return items;
    }

    private static JMenuItem action(String label, Runnable r, boolean enabled) {
        return action(label, (char) 0, r, enabled);
    }

    /**
     * A menu item with an optional mnemonic (the underlined letter that runs the
     * item while the menu is open). No accelerator is set: context-menu
     * accelerators do not fire in Burp and would collide with Burp's own chords.
     */
    private static JMenuItem action(String label, char mnemonic, Runnable r, boolean enabled) {
        JMenuItem item = new JMenuItem(label);
        if (mnemonic != 0) item.setMnemonic(mnemonic);
        item.setEnabled(enabled);
        item.addActionListener(e -> r.run());
        return item;
    }

    /** Best-effort text currently selected in a Burp message editor, or "". */
    private static String selectedText(ContextMenuEvent event) {
        try {
            if (event.messageEditorRequestResponse().isEmpty()) return "";
            var editor = event.messageEditorRequestResponse().get();
            var offsets = editor.selectionOffsets();
            if (offsets.isEmpty()) return "";
            var range = offsets.get();
            HttpRequestResponse rr = editor.requestResponse();
            String content;
            switch (editor.selectionContext()) {
                case REQUEST:
                    content = rr.request() == null ? "" : rr.request().toString();
                    break;
                default:
                    content = rr.hasResponse() ? rr.response().toString() : "";
            }
            int start = Math.max(0, range.startIndexInclusive());
            int end = Math.min(content.length(), range.endIndexExclusive());
            return start < end ? content.substring(start, end) : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static HttpRequestResponse selected(ContextMenuEvent event) {
        if (event.messageEditorRequestResponse().isPresent()) {
            return event.messageEditorRequestResponse().get().requestResponse();
        }
        List<HttpRequestResponse> list = event.selectedRequestResponses();
        return list.isEmpty() ? null : list.get(0);
    }

    // ---- actions ---------------------------------------------------------

    /**
     * @param kind          force page or API endpoint, or null to keep what the
     *                      traffic classifier suggested
     * @param annotateAfter open the parameter table once the page is registered
     */
    private void registerPage(HttpRequestResponse rr, Page.Kind kind, boolean annotateAfter) {
        if (!requireProject() || rr == null) return;
        // Parsing a large response with jsoup is not instant; keep it off the EDT.
        new SwingWorker<PageRegistration, Void>() {
            @Override protected PageRegistration doInBackground() {
                PageRegistration reg = extractor.toRegistration(rr);
                if (kind != null) reg.kind = kind;
                return reg;
            }
            @Override protected void done() {
                try {
                    new RegisterPageDialog(api, session, get(), annotateAfter).setVisible(true);
                } catch (Exception ex) {
                    api.logging().logToError("Could not analyze the selected message", ex);
                    BurpUi.error(api, "Could not analyze the selected message: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void registerResource(HttpRequestResponse rr) {
        if (!requireProject() || rr == null) return;
        String url = rr.request().url();
        Resource.Type type = guessType(url,
                rr.hasResponse() ? rr.response().headerValue("Content-Type") : null);
        Page match = matchPage(rr);
        SwingUtilities.invokeLater(() ->
                new RegisterResourceDialog(api, session, url, type, match).setVisible(true));
    }

    /**
     * Forms are captured as part of their page; this routes to the page review
     * dialog, whose FORMS checklist is the form-confirmation UI (spec §2), and
     * then opens the parameter table so each field can be described.
     */
    private void registerForm(HttpRequestResponse rr) {
        registerPage(rr, null, true);
    }

    /** Open the purpose/notes table for the selected request's forms (or all forms). */
    private void describeParameters(HttpRequestResponse rr) {
        if (!requireProject()) return;
        Page page = rr != null ? matchPage(rr) : null;
        List<String> formIds = page == null ? List.of()
                : session.controller().read(p -> new ArrayList<>(page.formIds));
        com.assessmentnotebook.burp.ui.ParameterNotesDialog dialog =
                new com.assessmentnotebook.burp.ui.ParameterNotesDialog(api, session, formIds);
        if (!dialog.hasForms()) {
            dialog.dispose();
            BurpUi.info(api, "No forms or request parameters are registered yet.\n"
                    + "Register the page or API endpoint first.");
            return;
        }
        dialog.setVisible(true);
    }

    /** Create an auto-capture rule for the selected request's host and switch capture on. */
    private void ruleFromRequest(HttpRequestResponse rr) {
        if (!requireProject() || rr == null) return;
        CaptureRule rule = new CaptureRule();
        String host = rr.httpService() != null ? rr.httpService().host()
                : com.assessmentnotebook.analyze.UrlTemplates.host(rr.request().url());
        rule.host = host;
        rule.name = host;
        if (!com.assessmentnotebook.burp.ui.CaptureRulesDialog.editRule(api, rule,
                "New Auto-Capture Rule")) {
            return;
        }
        run(() -> {
            CaptureConfig config = session.controller().captureConfig();
            config.rules.add(rule);
            config.enabled = true;
            session.controller().applyCaptureConfig(config);
            api.logging().logToOutput("Assessment Notebook: auto-capture is ON with "
                    + config.rules.size() + " rule(s); added \"" + rule.name + "\"");
        });
    }

    private void registerVariant(HttpRequestResponse rr) {
        if (!requireProject() || rr == null) return;
        String suggested = variantLabel(rr);
        String label = (String) JOptionPane.showInputDialog(BurpUi.owner(api),
                "Label this variant by its request condition\n(e.g. \"q=admin\" or "
                + "\"authenticated\"):", "Register Page Variant",
                JOptionPane.PLAIN_MESSAGE, null, null, suggested);
        if (label == null) return; // cancelled
        com.assessmentnotebook.model.PageVariant v = extractor.toVariant(rr, label.trim());
        String body = rr.hasResponse() ? rr.response().bodyToString() : "";
        run(() -> {
            session.controller().registerVariant(v, body);
            api.logging().logToOutput("Assessment Notebook: registered variant '" + label
                    + "' of " + v.url);
        });
    }

    /** A sensible default variant label from the first query/body parameter. */
    private static String variantLabel(HttpRequestResponse rr) {
        try {
            for (var p : rr.request().parameters()) {
                if (p.type() == burp.api.montoya.http.message.params.HttpParameterType.URL
                        || p.type() == burp.api.montoya.http.message.params.HttpParameterType.BODY) {
                    String val = p.value();
                    if (val != null && val.length() > 24) val = val.substring(0, 24) + "…";
                    return p.name() + "=" + val;
                }
            }
        } catch (RuntimeException ignored) { /* no params */ }
        return rr.hasResponse() ? "HTTP " + rr.response().statusCode() : "variant";
    }

    private void markInterestingString(HttpRequestResponse rr, String selectedText) {
        if (!requireProject()) return;
        JTextField value = new JTextField(selectedText == null ? "" : selectedText.trim(), 28);
        JComboBox<InterestingString.Category> cat =
                new JComboBox<>(InterestingString.Category.values());
        JTextField element = new JTextField(20);
        JTextField context = new JTextField(28);
        JPanel panel = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
        panel.add(new JLabel("Value:"));
        panel.add(value);
        panel.add(new JLabel("Wordlist category:"));
        panel.add(cat);
        panel.add(new JLabel("Source element (optional):"));
        panel.add(element);
        panel.add(new JLabel("Context (optional):"));
        panel.add(context);
        if (!BurpUi.confirm(api, panel, "Mark Interesting String") || value.getText().isBlank()) return;
        Page page = rr != null ? matchPage(rr) : null;
        String pageId = page != null ? page.id : null;
        run(() -> session.controller().addInterestingString(value.getText().trim(),
                (InterestingString.Category) cat.getSelectedItem(), pageId,
                element.getText().trim(), context.getText().trim(), ""));
    }

    private void registerElement(HttpRequestResponse rr, String selectedText) {
        if (!requireProject()) return;
        String[] kinds = {"Link", "Button", "Input", "Form", "Menu", "Hidden element",
                "Visible text", "Script reference", "Interesting parameter",
                "Technology indicator", "Security-relevant UI", "Other"};
        JComboBox<String> kind = new JComboBox<>(kinds);
        JTextArea captured = new JTextArea(selectedText == null ? "" : selectedText, 4, 30);
        JTextField note = new JTextField(28);
        JPanel panel = new JPanel(new java.awt.BorderLayout(6, 6));
        JPanel north = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
        north.add(new JLabel("Element type:"));
        north.add(kind);
        north.add(new JLabel("Captured (from your selection; edit if needed):"));
        panel.add(north, java.awt.BorderLayout.NORTH);
        panel.add(new JScrollPane(captured), java.awt.BorderLayout.CENTER);
        JPanel south = new JPanel(new java.awt.BorderLayout());
        south.add(new JLabel("Note:"), java.awt.BorderLayout.WEST);
        south.add(note, java.awt.BorderLayout.CENTER);
        panel.add(south, java.awt.BorderLayout.SOUTH);
        if (!BurpUi.confirm(api, panel, "Register Element")) return;
        Page page = rr != null ? matchPage(rr) : null;
        final EntityType type = page != null ? EntityType.PAGE : EntityType.PROJECT;
        final String id = page != null ? page.id : "";
        String body = "Element [" + kind.getSelectedItem() + "]: "
                + captured.getText().trim()
                + (note.getText().isBlank() ? "" : "\n" + note.getText().trim());
        run(() -> session.controller().addNote(type, id, Note.Kind.NOTE, body));
    }

    private void testParameter(HttpRequestResponse rr) {
        if (!requireProject() || rr == null) return;
        Page page = matchPage(rr);
        String pageId = page != null ? page.id : null;
        SwingUtilities.invokeLater(() ->
                new com.assessmentnotebook.burp.ui.ParameterTestDialog(api, session, rr, pageId)
                        .setVisible(true));
    }

    private void exportWordlists() {
        if (!requireProject()) return;
        run(() -> {
            java.util.List<String> files = session.controller().exportWordlists();
            String msg = files.isEmpty() ? "No interesting strings to export yet."
                    : "Wrote " + files.size() + " wordlist file(s):\n" + String.join("\n", files);
            javax.swing.SwingUtilities.invokeLater(() -> BurpUi.info(api, msg));
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
        if (!BurpUi.confirm(api, panel, "Add Observation") || text.getText().isBlank()) return;

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
        JTextField title = new JTextField(32);
        JComboBox<Vulnerability.Severity> sev = new JComboBox<>(Vulnerability.Severity.values());
        sev.setSelectedItem(Vulnerability.Severity.MEDIUM);
        JComboBox<Vulnerability.Status> status = new JComboBox<>(Vulnerability.Status.values());
        status.setSelectedItem(Vulnerability.Status.OPEN);
        JTextField url = new JTextField(rr != null ? rr.request().url() : "", 32);
        String comp = rr != null ? rr.request().method() + " " + rr.request().path() : "";
        JTextField component = new JTextField(comp, 32);
        JTextArea description = new JTextArea(3, 32);
        JTextArea observation = new JTextArea(3, 32);
        JTextArea steps = new JTextArea(4, 32);
        JTextArea impact = new JTextArea(3, 32);
        JTextArea remediation = new JTextArea(3, 32);
        JTextArea notes = new JTextArea(2, 32);

        JPanel panel = new JPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints g = new java.awt.GridBagConstraints();
        g.gridx = 0; g.gridy = 0; g.anchor = java.awt.GridBagConstraints.WEST;
        g.fill = java.awt.GridBagConstraints.HORIZONTAL; g.weightx = 1; g.insets = new java.awt.Insets(2, 2, 2, 2);
        addField(panel, g, "Title:", title);
        addField(panel, g, "Severity:", sev);
        addField(panel, g, "Status:", status);
        addField(panel, g, "Affected URL:", url);
        addField(panel, g, "Component (method / path / field):", component);
        addField(panel, g, "Description:", new JScrollPane(description));
        addField(panel, g, "Technical observation:", new JScrollPane(observation));
        addField(panel, g, "Steps to reproduce:", new JScrollPane(steps));
        addField(panel, g, "Impact:", new JScrollPane(impact));
        addField(panel, g, "Remediation:", new JScrollPane(remediation));
        addField(panel, g, "Notes:", new JScrollPane(notes));
        for (JTextArea a : new JTextArea[]{description, observation, steps, impact, remediation, notes}) {
            a.setLineWrap(true); a.setWrapStyleWord(true);
        }
        JScrollPane scroll = new JScrollPane(panel);
        scroll.setPreferredSize(new java.awt.Dimension(560, 560));
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        if (!BurpUi.confirm(api, scroll, "Create Vulnerability") || title.getText().isBlank()) return;
        Vulnerability v = new Vulnerability();
        v.title = title.getText().trim();
        v.severity = (Vulnerability.Severity) sev.getSelectedItem();
        v.status = (Vulnerability.Status) status.getSelectedItem();
        v.affectedUrl = url.getText().trim();
        v.affectedComponent = component.getText().trim();
        v.description = description.getText().trim();
        v.technicalObservation = observation.getText().trim();
        v.stepsToReproduce = steps.getText().trim();
        v.impact = impact.getText().trim();
        v.remediation = remediation.getText().trim();
        v.notes = notes.getText().trim();
        run(() -> session.controller().createVulnerability(v));
    }

    /** Add a "label above component" row to a GridBag panel and advance the row. */
    private static void addField(JPanel panel, java.awt.GridBagConstraints g, String label, JComponent field) {
        g.gridy++; panel.add(new JLabel(label), g);
        g.gridy++; panel.add(field, g);
    }

    private void captureScreenshot(HttpRequestResponse rr) {
        if (!requireProject()) return;
        Page page = rr != null ? matchPage(rr) : null;
        if (page == null) {
            BurpUi.info(api, "Register the page first, then capture screenshots for it.");
            return;
        }
        JTextField state = new JTextField("Default page", 24);
        JSpinner delay = new JSpinner(new SpinnerNumberModel(2, 0, 30, 1));
        JPanel panel = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
        panel.add(new JLabel("State description:"));
        panel.add(state);
        panel.add(new JLabel("Delay before capture (seconds) — arrange the window now:"));
        panel.add(delay);
        if (!BurpUi.confirm(api, panel, "Capture Screenshot")) return;
        int seconds = (Integer) delay.getValue();
        int seq = (int) session.controller().project().screenshots.stream()
                .filter(s -> page.id.equals(s.pageId)).count();
        new SwingWorker<Object[], Void>() {
            @Override protected Object[] doInBackground() throws Exception {
                byte[] png = ScreenshotCapture.captureFullScreen(seconds * 1000);
                var shot = session.controller().addScreenshot(page.id, png,
                        state.getText().trim(), seq, null);
                return new Object[]{png, shot.id};
            }
            @Override protected void done() {
                try {
                    Object[] r = get();
                    session.fireChanged();
                    byte[] png = (byte[]) r[0];
                    String shotId = (String) r[1];
                    api.logging().logToOutput("Assessment Notebook: captured screenshot for "
                            + page.url);
                    int annotate = JOptionPane.showConfirmDialog(BurpUi.owner(api),
                            "Screenshot captured. Annotate it now?", "Assessment Notebook",
                            JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                    if (annotate == JOptionPane.YES_OPTION) {
                        new ScreenshotAnnotator(api, session, shotId, png).setVisible(true);
                    }
                } catch (Exception ex) {
                    api.logging().logToError("Screenshot failed", ex);
                    BurpUi.error(api, "Capture failed: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void openDocs() {
        if (!requireProject()) return;
        java.nio.file.Path index = session.controller().layout().indexHtml();
        // Desktop.browse can block for seconds on Linux; keep it off the EDT.
        new SwingWorker<Boolean, Void>() {
            @Override protected Boolean doInBackground() {
                try {
                    if (!Desktop.isDesktopSupported()) return false;
                    Desktop.getDesktop().browse(index.toUri());
                    return true;
                } catch (Exception ex) {
                    return false;
                }
            }
            @Override protected void done() {
                try {
                    if (!get()) BurpUi.info(api, "Documentation: " + index);
                } catch (Exception ex) {
                    BurpUi.info(api, "Documentation: " + index);
                }
            }
        }.execute();
    }

    // ---- helpers ---------------------------------------------------------

    private void scanJs(HttpRequestResponse rr) {
        if (!requireProject()) return;
        if (rr == null || !rr.hasResponse()) {
            BurpUi.info(api, "Select a JavaScript response to scan.");
            return;
        }
        final String url = rr.request().url();
        final String body = rr.response().bodyToString();
        new SwingWorker<java.util.List<com.assessmentnotebook.analyze.JsScanner.Match>, Void>() {
            @Override protected java.util.List<com.assessmentnotebook.analyze.JsScanner.Match>
                    doInBackground() {
                return com.assessmentnotebook.analyze.JsScanner.scan(body);
            }
            @Override protected void done() {
                try {
                    var matches = get();
                    if (matches.isEmpty()) {
                        BurpUi.info(api, "No interesting data found in this script.");
                        return;
                    }
                    new JsScanDialog(api, session, url, body, matches).setVisible(true);
                } catch (Exception ex) {
                    api.logging().logToError("JS scan failed", ex);
                    BurpUi.error(api, "Scan failed: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private Page matchPage(HttpRequestResponse rr) {
        return session.controller().findPageFor(rr.request().url(), rr.request().method());
    }

    private boolean requireProject() {
        if (session.isOpen()) return true;
        BurpUi.warn(api, "Open or create a project in the Assessment Notebook tab first.");
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
                    BurpUi.error(api, "Action failed: " + ex.getMessage());
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
