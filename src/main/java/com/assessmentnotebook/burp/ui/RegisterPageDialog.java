package com.assessmentnotebook.burp.ui;

import burp.api.montoya.MontoyaApi;
import com.assessmentnotebook.analyze.DiscoveredPage;
import com.assessmentnotebook.burp.NotebookSession;
import com.assessmentnotebook.core.PageRegistration;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Presents an analyzed request/response for review before it is committed. The
 * tester sees the detected forms, links and resources and unticks anything that
 * should not be recorded, satisfying the rule that nothing is registered
 * without confirmation.
 */
public final class RegisterPageDialog extends JDialog {
    private final NotebookSession session;
    private final MontoyaApi api;
    private final PageRegistration reg;

    private final JComboBox<com.assessmentnotebook.model.DiscoverySource> discoveryKind =
            new JComboBox<>(com.assessmentnotebook.model.DiscoverySource.values());
    private final JTextField discovery = new JTextField(22);
    private final JComboBox<com.assessmentnotebook.model.Page.Kind> kind =
            new JComboBox<>(com.assessmentnotebook.model.Page.Kind.values());
    private final JCheckBox annotate = new JCheckBox(
            "Then describe the parameters (purpose / notes)", false);
    private final List<JCheckBox> formBoxes = new ArrayList<>();
    private final List<JCheckBox> linkBoxes = new ArrayList<>();
    private final List<JCheckBox> resourceBoxes = new ArrayList<>();
    private final JCheckBox captureBodies = new JCheckBox(
            "Capture ticked resource bodies (Burp history; fetch any unseen)", false);

    public RegisterPageDialog(MontoyaApi api, NotebookSession session, PageRegistration reg) {
        this(api, session, reg, false);
    }

    /**
     * @param annotateAfter preselect "describe the parameters" (opened after a
     *                      successful registration), for form-focused capture
     */
    public RegisterPageDialog(MontoyaApi api, NotebookSession session, PageRegistration reg,
            boolean annotateAfter) {
        super(BurpUi.owner(api), reg.kind == com.assessmentnotebook.model.Page.Kind.API
                ? "Register API Endpoint" : "Register Page", true);
        this.api = api;
        this.session = session;
        this.reg = reg;
        this.annotate.setSelected(annotateAfter);
        build();
        pack();
        setSize(new Dimension(720, Math.min(760, getHeight() + 40)));
        setLocationRelativeTo(getOwner());
    }

    private void build() {
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Summary.
        JPanel summary = new JPanel(new GridLayout(0, 1, 2, 2));
        summary.setBorder(RetroTheme.panelBorder(api, "PAGE"));
        summary.add(new JLabel(reg.method + "  " + reg.url));
        JPanel what = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        what.add(new JLabel("Register as:"));
        kind.setSelectedItem(reg.kind);
        kind.setToolTipText("An API endpoint (JSON/XHR data call) is documented once, with its "
                + "request parameters, response fields and the page that calls it — no need to "
                + "also register it as a resource or a form.");
        what.add(kind);
        what.add(new JLabel("   Status: " + reg.statusCode + "    Content-Type: "
                + (reg.contentType == null ? "" : reg.contentType)));
        summary.add(what);
        JPanel disc = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        disc.add(new JLabel("Discovered via:"));
        discoveryKind.setSelectedItem(reg.discoverySourceKind);
        disc.add(discoveryKind);
        disc.add(new JLabel("detail:"));
        discovery.setText(reg.discoverySource == null ? "" : reg.discoverySource);
        disc.add(discovery);
        summary.add(disc);
        content.add(summary, BorderLayout.NORTH);

        DiscoveredPage d = reg.discovered != null ? reg.discovered : new DiscoveredPage();
        JPanel lists = new JPanel(new GridLayout(1, 3, 8, 8));
        lists.add(checklist("FORMS (" + d.forms.size() + ")", formLabels(d), formBoxes));
        lists.add(checklist("LINKS (" + d.links.size() + ")", linkLabels(d), linkBoxes));
        lists.add(checklist("RESOURCES (" + d.resources.size() + ")", resourceLabels(d), resourceBoxes));
        content.add(lists, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JButton register = new JButton("Register");
        register.addActionListener(e -> commit());
        buttons.add(cancel);
        buttons.add(register);
        captureBodies.setToolTipText("Save each ticked resource's actual content into "
                + "the notebook, not just its URL. Uses the copy Burp already captured "
                + "while you browsed; only resources never seen trigger a single GET.");
        annotate.setToolTipText("After registering, open the parameter table for this page's "
                + "forms so you can note what each parameter is for.");
        // Options above, buttons below: side by side they overlap in a narrow dialog.
        JPanel south = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new GridLayout(0, 1, 0, 2));
        left.add(captureBodies);
        left.add(annotate);
        south.add(left, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);
        content.add(south, BorderLayout.SOUTH);

        setContentPane(content);
        RetroTheme.apply(api, content);
        Color bg = RetroTheme.background(api);
        if (bg != null) content.setBackground(bg);
        getRootPane().setDefaultButton(register);
    }

