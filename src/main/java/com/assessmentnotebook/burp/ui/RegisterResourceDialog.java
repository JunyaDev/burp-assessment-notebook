package com.assessmentnotebook.burp.ui;

import burp.api.montoya.MontoyaApi;
import com.assessmentnotebook.burp.NotebookSession;
import com.assessmentnotebook.model.Page;
import com.assessmentnotebook.model.Resource;

import javax.swing.*;
import java.awt.*;

/**
 * Review dialog for registering a non-page resource (script, stylesheet, image,
 * font, API/XHR endpoint, ...). Fixes the old "Register Resource" action, which
 * silently added a disconnected record with no feedback (spec §10): here the
 * tester confirms the type, can associate it with a page, adds notes, and gets
 * an explicit result. Deduplication on canonical URL happens in the controller.
 */
public final class RegisterResourceDialog extends JDialog {
    private final MontoyaApi api;
    private final NotebookSession session;

    private final JTextField url = new JTextField(36);
    private final JComboBox<Resource.Type> type = new JComboBox<>(Resource.Type.values());
    private final JTextArea notes = new JTextArea(3, 30);
    private final JCheckBox associate;
    private final Page page;

    public RegisterResourceDialog(MontoyaApi api, NotebookSession session,
            String initialUrl, Resource.Type guessedType, Page associatedPage) {
        super(BurpUi.owner(api), "Register Resource", true);
        this.api = api;
        this.session = session;
        this.page = associatedPage;
        this.associate = new JCheckBox(associatedPage == null
                ? "No matching page to associate"
                : "Associate with page " + associatedPage.url, associatedPage != null);
        this.associate.setEnabled(associatedPage != null);
        url.setText(initialUrl == null ? "" : initialUrl);
        type.setSelectedItem(guessedType == null ? Resource.Type.OTHER : guessedType);
        build();
        pack();
        setSize(new Dimension(620, getHeight()));
        setLocationRelativeTo(getOwner());
    }

    private void build() {
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(RetroTheme.panelBorder(api, "RESOURCE"));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 3, 3, 3);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;

        g.gridx = 0; g.gridy = 0; form.add(new JLabel("URL:"), g);
        g.gridx = 1; g.weightx = 1; form.add(url, g); g.weightx = 0;
        g.gridx = 0; g.gridy = 1; form.add(new JLabel("Type:"), g);
        g.gridx = 1; form.add(type, g);
        g.gridx = 0; g.gridy = 2; form.add(new JLabel("Notes:"), g);
        g.gridx = 1; form.add(new JScrollPane(notes), g);
        g.gridx = 1; g.gridy = 3; form.add(associate, g);
        content.add(form, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JButton register = new JButton("Register");
        register.addActionListener(e -> commit());
        buttons.add(cancel);
        buttons.add(register);
        content.add(buttons, BorderLayout.SOUTH);

        setContentPane(content);
        RetroTheme.apply(api, content);
        Color bg = RetroTheme.background(api);
        if (bg != null) content.setBackground(bg);
        getRootPane().setDefaultButton(register);
    }

    private void commit() {
        if (!session.isOpen()) { dispose(); return; }
        String u = url.getText().trim();
        if (u.isEmpty()) {
            JOptionPane.showMessageDialog(this, "A URL is required.");
            return;
        }
        Resource.Type t = (Resource.Type) type.getSelectedItem();
        String note = notes.getText().trim();
        String pageId = (associate.isSelected() && page != null) ? page.id : null;
        setEnabled(false);
        new SwingWorker<Resource, Void>() {
            @Override protected Resource doInBackground() throws Exception {
                return session.controller().addResource(u, t, note, pageId);
            }
            @Override protected void done() {
                try {
                    Resource r = get();
                    session.fireChanged();
                    api.logging().logToOutput("Assessment Notebook: registered resource "
                            + r.id + " " + r.url);
                    JOptionPane.showMessageDialog(RegisterResourceDialog.this,
                            "Registered " + r.type.label + " resource " + r.id + "\n" + r.url
                            + (r.pageIds.isEmpty() ? "" : "\nLoaded by " + r.pageIds.size()
                                    + " page(s)."),
                            "Assessment Notebook", JOptionPane.INFORMATION_MESSAGE);
                    dispose();
                } catch (Exception ex) {
                    setEnabled(true);
                    JOptionPane.showMessageDialog(RegisterResourceDialog.this,
                            "Registration failed: " + ex.getMessage(),
                            "Assessment Notebook", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
}
