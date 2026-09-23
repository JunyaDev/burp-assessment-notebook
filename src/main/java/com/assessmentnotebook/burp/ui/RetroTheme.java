package com.assessmentnotebook.burp.ui;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;

/**
 * The retro-futuristic phosphor palette and fonts for the extension's own Swing
 * UI, matching the generated HTML documentation so the tool reads as one
 * system. Colors mirror {@code assets/retro.css}.
 */
public final class RetroTheme {
    public static final Color BG      = new Color(0x08, 0x0b, 0x0a);
    public static final Color BG1     = new Color(0x0d, 0x12, 0x10);
    public static final Color BG2     = new Color(0x13, 0x19, 0x16);
    public static final Color BG3     = new Color(0x24, 0x30, 0x2a);
    public static final Color FG      = new Color(0xa8, 0xc0, 0xa8);
    public static final Color FG_DIM  = new Color(0x5f, 0x74, 0x66);
    public static final Color GREEN   = new Color(0x6e, 0xe7, 0x87);
    public static final Color AMBER   = new Color(0xf2, 0xb1, 0x34);
    public static final Color BLUE    = new Color(0x6f, 0xb2, 0xc9);
    public static final Color RED     = new Color(0xe8, 0x56, 0x4b);

    public static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 13);
    public static final Font MONO_BOLD = new Font(Font.MONOSPACED, Font.BOLD, 13);

    private RetroTheme() {}

    /** Apply the phosphor palette to a component and all its descendants. */
    public static void apply(Component c) {
        if (c instanceof JComponent) {
            c.setFont(MONO);
            c.setForeground(FG);
            c.setBackground(BG1);
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) apply(child);
        }
    }

    /** A titled, phosphor-bordered panel border. */
    public static javax.swing.border.Border panelBorder(String title) {
        return BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(BG3), title, 0, 0, MONO_BOLD, AMBER);
    }
}
