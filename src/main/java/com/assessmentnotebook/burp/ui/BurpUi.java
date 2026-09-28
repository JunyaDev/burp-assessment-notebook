package com.assessmentnotebook.burp.ui;

import burp.api.montoya.MontoyaApi;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.awt.Frame;

/**
 * Small helpers that tie the extension's windows to Burp's own: every dialog
 * and message box is owned by (and centred on) the Burp suite frame instead of
 * floating unowned in the middle of the primary screen.
 */
public final class BurpUi {
    private BurpUi() {}

    /** Burp's main window, or null if it cannot be resolved (e.g. in tests). */
    public static Frame owner(MontoyaApi api) {
        if (api == null) return null;
        try {
            return api.userInterface().swingUtils().suiteFrame();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static void info(MontoyaApi api, String message) {
        JOptionPane.showMessageDialog(owner(api), message, "Assessment Notebook",
                JOptionPane.INFORMATION_MESSAGE);
    }

    public static void warn(MontoyaApi api, String message) {
        JOptionPane.showMessageDialog(owner(api), message, "Assessment Notebook",
                JOptionPane.WARNING_MESSAGE);
    }

    public static void error(MontoyaApi api, String message) {
        JOptionPane.showMessageDialog(owner(api), message, "Assessment Notebook",
                JOptionPane.ERROR_MESSAGE);
    }

    /** OK/Cancel form dialog; true when the user confirmed. */
    public static boolean confirm(MontoyaApi api, Component form, String title) {
        int ok = JOptionPane.showConfirmDialog(owner(api), form, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        return ok == JOptionPane.OK_OPTION;
    }
}
