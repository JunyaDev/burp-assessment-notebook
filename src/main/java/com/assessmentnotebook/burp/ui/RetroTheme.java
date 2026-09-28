package com.assessmentnotebook.burp.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.Theme;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;

/**
 * Colours and fonts for the extension's own Swing UI.
 *
 * <p>When Burp runs its dark theme the phosphor palette of the generated HTML
 * ({@code assets/retro.css}) is applied so the tool reads as one system. When
 * Burp runs its light theme the components keep Burp's own look (via
 * {@link burp.api.montoya.ui.UserInterface#applyThemeToComponent}) and only the
 * accent colours are used, in darker variants that stay readable on light
 * backgrounds. Accents are declared with {@link #accent} so that {@link #apply}
 * preserves them instead of flattening every label to the base foreground.
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

    /** Semantic accents; each resolves to a dark- or light-theme colour. */
    public enum Accent {
        TITLE(GREEN, new Color(0x1f, 0x7a, 0x3a)),
        HINT(FG_DIM, new Color(0x6a, 0x74, 0x70)),
        WARN(AMBER, new Color(0x9a, 0x62, 0x00)),
        DANGER(RED, new Color(0xb3, 0x26, 0x1e));

        final Color dark;
        final Color light;
        Accent(Color dark, Color light) { this.dark = dark; this.light = light; }
    }

    private static final String ACCENT_KEY = "assessmentnotebook.accent";

    private RetroTheme() {}

    /** Mark a component as carrying an accent colour that {@link #apply} must keep. */
    public static <T extends JComponent> T accent(T c, Accent accent) {
        c.putClientProperty(ACCENT_KEY, accent);
        c.setForeground(accent.dark);
        return c;
    }

    /** True when Burp is currently using its dark theme (best effort). */
    public static boolean isDark(MontoyaApi api) {
        try {
            return api != null && api.userInterface().currentTheme() == Theme.DARK;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * Theme a component tree to match Burp: the phosphor palette on Burp's
     * dark theme, Burp's own component theme on its light theme. Accents set
     * with {@link #accent} are re-applied afterwards in the matching variant.
     */
    public static void apply(MontoyaApi api, Component root) {
        boolean dark = isDark(api);
        if (dark) {
            applyDark(root);
        } else {
            try {
                api.userInterface().applyThemeToComponent(root);
            } catch (RuntimeException ignored) { /* keep look-and-feel defaults */ }
        }
        applyAccents(root, dark);
    }

    /** Apply the phosphor palette to a component and all its descendants. */
    public static void apply(Component c) {
        applyDark(c);
        applyAccents(c, true);
    }

    private static void applyDark(Component c) {
        if (c instanceof JComponent) {
            c.setFont(MONO);
            c.setForeground(FG);
            c.setBackground(BG1);
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) applyDark(child);
        }
    }

    private static void applyAccents(Component c, boolean dark) {
        if (c instanceof JComponent) {
            Object a = ((JComponent) c).getClientProperty(ACCENT_KEY);
            if (a instanceof Accent) c.setForeground(dark ? ((Accent) a).dark : ((Accent) a).light);
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) applyAccents(child, dark);
        }
    }

    /** Background for a themed root container. */
    public static Color background(MontoyaApi api) {
        return isDark(api) ? BG : null;
    }

    /** A titled, bordered panel border in the matching accent. */
    public static javax.swing.border.Border panelBorder(String title) {
        return BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(BG3), title, 0, 0, MONO_BOLD, AMBER);
    }

    /** A titled panel border whose colours follow Burp's current theme. */
    public static javax.swing.border.Border panelBorder(MontoyaApi api, String title) {
        boolean dark = isDark(api);
        return BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(dark ? BG3 : new Color(0xc8, 0xcc, 0xc9)),
                title, 0, 0, MONO_BOLD, dark ? AMBER : Accent.WARN.light);
    }
}
