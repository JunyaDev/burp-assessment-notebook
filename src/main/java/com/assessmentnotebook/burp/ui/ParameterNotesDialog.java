package com.assessmentnotebook.burp.ui;

import burp.api.montoya.MontoyaApi;
import com.assessmentnotebook.analyze.ParameterPurposeGuess;
import com.assessmentnotebook.burp.NotebookSession;
import com.assessmentnotebook.core.NotebookController.ParameterEdit;
import com.assessmentnotebook.model.Form;
import com.assessmentnotebook.model.Page;
import com.assessmentnotebook.model.Parameter;
import com.assessmentnotebook.model.Project;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the tester writes down what each parameter is for. Lists a form's
 * parameters with an editable purpose and notes, across one or many forms, and
 * saves them into the notebook (they render on the form and page documents).
 *
 * <p>The window is not modal, so requests can be inspected in Burp while
 * annotating.
 */
public final class ParameterNotesDialog extends JDialog {
    private static final String[] COLUMNS = {"Parameter", "Type", "Example values", "Purpose", "Notes"};

    /** One parameter as shown in the table; {@code purpose}/{@code notes} hold the edits. */
    private static final class ParamRow {
        String id;
        String name;
        String type;
        String examples;
        String originalPurpose;
        String originalNotes;
        String purpose;
        String notes;
    }

    private static final class FormRow {
        String id;
        String label;
        String originalNotes;
        String notes;
        final List<ParamRow> params = new ArrayList<>();

        @Override public String toString() { return label; }
    }

    private final MontoyaApi api;
    private final NotebookSession session;
    private final List<FormRow> forms;

    private final JComboBox<FormRow> formBox = new JComboBox<>();
    private final JTextField formNotes = new JTextField(40);
    private final ParamTableModel model = new ParamTableModel();
    private final JTable table = new JTable(model);
    private FormRow current;

    /**
     * @param formIds the forms to offer, in order; null or empty offers every
     *                form in the project
     */
    public ParameterNotesDialog(MontoyaApi api, NotebookSession session, Collection<String> formIds) {
        super(BurpUi.owner(api), "Parameter Purpose & Notes", false);
        this.api = api;
        this.session = session;
        this.forms = session.controller().read(p -> snapshot(p, formIds));
        build();
        pack();
        setSize(new Dimension(900, 460));
        setLocationRelativeTo(getOwner());
    }

    /** True when the dialog has at least one form to show. */
    public boolean hasForms() { return !forms.isEmpty(); }

    /** Copy what the dialog shows out of the model, so later edits need no model access. */
    private static List<FormRow> snapshot(Project p, Collection<String> formIds) {
        List<Form> chosen = new ArrayList<>();
        if (formIds == null || formIds.isEmpty()) {
            chosen.addAll(p.forms);
        } else {
            for (String id : formIds) {
                Form f = p.findForm(id);
                if (f != null) chosen.add(f);
            }
        }
        List<FormRow> out = new ArrayList<>();
        for (Form f : chosen) {
            FormRow row = new FormRow();
            row.id = f.id;
            Page page = p.findPage(f.pageId);
            row.label = f.id + "   " + f.method + " " + shorten(f.action)
                    + (f.formIdentifier == null || f.formIdentifier.isBlank()
                            ? "" : "   [" + f.formIdentifier + "]")
                    + "   — " + f.parameterIds.size() + " params"
                    + (page == null ? "" : ", on " + shorten(page.url));
            row.originalNotes = row.notes = f.notes == null ? "" : f.notes;
            for (String pid : f.parameterIds) {
                Parameter param = p.findParameter(pid);
                if (param == null) continue;
                ParamRow pr = new ParamRow();
                pr.id = param.id;
                pr.name = param.name == null || param.name.isEmpty() ? "(unnamed)" : param.name;
                pr.type = param.inputType;
                List<String> examples = new ArrayList<>(param.observedValues);
                if (param.defaultValue != null && !param.defaultValue.isEmpty()
                        && !examples.contains(param.defaultValue)) {
                    examples.add(0, param.defaultValue);
                }
                pr.examples = String.join(" | ", examples);
                pr.originalPurpose = pr.purpose = param.purpose == null ? "" : param.purpose;
                pr.originalNotes = pr.notes = param.notes == null ? "" : param.notes;
                row.params.add(pr);
            }
            out.add(row);
        }
        return out;
    }

    private static String shorten(String url) {
        if (url == null) return "";
        int scheme = url.indexOf("://");
        int slash = scheme < 0 ? -1 : url.indexOf('/', scheme + 3);
        String path = slash < 0 ? url : url.substring(slash);
        return path.length() > 60 ? path.substring(0, 59) + "…" : path;
    }