    private JComponent checklist(String title, List<String> labels, List<JCheckBox> out) {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        if (labels.isEmpty()) {
            JLabel none = new JLabel("(none detected)");
            RetroTheme.accent(none, RetroTheme.Accent.HINT);
            box.add(none);
        }
        for (String label : labels) {
            JCheckBox cb = new JCheckBox(label, true);
            out.add(cb);
            box.add(cb);
        }
        JScrollPane scroll = new JScrollPane(box);
        scroll.setBorder(RetroTheme.panelBorder(api, title));
        return scroll;
    }

    private static List<String> formLabels(DiscoveredPage d) {
        List<String> out = new ArrayList<>();
        for (DiscoveredPage.DiscoveredForm f : d.forms) {
            out.add(f.method + " " + f.action + "  (" + f.inputs.size() + " inputs)");
        }
        return out;
    }

    private static List<String> linkLabels(DiscoveredPage d) {
        List<String> out = new ArrayList<>();
        for (DiscoveredPage.DiscoveredLink l : d.links) {
            out.add(l.elementType + ": " + l.url);
        }
        return out;
    }

    private static List<String> resourceLabels(DiscoveredPage d) {
        List<String> out = new ArrayList<>();
        for (DiscoveredPage.DiscoveredResource r : d.resources) {
            out.add(r.type + ": " + r.url);
        }
        return out;
    }

    /** Build a pruned DiscoveredPage from the ticked boxes and register it. */
    private void commit() {
        if (!session.isOpen()) {
            JOptionPane.showMessageDialog(this, "Open a project first.");
            return;
        }
        DiscoveredPage original = reg.discovered != null ? reg.discovered : new DiscoveredPage();
        DiscoveredPage pruned = new DiscoveredPage();
        pruned.title = original.title;
        pruned.contentType = original.contentType;
        for (int i = 0; i < original.forms.size(); i++) {
            if (formBoxes.get(i).isSelected()) pruned.forms.add(original.forms.get(i));
        }
        for (int i = 0; i < original.links.size(); i++) {
            if (linkBoxes.get(i).isSelected()) pruned.links.add(original.links.get(i));
        }
        for (int i = 0; i < original.resources.size(); i++) {
            if (resourceBoxes.get(i).isSelected()) pruned.resources.add(original.resources.get(i));
        }
        reg.discovered = pruned;
        reg.discoverySourceKind =
                (com.assessmentnotebook.model.DiscoverySource) discoveryKind.getSelectedItem();
        reg.discoverySource = discovery.getText().trim();
        reg.kind = (com.assessmentnotebook.model.Page.Kind) kind.getSelectedItem();

        setEnabled(false);
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() throws Exception {
                if (captureBodies.isSelected() && reg.discovered != null) {
                    // History first, live fetch as fallback (off the EDT).
                    new com.assessmentnotebook.burp.ResourceBodyFetcher(api)
                            .capture(reg.discovered, true);
                }
                return new ArrayList<>(session.controller().registerPage(reg).formIds);
            }
            @Override protected void done() {
                try {
                    List<String> formIds = get();
                    session.fireChanged();
                    api.logging().logToOutput("Assessment Notebook: registered " + reg.url);
                    dispose();
                    if (annotate.isSelected() && !formIds.isEmpty()) {
                        new ParameterNotesDialog(api, session, formIds).setVisible(true);
                    }
                } catch (Exception ex) {
                    setEnabled(true);
                    JOptionPane.showMessageDialog(RegisterPageDialog.this,
                            "Registration failed: " + ex.getMessage(),
                            "Assessment Notebook", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
}
