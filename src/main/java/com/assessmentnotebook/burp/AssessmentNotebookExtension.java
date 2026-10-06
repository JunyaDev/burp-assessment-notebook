package com.assessmentnotebook.burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import com.assessmentnotebook.burp.ui.MainTab;

import javax.swing.SwingUtilities;

/**
 * Burp extension entry point (Montoya API). Registers the suite tab, the
 * request context-menu and the auto-capture listener, sharing one
 * {@link NotebookSession} between them so they always act on the same open
 * project.
 *
 * <p>Load in Burp Suite Professional via Extensions -> Add -> Extension type:
 * Java -> select the built jar.
 */
public final class AssessmentNotebookExtension implements BurpExtension {

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Assessment Notebook");

        NotebookSession session = new NotebookSession();

        // Passive listener behind the project's auto-capture rules.
        AutoCaptureService capture = new AutoCaptureService(api, session);
        session.attachCapture(capture);
        api.http().registerHttpHandler(capture);
        api.extension().registerUnloadingHandler(capture::shutdown);

        SwingUtilities.invokeLater(() -> {
            MainTab tab = new MainTab(api, session);
            api.userInterface().registerSuiteTab("Assessment Notebook", tab);
        });
        api.userInterface().registerContextMenuItemsProvider(
                new ContextMenuProvider(api, session));

        api.logging().logToOutput(
                "Assessment Notebook loaded. Open the tab to create or open a project, "
                + "then right-click requests to capture into it, or set up Auto-Capture "
                + "rules to register traffic as you browse.");
    }
}
