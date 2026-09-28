package com.assessmentnotebook.burp.ui;

import burp.api.montoya.MontoyaApi;
import com.assessmentnotebook.analyze.JsScanner;
import com.assessmentnotebook.burp.NotebookSession;
import com.assessmentnotebook.model.JsFinding;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Review dialog for JavaScript scan results. The tester sees every candidate
 * grouped by kind (endpoints, secrets, dangerous calls, exports, bypass hints),
 * unticks the noise, and saves the rest. Saved findings attach to the script
 * resource and appear on every page that loads it.
 */
public final class JsScanDialog extends JDialog {
    private final MontoyaApi api;
    private final NotebookSession session;
    private final String scriptUrl;
    private final String jsBody;
    private final List<JsScanner.Match> matches;
    private final List<JCheckBox> boxes = new ArrayList<>();

    public JsScanDialog(MontoyaApi api, NotebookSession session, String scriptUrl,
            String jsBody, List<JsScanner.Match> matches) {
        super(BurpUi.owner(api), "Scan JS for interesting data", true);
        this.api = api;
        this.session = session;
        this.scriptUrl = scriptUrl;
        this.jsBody = jsBody;
        this.matches = matches;
        build();
        pack();
        setSize(new Dimension(760, Math.min(720, getHeight() + 40)));
        setLocationRelativeTo(getOwner());
    }

    private void build() {
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel head = new JLabel(matches.size() + " candidate(s) in " + scriptUrl);
        content.add(head, BorderLayout.NORTH);

        JPanel groups = new JPanel();
        groups.setLayout(new BoxLayout(groups, BoxLayout.Y_AXIS));
        Map<JsFinding.Kind, List<JsScanner.Match>> byKind = new LinkedHashMap<>();
        for (JsFinding.Kind k : JsFinding.Kind.values()) {
            List<JsScanner.Match> in = new ArrayList<>();
            for (JsScanner.Match m : matches) if (m.kind == k) in.add(m);
            if (!in.isEmpty()) byKind.put(k, in);
        }
        for (Map.Entry<JsFinding.Kind, List<JsScanner.Match>> e : byKind.entrySet()) {
            JPanel grp = new JPanel(new GridLayout(0, 1, 2, 1));
            grp.setBorder(RetroTheme.panelBorder(api, e.getKey().label + " (" + e.getValue().size() + ")"));
            for (JsScanner.Match m : e.getValue()) {
                String label = m.value + "   [" + m.detail
                        + (m.line > 0 ? " · line " + m.line : "") + "]";
                JCheckBox cb = new JCheckBox(label, true);
                cb.putClientProperty("match", m);
                boxes.add(cb);
                grp.add(cb);
            }
            groups.add(grp);
        }
        JScrollPane scroll = new JScrollPane(groups);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        content.add(scroll, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        JPanel select = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton all = new JButton("Select all");
        all.addActionListener(e -> boxes.forEach(b -> b.setSelected(true)));
        JButton none = new JButton("Select none");
        none.addActionListener(e -> boxes.forEach(b -> b.setSelected(false)));
        select.add(all); select.add(none);
        south.add(select, BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JButton save = new JButton("Save selected");
        save.addActionListener(e -> commit());
        buttons.add(cancel); buttons.add(save);
        south.add(buttons, BorderLayout.EAST);
        content.add(south, BorderLayout.SOUTH);

        setContentPane(content);
        RetroTheme.apply(api, content);
        Color bg = RetroTheme.background(api);
        if (bg != null) content.setBackground(bg);
        getRootPane().setDefaultButton(save);
    }

    private void commit() {
        if (!session.isOpen()) { JOptionPane.showMessageDialog(this, "Open a project first."); return; }
        List<JsFinding> selected = new ArrayList<>();
        for (JCheckBox cb : boxes) {
            if (!cb.isSelected()) continue;
            JsScanner.Match m = (JsScanner.Match) cb.getClientProperty("match");
            JsFinding f = new JsFinding();
            f.kind = m.kind;
            f.value = m.value;
            f.detail = m.detail;
            f.context = m.context;
            f.lineNumber = m.line;
            selected.add(f);
        }
        if (selected.isEmpty()) { JOptionPane.showMessageDialog(this, "Select at least one item."); return; }
        setEnabled(false);
        new SwingWorker<List<JsFinding>, Void>() {
            @Override protected List<JsFinding> doInBackground() throws Exception {
                return session.controller().recordJsFindings(scriptUrl, null, jsBody, selected);
            }
            @Override protected void done() {
                try {
                    List<JsFinding> saved = get();
                    session.fireChanged();
                    api.logging().logToOutput("Assessment Notebook: saved " + saved.size()
                            + " JS finding(s) from " + scriptUrl);
                    dispose();
                } catch (Exception ex) {
                    setEnabled(true);
                    api.logging().logToError("Saving JS findings failed", ex);
                    JOptionPane.showMessageDialog(JsScanDialog.this,
                            "Save failed: " + ex.getMessage(), "Assessment Notebook",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
}
