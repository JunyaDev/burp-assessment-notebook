package com.assessmentnotebook.burp.ui;

import burp.api.montoya.MontoyaApi;
import com.assessmentnotebook.burp.NotebookSession;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Draw rectangles on a captured screenshot to call out elements (spec §9). The
 * tester drags to draw a prominent red box, is asked for an optional label, and
 * on save each box is persisted as an {@link com.assessmentnotebook.model.Annotation}
 * in the screenshot's own image coordinates.
 */
public final class ScreenshotAnnotator extends JDialog {
    private final MontoyaApi api;
    private final NotebookSession session;
    private final String screenshotId;
    private final BufferedImage image;
    private final List<Rectangle> rects = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private final Canvas canvas;
    private JButton save;

    public ScreenshotAnnotator(MontoyaApi api, NotebookSession session, String screenshotId,
            byte[] png) {
        super(BurpUi.owner(api), "Annotate Screenshot", true);
        this.api = api;
        this.session = session;
        this.screenshotId = screenshotId;
        BufferedImage img = null;
        try { img = ImageIO.read(new ByteArrayInputStream(png)); } catch (Exception ignored) { }
        this.image = img;
        this.canvas = new Canvas();
        build();
    }

    private void build() {
        JPanel content = new JPanel(new BorderLayout(6, 6));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JLabel help = new JLabel("Drag to draw a red box around an element. "
                + "You'll be asked for a label. Multiple boxes are supported.");
        RetroTheme.accent(help, RetroTheme.Accent.HINT);
        content.add(help, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(canvas);
        content.add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton undo = new JButton("Undo last box");
        undo.addActionListener(e -> {
            if (!rects.isEmpty()) { rects.remove(rects.size() - 1); labels.remove(labels.size() - 1); canvas.repaint(); }
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        save = new JButton("Save annotations");
        save.addActionListener(e -> commit());
        buttons.add(undo);
        buttons.add(cancel);
        buttons.add(save);
        content.add(buttons, BorderLayout.SOUTH);

        setContentPane(content);
        RetroTheme.apply(api, content);
        Color bg = RetroTheme.background(api);
        if (bg != null) content.setBackground(bg);
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(Math.min(screen.width - 80, image == null ? 800 : image.getWidth() + 60),
                Math.min(screen.height - 80, image == null ? 600 : image.getHeight() + 140));
        setLocationRelativeTo(getOwner());
    }

    /** Persist every box in one batch (one save, one page regeneration), off the EDT. */
    private void commit() {
        if (!session.isOpen()) { dispose(); return; }
        if (rects.isEmpty()) { dispose(); return; }
        List<com.assessmentnotebook.model.Annotation> batch = new ArrayList<>();
        for (int i = 0; i < rects.size(); i++) {
            Rectangle r = rects.get(i);
            com.assessmentnotebook.model.Annotation a = new com.assessmentnotebook.model.Annotation();
            a.x = r.x; a.y = r.y; a.width = r.width; a.height = r.height;
            a.label = labels.get(i);
            batch.add(a);
        }
        save.setEnabled(false);
        new SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() throws Exception {
                return session.controller().addAnnotations(screenshotId, batch).size();
            }
            @Override protected void done() {
                try {
                    int n = get();
                    session.fireChanged();
                    api.logging().logToOutput("Assessment Notebook: saved " + n + " annotation(s)");
                    dispose();
                } catch (Exception ex) {
                    save.setEnabled(true);
                    JOptionPane.showMessageDialog(ScreenshotAnnotator.this,
                            "Could not save annotations: " + ex.getMessage(),
                            "Assessment Notebook", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /** The image with live rectangle drawing in image coordinates. */
    private final class Canvas extends JPanel {
        private Point start;
        private Rectangle dragging;

        Canvas() {
            setPreferredSize(image == null ? new Dimension(600, 400)
                    : new Dimension(image.getWidth(), image.getHeight()));
            MouseAdapter m = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { start = e.getPoint(); }
                @Override public void mouseDragged(MouseEvent e) {
                    dragging = rect(start, e.getPoint());
                    repaint();
                }
                @Override public void mouseReleased(MouseEvent e) {
                    Rectangle r = rect(start, e.getPoint());
                    dragging = null;
                    if (r.width > 3 && r.height > 3) {
                        String label = JOptionPane.showInputDialog(ScreenshotAnnotator.this,
                                "Label for this box (optional):", "");
                        if (label != null) {
                            rects.add(r);
                            labels.add(label);
                        }
                    }
                    repaint();
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        private Rectangle rect(Point a, Point b) {
            if (a == null || b == null) return new Rectangle();
            return new Rectangle(Math.min(a.x, b.x), Math.min(a.y, b.y),
                    Math.abs(a.x - b.x), Math.abs(a.y - b.y));
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (image != null) g.drawImage(image, 0, 0, this);
            Graphics2D g2 = (Graphics2D) g;
            g2.setStroke(new BasicStroke(3f));
            g2.setColor(RetroTheme.RED);
            for (int i = 0; i < rects.size(); i++) {
                Rectangle r = rects.get(i);
                g2.draw(r);
                if (!labels.get(i).isBlank()) g2.drawString(labels.get(i), r.x, Math.max(12, r.y - 4));
            }
            if (dragging != null) g2.draw(dragging);
        }
    }
}