    private void build() {
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        for (FormRow f : forms) formBox.addItem(f);
        formBox.addActionListener(e -> showForm((FormRow) formBox.getSelectedItem()));
        JPanel north = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 3, 3, 3);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.gridx = 0; g.gridy = 0; north.add(new JLabel("Form:"), g);
        g.gridx = 1; g.weightx = 1; north.add(formBox, g);
        g.gridx = 0; g.gridy = 1; g.weightx = 0; north.add(new JLabel("Form notes:"), g);
        g.gridx = 1; g.weightx = 1; north.add(formNotes, g);
        content.add(north, BorderLayout.NORTH);

        table.setFillsViewportHeight(true);
        table.setRowHeight(table.getRowHeight() + 4);
        // Keep what was typed when the tester clicks a button mid-edit.
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.getColumnModel().getColumn(1).setMaxWidth(90);
        table.getColumnModel().getColumn(3).setPreferredWidth(220);
        table.getColumnModel().getColumn(4).setPreferredWidth(300);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(RetroTheme.panelBorder(api, "PARAMETERS — edit Purpose and Notes"));
        content.add(scroll, BorderLayout.CENTER);

        JButton suggest = new JButton("Suggest Purposes");
        suggest.setToolTipText("Fill the empty Purpose cells of this form with a guess from the "
                + "parameter name. Review them: nothing is saved until you press Save.");
        suggest.addActionListener(e -> suggest());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JButton save = new JButton("Save");
        save.addActionListener(e -> save());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.add(suggest);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        right.add(cancel);
        right.add(save);
        JPanel south = new JPanel(new BorderLayout());
        south.add(left, BorderLayout.WEST);
        south.add(right, BorderLayout.EAST);
        content.add(south, BorderLayout.SOUTH);

        setContentPane(content);
        RetroTheme.apply(api, content);
        Color bg = RetroTheme.background(api);
        if (bg != null) content.setBackground(bg);
        getRootPane().setDefaultButton(save);
        if (!forms.isEmpty()) showForm(forms.get(0));
    }

    /** Switch the table to another form, keeping the edits made to the previous one. */
    private void showForm(FormRow next) {
        commitEdits();
        current = next;
        formNotes.setText(next == null ? "" : next.notes);
        model.fireTableDataChanged();
    }

    /** Push in-progress cell and field edits into the rows. */
    private void commitEdits() {
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        if (current != null) current.notes = formNotes.getText().trim();
    }

    private void suggest() {
        commitEdits();
        if (current == null) return;
        int filled = 0;
        for (ParamRow p : current.params) {
            if (!p.purpose.isBlank()) continue;
            String guess = ParameterPurposeGuess.guess(p.name);
            if (!guess.isEmpty()) { p.purpose = guess; filled++; }
        }
        model.fireTableDataChanged();
        if (filled == 0) {
            JOptionPane.showMessageDialog(this, "No suggestion for the remaining parameters.",
                    "Assessment Notebook", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void save() {
        commitEdits();
        List<ParameterEdit> edits = new ArrayList<>();
        Map<String, String> notes = new LinkedHashMap<>();
        for (FormRow f : forms) {
            if (!f.notes.equals(f.originalNotes)) notes.put(f.id, f.notes);
            for (ParamRow p : f.params) {
                if (!p.purpose.equals(p.originalPurpose) || !p.notes.equals(p.originalNotes)) {
                    edits.add(new ParameterEdit(p.id, p.purpose, p.notes));
                }
            }
        }
        if (edits.isEmpty() && notes.isEmpty()) { dispose(); return; }
        if (!session.isOpen()) { dispose(); return; }
        setEnabled(false);
        new SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() throws Exception {
                return session.controller().annotateParameters(edits, notes);
            }
            @Override protected void done() {
                try {
                    api.logging().logToOutput("Assessment Notebook: saved notes for " + get()
                            + " parameter(s)/form(s)");
                    session.fireChanged();
                    dispose();
                } catch (Exception ex) {
                    setEnabled(true);
                    JOptionPane.showMessageDialog(ParameterNotesDialog.this,
                            "Could not save: " + ex.getMessage(),
                            "Assessment Notebook", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private final class ParamTableModel extends AbstractTableModel {
        @Override public int getRowCount() { return current == null ? 0 : current.params.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }
        @Override public boolean isCellEditable(int row, int column) { return column >= 3; }

        @Override public Object getValueAt(int row, int column) {
            ParamRow p = current.params.get(row);
            switch (column) {
                case 0: return p.name;
                case 1: return p.type;
                case 2: return p.examples;
                case 3: return p.purpose;
                default: return p.notes;
            }
        }

        @Override public void setValueAt(Object value, int row, int column) {
            ParamRow p = current.params.get(row);
            String text = value == null ? "" : value.toString().trim();
            if (column == 3) p.purpose = text; else if (column == 4) p.notes = text;
        }
    }
}
