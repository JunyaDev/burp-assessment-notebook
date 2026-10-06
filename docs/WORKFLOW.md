# Assessment workflow

This is the day-to-day loop the extension is built around: browse the target
through Burp as usual, and capture what matters into a project as you go. The
documentation regenerates itself after every action.

## 1. Create or open a project

Assessment Notebook tab → **New / Open Project…** → choose an empty directory.
If it has no `project.json`, you are asked for an assessment name and a fresh
project is created (skeleton directories + `index.html`). If it already has one,
it is opened. One project is active at a time and is shared by the tab and the
right-click menu.

## 2. Record target and technologies

Open `project.json` and fill in `target` (domain, main URL, name, date, notes),
or edit it later. Technologies are structured entries grouped by category; add
them from your own analysis. (Programmatically, the same is `addTechnology`.)

## 3. Register pages as you browse

Right-click a request (Proxy history, Repeater, target site map, anywhere) →
**Assessment Notebook → Register Page…**. The extension:

1. parses the request/response (URL, method, status, content type),
2. runs the HTML analyzer to detect forms, inputs, links and resources,
3. shows them to you in a review dialog.

Untick anything you don't want recorded, adjust the discovery source, and
**Register**. Nothing is committed until you confirm. Registering the same URL
again updates the existing page rather than duplicating it. The raw request and
response and the page source are saved under `source/` as evidence.

When you later register a page that an earlier page linked to, the link resolves
automatically and a `links-to` edge appears between them.

## 4. Capture visual and interactive states

Right-click a request → **Capture Screenshot…** (the page must be registered).
Set a short delay, arrange the target window or open the menu/dialog you want,
and it captures the screen as the next visual state. Repeat to build a sequence
(default → menu open → dialog shown); the page document steps through them in
order. Pair a screenshot with an interaction to record "what I did → what
happened".

## 5. Keep observations, not just conclusions

Right-click → **Add Observation…**, choose a kind (observation, hypothesis, TODO,
confirmed finding, rejected hypothesis) and write freely. It attaches to the
matching page, or to the project if none matches. Reflections of a submitted
value are recorded with their context automatically when you analyze a parameter,
and are never auto-labeled as vulnerabilities — you decide.

## 6. Raise findings and wire them to evidence

Right-click → **Create Vulnerability…** (URL pre-filled). Fill in severity, and
later the description, steps, impact and remediation in `project.json`. A finding
at a page's URL is linked to that page automatically; you can add `affects` and
`evidenced-by` edges to the exact form, parameter, request or screenshot. Those
edges are what let the finding document show the full chain.

## 7. Read and hand off the documentation

**Open Documentation** opens `index.html`: the target overview, the technologies
table, the application tree, discovery paths, findings, and recent observations.
Every node links down to its detailed document. Because the folder is
self-contained and uses relative links, you can zip it, hand it to a teammate, or
open it on a machine without Burp — it is the raw material for the report.

## Notes on regeneration and portability

- Every mutating action saves `project.json` and rebuilds all HTML from it, so
  disk and documentation never drift.
- Your notes and findings live in the model, so regeneration never erases them,
  and stable ids mean links are never broken by new discoveries.
- Screenshots and source are stored as files and referenced by relative path;
  copying the project directory copies everything.

## v1.1 workflows

### Keyboard-driven capture

Right-click a request → **Assessment Notebook**. Each action has an underlined
**mnemonic letter** (Register **P**age, **M**ark Interesting String, **T**est
Parameter, ...): open the submenu, then press the letter. The extension sets no
global shortcuts and no menu accelerators — those would not fire on Burp's
context menu and would collide with Burp's own chords (e.g. `Ctrl+Shift+P` opens
Burp's Proxy tab).

### Guided project setup

Tab → **Project Setup…** opens a wizard that explains every field and pre-fills
Domain, Main URL, Server and Frameworks from the traffic captured so far. Edit
anything and save; re-run it any time to re-detect.

### Automatic technology detection

Every page you register is scanned for technologies (headers, cookies, meta
tags, script/link URLs). Results appear in the overview's technologies table
with confidence and evidence. Detection updates existing records in place
(upgrading versions, raising confidence, logging history); your manual edits are
never overwritten.

### Interesting strings and wordlists

Select text in any Burp editor → **Mark Interesting String…**, pick a category
(usernames, directories, parameters, ...). **Export Wordlists** writes
`wordlists/<category>.txt` for use by other authorized tools.

### Page variants and dynamic differences

Register the same URL under different conditions with **Register as Page
Variant…** (right-click a request); the page document
lists them and shows, against the first as a baseline, which inputs changed and
which behavior changed (status, length, title, structure, reflected inputs).

### Quick parameter testing

Right-click a request → **Test Parameter…**, choose a query/body/JSON parameter
and a set of non-destructive probes, confirm authorization, and run. Each result
is characterized conservatively (observed behavior / potential issue — never
auto-confirmed) and saved to the project; promote a real one to a finding.

### Screenshot annotations

After **Capture Screenshot…**, choose to annotate: drag red boxes around
elements and label them. They render as a scalable overlay in the docs and are
report-ready.

## v1.3 workflows

### Auto-capture

Instead of registering requests one by one, right-click a request to the target
→ **Auto-Capture Rule from this Request…**, accept the rule, and browse. Pages,
API endpoints and resources are registered as their responses pass through the
Proxy; a page already in the notebook is skipped unless it answers differently
(status, parameter names, authentication, response structure), in which case it
is added as a page variant. Manage the rules, add *Ignore* exceptions and apply
them to existing Proxy history from **Auto-Capture…** on the tab. Manual
registration still works alongside it and is never duplicated by it.

### JSON / single-page / Flutter applications

Register a data call once, as an **API endpoint** (auto-capture does this by
itself). The endpoint document holds its request parameters, its response
fields, the page that calls it and its variants, so there is no reason to also
register it as a resource or a form.

### Describing parameters

**Describe Parameters…** on a request (or **Parameters…** on the tab) opens a
table of each form's parameters where you record what each one is for and any
notes; both appear on the form and page documents.
