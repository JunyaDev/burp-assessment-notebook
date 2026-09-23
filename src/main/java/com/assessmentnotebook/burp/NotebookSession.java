package com.assessmentnotebook.burp;

import com.assessmentnotebook.core.NotebookController;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds the currently open project for the whole extension and notifies the UI
 * when it changes. A single session is shared between the suite tab and the
 * context-menu actions so both always act on the same project.
 */
public final class NotebookSession {
    private NotebookController controller;
    private Path root;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Open the project at {@code root}, creating it (with {@code name}) if absent. */
    public synchronized void openOrCreate(Path root, String name) throws IOException {
        NotebookController c = new NotebookController(root);
        if (c.exists()) {
            c.open();
        } else {
            c.create(name);
        }
        this.controller = c;
        this.root = root;
        fireChanged();
    }

    public synchronized boolean isOpen() { return controller != null; }
    public synchronized NotebookController controller() { return controller; }
    public synchronized Path root() { return root; }

    public void addListener(Runnable r) { listeners.add(r); }

    /** Notify listeners on the Swing thread that the project changed. */
    public void fireChanged() {
        for (Runnable r : listeners) SwingUtilities.invokeLater(r);
    }
}
