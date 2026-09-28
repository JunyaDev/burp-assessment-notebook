# Assessment of the existing extension

This is the review that precedes the improvement work. It records how the
extension behaves today, how each workflow was exercised, and every defect or
gap found, with file/line evidence. The improvement plan and its progress live
in [`RELEASE_NOTES.md`](../RELEASE_NOTES.md).

## How it was exercised

The extension's capture, modelling and documentation logic lives in
`NotebookController`, the `model` package, `analyze`, `graph` and `html`, none
of which depend on Burp. It was exercised three ways without a running Burp GUI:

1. **Unit suite** — `bash scripts/test.sh` (20 tests) covering the analyzer,
   reflection detector, project store, graph and HTML generator.
2. **End-to-end example** — `ExampleProjectGenerator` drives the *real*
   controller and generator to build `example-project/`, so its `project.json`
   and HTML are exactly what the extension produces. Its output was inspected
   directly.
3. **Code review** of the Burp glue (`ContextMenuProvider`, `RequestExtractor`,
   `MainTab`, `RegisterPageDialog`), which is the only part that needs a running
   Burp and so cannot be driven headlessly.

> The Burp Swing GUI itself is not scriptable in this environment. Everything
> below about the menu layer is from code review; everything about the model,
> controller and generator is confirmed by running them.

## What works

- **Project = a directory of interlinked HTML.** Create/open, atomic saves,
  regenerate-everything-from-JSON, portable relative links. Solid.
- **Register Page** parses the request/response, runs the HTML analyzer, and
  shows a review dialog where detected forms/links/resources can be unticked
  before commit. Re-registering the same URL+method updates in place.
- **The graph is real.** Relationships are first-class typed edges; discovery
  paths, "loaded by" lists and the path tree are all derived from them. The
  resource document already lists every page that loads it
  (`HtmlGenerator.buildResource`), so the *display* side of shared resources is
  correct — only the *identity* side (below) is wrong.
- **Reflection detection** records context (HTML text/attribute, JS, JSON,
  header) as an observation, never auto-classified. Screenshots support a
  sequence of visual states.

## Defects and gaps found

### D1 — `Register Resource` appears to do nothing (spec §10)
`ContextMenuProvider.registerResource` (line ~96) calls
`controller.addResource(url, type)` and logs one line. There is **no dialog, no
visible confirmation, and no association with the page** the request came from,
so the tester sees nothing happen. It will also happily register an HTML
document as a stray `OTHER` resource, duplicating Register Page. The controller
method works; the *action* is unusable.

### D2 — Resource identity is keyed on URL **and** type (spec §10, §11)
Both `registerResource` (line ~180) and `addResource` (line ~214) dedupe with
`existing.url.equals(dr.url) && existing.type == type`, an **exact string**
match. Consequences:
- The same `/static/app.js` discovered as `SCRIPT` from page analysis and
  registered as `OTHER`/`API` from the menu becomes **two** records.
- Trailing slash, case, `#fragment` or `?cachebust` differences duplicate a
  resource that is really one file.
- This is the direct cause of the "JavaScript files are not connected" problem
  in §11: a JS file has no single canonical identity.

### D3 — Discovery source is unstructured and not inferred (spec §3)
`Page.discoverySource` is a free-text string. The menu hard-codes
`"Burp: selected request"` (`ContextMenuProvider.registerPage`), the example
uses ad-hoc phrases ("linked from home", "302 redirect from /session"). There
is no controlled vocabulary and nothing is inferred from the traffic (a 3xx +
`Location`, a referring page, the originating Burp tool).

### D4 — No structured project initialization (spec §4)
`TargetInfo` is edited by hand in `project.json`; `MainTab` only asks for a
name. Nothing infers domain, main URL, server or technologies from traffic.

### D5 — Technology records are thin and manual (spec §5, §6)
`Technology` has no confidence, no evidence list, no first-observed/last-updated
distinct from create/update, and there is **no automatic detection** at all.
Editing exists only by hand-editing JSON.

### D6 — One source per page; no variants or diffing (spec §12, §13)
A page stores `sourceFiles` but the model has no notion of a **variant** (same
URL, different inputs/cookies/auth) and no comparison between variants.

### D7 — No wordlist / interesting-string capture (spec §8)
No model, no capture, no export.

### D8 — No parameter analysis / probing / JSON parameters (spec §14–§18)
`Parameter` exists and holds reflections, but there is no parameter inventory
workflow, no baseline/probe testing, no JSON parameter extraction, and no place
to persist probe results.

### D9 — No keyboard-driven action menu (spec §2)
Capture is mouse-only through a right-click submenu. No accelerators, no quick
chooser, nothing configurable.

### D10 — Screenshots cannot be annotated (spec §9)
`Screenshot` has no annotation model; the generator renders the raw image only.

## Priority (matches the spec's own ordering)

Phase 1 fixes the existing behavior (D1–D3), because those are bugs in shipped
features. Phases 2–5 add the new capabilities (D4–D10). Progress is tracked in
`RELEASE_NOTES.md`.
