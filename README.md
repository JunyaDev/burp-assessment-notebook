# Assessment Notebook

A Burp Suite Professional extension that turns an authorized web application
assessment into a navigable, self-documenting knowledge base. It records what
you observed, preserves how the application's components connect, and renders
everything as a set of interlinked HTML files you can browse, analyze, and turn
into a report.

It is a **persistent assessment notebook, application map, evidence repository,
and documentation generator** built into Burp. It does not attack anything and
sends nothing anywhere: it reads the traffic you select in Burp and writes files
to a project directory on your disk.

> Intended for authorized testing only (engagements you are contracted for, your
> own labs, CTFs). It captures and organizes observations; it does not exploit.

---

## What it does

- **Project = a directory of interlinked HTML.** Copy the folder and the whole
  documentation, evidence, and links travel with it. No database, no server, no
  network needed to read it — open `index.html` in any browser.
- **Application map, not a request list.** Pages, forms, parameters, links,
  resources, requests, screenshots, observations and findings are modeled as a
  **graph**: the same resource can belong to many pages, and a finding can be
  traced to the exact form, parameter and request that evidence it.
- **Capture from the right-click menu.** Select a request anywhere in Burp and
  register it as a page (with its detected forms/links/resources shown for your
  review first), add an observation, create a finding, or grab a screenshot.
- **Reflection recording, not guessing.** Where a submitted value is echoed back,
  the context (HTML text, attribute, script, JSON, header, ...) is recorded as an
  observation. It is never auto-labeled a vulnerability — you classify it.
- **Sequential visual states.** A page can hold many screenshots (default, menu
  open, dialog shown, ...) that the generated page steps through in order, so
  dynamic UI that the initial HTML never shows is preserved.
- **Retro-futuristic phosphor theme** across both the Burp panel and the
  generated documentation, so the tool reads as one system.

### v1.1 additions
- **Keyboard capture via menu mnemonics** (no global hotkeys), a **project setup
  wizard**, and **automatic technology detection** with a confidence/evidence
  model that updates records instead of duplicating them.
- **Canonical resources**: one file/endpoint is one record with many loaders,
  even when discovered many ways (fixes JS relationship duplication).
- **Structured, inferred discovery source**; **interesting-string wordlists**;
  **screenshot annotations**; **page variants with dynamic diffing**; and
  **quick, non-destructive parameter probing** (incl. JSON parameters) whose
  results save into the project. See [`docs/ASSESSMENT.md`](docs/ASSESSMENT.md)
  and [`RELEASE_NOTES.md`](RELEASE_NOTES.md). A full walkthrough of every
  capability, plus how the lab project was produced, is in
  [`docs/GUIDE.md`](docs/GUIDE.md).

### Auto-capture, API endpoints and parameter notes
- **Auto-capture rules**: define what to register (host, path, method, response
  type, status, scope) and pages, API endpoints and resources are registered as
  you browse, or from existing Proxy history. Known pages are skipped; a page
  that answers differently is added as a **page variant** labelled with the
  difference.
- **API endpoints**: a JSON/XHR call is registered once, with its request
  parameters, response fields and the page that calls it — no more registering
  it as a page, a resource and a form to be safe.
- **Parameter purpose and notes**: a table for recording what each form or
  request parameter is for, with example values from traffic.

Every section of the original specification maps to a feature; see
[`docs/WORKFLOW.md`](docs/WORKFLOW.md) and [`docs/DATA_MODEL.md`](docs/DATA_MODEL.md).

---

## Build

Requires JDK 17+.

### With Gradle

```bash
gradle shadowJar        # -> build/libs/assessment-notebook-1.0.0-all.jar
gradle test             # run the JUnit 5 suite
gradle generateExample  # (re)build ./example-project
```

### Without Gradle (plain javac)

The `scripts/` build needs no build tool; it fetches three jars from Maven
Central into `lib/` and uses `javac`/`jar` directly.

