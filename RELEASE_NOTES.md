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

---

## v1.1.0 (in progress) — interactive assessment upgrade

Improvements from the web-assessment-organizer requirements. See
[`docs/ASSESSMENT.md`](docs/ASSESSMENT.md) for the review of v1.0.0 that drove
this work.

### Phase 1 — fixed existing behavior
- **Register Resource now works (§10).** Replaced the silent, page-less action
  with a review dialog (type, notes, associate-with-page) and explicit result.
- **Canonical resource identity (§10, §11).** Resources dedupe on a normalized
  URL (`ResourceUrls`): lower-cased scheme/host, default ports and fragments
  dropped, cache-buster queries ignored for static assets. The same script
  discovered from many pages is now one record with many "loaded by" pages, and
  a specific type upgrades a generic `OTHER`. Raw URL forms are preserved.
- **Structured, inferred discovery source (§3).** A controlled `DiscoverySource`
  vocabulary replaces free text; the kind is inferred at capture from the
  referer/XHR headers, method and Burp tool, redirect discovery is detected
  automatically across captured 3xx responses, and the tester can override it.

Tests: 34 (was 20); jar builds against Montoya 2025.5.

### Phase 2 — workflow
- **Project Setup Wizard (§4).** A guided dialog explains each field and
  pre-fills Domain, Main URL, Server and Frameworks from captured traffic; all
  editable. Reachable from the tab's **Project Setup…** button.
- **Automatic technology detection (§5).** A Wappalyzer-in-spirit detector draws
  on HTTP headers, cookies, meta tags and script/link URLs, extracting versions
  and attaching per-signal confidence. It runs automatically on every registered
  page. Detections merge into existing records — a more specific version and a
  higher confidence update in place, evidence accumulates, and every change is
  logged to history, so nothing is duplicated.
- **Editable technology records (§6).** Technologies carry category, version,
  confidence, structured evidence, first-observed/last-updated and notes, and are
  fully editable; a tester edit is never overwritten by later detection.

### Phase 3 — visual discovery
- **Keyboard-driven action menu (§2).** The context submenu gives each action an
  underlined mnemonic letter (Register Page, Resource, Note, Mark Interesting
  String, Test Parameter, Screenshot, ...). A global cross-Burp hotkey is
  not exposed by the Montoya API, so this is the keyboard path the platform
  allows.
- **Mark interesting strings + wordlists (§8).** Select text in a Burp editor and
  mark it into a wordlist category (usernames, directories, parameters, ...);
  export writes `wordlists/*.txt` for other authorized tools.
- **Register element from selection (§7).** Capture a selected link/input/etc.
  as a page-linked record without retyping it. (Live in-browser DOM click-to-
  register is not available through Montoya; selection-based capture is the
  supported path.)
- **Screenshot annotations (§9).** Draw prominent red rectangles with labels on a
  captured screenshot; they render as a scalable SVG overlay in the docs and
  carry coordinates/label/description metadata.

### Phase 4 — dynamic analysis
- **Page variants (§12).** The same URL can hold many variants, each recording
  its request conditions (query/body/JSON params, cookies, headers, auth) and
  response.
- **Variant diffing (§13).** Variants are compared against a baseline; input
  differences and behavior changes (status, length, title, structure, reflected
  inputs) are reported as structured lists on the page document.

### Phase 5 — parameter analysis
- **JSON parameters (§17).** JSON bodies flatten to dotted/indexed paths
  (`settings.theme`, `roles[0]`) and a single leaf can be changed while the rest
  of the structure is preserved.
- **Quick parameter testing (§14–§16, §18).** A configurable set of
  non-destructive probes (empty, omitted, long, special chars, reflection
  canaries, ...) can be sent for a query/body/JSON parameter after an explicit
  authorization confirmation. Results are characterized conservatively (observed
  behavior / potential issue — never auto-confirmed), saved into the project, and
  can be promoted to a finding by the tester.

Tests: 56. Jar builds against Montoya 2025.5; example project regenerates with
25 interlinked HTML files and no broken internal links.

> Verification note: the model, controller, analyzers and HTML generator are
> exercised by the JUnit suite and by regenerating the example project. The Burp
> Swing UI (dialogs, menus, live probe sending, annotation drawing) compiles
> against Montoya but must be verified inside a running Burp, as that GUI is not
> scriptable here.

### v1.1.1 — fix: page variants were not creatable from the Burp UI
Page variants (§12/§13) shipped with a data model, diffing and rendering, but no
context-menu action to *create* one — they could only be added via the API. Added
**Register as Page Variant…** to the request context menu, which
captures the request conditions (query/body/JSON params, cookies, auth context)
and the response via `RequestExtractor.toVariant(...)`. Variants now group by
**path** (query string ignored), so a page first registered with a query string
still collects all its variants under one page. Tests: 57.

### v1.1.2 — fix: menu hotkeys were non-functional and clashed with Burp
The context-menu items had `Ctrl+Shift+*` accelerators. On a Burp context menu
these never actually fire, and several collided with Burp's own global chords
(e.g. `Ctrl+Shift+P` opens Burp's Proxy tab). Removed all accelerators. Keyboard
use is now purely the menu **mnemonics**: open the Assessment Notebook submenu,
then press an action's underlined letter. The extension registers no global
shortcuts (Montoya exposes none, and a capture action needs the request selected
in the menu event, so a global chord would have no target). Docs corrected
accordingly. Tests: 57.

### v1.1.3 — fixes from the 2026-09-28 code review
- **Re-registering a page no longer duplicates its children.** Registering the
  same URL again now matches forms (action + method + identifier), parameters
  (name, or type for unnamed controls) and links (destination + element type)
  against what the page already has and updates them in place; parameter ids
  stay stable. An identical response body is not saved as a second source file;
  a changed body still is, as new evidence. Regression test: `ReregistrationTest`.
- **Regeneration cost.** The HTML generator now indexes links, screenshots,
  interactions, variants, notes and parameter tests per owner once per run
  instead of scanning every list for every page; entity lookups no longer use
  reflection; relationship de-duplication and link resolution are O(1) per edge;
  unchanged documents are not rewritten. Small edits (notes, findings,
  annotations, probe results, project setup, technologies, screenshots,
  interactions, variants of an existing page, resources) regenerate only the
  documents they affect. Page registration still does a full rebuild. Measured on
  a dense synthetic project: 200 registrations 155 s → 5.4 s; one note on a
  201-page project 1.8 s → 15 ms.
- **Parameter tests are recorded as one batch** (`recordParameterTests`): one
  save and one partial regeneration per run instead of one full rebuild per
  probe. The dialog shows which probe is in flight. Results are linked to the
  page's form parameter of the same name when there is exactly one, so they
  render on the form document; the completion message says when they could not
  be linked. Probe values are URL-encoded before insertion (the special-character
  probe previously split the query string), and the captured response is used as
  the baseline instead of re-sending the original request when one is available.
- **Dialogs belong to Burp's window.** Every dialog and message box is owned by
  the Burp suite frame (`swingUtils().suiteFrame()`) and centred on it instead
  of floating unowned on the primary screen. The tab and dialogs follow Burp's
  theme: the phosphor palette on the dark theme, Burp's own component theme on
  the light theme with darker accent variants. Accent colours (title, hints,
  the authorization checkbox) were previously wiped by the palette pass and now
  survive it. Page analysis, annotation saving, project-setup saving and
  opening the documentation in a browser run off the event thread; annotations
  are saved as one batch.
- Verified: 59 JUnit tests pass; jar builds against Montoya 2025.5; example
  project regenerates with the same content. The Swing changes compile but are
  not exercised headlessly.
