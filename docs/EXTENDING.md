# Architecture & extending

The project is deliberately split so that everything meaningful is testable
without Burp, and the Burp-specific code is thin glue.

## Module map

```
com.assessmentnotebook
├── model/      Plain entities + enums. No logic, no dependencies. (project.json shape)
├── store/      ProjectStore (Gson JSON + atomic writes), ProjectLayout, Timestamps.
├── graph/      AppGraph (edge queries, discovery paths) + TreeNode (URL tree).
├── analyze/    HtmlAnalyzer (jsoup) + ReflectionDetector. Pure functions over strings.
├── html/       HtmlGenerator + Html escaping + Assets (copies retro theme/JS out).
├── core/       NotebookController — every mutating operation; PageRegistration DTO.
├── example/    ExampleProjectGenerator — builds the sample via the real code.
└── burp/       Montoya entry point, context menu, request extractor, screenshot,
    └── ui/     Swing suite tab, register dialog, retro theme.
```

Dependency direction is one-way: `burp/` → `core/` → (`store/`, `graph/`,
`analyze/`, `html/`) → `model/`. Nothing below `burp/` imports Montoya, so the
whole capture-and-document pipeline runs in a unit test (see
`src/test/java/...HtmlGeneratorTest`).

## The two seams you will use most

- **`NotebookController`** is the single entry point for change. It owns save +
  regenerate, so any new operation should be a method here that mutates the
  `Project` and calls `saveAndGenerate()`. This keeps disk and HTML consistent
  and gives you a testable unit for free.
- **`HtmlGenerator`** owns rendering. Every document is built from the model with
  small string helpers and the shared shell; there is no template engine to
  learn. Escape everything target-derived through `Html.esc`.

## How to add capabilities

**A new analysis** (e.g. detect a technology from headers): add a pure class in
`analyze/` that takes strings/records and returns a result DTO. Call it from a new
`NotebookController` method (or from `RequestExtractor` if it runs at capture
time). Add a unit test with sample input — no Burp needed.

**A new entity or field**: add the field to the `model/` class (Gson picks it up;
bump `Project.schemaVersion` if you need a migration). Render it in the relevant
`HtmlGenerator.buildX` method. If it participates in the graph, add a relationship
label constant in `Relationship` and create edges in the controller.

**A new document type**: add a `buildX` method and a `write(...)` call in
`HtmlGenerator.generateAll`, and a directory in `ProjectLayout` if it needs its
own folder. Link to it from the pages/index that reference it.

**A new capture action**: add a `JMenuItem` in `ContextMenuProvider`, extract what
you need from the `HttpRequestResponse` (see `RequestExtractor` for the Montoya
accessors), and call a controller method on a background `SwingWorker`, then
`session.fireChanged()` to refresh the tab.

**A new front-end behavior**: edit `src/main/resources/assets/app.js` or
`highlight.js`. They must stay dependency-free and work from `file://` (no
`fetch`, no CDN), because a copied project has no server. Keep styling in
`retro.css` using the existing CSS variables.

## Conventions worth keeping

- The model is the source of truth; never hand-edit generated HTML.
- Ids are stable and minted by `Project.nextId`; do not reuse or renumber them.
- All inter-document links are relative; test with the link-check approach used
  in CI (walk the HTML, resolve every non-external `href`/`src`, assert it exists).
- Registration is additive and de-duplicating; re-registering a URL updates it.
- Everything runs offline: no network calls at view time, ever.

## Build internals

Gradle (`build.gradle`) is the primary build; `shadowJar` bundles gson + jsoup and
leaves Montoya as `compileOnly`. The `scripts/*.sh` reproduce the same jar with
plain `javac`/`jar` for environments without Gradle, and are what the test and
package steps use when a build tool is unavailable.
