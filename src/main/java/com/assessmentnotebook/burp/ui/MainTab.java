package com.assessmentnotebook.burp.ui;

import burp.api.montoya.MontoyaApi;
import com.assessmentnotebook.burp.NotebookSession;
import com.assessmentnotebook.core.NotebookController;
import com.assessmentnotebook.graph.AppGraph;
import com.assessmentnotebook.graph.TreeNode;
import com.assessmentnotebook.model.Note;
import com.assessmentnotebook.model.Project;
import com.assessmentnotebook.model.Vulnerability;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The extension's suite tab: project controls, a live application tree, the
 * current status counts, and recent observations and findings. Day-to-day
 * capture happens through the right-click context menu on requests; this tab is
 * the bird's-eye view of what has been captured so far.
 */
public final class MainTab extends JPanel {
    private final MontoyaApi api;
    private final NotebookSession session;

    private final JLabel projectLabel = new JLabel("No project open");
    private final JLabel statusLabel = new JLabel(" ");
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("(no project)");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(treeRoot);
    private final JTree appTree = new JTree(treeModel);
    private final DefaultListModel<String> notesModel = new DefaultListModel<>();
    private final DefaultListModel<String> vulnModel = new DefaultListModel<>();

    public MainTab(MontoyaApi api, NotebookSession session) {
        super(new BorderLayout(8, 8));
        this.api = api;
        this.session = session;
        build();
        session.addListener(this::refresh);
        refresh();
    }

    private void build() {
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Toolbar.
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton open = new JButton("New / Open Project…");
        open.addActionListener(e -> chooseProject());
        JButton setup = new JButton("Project Setup…");
        setup.addActionListener(e -> openSetupWizard());
        JButton docs = new JButton("Open Documentation");
        docs.addActionListener(e -> openDocs());
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> refresh());
        top.add(open);
        top.add(setup);
        top.add(docs);
        top.add(refresh);
        top.add(projectLabel);

        JPanel header = new JPanel(new BorderLayout());
        JLabel title = new JLabel("▣ ASSESSMENT NOTEBOOK");
        RetroTheme.accent(title, RetroTheme.Accent.TITLE);
        header.add(title, BorderLayout.WEST);
        header.add(top, BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);

        // Application tree.
        appTree.setRootVisible(true);
        appTree.setShowsRootHandles(true);
        JScrollPane treeScroll = new JScrollPane(appTree);
        treeScroll.setBorder(RetroTheme.panelBorder(api, "APPLICATION STRUCTURE"));

        // Recent observations + findings.
        JList<String> notesList = new JList<>(notesModel);
        JScrollPane notesScroll = new JScrollPane(notesList);
        notesScroll.setBorder(RetroTheme.panelBorder(api, "RECENT OBSERVATIONS"));

