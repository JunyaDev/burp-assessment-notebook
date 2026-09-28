package com.assessmentnotebook.burp.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.params.ParsedHttpParameter;
import com.assessmentnotebook.analyze.JsonParameters;
import com.assessmentnotebook.analyze.ProbeGenerator;
import com.assessmentnotebook.burp.NotebookSession;
import com.assessmentnotebook.burp.ParameterTester;
import com.assessmentnotebook.model.ParameterTest;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Quick parameter testing (spec §14–§18). The tester picks a parameter (query,
 * body or JSON), chooses which non-destructive probes to run, and — only after
 * confirming authorization — sends them. Results are saved into the project in
 * one batch; a status line shows which probe is in flight.
 */
public final class ParameterTestDialog extends JDialog {
    private final MontoyaApi api;
    private final NotebookSession session;
    private final HttpRequestResponse rr;
    private final String pageId;

    private final DefaultComboBoxModel<Item> paramModel = new DefaultComboBoxModel<>();
    private final JComboBox<Item> params = new JComboBox<>(paramModel);
    private final List<JCheckBox> probeBoxes = new ArrayList<>();
    private final JCheckBox authorized = new JCheckBox(
            "I am authorized to send test requests to this target");
    private final JLabel status = new JLabel(" ");
    private JButton run;

    private static final class Item {
        final String name; final ParameterTest.Source source;
        Item(String name, ParameterTest.Source source) { this.name = name; this.source = source; }
        @Override public String toString() { return source + "  " + name; }
    }

    public ParameterTestDialog(MontoyaApi api, NotebookSession session, HttpRequestResponse rr,
            String pageId) {
        super(BurpUi.owner(api), "Test Parameter", true);
        this.api = api;
        this.session = session;
        this.rr = rr;
        this.pageId = pageId;
        collectParameters();
        build();
        pack();
        setSize(new Dimension(560, getHeight()));
        setLocationRelativeTo(getOwner());
    }

    private void collectParameters() {
        try {
            for (ParsedHttpParameter p : rr.request().parameters()) {
                ParameterTest.Source src;
                switch (p.type()) {
                    case URL: src = ParameterTest.Source.QUERY; break;
                    case BODY: src = ParameterTest.Source.BODY; break;
                    default: continue; // cookies etc. not tested here
                }
                paramModel.addElement(new Item(p.name(), src));
            }
        } catch (RuntimeException ignored) { /* fall through to JSON */ }

        String body = rr.request().bodyToString();
        for (String path : JsonParameters.flatten(body).keySet()) {
            paramModel.addElement(new Item(path, ParameterTest.Source.JSON));
        }
    }

    private void build() {
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        top.add(new JLabel("Parameter:"));
        top.add(params);
        content.add(top, BorderLayout.NORTH);

        Set<ProbeGenerator.Kind> def = ProbeGenerator.defaultKinds();
        JPanel probes = new JPanel(new GridLayout(0, 2, 4, 2));
        probes.setBorder(RetroTheme.panelBorder(api, "PROBES (non-destructive)"));
        JPanel sqli = new JPanel(new GridLayout(0, 2, 4, 2));
        sqli.setBorder(RetroTheme.panelBorder(api, "SQL INJECTION (read-only, opt-in)"));
        for (ProbeGenerator.Kind k : ProbeGenerator.Kind.values()) {
            JCheckBox cb = new JCheckBox(k.label, !k.sqli && def.contains(k));
            cb.putClientProperty("kind", k);
            probeBoxes.add(cb);
            (k.sqli ? sqli : probes).add(cb);
        }
        JPanel groups = new JPanel();
        groups.setLayout(new BoxLayout(groups, BoxLayout.Y_AXIS));
        groups.add(probes);
        groups.add(sqli);
        content.add(groups, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        RetroTheme.accent(authorized, RetroTheme.Accent.WARN);
        south.add(authorized, BorderLayout.NORTH);
        RetroTheme.accent(status, RetroTheme.Accent.HINT);
        south.add(status, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        run = new JButton("Run probes");
        run.addActionListener(e -> commit());
        buttons.add(cancel);
        buttons.add(run);
        south.add(buttons, BorderLayout.SOUTH);
        content.add(south, BorderLayout.SOUTH);

        setContentPane(content);
        RetroTheme.apply(api, content);
        Color bg = RetroTheme.background(api);
        if (bg != null) content.setBackground(bg);
        getRootPane().setDefaultButton(run);
        if (paramModel.getSize() == 0) {
            status.setText("No query/body/JSON parameters detected in this request.");
            run.setEnabled(false);
        }
    }

    private void commit() {
        Item item = (Item) params.getSelectedItem();
        if (item == null) { dispose(); return; }
        if (!authorized.isSelected()) {
            JOptionPane.showMessageDialog(this,
                    "Confirm you are authorized before sending test requests.",
                    "Assessment Notebook", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Set<ProbeGenerator.Kind> kinds = EnumSet.noneOf(ProbeGenerator.Kind.class);
        for (JCheckBox cb : probeBoxes) {
            if (cb.isSelected()) kinds.add((ProbeGenerator.Kind) cb.getClientProperty("kind"));
        }
        if (kinds.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Select at least one probe.");
            return;
        }
        List<ProbeGenerator.Probe> probes = ProbeGenerator.generate(itemValue(item), kinds);
        setBusy(true);
        new SwingWorker<List<ParameterTest>, String>() {
            @Override protected List<ParameterTest> doInBackground() throws Exception {
                return new ParameterTester(api, session.controller())
                        .run(rr, item.name, item.source, probes, pageId, null, this::publish);
            }
            @Override protected void process(List<String> lines) {
                status.setText(lines.get(lines.size() - 1));
            }
            @Override protected void done() {
                try {
                    List<ParameterTest> results = get();
                    session.fireChanged();
                    boolean linked = results.stream().anyMatch(t -> t.parameterId != null);
                    JOptionPane.showMessageDialog(ParameterTestDialog.this,
                            "Ran " + results.size() + " probe(s) on " + item.name
                            + ". Results are saved in the project"
                            + (linked ? " and shown on the parameter's form document."
                                      : " (project.json); no form parameter of that name"
                                        + " was found on the page to attach them to."),
                            "Assessment Notebook", JOptionPane.INFORMATION_MESSAGE);
                    dispose();
                } catch (Exception ex) {
                    setBusy(false);
                    api.logging().logToError("Parameter test failed", ex);
                    JOptionPane.showMessageDialog(ParameterTestDialog.this,
                            "Testing failed: " + ex.getMessage(),
                            "Assessment Notebook", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void setBusy(boolean busy) {
        run.setEnabled(!busy);
        params.setEnabled(!busy);
        authorized.setEnabled(!busy);
        for (JCheckBox cb : probeBoxes) cb.setEnabled(!busy);
        setCursor(Cursor.getPredefinedCursor(busy ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
        if (!busy) status.setText(" ");
    }

    private String itemValue(Item item) {
        try {
            if (item.source == ParameterTest.Source.JSON) {
                return JsonParameters.flatten(rr.request().bodyToString())
                        .getOrDefault(item.name, "");
            }
            for (ParsedHttpParameter p : rr.request().parameters()) {
                if (p.name().equals(item.name)) return p.value();
            }
        } catch (RuntimeException ignored) { }
        return "";
    }
}
