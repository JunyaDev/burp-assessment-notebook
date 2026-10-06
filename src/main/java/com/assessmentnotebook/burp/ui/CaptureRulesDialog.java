package com.assessmentnotebook.burp.ui;

import burp.api.montoya.MontoyaApi;
import com.assessmentnotebook.analyze.RuleMatcher;
import com.assessmentnotebook.burp.AutoCaptureService;
import com.assessmentnotebook.burp.NotebookSession;
import com.assessmentnotebook.model.CaptureConfig;
import com.assessmentnotebook.model.CaptureRule;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Editor for the project's auto-capture rules: which traffic is registered
 * without a right-click, as what, and how repeat sightings are treated. Changes
 * apply when saved; nothing is captured while the master switch is off.
 */
public final class CaptureRulesDialog extends JDialog {
    private static final String[] COLUMNS = {"On", "Name", "Host", "Path", "Methods",
            "Content-Type", "Status", "Scope", "Tools", "Register as"};

    private final MontoyaApi api;
    private final NotebookSession session;
    private final CaptureConfig config;

    private final JCheckBox enabled = new JCheckBox("Register matching traffic automatically");
    private final JCheckBox collapseIds = new JCheckBox(
            "Treat URLs that differ only by an id as one page  (/users/17 = /users/42)");
    private final JSpinner maxVariants = new JSpinner(new SpinnerNumberModel(10, 1, 200, 1));
    private final RuleTableModel model = new RuleTableModel();
    private final JTable table = new JTable(model);

    public CaptureRulesDialog(MontoyaApi api, NotebookSession session) {
        super(BurpUi.owner(api), "Auto-Capture Rules", true);
        this.api = api;
        this.session = session;
        this.config = session.controller().captureConfig();
        build();
        pack();
        setSize(new Dimension(980, 520));
        setLocationRelativeTo(getOwner());
    }

