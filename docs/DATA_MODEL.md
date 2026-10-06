# Data model

The whole project is one `Project` aggregate serialized to `project.json`. HTML
and binary evidence are **derived** from it and can be regenerated at any time;
the JSON is the single source of truth.

Every documented thing is an entity with a stable, human-readable id
(`page-0007`, `form-0003`, ...) minted per type by `Project.nextId`. Ids never
change, so relative links between generated documents never break.

## Entities

| Entity | Id prefix | Purpose |
|---|---|---|
| `Project` | — | Root aggregate: target info, all entity lists, id counters |
| `TargetInfo` | — | Domain, main URL, assessment name/date, notes |
| `Technology` | `tech` | A discovered technology, grouped by category |
| `Page` | `page` | A discovered page/endpoint — the central node |
| `Resource` | `res` | Script/stylesheet/image/font/API/XHR, shared across pages |
| `Link` | `link` | A hyperlink or navigational edge found on a page |
| `Form` | `form` | An HTML form on a page |
| `Parameter` | `param` | A single form input; holds observed reflections |
| `Reflection` | — | An observed echo of a submitted value, with its context |
| `Interaction` | `act` | A UI action and the behavior it produced |
| `RequestRecord` | `req` | A captured HTTP request (raw bytes in `source/`) |
| `ResponseRecord` | `resp` | A captured HTTP response (raw bytes in `source/`) |
| `Screenshot` | `shot` | An image of a page in one visual state |
| `Note` | `note` | A classified, timestamped note attachable to any entity |
| `Vulnerability` | `vuln` | A finding, linked to the components it involves |
| `PageVariant` | `var` | One rendering of a page under specific request conditions (§12) |
| `InterestingString` | `istr` | A marked string for later wordlists (§8) |
| `ParameterTest` | `ptest` | The recorded result of one probe against a parameter (§16, §18) |
| `Annotation` | `anno` | A labelled rectangle drawn on a screenshot (§9); embedded in `Screenshot` |
| `Relationship` | — | A directed, typed edge between two entities |

### Enrichments added in v1.1

- **`Resource`** now has a *canonical* `url` (normalized by `ResourceUrls`) plus
  `observedUrls`, so one file/endpoint is one record no matter how it was found.
- **`Page`** gains `discoverySourceKind` (a `DiscoverySource` enum: direct,
  redirect, link, form, XHR, Burp history/repeater, ...) with `discoverySource`
  kept as free-text detail, and `variantIds`.
- **`Technology`** gains `confidence`, a structured `evidences` list, `history`,
  `firstObserved`/`lastUpdated`, and a `userEdited` flag that protects manual
  corrections from automatic detection.
- **`Screenshot`** gains `imageWidth`/`imageHeight` and an `annotations` list.
- **`TargetInfo`** gains `server`, `frameworks`, `authentication` (filled by the
  setup wizard).

### Enrichments added in v1.3

- **`Page.kind`** is `PAGE` or `API` (an API endpoint: a JSON/XHR data call).
  An endpoint is a page record, so forms, variants, evidence and notes work the
  same; it additionally has `responseFields` (JSON field paths it returned) and
  is the target of `calls` edges.
- **`Page.pathTemplate`** (`/api/users/{id}`) is set when auto-capture grouped
  several concrete URLs under one record; `Page.url` stays the first URL seen.
- **`Page.fingerprints`** lists the capture fingerprints already documented
  (`status|parameter names|auth|response shape`; see `CaptureFingerprint`), and
  **`Page.baseline`** holds the first capture's request conditions until a
  differing capture promotes it to a real `PageVariant`.
- **`PageVariant`** gains `auto` (registered by auto-capture) and
  `responseFields` (so two JSON variants are compared field by field).
- **`Parameter.purpose` / `Parameter.notes`** are now editable in the UI, and
  `observedValues` is filled from traffic (a few examples per parameter; never
  for password/token-like names).
- **`Project.capture`** is a `CaptureConfig`: the `enabled` switch, the ordered
  `rules` (`CaptureRule`: host, path, methods, content type, status, scope,
  tools, action), `collapseIds` and `maxVariantsPerPage`.

Projects written by earlier versions load unchanged: missing fields take their
defaults, and an existing page adopts its next sighting as its baseline.

`Reflection` and `Relationship` are embedded/value types (no standalone document);
everything else has an id.

## Notes and observations are one type

The "notes and observations" system is a single `Note` entity carrying a `Kind`:
`OBSERVATION`, `HYPOTHESIS`, `TODO`, `CONFIRMED`, `REJECTED`, or plain `NOTE`. This
preserves the investigative history — a hunch, a dead end, a confirmed finding —
not just the conclusions. "Add Observation" creates a `Note` with the chosen kind.

## Relationships are first-class

Structure between entities is stored as `Relationship` edges, not implied by
prose, so the model is a graph. Common edge labels:

```
discovered        page  -> page      (parent discovered child)
redirects-to      page  -> page      (e.g. 302 after login)
links-to          page  -> page      (resolved hyperlink)
contains-form     page  -> form
contains-link     page  -> link
loads-resource    page  -> resource
calls             page  -> page      (a page's scripts call an API endpoint)
has-parameter     form  -> parameter
produces-response request -> response
affects           vuln  -> page/form/parameter
evidenced-by      vuln  -> request/screenshot
relates-to        any   -> any       (generic)
```

Because edges are directed and typed, the generator can answer questions like
"how was this page discovered?" (incoming `discovered`/`redirects-to`/`links-to`),
"what loads this script?" (incoming `loads-resource`), and "what does this finding
touch?" (outgoing edges from the vulnerability). A resource shared by many pages is
one entity with many incoming edges — the reason a tree alone is not enough.

## The application tree vs. the graph

The overview shows a **path-based tree** built from page URLs (structure), while
**discovery paths** (behavior) are listed separately from the discovery/redirect/
link edges. A page sits at one path in the tree but may be reachable by several
routes in the graph; both views are generated from the same data.

## Example (from the shipped sample)

```
Page /dashboard
  ├── discovered:      /login -> redirect (302 after successful auth)
  ├── contains-form:   form-0002 (/search)
  │     └── has-parameter: param q
  │            └── note (HYPOTHESIS): "q reflects unencoded into HTML text"
  │            └── reflection: HTML text context
  └── loads-resource:  res app.js

Vulnerability vuln-0001 "Reflected XSS via q"
  └── affects -> parameter q  ->  form-0002  ->  page /dashboard
```

Walking those edges is what makes report writing mechanical rather than manual.
