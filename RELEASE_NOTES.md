## Assessment Notebook v1.0.0

A Burp Suite Professional extension (Montoya API, Java 17) that turns an
authorized web application assessment into a navigable, self-documenting
knowledge base rendered as interlinked, offline HTML.

### Install
1. Download `assessment-notebook-v1.0.0.jar` below.
2. Burp Suite Professional → **Extensions → Add** → Extension type: **Java** → select the jar.
3. Open the **Assessment Notebook** tab to create or open a project, then
   right-click requests to capture into it.

### Highlights
- Graph data model (pages, forms, parameters, links, resources, requests,
  screenshots, notes, findings) with typed relationships as first-class data.
- Right-click capture with review-before-commit; reflection-context recording
  (never auto-labeled a vulnerability); sequential screenshot states.
- Self-contained retro-futuristic HTML output; portable project directory with
  relative links; JSON as the source of truth, HTML regenerated from it.

### Notes
- The jar bundles gson and jsoup; the Montoya API is provided by Burp at runtime.
- Verified: full compile against Montoya 2025.5, JUnit 5 suite (20 tests),
  example project with all internal links resolving.