    private void build() {
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        enabled.setSelected(config.enabled);
        // A fixed body width makes the label wrap instead of running off the dialog.
        JLabel hint = new JLabel("<html><body style='width:700px'>Rules are tried top to bottom and the first match decides, "
                + "so put <b>Ignore</b> rules above broader ones. A page already in the notebook is "
                + "not registered again; if it answers differently (status, parameters, auth, "
                + "structure) it is added as a page variant.</body></html>");
        RetroTheme.accent(hint, RetroTheme.Accent.HINT);
        JPanel north = new JPanel(new BorderLayout(4, 6));
        north.add(enabled, BorderLayout.NORTH);
        north.add(hint, BorderLayout.CENTER);
        content.add(north, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(7).setMaxWidth(60);
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.columnAtPoint(e.getPoint()) != 0) editSelected();
            }
        });
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(RetroTheme.panelBorder(api, "RULES"));
        content.add(scroll, BorderLayout.CENTER);

        JPanel side = new JPanel(new GridLayout(0, 1, 4, 4));
        side.add(button("Add…", this::addRule));
        side.add(button("Edit…", this::editSelected));
        side.add(button("Duplicate", this::duplicateSelected));
        side.add(button("Remove", this::removeSelected));
        side.add(button("Move Up", () -> move(-1)));
        side.add(button("Move Down", () -> move(1)));
        JPanel sideWrap = new JPanel(new BorderLayout());
        sideWrap.add(side, BorderLayout.NORTH);
        content.add(sideWrap, BorderLayout.EAST);

        collapseIds.setSelected(config.collapseIds);
        maxVariants.setValue(Math.max(1, Math.min(200, config.maxVariantsPerPage)));
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        options.add(collapseIds);
        options.add(new JLabel("   Variants kept per page:"));
        options.add(maxVariants);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton history = button("Apply to Proxy History…", this::applyToHistory);
        history.setToolTipText("Save the rules and run them over everything already in Proxy "
                + "history, so traffic browsed earlier is registered too.");
        JButton save = button("Save", () -> { if (save()) dispose(); });
        buttons.add(history);
        buttons.add(button("Cancel", this::dispose));
        buttons.add(save);

        JPanel south = new JPanel(new BorderLayout());
        south.add(options, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);
        content.add(south, BorderLayout.SOUTH);

        setContentPane(content);
        RetroTheme.apply(api, content);
        Color bg = RetroTheme.background(api);
        if (bg != null) content.setBackground(bg);
        getRootPane().setDefaultButton(save);
    }

    private static JButton button(String label, Runnable action) {
        JButton b = new JButton(label);
        b.addActionListener(e -> action.run());
        return b;
    }

    // ---- rule list -------------------------------------------------------

    private void addRule() {
        CaptureRule rule = new CaptureRule();
        rule.name = "Rule " + (config.rules.size() + 1);
        String domain = session.controller().read(p -> p.target.domain);
        if (domain != null && !domain.isBlank()) rule.host = domain.trim() + ", *." + domain.trim();
        if (!editRule(api, rule, "Add Rule")) return;
        config.rules.add(rule);
        model.fireTableDataChanged();
        select(config.rules.size() - 1);
    }

    private void editSelected() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        CaptureRule copy = config.rules.get(row).copy();
        if (!editRule(api, copy, "Edit Rule")) return;
        config.rules.set(row, copy);
        model.fireTableDataChanged();
        select(row);
    }

    private void duplicateSelected() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        CaptureRule copy = config.rules.get(row).copy();
        copy.name = copy.name + " (copy)";
        config.rules.add(row + 1, copy);
        model.fireTableDataChanged();
        select(row + 1);
    }

    private void removeSelected() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        config.rules.remove(row);
        model.fireTableDataChanged();
        if (!config.rules.isEmpty()) select(Math.min(row, config.rules.size() - 1));
    }

    private void move(int delta) {
        int row = table.getSelectedRow();
        int target = row + delta;
        if (row < 0 || target < 0 || target >= config.rules.size()) return;
        java.util.Collections.swap(config.rules, row, target);
        model.fireTableDataChanged();
        select(target);
    }

    private void select(int row) {
        table.getSelectionModel().setSelectionInterval(row, row);
    }

    // ---- saving ----------------------------------------------------------

    /** Persist the edited settings; false (with a message) if that failed. */
    private boolean save() {
        if (!session.isOpen()) return false;
        config.enabled = enabled.isSelected();
        config.collapseIds = collapseIds.isSelected();
        config.maxVariantsPerPage = (Integer) maxVariants.getValue();
        if (config.enabled && config.rules.stream().noneMatch(r -> r.enabled
                && r.action != CaptureRule.Action.IGNORE)) {
            JOptionPane.showMessageDialog(this, "Auto-capture is on, but no enabled rule "
                    + "registers anything yet. Add a rule or nothing will be captured.",
                    "Assessment Notebook", JOptionPane.WARNING_MESSAGE);
        }
        try {
            session.controller().applyCaptureConfig(config);
            session.fireChanged();
            return true;
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not save the rules: " + ex.getMessage(),
                    "Assessment Notebook", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private void applyToHistory() {
        AutoCaptureService service = session.capture();
        if (service == null) return;
        if (config.rules.stream().noneMatch(r -> r.enabled && r.fromProxy
                && r.action != CaptureRule.Action.IGNORE)) {
            JOptionPane.showMessageDialog(this, "No enabled rule listens to Proxy traffic.",
                    "Assessment Notebook", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int ok = JOptionPane.showConfirmDialog(this, "Save these rules and register everything "
                + "in Proxy history that matches them?\nNothing is sent to the target; only "
                + "traffic Burp already recorded is read.", "Apply to Proxy History",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ok != JOptionPane.OK_OPTION || !save()) return;
        dispose();
        new SwingWorker<AutoCaptureService.ReplayResult, Void>() {
            @Override protected AutoCaptureService.ReplayResult doInBackground() {
                return service.replayProxyHistory();
            }
            @Override protected void done() {
                try {
                    AutoCaptureService.ReplayResult r = get();
                    api.logging().logToOutput("Assessment Notebook: " + r.queued + " of "
                            + r.examined + " Proxy history items matched the auto-capture rules.");
                    BurpUi.info(api, r.queued + " of " + r.examined + " Proxy history items "
                            + "matched the rules.\nThey are being registered in the background; "
                            + "the tab's status line shows progress.");
                } catch (Exception ex) {
                    api.logging().logToError("Applying rules to Proxy history failed", ex);
                    BurpUi.error(api, "Could not read Proxy history: " + ex.getMessage());
                }
            }
        }.execute();
    }

    // ---- rule editor (also used by the context menu) -----------------------

    /**
     * Show the form for one rule, editing {@code rule} in place. Returns false
     * if the tester cancelled; an invalid rule is reported and the form reopens.
     */
    public static boolean editRule(MontoyaApi api, CaptureRule rule, String title) {
        JTextField name = new JTextField(rule.name, 30);
        JTextField host = new JTextField(rule.host, 30);
        JTextField path = new JTextField(rule.path, 30);
        JTextField methods = new JTextField(rule.methods, 30);
        JTextField contentType = new JTextField(rule.contentType, 30);
        JTextField status = new JTextField(rule.status, 30);
        JCheckBox scope = new JCheckBox("Only URLs in Burp's target scope", rule.inScopeOnly);
        JCheckBox proxy = new JCheckBox("Proxy", rule.fromProxy);
        JCheckBox repeater = new JCheckBox("Repeater", rule.fromRepeater);
        JComboBox<CaptureRule.Action> action = new JComboBox<>(CaptureRule.Action.values());
        action.setSelectedItem(rule.action);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 3, 3, 3);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.gridy = -1;
        field(form, g, "Name:", name, null);
        field(form, g, "Host:", host, "app.example.com, *.example.com — blank for any");
        field(form, g, "Path:", path, "/api/*  or  re:^/v\\d+/users — blank for any");
        field(form, g, "Methods:", methods, "GET, POST — blank for any");
        field(form, g, "Response type:", contentType, "json, html — part of Content-Type; blank for any");
        field(form, g, "Status:", status, "200, 3xx, 400-404 — blank for any");
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        tools.add(proxy);
        tools.add(repeater);
        field(form, g, "Traffic from:", tools, null);
        field(form, g, "", scope, null);
        field(form, g, "Register as:", action,
                "Auto-detect files each match as a page, an API endpoint or a resource");
        RetroTheme.apply(api, form);

        while (true) {
            if (!BurpUi.confirm(api, form, title)) return false;
            CaptureRule edited = rule.copy();
            edited.name = name.getText().trim();
            edited.host = host.getText().trim();
            edited.path = path.getText().trim();
            edited.methods = methods.getText().trim();
            edited.contentType = contentType.getText().trim();
            edited.status = status.getText().trim();
            edited.inScopeOnly = scope.isSelected();
            edited.fromProxy = proxy.isSelected();
            edited.fromRepeater = repeater.isSelected();
            edited.action = (CaptureRule.Action) action.getSelectedItem();
            String problem = RuleMatcher.validate(edited);
            if (problem != null) {
                BurpUi.warn(api, problem);
                continue;
            }
            rule.name = edited.name.isEmpty() ? describe(edited) : edited.name;
            rule.host = edited.host;
            rule.path = edited.path;
            rule.methods = edited.methods;
            rule.contentType = edited.contentType;
            rule.status = edited.status;
            rule.inScopeOnly = edited.inScopeOnly;
            rule.fromProxy = edited.fromProxy;
            rule.fromRepeater = edited.fromRepeater;
            rule.action = edited.action;
            return true;
        }
    }

    private static void field(JPanel form, GridBagConstraints g, String label, JComponent field,
            String hint) {
        g.gridy++;
        g.gridx = 0; g.weightx = 0;
        form.add(new JLabel(label), g);
        g.gridx = 1; g.weightx = 1;
        form.add(field, g);
        if (hint != null) {
            g.gridy++;
            JLabel h = new JLabel(hint);
            RetroTheme.accent(h, RetroTheme.Accent.HINT);
            form.add(h, g);
        }
    }

    /** A name for a rule the tester left unnamed. */
    private static String describe(CaptureRule r) {
        String where = (r.host.isEmpty() ? "any host" : r.host) + (r.path.isEmpty() ? "" : " " + r.path);
        return r.action.label + ": " + where;
    }

    private final class RuleTableModel extends AbstractTableModel {
        @Override public int getRowCount() { return config.rules.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }

        @Override public Class<?> getColumnClass(int column) {
            return column == 0 || column == 7 ? Boolean.class : String.class;
        }

        @Override public boolean isCellEditable(int row, int column) { return column == 0; }

        @Override public Object getValueAt(int row, int column) {
            CaptureRule r = config.rules.get(row);
            switch (column) {
                case 0: return r.enabled;
                case 1: return r.name;
                case 2: return any(r.host);
                case 3: return any(r.path);
                case 4: return any(r.methods);
                case 5: return any(r.contentType);
                case 6: return any(r.status);
                case 7: return r.inScopeOnly;
                case 8: return (r.fromProxy ? "Proxy" : "")
                        + (r.fromProxy && r.fromRepeater ? " + " : "")
                        + (r.fromRepeater ? "Repeater" : "");
                default: return r.action.label;
            }
        }

        @Override public void setValueAt(Object value, int row, int column) {
            if (column == 0) config.rules.get(row).enabled = Boolean.TRUE.equals(value);
        }

        private String any(String s) { return s == null || s.isBlank() ? "any" : s; }
    }
}
