[![Quality gate status](https://sonarcloud.io/api/project_badges/measure?project=actionable-evidence_sonarqube-sarif-plugin&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=actionable-evidence_sonarqube-sarif-plugin)

# SARIF Export Plugin for SonarQube Server

<img width="1338" height="724" alt="sarif export preview" src="https://github.com/user-attachments/assets/2c324c62-809c-40ff-9da0-169658e3064c" />

Adds a web service, `GET /api/sarif/export`, that converts a project's
current issues into a [SARIF 2.1.0](https://docs.oasis-open.org/sarif/sarif/v2.1.0/)
report — the format GitHub code scanning, Azure DevOps, and most CI
security dashboards consume.

## How it works

- A project page ("SARIF Export", registered via `PageDefinition`) shows an
  **Export SARIF** button, rendered by `static/export.js`. To reach it, open
  any project in SonarQube and select **SARIF Export** from the project's
  navigation menu (the page is scoped to projects, so it only appears on a
  project — not the global or portfolio views). Clicking it
  downloads the report for the currently viewed project/branch/PR. Below the
  button, the page also fetches the same report in the background and shows a
  result/rule count plus a pretty-printed JSON preview (capped at the first 50
  results — the download itself is never capped).
- The plugin registers a `WebService` extension at `api/sarif/export`.
- Inside the handler, it calls the built-in `api/issues/search` (and,
  optionally, `api/rules/show`) web services **in-process**, using
  `Request#localConnector()` + `sonar-ws`'s `WsClient`. This is the
  officially documented way for one web service to call another: no HTTP
  round trip, and the call runs under the permissions of whoever hit
  `/api/sarif/export`, so a caller only ever sees issues from projects they
  can already browse.
- Results are paged (500 issues/page), aggregated, and converted to a SARIF
  `run` with a deduplicated `rules` table and one `result` per issue.

This is intentionally **not** built on internal classes like `DbClient` or
`IssueIndex`. Those aren't part of the public Plugin API, aren't guaranteed
stable across versions, and can break silently on upgrade. Going through the
existing web service JSON contract costs one extra in-process call but keeps
the plugin portable.

## Project layout

```
pom.xml
src/main/java/com/sonarsource/sarifexport/
  SarifExportPlugin.java          # entry point (org.sonar.api.Plugin)
  SarifExportPageDefinition.java  # registers the "SARIF Export" project page
  ws/SarifExportWs.java           # defines GET /api/sarif/export
  sarif/IssuesFetcher.java        # pages through api/issues/search, api/rules/show
  sarif/SarifBuilder.java         # builds the SARIF 2.1.0 JSON document
src/main/resources/static/
  sarifexport.js                  # renders the Export SARIF button on that page
```

## Before you build

Edit `pom.xml`:

1. `sonar.plugin.api.version` — pick the version matching your SonarQube
   Server release from the [compatibility matrix](https://github.com/SonarSource/sonar-plugin-api#sonarqube).
   The pom currently targets the 2026.1 LTA line (`13.4.3.4290`), i.e.
   SonarQube Server 2026.1.x / Community Build 26.1, which runs on Java 21.
2. `sonar.ws.version` — any recent SonarQube release train version works;
   the `WsClient`/`GetRequest` classes this plugin uses have been stable for
   years. Pinned to `26.1.0.118079` (the 2026.1 LTA build) as a starting point.

## Build

```bash
mvn clean package
```

Produces `target/sonar-sarifexport-plugin-1.0.0.jar`.

## Deploy

1. Copy the jar to `$SONARQUBE_HOME/extensions/plugins/`.
2. Restart the server.
3. Confirm it loaded: `logs/web.log` should contain a line like
   `Deploy plugin SARIF Export Plugin / 1.0.0`.

## Use it

From the UI: open a project, find **SARIF Export** in the project menu, and
click **Export SARIF** — the report downloads for whichever branch/PR you're
currently viewing.

From the command line:

```bash
curl -u <token>: \
  "https://sonarqube.example.com/api/sarif/export?project=my_project" \
  -o my_project.sarif.json
```

Useful parameters:

| Param                 | Purpose                                                          |
|------------------------|-------------------------------------------------------------------|
| `project` (required)  | Project key                                                       |
| `branch`               | Export a specific branch (mutually exclusive with `pullRequest`) |
| `pullRequest`          | Export a specific PR's issues                                    |
| `statuses`             | Default `OPEN,CONFIRMED,REOPENED`                                 |
| `includeRuleMetadata`  | `true`/`false` — fetch rule name/description via `api/rules/show` (default `true`) |

Typical CI usage: run your SonarQube analysis step, wait for the Quality
Gate/analysis to finish processing (issues are only final after the server
has processed the scan), then `curl` this endpoint and upload the resulting
`.sarif.json` — e.g. with `github/codeql-action/upload-sarif` on GitHub
Actions.

## Severity mapping

The legacy SonarQube `severity` field is mapped to the SARIF `level` in
`SarifBuilder#toSarifLevel`:

| SonarQube severity | SARIF level |
|--------------------|-------------|
| `BLOCKER`          | `error`     |
| `CRITICAL`         | `error`     |
| `MAJOR`            | `warning`   |
| `MINOR`            | `note`      |
| `INFO`             | `note`      |

Any unrecognized value falls back to `note`. Issues with no severity default
to `MAJOR` (→ `warning`).

## Known limitations / good next steps

- **Security Hotspots** are a separate concept from issues in SonarQube and
  live under `api/hotspots/search`; they're not included yet. Add a second
  fetch + mapping in `IssuesFetcher`/`SarifBuilder` if you need them.
- **Clean Code "impacts"** (software quality + severity, introduced as a
  replacement for the single `severity`/`type` fields) aren't mapped; the
  legacy `severity` field is used for the SARIF `level`. If your server
  relies on impacts only, extend `SarifBuilder#toSarifLevel`.
- No caching: `includeRuleMetadata=true` issues one `api/rules/show` call
  per distinct rule key on every export. Fine for interactive/CI use;
  add a cache if you'll call this very frequently.
- No pagination cap: a project with tens of thousands of open issues will
  make that many `/500` requests to `api/issues/search`. Consider adding a
  `maxIssues` guard if that's a concern for you.