```bash
scripts/fetch-deps.sh    # download gson, jsoup, montoya-api, junit-console
scripts/build-jar.sh     # -> build/libs/assessment-notebook.jar  (load this into Burp)
scripts/test.sh          # compile and run the tests
```

Both paths bundle gson and jsoup into the jar and leave the Montoya API out, since
Burp provides it at runtime.

---

## Install into Burp Suite Professional

1. Build the jar (above).
2. Burp → **Extensions → Add**.
3. Extension type: **Java**.
4. Select `build/libs/assessment-notebook.jar` (or the `-all.jar` from Gradle).
5. A new **Assessment Notebook** tab appears; the output log confirms it loaded.

The extension declares its entry point through
`META-INF/services/burp.api.montoya.BurpExtension`, so Burp finds it automatically.

---

## Quick start

1. Open the **Assessment Notebook** tab → **New / Open Project…** → pick an empty
   directory and name the assessment. The project skeleton and `index.html` are
   created immediately.
2. Fill in target and technology details (edit `project.json`, or let capture
   populate structure as you browse).
3. As you work, right-click a request → **Assessment Notebook**:
   - **Register Page…** — review the detected forms/links/resources, untick
     anything you don't want, then commit.
   - **Auto-Capture Rule from this Request…** — register this host's pages,
     API endpoints and resources automatically from now on.
   - **Describe Parameters…** — note what each parameter is for.
   - **Add Observation…** — record an observation, hypothesis, TODO, confirmed
     finding, or rejected hypothesis, attached to the matching page.
   - **Create Vulnerability…** — start a finding, pre-filled with the URL.
   - **Capture Screenshot…** — grab the screen (with a delay to arrange menus)
     as the next visual state of the page.
4. **Open Documentation** (tab button or menu) to browse the living `index.html`.

The documentation is regenerated from the model on every change, so it never goes
stale, and your notes live in the model — regeneration never overwrites them.

---

## Project layout

```
project/
├── project.json        machine-readable model (the source of truth)
├── index.html          generated overview: target, tech, app tree, findings
├── assets/             shared retro theme CSS, offline highlighter, page script
├── pages/              one document per page
├── forms/              one document per form (parameters, reflections)
├── links/              index of all discovered links
├── scripts/            documents for script/style/API/XHR resources
├── files/              documents for image/font/other resources, imported files
├── vulnerabilities/    one document per finding
├── screenshots/        captured images
└── source/             saved page source and raw request/response bytes
```

All links between documents are relative, so the folder is portable.

---

## Example project

A complete sample (invented traffic, no real target) is in
[`example-project/`](example-project/) — open `example-project/index.html`.
Rebuild it any time with `gradle generateExample` or:

```bash
java -cp "build/classes:src/main/resources:lib/gson-2.11.0.jar:lib/jsoup-1.18.1.jar" \
  com.assessmentnotebook.example.ExampleProjectGenerator example-project
```

Because it is produced by the real model and generator, it always reflects the
extension's actual output.

---

## Repairing a project

`scripts/repair-project.sh <project-dir>` checks a project for inconsistencies
(dangling references, pages that lost their forms or links, unresolved links,
duplicated or mistyped resources, API calls filed as resources, moved evidence
files, stale documents) and reports them. Add `--apply` to fix them, rebuild
every document and verify every internal link; `project.json` is backed up
first. See [`docs/GUIDE.md`](docs/GUIDE.md) §18.

## Testing

JUnit 5 covers the Burp-independent core: HTML parsing, reflection-context
detection, persistence round-trips, the application graph/tree, and end-to-end
registration + HTML generation. Run `gradle test` or `scripts/test.sh`.

The Burp UI layer (Montoya, Swing) is thin glue over that tested core and is
verified by compiling against the Montoya API.

---

## Documentation

- [`docs/WORKFLOW.md`](docs/WORKFLOW.md) — the assessment workflow end to end.
- [`docs/DATA_MODEL.md`](docs/DATA_MODEL.md) — entities, relationships, the schema.
- [`docs/EXTENDING.md`](docs/EXTENDING.md) — architecture and how to add capabilities.
