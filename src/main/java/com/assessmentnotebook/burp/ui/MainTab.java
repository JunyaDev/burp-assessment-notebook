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
    private final JLabel captureLabel = new JLabel(" ");
    /** Counts refreshes so a slow snapshot never overwrites a newer one. */
    private int refreshSeq;
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
        JButton capture = new JButton("Auto-Capture…");
        capture.setToolTipText("Rules that register pages, API endpoints and resources "
                + "automatically as you browse.");
        capture.addActionListener(e -> openCaptureRules());
        JButton params = new JButton("Parameters…");
        params.setToolTipText("Describe what each form / request parameter is for.");
        params.addActionListener(e -> openParameters());
        JButton docs = new JButton("Open Documentation");
        docs.addActionListener(e -> openDocs());
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> refresh());
        top.add(open);
        top.add(setup);
        top.add(capture);
        top.add(params);
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
                + "Register Page / API Endpoint / Describe Parameters / Create Vulnerability — "
                + "or let Auto-Capture register traffic as you browse");
        RetroTheme.accent(help, RetroTheme.Accent.HINT);
        RetroTheme.accent(captureLabel, RetroTheme.Accent.WARN);
        south.setLayout(new GridLayout(0, 1, 0, 2));
        south.add(statusLabel);
        south.add(captureLabel);
        south.add(help);
        add(south, BorderLayout.SOUTH);

        // Auto-capture counters move without the project changing (duplicates
        // are skipped silently), so they are polled rather than pushed.
        Timer captureTimer = new Timer(1000, e -> updateCaptureLabel());
        captureTimer.start();
        addHierarchyListener(e -> {
            if (!isDisplayable()) captureTimer.stop(); // extension unloaded
            else if (!captureTimer.isRunning()) captureTimer.start();
        });

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

    private void openCaptureRules() {
        if (!session.isOpen()) { error("Open a project first."); return; }
        new CaptureRulesDialog(api, session).setVisible(true);
    }

    private void openParameters() {
        if (!session.isOpen()) { error("Open a project first."); return; }
        ParameterNotesDialog dialog = new ParameterNotesDialog(api, session, null);
        if (!dialog.hasForms()) {
            dialog.dispose();
            info("No forms or request parameters are registered yet.");
            return;
        }
        dialog.setVisible(true);
    }

    private void updateCaptureLabel() {
        com.assessmentnotebook.burp.AutoCaptureService service = session.capture();
        NotebookController c = session.controller();
        if (service == null || c == null) { captureLabel.setText(" "); return; }
        com.assessmentnotebook.model.CaptureConfig cfg = c.project().capture;
        com.assessmentnotebook.burp.AutoCaptureService.Stats s = service.stats();
        StringBuilder b = new StringBuilder(cfg.enabled
                ? "AUTO-CAPTURE ON (" + cfg.rules.size() + " rules)" : "auto-capture off");
        int registered = s.pages.get() + s.endpoints.get() + s.resources.get() + s.variants.get();
        if (cfg.enabled || registered > 0 || service.pending() > 0) {
            b.append("   this session: +").append(s.pages.get()).append(" pages  +")
                    .append(s.endpoints.get()).append(" endpoints  +").append(s.resources.get())
                    .append(" resources  +").append(s.variants.get()).append(" variants   ")
                    .append(s.skipped.get()).append(" skipped (already known or ignored)");
            if (service.pending() > 0) b.append("   ").append(service.pending()).append(" queued");
            if (s.dropped.get() > 0) b.append("   ").append(s.dropped.get()).append(" dropped");
            if (s.errors.get() > 0) b.append("   ").append(s.errors.get()).append(" errors (see Extensions output)");
        }
        captureLabel.setText(b.toString());
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

    /** What the tab shows, computed from the model in one consistent read. */
    private static final class View {
        String project;
        String status;
        String rootLabel;
        final java.util.List<DefaultMutableTreeNode> children = new java.util.ArrayList<>();
        final java.util.List<String> notes = new java.util.ArrayList<>();
        final java.util.List<String> vulns = new java.util.ArrayList<>();
    }

    /**
     * Rebuild the tab from the current project. Call on the EDT. The model is
     * read on a background thread, under the controller's lock, because
     * auto-capture may be writing to it at the same moment and a save can hold
     * that lock for longer than the UI should wait.
     */
    public void refresh() {
        final int seq = ++refreshSeq;
        updateCaptureLabel();
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
        final NotebookController controller = session.controller();
        final Path root = session.root();
        new SwingWorker<View, Void>() {
            @Override protected View doInBackground() {
                return controller.read(p -> snapshot(p, root));
            }
            @Override protected void done() {
                if (seq != refreshSeq) return; // a newer refresh is on its way
                try {
                    show(get());
                } catch (Exception ex) {
                    api.logging().logToError("Assessment Notebook: could not refresh the tab", ex);
                }
            }
        }.execute();
    }

    private static View snapshot(Project p, Path root) {
        View v = new View();
        v.project = "   " + p.name + "  —  " + root;
        long endpoints = p.pages.stream()
                .filter(pg -> pg.kind == com.assessmentnotebook.model.Page.Kind.API).count();
        v.status = String.format(
                "pages %d   API endpoints %d   forms %d   params %d   resources %d   variants %d"
                + "   findings %d   notes %d",
                p.pages.size() - endpoints, endpoints, p.forms.size(), p.parameters.size(),
                p.resources.size(), p.variants.size(), p.vulnerabilities.size(), p.notes.size());

        TreeNode tree = new AppGraph(p).buildTree();
        v.rootLabel = tree.label;
        for (TreeNode child : tree.sortedChildren()) v.children.add(toSwing(p, child));

        // Observations (most recent first).
        p.notes.stream()
                .sorted(java.util.Comparator.comparing((Note n) -> n.createdAt == null ? "" : n.createdAt)
                        .reversed())
                .limit(50)
                .forEach(n -> v.notes.add("[" + n.kind.label + "] " + n.text));

        // Findings (most severe first).
        p.vulnerabilities.stream()
                .sorted((a, b) -> b.severity.ordinal() - a.severity.ordinal())
                .forEach((Vulnerability vuln) -> v.vulns.add(vuln.severity + "  " + vuln.title));
        return v;
    }

    private void show(View v) {
        projectLabel.setText(v.project);
        statusLabel.setText(v.status);
        treeRoot.setUserObject(v.rootLabel);
        treeRoot.removeAllChildren();
        for (DefaultMutableTreeNode child : v.children) treeRoot.add(child);
        treeModel.reload();
        for (int i = 0; i < appTree.getRowCount(); i++) appTree.expandRow(i);
        notesModel.clear();
        for (String n : v.notes) notesModel.addElement(n);
        vulnModel.clear();
        for (String n : v.vulns) vulnModel.addElement(n);
    }

    /** ◉ marks a registered page, ⇄ an API endpoint; non-GET methods are spelled out. */
    private static DefaultMutableTreeNode toSwing(Project p, TreeNode node) {
        StringBuilder label = new StringBuilder(node.label);
        if (node.isPage()) {
            boolean api = false;
            java.util.List<String> methods = new java.util.ArrayList<>();
            for (String pid : node.pageIds) {
                com.assessmentnotebook.model.Page page = p.findPage(pid);
                if (page == null) continue;
                api |= page.kind == com.assessmentnotebook.model.Page.Kind.API;
                if (!methods.contains(page.method)) methods.add(page.method);
            }
            label.append(api ? "  ⇄" : "  ◉");
            if (methods.size() > 1 || (methods.size() == 1 && !"GET".equalsIgnoreCase(methods.get(0)))) {
                label.append("  ").append(String.join(" ", methods));
            }
        }
        DefaultMutableTreeNode swing = new DefaultMutableTreeNode(label.toString());
        for (TreeNode child : node.sortedChildren()) swing.add(toSwing(p, child));
        return swing;
    }

    private void error(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Assessment Notebook", JOptionPane.ERROR_MESSAGE);
    }

    private void info(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Assessment Notebook", JOptionPane.INFORMATION_MESSAGE);
    }
}
