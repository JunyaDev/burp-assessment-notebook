# Assessment Notebook — In-Depth Guide

This is the practical, end-to-end guide to the Assessment Notebook Burp
extension: what it models, how to install it, how to use every capability to its
best advantage during an authorized engagement, and — as a worked case study —
exactly how the **Blind SQLi Lab** documented project was produced against a live
target on `localhost:3000`.

It is written for testers who want the fast, repeatable capture loop the tool was
built for, and for anyone who needs to reproduce or extend the lab walkthrough.

> **Scope and authorization.** Everything here assumes engagements you are
> contracted for, your own labs, or CTFs. The tool documents and organizes what
> you observe. Two features send traffic — *Test Parameter* (probes) and any
> request you replay — and both are gated behind an explicit authorization
> confirmation. Only point them at systems you are allowed to test.

---

## 1. What it is, and the model it uses

Assessment Notebook turns an assessment into a **navigable, self-documenting
knowledge base** rendered as interlinked, offline HTML. A project is a single
directory:

- `project.json` is the **source of truth** (one `Project` aggregate).
- Every HTML file, screenshot, saved source and wordlist is **derived** from it
  and regenerated after each change, so disk and documentation never drift.
- Links between documents are **relative**, so you can zip the folder, hand it to
  a teammate, or open `index.html` on a machine with no Burp.

The model is a **graph, not a request list**. The important idea: the same thing
discovered many ways is *one* entity with many edges.

```
Project
 ├── TargetInfo (domain, main URL, server, frameworks, auth, notes)
 ├── Technologies        (confidence + evidence + history)
 ├── Pages
 │    ├── Variants       (same URL, different request conditions)
 │    ├── Forms → Parameters → ParameterTests / Reflections
 │    ├── Links
 │    ├── Resources      (one canonical record, many "loaded by" pages)
 │    ├── Interactions
 │    └── Screenshots → Annotations
 ├── Requests / Responses (raw evidence on disk)
 ├── InterestingStrings  (wordlists)
 ├── Notes / Observations (with an investigative kind)
 └── Vulnerabilities     (wired by typed edges to their evidence)
```

Relationships are first-class typed edges (`discovered`, `redirects-to`,
`links-to`, `loads-resource`, `has-parameter`, `affects`, `evidenced-by`, ...),
which is what lets a finding walk down to the exact parameter, probe and request
that prove it.

---

## 2. Install and build

**Requirements:** Burp Suite Professional (or Community for most features; screen
capture and context menus work in both), and JDK 17+ only if you build from
source. The Montoya API is provided by Burp at runtime.

**Use the prebuilt jar** (`build/libs/assessment-notebook.jar`) or build it:

```bash
# No Gradle needed; javac + jar, deps vendored in lib/
bash scripts/build-jar.sh      # -> build/libs/assessment-notebook.jar
bash scripts/test.sh           # JUnit suite (56 tests)
```

**Load it into Burp:** Extensions → Add → Extension type **Java** → select the
jar. You should see a new **Assessment Notebook** suite tab and, on the extension
output, a "loaded" line.

---

## 3. First run: create a project and the setup wizard

1. Open the **Assessment Notebook** tab → **New / Open Project…** → choose an
   empty directory. If it has no `project.json`, you are asked for a name and a
   fresh project skeleton is created; if it does, it is opened. One project is
   active at a time and is shared by the tab and the right-click menu.
2. Browse the target through Burp for a few minutes so there is traffic to learn
   from.
3. Click **Project Setup…**. The wizard explains each field and **pre-fills**
   Domain, Main URL, Server and Frameworks from what it has seen. Correct
   anything and **Save**. Re-run it any time — it re-detects but keeps your
   non-blank edits.

The setup fields (Domain, Main URL, Server, Frameworks, Authentication, Notes)
appear on `index.html` and are what a reader sees first.

---

## 4. The capture loop (keyboard-first)

Day-to-day capture is a right-click on any request, anywhere in Burp (Proxy
history, Repeater, target site map, the message editor). The submenu is
**Assessment Notebook**, and each action has an underlined **mnemonic letter**:
open the submenu, then press the letter to run it.

