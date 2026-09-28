package com.assessmentnotebook.burp.ui;

import burp.api.montoya.MontoyaApi;
import com.assessmentnotebook.analyze.ProjectMetadataInference;
import com.assessmentnotebook.burp.NotebookSession;
import com.assessmentnotebook.model.TargetInfo;

import javax.swing.*;
import java.awt.*;

/**
 * A guided project setup (spec §4). Each field is explained, and Domain, Main
 * URL, Server and Frameworks are pre-filled from traffic already captured so the
 * tester corrects rather than guesses. Everything remains editable afterward via
 * this same dialog.
 */
public final class ProjectSetupWizard extends JDialog {
    private final MontoyaApi api;
    private final NotebookSession session;

    private final JTextField domain = new JTextField(30);
    private final JTextField mainUrl = new JTextField(30);
    private final JTextField server = new JTextField(30);
    private final JTextField frameworks = new JTextField(30);
    private final JTextField auth = new JTextField(30);
    private final JTextField name = new JTextField(30);
    private final JTextField date = new JTextField(30);
    private final JTextArea notes = new JTextArea(4, 30);
    private JButton save;

    public ProjectSetupWizard(MontoyaApi api, NotebookSession session) {
        super(BurpUi.owner(api), "Project Setup Wizard", true);
        this.api = api;
        this.session = session;
        build();
        prefill();
        pack();
        setSize(new Dimension(680, getHeight()));
        setLocationRelativeTo(getOwner());
    }

    private void build() {
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel intro = new JLabel("<html>Confirm the target facts below. Fields are pre-filled "
                + "from captured traffic where possible; edit anything.</html>");
        RetroTheme.accent(intro, RetroTheme.Accent.HINT);
        content.add(intro, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(RetroTheme.panelBorder(api, "PROJECT"));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 4, 3, 4);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        int y = 0;
        y = addRow(form, g, y, "Domain", domain, "The primary host in scope, e.g. portal.acme.test");
        y = addRow(form, g, y, "Main URL", mainUrl, "The application's entry point URL");
        y = addRow(form, g, y, "Assessment name", name, "A label for this engagement");
        y = addRow(form, g, y, "Date", date, "When the assessment was performed");
        y = addRow(form, g, y, "Server", server, "Web server / OS, from Server headers");
        y = addRow(form, g, y, "Frameworks", frameworks, "Detected frameworks and languages");
        y = addRow(form, g, y, "Authentication", auth, "How the app authenticates users");

        g.gridx = 0; g.gridy = y; g.anchor = GridBagConstraints.NORTHWEST;
        form.add(new JLabel("Notes"), g);
        g.gridx = 1; g.weightx = 1;
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        form.add(new JScrollPane(notes), g);

        content.add(form, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton refresh = new JButton("Re-detect from traffic");
        refresh.addActionListener(e -> prefill());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        save = new JButton("Save");
        save.addActionListener(e -> commit());
        buttons.add(refresh);
        buttons.add(cancel);
        buttons.add(save);
        content.add(buttons, BorderLayout.SOUTH);

        setContentPane(content);
        RetroTheme.apply(api, content);
        Color bg = RetroTheme.background(api);
        if (bg != null) content.setBackground(bg);
        getRootPane().setDefaultButton(save);
    }

    private int addRow(JPanel form, GridBagConstraints g, int y, String label, JComponent field,
                       String hint) {
        g.gridx = 0; g.gridy = y; g.weightx = 0;
        form.add(new JLabel(label), g);
        g.gridx = 1; g.weightx = 1;
        form.add(field, g);
        g.gridx = 2; g.weightx = 0;
        JLabel h = new JLabel(hint);
        RetroTheme.accent(h, RetroTheme.Accent.HINT);
        form.add(h, g);
        return y + 1;
    }

    /** Merge current TargetInfo with fresh inference, keeping non-blank user values. */
    private void prefill() {
        if (!session.isOpen()) return;
        TargetInfo t = session.controller().project().target;
        ProjectMetadataInference.Suggestion s = session.controller().suggestProjectMetadata();
        domain.setText(firstNonBlank(t.domain, s.domain));
        mainUrl.setText(firstNonBlank(t.mainUrl, s.mainUrl));
        server.setText(firstNonBlank(t.server, s.server));
        frameworks.setText(firstNonBlank(t.frameworks, s.frameworks));
        auth.setText(firstNonBlank(t.authentication, s.authentication));
        name.setText(t.assessmentName);
        date.setText(t.assessmentDate);
        notes.setText(t.notes);
    }

    private void commit() {
        if (!session.isOpen()) { dispose(); return; }
        TargetInfo t = new TargetInfo();
        t.domain = domain.getText().trim();
        t.mainUrl = mainUrl.getText().trim();
        t.assessmentName = name.getText().trim();
        t.assessmentDate = date.getText().trim();
        t.server = server.getText().trim();
        t.frameworks = frameworks.getText().trim();
        t.authentication = auth.getText().trim();
        t.notes = notes.getText().trim();
        save.setEnabled(false);
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                session.controller().applyProjectSetup(t);
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    session.fireChanged();
                    dispose();
                } catch (Exception ex) {
                    save.setEnabled(true);
                    JOptionPane.showMessageDialog(ProjectSetupWizard.this,
                            "Could not save: " + ex.getMessage(),
                            "Assessment Notebook", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : (b == null ? "" : b);
    }
}