        JList<String> vulnList = new JList<>(vulnModel);
        JScrollPane vulnScroll = new JScrollPane(vulnList);
        vulnScroll.setBorder(RetroTheme.panelBorder(api, "VULNERABILITIES"));

        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, vulnScroll, notesScroll);
        rightSplit.setResizeWeight(0.5);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, rightSplit);
        split.setResizeWeight(0.55);
        add(split, BorderLayout.CENTER);

        // Footer: status + help.
        JPanel south = new JPanel(new BorderLayout());
        RetroTheme.accent(statusLabel, RetroTheme.Accent.HINT);
        JLabel help = new JLabel("Right-click a request → Assessment Notebook → "
                + "Register Page / Add Observation / Create Vulnerability / Capture Screenshot");
        RetroTheme.accent(help, RetroTheme.Accent.HINT);
        south.add(statusLabel, BorderLayout.WEST);
        south.add(help, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);

        RetroTheme.apply(api, this);
        // The title keeps its larger bold face regardless of theme.
        title.setFont(new Font(Font.MONOSPACED, Font.BOLD, 16));
        Color bg = RetroTheme.background(api);
        if (bg != null) setBackground(bg);
    }

    // ---- actions ---------------------------------------------------------

    private void chooseProject() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select or create a project directory");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path dir = chooser.getSelectedFile().toPath();
        String name = dir.getFileName() != null ? dir.getFileName().toString() : "assessment";
        NotebookController probe = new NotebookController(dir);
        try {
            if (!probe.exists()) {
                String entered = JOptionPane.showInputDialog(this,
                        "New project name:", name);
                if (entered == null) return; // cancelled
                name = entered.isBlank() ? name : entered;
            }
            session.openOrCreate(dir, name);
            api.logging().logToOutput("Assessment Notebook: opened project at " + dir);
        } catch (IOException ex) {
            error("Could not open project: " + ex.getMessage());
        }
    }

    private void openSetupWizard() {
        if (!session.isOpen()) { error("Open a project first."); return; }
        new ProjectSetupWizard(api, session).setVisible(true);
    }

    private void openDocs() {
        if (!session.isOpen()) { error("Open a project first."); return; }
        Path index = session.controller().layout().indexHtml();
        // Desktop.browse can block for seconds on Linux while the handler
        // starts, so keep it off the event thread.
        new SwingWorker<Boolean, Void>() {
            @Override protected Boolean doInBackground() {
                try {
                    if (!Desktop.isDesktopSupported()) return false;
                    Desktop.getDesktop().browse(index.toUri());
                    return true;
                } catch (IOException | RuntimeException ex) {
                    return false;
                }
            }
            @Override protected void done() {
                try {
                    if (!get()) info("Documentation is at:\n" + index);
                } catch (Exception ex) {
                    info("Documentation is at:\n" + index);
                }
            }
        }.execute();
    }

    /** Rebuild the tab from the current project. Safe to call on the EDT. */
    public void refresh() {
        if (!session.isOpen()) {
            projectLabel.setText("No project open");
            statusLabel.setText(" ");
            treeRoot.setUserObject("(no project)");
            treeRoot.removeAllChildren();
            treeModel.reload();
            notesModel.clear();
            vulnModel.clear();
            return;
        }
        Project p = session.controller().project();
        projectLabel.setText("   " + p.name + "  —  " + session.root());
        statusLabel.setText(String.format(
                "pages %d   forms %d   params %d   resources %d   findings %d   notes %d",
                p.pages.size(), p.forms.size(), p.parameters.size(),
                p.resources.size(), p.vulnerabilities.size(), p.notes.size()));

        // Tree.
        TreeNode root = new AppGraph(p).buildTree();
        treeRoot.setUserObject(root.label);
        treeRoot.removeAllChildren();
        for (TreeNode child : root.sortedChildren()) treeRoot.add(toSwing(child));
        treeModel.reload();
        for (int i = 0; i < appTree.getRowCount(); i++) appTree.expandRow(i);

        // Observations (most recent first).
        notesModel.clear();
        p.notes.stream()
                .sorted(java.util.Comparator.comparing((Note n) -> n.createdAt == null ? "" : n.createdAt)
                        .reversed())
                .limit(50)
                .forEach(n -> notesModel.addElement("[" + n.kind.label + "] " + n.text));

        // Findings (most severe first).
        vulnModel.clear();
        p.vulnerabilities.stream()
                .sorted((a, b) -> b.severity.ordinal() - a.severity.ordinal())
                .forEach((Vulnerability v) ->
                        vulnModel.addElement(v.severity + "  " + v.title));
    }

    private DefaultMutableTreeNode toSwing(TreeNode node) {
        String label = node.isPage() ? node.label + "  ◉" : node.label;
        DefaultMutableTreeNode swing = new DefaultMutableTreeNode(label);
        for (TreeNode child : node.sortedChildren()) swing.add(toSwing(child));
        return swing;
    }

    private void error(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Assessment Notebook", JOptionPane.ERROR_MESSAGE);
    }

    private void info(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Assessment Notebook", JOptionPane.INFORMATION_MESSAGE);
    }
}