| Action | Mnemonic |
|---|---|
| Register Page… | P |
| Register API Endpoint… | I |
| Register Resource… | R |
| Register as Page Variant… | B |
| Register Form… | F |
| Describe Parameters (purpose / notes)… | O |
| Add Note / Observation… | N |
| Mark Interesting String… | M |
| Register Element from selection… | E |
| Test Parameter (quick probes)… | T |
| Create Vulnerability… | V |
| Capture Screenshot… | S |
| Auto-Capture Rule from this Request… | U |
| Export Wordlists | W |
| Open Project Documentation | D |

> **No global hotkeys, by design.** The extension does not register any
> keyboard shortcuts. Montoya exposes no global extension hotkey, and a capture
> action needs the request you have selected — which only exists inside the
> context-menu event — so a global chord would have nothing to act on.
> Menu **accelerators** are also intentionally omitted: on a context menu Burp
> rebuilds each time they never fire, and chords such as `Ctrl+Shift+P` are
> Burp's own (that one opens the Proxy tab). The mnemonic letters above are the
> keyboard path that actually works and never collides with Burp.

### 4.1 Register Page

Right-click a request → **Register Page…**. The extension parses the
request/response, runs the HTML analyzer, and shows a **review dialog** with the
detected forms, links and resources. Untick anything you don't want, confirm the
discovery source, and **Register**. Nothing is committed until you confirm.
Registering the same URL+method again **updates** the page in place. The raw
request/response and the page source are saved under `source/` as evidence.

**Best-ability tip:** register the *page you care about*, then let its detected
forms/links/resources come along for free. You rarely need to register children
by hand.

### 4.2 Discovery source (what it means, and how it fills itself)

"Discovery source" answers **how you reached this page**, from a fixed
vocabulary: Direct navigation, HTTP redirect, Link from another page, Form
submission, JavaScript, XHR/fetch, Burp HTTP history, Burp Repeater, Manual,
Browser interaction, Source-code reference, API response, Other.

It is **inferred** at capture time from the `Referer`/XHR headers, the method,
and the originating tool, and **redirects are detected automatically**: when you
register a page that an earlier captured 3xx response pointed at, the tool adds a
`redirects-to` edge and marks the target as an HTTP redirect. You can always
override the kind and add a free-text detail (e.g. the referring URL) in the
Register dialog.

### 4.3 Register Resource, and canonical identity

Use **Register Resource…** for things that are not pages: scripts, stylesheets,
images, fonts, API/XHR endpoints, downloads, JSON, source maps, WebSockets. The
dialog lets you set the type, add notes, and **associate it with a page**.

The important behavior is **one canonical identity per resource**. Identity is a
normalized URL (`ResourceUrls`): lower-cased scheme/host, default ports and
`#fragments` dropped, and cache-buster queries ignored for static assets. So
`/static/app.js`, `/static/app.js?v=9f3a` and `/static/app.js#load` are the
**same** record, with a "loaded by" list of every page that references it, and a
specific type (SCRIPT) upgrades a generic one. This is what fixes the classic
"the same JS file appears as three unrelated entries" problem.

### 4.4 Forms, parameters, links

Forms and their inputs are captured as part of the page and rendered on the
form's own document, where each parameter shows its type, default, observed
values, reflections, and any parameter tests. Links become navigational edges;
when a link's destination is later registered as a page, a `links-to` edge
appears automatically.

