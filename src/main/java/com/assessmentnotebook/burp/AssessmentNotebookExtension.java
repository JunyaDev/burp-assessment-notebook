package com.assessmentnotebook.burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import com.assessmentnotebook.burp.ui.MainTab;

import javax.swing.SwingUtilities;

/**
 * Burp extension entry point (Montoya API). Registers the suite tab and the
 * request context-menu, sharing one {@link NotebookSession} between them so the
 * tab and the menu always act on the same open project.
 *
 * <p>Load in Burp Suite Professional via Extensions -> Add -> Extension type:
 * Java -> select the built jar.
 */
public final class AssessmentNotebookExtension implements BurpExtension {

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Assessment Notebook");

        NotebookSession session = new NotebookSession();

        SwingUtilities.invokeLater(() -> {
            MainTab tab = new MainTab(api, session);
            api.userInterface().registerSuiteTab("Assessment Notebook", tab);
        });
        api.userInterface().registerContextMenuItemsProvider(
                new ContextMenuProvider(api, session));

        api.logging().logToOutput(
                "Assessment Notebook loaded. Open the tab to create or open a project, "
                + "then right-click requests to capture into it.");
    }
}