**Describing parameters.** Every parameter has a **Purpose** ("what is this
for?") and free **Notes**. Edit them in the parameter table, which opens from:

- **Describe Parameters (purpose / notes)…** on a request — the forms of that
  request's page (or every form, if the request is not registered);
- the **Parameters…** button on the tab — every form in the project;
- **Register Form…**, and the *Then describe the parameters* tick-box in the
  Register dialog — straight after registering.

Pick a form, type into the Purpose and Notes cells, **Save**. The *Example
values* column shows the default plus values seen in traffic, which usually
tells you what the field carries. **Suggest Purposes** fills the empty Purpose
cells of the current form with a guess from the name (`csrf_token` → Anti-CSRF
token, `orderId` → Object identifier); it is only a starting point and nothing
is stored until you save. The window is not modal, so you can keep reading
requests in Burp while you annotate. Purposes and notes show on the form
document and in the parameter table of the page document, and re-registering a
page never overwrites them.

### 4.5 API endpoints (JSON / XHR) — register once

A JSON call is not a page a user sees, not a static file, and has no HTML form,
which makes "page, resource or form?" a guess. It is its own kind: an **API
endpoint**. Use **Register API Endpoint…** (or **Register Page…** — the dialog's
*Register as* box is preselected from the response type and request headers).
One registration records everything the three separate ones were approximating:

| What you wanted | Where it is on the endpoint document |
|---|---|
| the request's fields (the "form") | **Request parameters** — JSON body fields, flattened (`user.email`, `items[0].sku`), plus query parameters |
| what it returns | **Response fields** — the field paths of the JSON response (`items[].id`), and the saved, re-indented body under **Source** |
| which page uses it (the "resource" link) | **Called by** — taken from the request's `Referer`; the calling page lists it under **API calls** |
| how it behaves under other conditions | **Variants** and **Dynamic differences**, with added/removed response fields named |

Endpoints are marked `API` in the application tree and counted separately on
the overview. Use **Register Resource…** only for files: scripts, styles,
images, fonts, downloads.

### 4.6 Auto-capture rules

Right-clicking every request gets old quickly. **Auto-Capture…** on the tab
holds an ordered list of rules; with the master switch on, each response from
the tools a rule listens to (Proxy by default, optionally Repeater) is tested
against them and registered without a dialog. It is passive: it only reads
traffic Burp already handled and never sends a request.

A rule's conditions are all optional (blank means "any"):

| Condition | Example | Notes |
|---|---|---|
| Host | `app.example.com, *.example.com` | comma-separated globs |
| Path | `/api/*` or `re:^/v\d+/users` | glob on the path, or a regex on path?query |
| Methods | `GET, POST` | |
| Response type | `json, html` | parts of the response `Content-Type` |
| Status | `200, 3xx, 400-404` | |
| In scope only | | uses Burp's target scope |

and its **Register as** says what a match becomes: *Auto-detect* (the usual
choice — HTML becomes a page, JSON/XML/XHR an API endpoint, scripts, styles,
images and fonts resources), a fixed *Page* / *API endpoint* / *Resource*, or
*Ignore*. Rules are tried top to bottom and the **first match decides**, so put
*Ignore* rules (health checks, analytics, `/assets/*`) above the broad one.

The quickest start: right-click any request to the target →
**Auto-Capture Rule from this Request…** creates a rule for that host and
switches capture on. **Apply to Proxy History…** in the rules dialog runs the
rules over what you already browsed.

**Nothing is registered twice.** A page is identified by method and path; the
query string is ignored and, by default, id-like path segments (numbers, UUIDs,
long hex) are collapsed, so `/api/users/17` and `/api/users/42?full=1` are one
record shown as `/api/users/{id}`. When a known page is seen again the capture
is compared on four things:

- the **status code**,
- the **names** of the request parameters (query, body, JSON),
- whether the request carried an `Authorization` header,
- the **structure** of the response — the set of JSON field paths, or the set
  of HTML element paths and form-field names.

If all four match something already documented, the capture is dropped (only
new example values for its parameters are kept). If any differs, it is added as
a **page variant** labelled with the difference — `status 200 → 403`,
`new parameter debug`, `response structure changed` — the first capture is kept
alongside it as the baseline, and any new forms, links, resources and response
fields are merged into the page. Values are deliberately not compared: another
search term, record or CSRF token is the same page. A page stops collecting
automatic variants at the limit set in the dialog (10 by default).

The tab's status line shows what auto-capture has registered and skipped this
session. Static resources are registered by URL (one record per file, linked to
the page that loaded it); responses over 5 MB are noted by URL only.

---

## 5. Technology detection and editable records

Every page you register is scanned automatically (`TechnologyDetector`) using
HTTP headers, cookies, meta tags, and script/link URLs. Detections carry a
**confidence** and the **evidence** that produced them, and they **merge** into
the technologies table rather than duplicating:

- a more specific version and a higher confidence **update in place**;
- new evidence **accumulates**;
- every change is logged to a **history**;
- a record you have edited (`userEdited`) is **never overwritten** by later
  detection.

**Best-ability tips:**
- Treat auto-detection as a first draft. Open a technology, fix a false positive
  or refine a version, and it locks against the detector.
- The overview's Technologies table shows category, name, version, confidence,
  evidence and last-updated — good raw material for the report's "environment"
  section.

---

## 6. Interesting strings and wordlists

Select text in any Burp editor → **Mark Interesting String…**. Pick a category
(General, Directories, Parameters, Usernames, Technology-specific, Application
terminology, Custom), optionally note the element and context, and it is stored
with its source page. **Export Wordlists** writes one `wordlists/<category>.txt`
per non-empty bucket, deduplicated and sorted, ready for other authorized tools.

**Best-ability tips:**
- Bucket as you go: put usernames in Usernames, endpoints in Directories, odd
  identifiers in Custom. The exported files are then immediately useful.
- Leaked internal names (column names, service names, feature flags) are gold —
  mark them under Technology-specific or Application terminology.

---

## 7. Element capture from a selection

**Register Element from selection…** captures whatever you have selected in a
Burp message editor (a link, input, hidden field, script reference, interesting
parameter, ...) as a record linked to the current page, so you don't retype the
URL, text, HTML or selector. Choose the element type, edit the captured text if
needed, and save.

> True in-browser DOM "hover and click to register" is not available through the
> Montoya API. Selection-based capture from the request/response or source view
> is the supported path.

---

## 8. Screenshots and annotations

**Capture Screenshot…** (the page must be registered) waits an optional delay so
you can arrange the window or open a menu, then captures the screen as the next
visual state. A page can hold a **sequence** of states (default → menu open →
dialog shown) that the generated page steps through in order.

After capture you are offered **annotation**: drag prominent red rectangles
around elements and give each an optional label. Annotations render as a
**scalable SVG overlay** in the documentation (they stay aligned at any size) and
carry coordinates, label and description metadata — useful both during the
assessment and in the final report. Multiple rectangles per screenshot are
supported.

---

## 9. Page variants and dynamic differences

The same URL can behave differently depending on query/body/JSON parameters,
cookies, headers, or auth state. Capture each with right-click → **Register as
Page Variant…**; they group under one page by path, so a page registered with a
query string still collects all its variants. Each
records its **request conditions** and the response (status, length, title, a
structural fingerprint, and which inputs were reflected).

The page document then shows, for every variant against the first as a
**baseline**, which **inputs changed** and which **behaviors changed** (status,
length, title, "page structure changed", reflected inputs). This is how you
answer, mechanically, *which request inputs cause which application behavior
changes* — the core question behind differential testing and blind injection.

---

## 10. Parameter analysis and quick probing (including JSON)

Right-click a request → **Test Parameter…**. Pick a parameter — from the query
string, the form body, or a **flattened JSON body** (`settings.theme`,
`roles[0]`) — choose which **non-destructive probes** to run, **confirm you are
authorized**, and run.

The default probe set is deliberately safe: original, empty, omitted, short,
long, numeric, string, whitespace, special characters, and two inert
**reflection canaries** (a quote canary and a markup canary — distinctive
markers, never executable payloads). For JSON, only the selected leaf is changed;
the rest of the structure is preserved.

Each result is recorded with its status, length and whether the value reflected,
and **characterized conservatively**:

- **Observed behavior** — the neutral default.
- **Potential issue** — e.g. a reflection canary that actually reflected.
- **Confirmed vulnerability** — *only* when you promote it.

A per-parameter **summary** (required / accepts-empty / optional / reflected /
status-varies / length-varies / length-limited) is derived from the results and
shown on the form document. Results are permanent project knowledge and link back
to the page, parameter and request.

**Best-ability tips:**
- Start with the default set to *characterize* a parameter, then narrow to the
  probes that moved the response.
- A probe that flips the status (e.g. a lone quote → 500) or the length is your
  lead. Promote the telling one to a finding (below).

---

## 11. Findings and the evidence chain

Raise a finding with **Create Vulnerability…**, or **promote** a probe result
straight into one. A finding links by typed edges to the components it involves —
parameter, probe/test, page, technology — so the finding document shows the full
chain from claim to evidence. Fill in severity, description, technical
observation, steps, impact and remediation (editable in `project.json` or via the
UI), and the report writes itself from the graph.

The tool never auto-classifies anything as a confirmed vulnerability. You decide;
it preserves the reasoning, including rejected hypotheses (Note kinds:
observation, hypothesis, TODO, confirmed, rejected, note).

---

## 12. Reading and handing off the documentation

**Open Documentation** opens `index.html`: target overview, technologies table,
application tree, discovery paths, findings, wordlists, and recent observations.
Every node links down to its detail document. Because the folder is
self-contained with relative links, zip it and hand it over, or open it anywhere
without Burp. It is the raw material for the report.

---

## 13. Getting the most out of it — a playbook

1. **Create the project first, browse a little, then run the Setup Wizard.** Let
   it infer the environment; correct it once.
2. **Register the pages you care about, not every request.** Children come along
   automatically; the graph fills itself.
3. **Trust discovery inference, fix it when wrong.** Redirect chains and referer
   links wire themselves; override only the odd case.
4. **Let technology detection run, then curate.** Fix false positives and
   versions; your edits lock.
5. **Mark strings the moment you notice them.** Bucket them; export wordlists at
   the end of a session.
6. **Capture a variant whenever the same URL behaves differently.** The diff is
   where dynamic behavior becomes visible.
7. **Probe parameters to characterize, not to attack.** Use the default safe set;
   promote only what you have judged real.
8. **Annotate screenshots for the report as you capture them**, while you still
   remember what mattered.
9. **Keep observations, not just conclusions.** Hypotheses and rejected leads are
   part of the record.
10. **Regeneration is free and idempotent** — every action rewrites the HTML from
    `project.json`, so you can always re-open the docs and trust them.

---

## 14. Keyboard reference

See the mnemonic table in §4. The extension defines no global shortcuts and no
menu accelerators (see the note in §4 for why). The keyboard path is: open the
**Assessment Notebook** submenu, then press an action's underlined letter
(P, I, R, B, F, O, N, M, E, T, J, V, S, U, W, D).

---

## 15. Data model and files on disk

```
project.json          the model (source of truth)
index.html            generated overview
assets/               retro theme CSS + offline highlighter + page script
pages/  forms/  links/  scripts/  files/  vulnerabilities/   generated docs
wordlists/            exported <category>.txt + an index.html
screenshots/          captured PNGs
source/               saved page source, variant bodies, raw request/response
```

Entity id prefixes: `page`, `res`, `link`, `form`, `param`, `tech`, `req`,
`resp`, `shot`, `note`, `vuln`, `var` (variant), `istr` (interesting string),
`ptest` (parameter test), `anno` (annotation). Ids are stable, so links never
break as new things are discovered. Full reference: `docs/DATA_MODEL.md`.

---

## 16. Case study — how the Blind SQLi Lab project was produced

The target was a local, authorized lab on `http://localhost:3000`: an Express
front end over Microsoft SQL Server, with a search endpoint that concatenates a
username filter into a SQL query. The produced project lives in
`blind-sqli-lab-notebook/` (open its `index.html`).

### 16.1 The intended path — inside Burp

In a normal engagement you would do exactly this, all from the right-click menu:

1. Proxy the app through Burp; open the **Assessment Notebook** tab and create a
   project; run **Project Setup…** (it detects Express from `X-Powered-By`).
2. **Register Page…** on `GET /` (captures the search form and its fields),
   `GET /search?q=juniper`, and `POST /search`.
3. **Mark Interesting String…** on `juniper`, the parameter names, and the leaked
   result-column names `dan_id` / `dan_username` / `dan_email`; **Export
   Wordlists**.
4. In Repeater, send `q=juniper' AND '1'='1` and `q=juniper' AND '1'='2`, and
   right-click each response → **Register as Page Variant…**; read the **dynamic
   differences** panel.
5. **Test Parameter…** on `q` with the default probes (authorization confirmed).
6. **Capture Screenshot…** of the found vs. not-found result and **annotate** the
   differing row.
7. **Promote** the telling probe to a finding and fill in the narrative.

### 16.2 How this run was actually done — headless driver

Because Burp's Swing GUI cannot be scripted in the environment this was performed
in, the same work was done by driving the extension's **Burp-independent core**
directly. The controller, analyzers, technology detector, variant differ and HTML
generator have no dependency on Burp, so a small driver fetched the *real* lab
traffic over HTTP and fed it through the identical code path the menu uses:

- `HttpClient` fetched `/`, `/search?q=...` and `POST /search`; each response was
  turned into a `PageRegistration` (URL, method, status, content type, headers,
  raw bytes, body) exactly as `RequestExtractor` does in Burp, and handed to
  `NotebookController.registerPage(...)`.
- `NotebookController.suggestProjectMetadata()` + `applyProjectSetup(...)` filled
  the target facts (this is the wizard's engine).
- `TechnologyDetector` ran automatically during registration (Express + Node.js
  from headers); SQL Server was added and confirmed with
  `addTechnology(...)` / `updateTechnology(...)`.
- `addInterestingString(...)` + `exportWordlists()` produced the wordlists.
- `registerVariant(...)` recorded the four `/search` variants;
  `compareVariants(...)` produced the differences.
- The probe set from `ProbeGenerator.generate("juniper")` was sent and each
  result recorded with `recordParameterTest(...)`; the quote-canary result was
  promoted with `promoteTestToFinding(...)`.
- `addScreenshot(...)` + `addAnnotation(...)` stored the (synthesized) result
  images with red callouts.

Everything went through the shipped controller and generator, so the output is
byte-for-byte what the extension produces from Burp — only the traffic source
differed. The driver is a thin harness kept outside the extension; it is not part
of the loadable jar. Running this also surfaced and fixed one real bug in the
tool: the metadata inference had dropped non-default ports, so the domain now
correctly reads `localhost:3000`.

### 16.3 What the oracle looked like

The variant diff captured the blind-SQLi oracle cleanly, comparing each variant
to the `q=juniper` baseline:

```
q=zzzznotreal            length: 586 -> 523 (-63), page structure changed
q=juniper' AND '1'='1    (no observable change from baseline)
q=juniper' AND '1'='2    length: 586 -> 523 (-63), page structure changed
```

The TRUE injection is **indistinguishable** from the legitimate result, while the
FALSE condition collapses to the empty-result page — a textbook boolean oracle,
made visible by the differences panel. Parameter probing corroborated it: the
single-quote canary returned **HTTP 500** (a SQL syntax error from the unbalanced
quote), which was promoted to the confirmed finding and wired to the `q`
parameter, the probe, and the SQL Server technology.

To reproduce the driver run (against your own authorized lab):

```bash
# Build the extension classes, then compile and run the driver against :3000
bash scripts/build-jar.sh
CP="build/classes:lib/gson-2.11.0.jar:lib/jsoup-1.18.1.jar:src/main/resources"
javac -d /tmp/nbdriver -cp "$CP" LabDriver.java
java  -cp "/tmp/nbdriver:$CP" LabDriver ./blind-sqli-lab-notebook
open ./blind-sqli-lab-notebook/index.html
```

---

## 17. Troubleshooting

- **"Open or create a project first."** The tab has no active project; use
  **New / Open Project…**.
- **Screen capture unavailable / headless.** Screenshots need a display; on a
  headless host, import images or run Burp with a desktop.
- **Nothing detected as a technology.** Detection needs the response headers
  and/or body; register the page with its response present.
- **A parameter isn't offered in Test Parameter.** Only query, body and JSON
  parameters are enumerated; cookies and path segments are not probed here.
- **Docs look stale.** Every action regenerates them; click **Refresh** or
  re-open `index.html`.
- **Documents don't link to each other, things are listed twice or in the wrong
  place.** Run `scripts/repair-project.sh` on the project directory (§18).
- **Test Parameter won't run.** You must tick the authorization confirmation and
  select at least one probe.

---

## 18. Checking and repairing a project

A project can drift out of shape: it was made by an older version, a save was
interrupted, the directory was copied between machines, `project.json` was
edited by hand, or the same thing was registered several ways. The symptoms are
documents that do not link to each other, pages missing their forms, the same
script listed twice, an API call filed as a resource. `scripts/repair-project.sh`
checks for all of that and repairs what it can.

```bash
scripts/repair-project.sh /path/to/project            # report only: nothing is changed
scripts/repair-project.sh /path/to/project --apply    # fix, rebuild documents, check links
scripts/repair-project.sh /path/to/project --apply --prune
scripts/repair-project.sh --help
```

It runs outside Burp, on the project's files, with the same model and document
generator as the extension (it builds the jar first if needed). **Close the
project in Burp before repairing, or re-open it right afterwards** (*New / Open
Project…* → the same directory): the extension keeps the project in memory and
would otherwise save its old copy over the repaired one.

**It is safe to run.** The default is a dry run. With `--apply`,
`project.json` is copied to `project.json.bak-<time>` before it is rewritten.
Nothing you wrote is deleted: a note whose subject is gone is kept at project
level, and a record that cannot be reconnected is reported, not removed.

What it checks, and what `--apply` does about it:

| Area | Problem | Repair |
|---|---|---|
| Model structure | empty/`null` lists and fields, unknown enum values | restored to defaults |
| | id counter behind the ids in use (the next registration would reuse an id) | counter raised |
| | records with no id, or two with the same id | given a fresh id |
| | `project.json` unreadable after an interrupted save | recovered from `project.json.tmp`; the broken file is kept |
| Dangling references | lists and fields naming records that do not exist; relationship edges to nothing | removed / cleared |
| | notes on a record that no longer exists | moved to the project, saying what they were about |
| Missing connections | a form, link, variant, screenshot or interaction and its page disagree about who owns it | re-attached (the page's list wins; otherwise the child's own reference, a relationship edge or, for a variant, its URL) |
| | page ↔ resource "loads" lists out of step | brought into agreement |
| | links whose destination is a registered page but were never resolved | connected, ignoring port, case, trailing slash and fragment |
| | redirects, findings and API callers registered in the "wrong" order | connected from the saved responses, URLs and `Referer` headers |
| | parameter tests not tied to their parameter | linked when the page has exactly one parameter of that name |
| Resources | URL not in canonical form; the same file recorded twice | normalized; merged into one record with every loader |
| | type contradicts the file extension | corrected (`.js` → JavaScript, …) |
| | JSON/XHR page filed as an ordinary page | becomes an API endpoint (§4.5) |
| | API call registered both as an endpoint and as a resource | resource folded into the endpoint: its loaders become callers, its notes move over |
| Evidence files | paths from another machine (absolute, backslashes) | rewritten relative; a moved file is found again by name |
| | references to files that are gone | dropped, so documents stop pointing at them |
| Documents | documents for records that no longer exist, or in the wrong folder | removed; every document is regenerated |
| | internal links that lead nowhere | reported after regeneration (there should be none) |

Some things only you can decide, and are listed as **needs attention**: forms
or parameters that belong to no page, evidence files no record refers to,
screenshots whose image is missing, two pages registered under equivalent URLs.
`--prune` (with `--apply`) resolves the first three: it removes unreachable
records that carry none of your notes, purposes or tests, moves unreferenced
files into `_orphaned/` (moved, never deleted), and drops screenshot records
whose image is gone.

`--no-reclassify` leaves page kinds and endpoint resources as they are, for a
project where a JSON response was deliberately registered as a page.

The exit status is `0` when the project is consistent, `1` when problems were
found (dry run) or left for you (repair), and `2` when the project could not be
read — so the check can run in a script. Every fix is stable: running the
repair again finds nothing more to fix.

---

## Appendix — safety and authorization

- The tool captures and organizes; it does not exploit. The only outbound traffic
  is *Test Parameter* probes and requests you explicitly replay.
- Probes are **non-destructive** by design — no SQL/OS payloads that change state,
  no destructive fuzzing. Canaries are inert markers.
- *Test Parameter* requires an explicit **"I am authorized"** confirmation before
  sending anything.
- Nothing is classified as a confirmed vulnerability automatically; promotion to
  a finding is always a human decision.
- Keep projects for authorized engagements only, and treat captured evidence
  (which can include credentials and tokens in raw traffic) as sensitive.

---

*See also: `README.md` (overview), `docs/WORKFLOW.md` (the day-to-day loop),
`docs/DATA_MODEL.md` (entities and edges), `docs/ASSESSMENT.md` (the review that
drove v1.1), and `RELEASE_NOTES.md` (what changed).*
