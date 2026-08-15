# Testing QuickDrop

How to actually test this app — for both AI agents and humans. Three layers, each
answering a different question: does the code work in isolation (Layer 1), does the
running app behave correctly over real HTTP (Layer 2), does it actually look and feel
right in a browser (Layer 3).

---

## Layer 1 — JUnit / MockMvc suite

```bash
./mvnw test            # full suite
./mvnw test -o          # offline, once dependencies are resolved -- much faster
./mvnw clean verify     # full suite + JaCoCo coverage gate (70% line minimum) + jar packaging
```

Lives under `src/test/java/`, organized by `controller/`, `service/`, `storage/`,
`interceptor/`, `migration/`, `support/`. Runs on every CI build (Jenkinsfile's
`Build and Test` stage) and gates Docker publish. ~381 tests as of this writing — run
the suite for the exact current count, don't trust a number in a doc.

### Conventions that matter before you add a test

- **`QuickdropIntegrationTest`** (`src/test/java/.../support/`) is the base class for
  anything needing a real Spring context + MockMvc. It activates the `test` profile
  (`application-test.properties`: isolated per-context SQLite DB and log file under
  `target/test-data/`, keyed by `${random.uuid}`) and redirects `fileStoragePath` to a
  fresh `@TempDir` before every test method.
- **`ControllerTestSupport`** (`src/test/java/.../controller/`) adds fixtures on top:
  `adminSession()`, `fileSession(uuid, password)`, `createFile(...)`, `createPaste(...)`,
  `updateSettings(mutator)`. Reuse these rather than hand-rolling setup.
- **Spring contexts are cached and shared** across test classes with identical
  configuration — a mutation one test leaves behind (a toggled setting, an enabled app
  password) is visible to every other test class that runs after it in the same JVM,
  regardless of file. This has bitten this suite before. Two defenses already in use:
  - Restore any non-default state you set, in a `finally` block, even on assertion
    failure (see `ApplicationSettingsServiceTest` for the pattern).
  - `@DirtiesContext` (method- or class-level) when a test genuinely needs a pristine
    context (e.g. "no admin password set yet") or when it's sensitive to *other*
    classes' state in a way that isn't practical to fix by restoring — see
    `CsrfTokenHandlingTest`'s class-level `@DirtiesContext(BEFORE_CLASS)` for a real
    example: `CookieCsrfTokenRepository` stopped issuing a cookie on the first request
    when that class shared a context with `AdminViewControllerTest`'s full run,
    reproducibly. Root cause wasn't fully pinned down; isolating the context sidesteps
    it rather than leaving an order-dependent flake in the suite.
- **`SecurityMockMvcRequestPostProcessors.csrf()`** injects a valid CSRF token as a
  request attribute, bypassing real header/cookie resolution entirely. That's fine for
  tests that don't care about CSRF mechanics, but if you're testing CSRF handling
  itself, it will hide bugs — see `CsrfTokenHandlingTest` for how to exercise the real
  masked-token/raw-cookie-token resolution path instead (load a real page, scrape the
  rendered token, submit it back through the actual header/cookie a browser would use).
- **Never hit a live reputation feed or API from a test.** `PhishingArmyProviderTest` and
  `UrlhausProviderTest` exercise the real parse/hash/tier-1/tier-2 matching pipeline via
  `AbstractHashFeedProvider#loadForTest(byte[])` — a package-private hook that runs the same
  code `refresh()` does, minus the network call — against known content written inline in the
  test. `ReputationCheckServiceTest` mocks `ReputationProvider` directly. `SafeBrowsingProvider`
  has no dedicated unit test for the same reason (its whole job is one live API call); if you
  add one, mock `RestTemplate`, don't call the real endpoint. This is deliberate, not an
  oversight — the reputation feature ships disabled by default specifically so a default test
  run never depends on a third party being reachable, and every test in this suite should
  preserve that.
- **Known flaky test**: `FileViewControllerTest.downloadFile_authorizedPlainFile_streamsContent`
  occasionally throws `ConcurrentModificationException` from
  `MockHttpServletResponse`'s header map — a timing race between a `StreamingResponseBody`
  writing on its own thread and Spring Security's header-writer filter touching the
  response on the main thread. Confirmed non-deterministic (passes on retry) and
  unrelated to application code. If you see it, rerun before assuming a regression.

---

## Layer 2 — Live isolated instances (HTTP / API testing)

For anything Layer 1 can't reach: real Tomcat behavior, actual multipart limits,
session cookies persisting across real requests, chunked upload timing, concurrent
request behavior.

### Launching an instance

`.claude/launch.json` defines five configs. `quickdrop` (port 8080) is the real dev
server backed by `db/quickdrop.db` / `files/` / `log/` at the repo root — **never** run
destructive tests against it. `quickdrop-e2e`, `-e2e2`, `-e2e3`, `-e2e4` (ports
8081–8084) are fully isolated: each uses its own `application-e2eN.properties`
pointing at its own SQLite file and log under `e2e-dataN/` (gitignored, throwaway,
recreated on next boot).

Start one directly:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=e2e
```

Or, if you're an agent with the Browser-pane tooling, use `preview_start` with the
launch.json config name (`"quickdrop-e2e"`) — it starts the server and opens a tab.

Run several in parallel (different ports/DBs) when you need independent, non-colliding
test tracks — e.g. one instance for security probes, another for a concurrent-upload
test, so they never share state.

### Seeding

```bash
pip install requests   # once
python scripts/seed_e2e.py [--base-url http://localhost:8081] \
                           [--storage-path e2e-data/files] \
                           [--admin-password admin-e2e-pw]
```

Start the target instance first — this script doesn't launch it. It goes through the
**real HTTP API** end to end (never raw DB inserts): sets the admin password, logs in,
re-points `fileStoragePath` at `e2e-data/files` (the DB-seeded default is `files`
relative to CWD — the real repo directory — so this step is not optional), then
uploads a small set of files and pastes so there's something to click through.

Its `FormFieldScraper` pattern — scrape every current field out of a rendered
`<form>`, resubmit unchanged except the one field you want to change — is worth
reusing any time you POST to `/admin/save` outside a browser. `ApplicationSettingsViewModel`
binds unset boolean fields to `false`, so a hand-built payload that omits a checkbox
will silently disable whatever feature that checkbox controls.

### Changing settings live and proving it took effect

This is the one thing worth calling out explicitly, since it's easy to get wrong: a
`fetch()`-based settings save against `/admin/save` can return what looks like success
(a same-origin redirect shows up as `{status: 0, type: "opaqueredirect"}` under
`fetch`, which is the *correct*, expected shape for a successful POST — don't mistake
that for a failure) while silently no-op'ing if the form reference was scraped from the
wrong page, or with a stale CSRF token. **Verify the actual persisted value** — either
reload the settings page and re-check the field, or query the SQLite file directly
(`SELECT ... FROM app_settings WHERE id=1`) — before trusting that a live-update test
proved anything.

### Fixtures

```bash
python scripts/generate-test-fixtures.py
```

Regenerates the small binary fixtures in `src/test/resources/fixtures/` that aren't
practical to hand-author as text (a JPEG with EXIF/GPS data for metadata-stripping
tests, an SVG with an embedded `<script>` for rasterization-path tests). Multi-MB
payloads and folder trees are deliberately *not* produced here — those are generated
at test time by `FixtureFiles` in the JUnit suite so multi-MB binaries never get
committed.

### Security probe methodology

When probing an instance for security regressions, the categories worth checking
systematically (not an exhaustive list, a starting checklist):

- **IDOR**: as anonymous and as a session holding a *different* resource's token,
  attempt every mutating endpoint (delete, extend, edit, toggle) — all must be denied.
- **Stored XSS**: filenames, folder manifests, paste content — script tags, event
  handler attributes, `javascript:` URIs, encoding-bypass attempts. Confirm sanitization
  holds both server-side and in the rendered page.
- **Path traversal**: `../`, absolute paths, URL-encoded traversal, null bytes in
  filenames/UUIDs — must never escape the configured storage path.
- **Share-token abuse**: reuse past the download limit, use an expired token, check
  concurrent-request behavior against limits specifically (a naive check-then-act
  implementation can be bypassed by simultaneous requests even when it looks correct
  under sequential testing).
- **Auth**: rate limiting on password attempts, BCrypt (not a fast hash) on every
  password field, session tokens actually invalidated on logout/settings change/expiry.
- **Settings tampering**: out-of-range values (negative sizes, malformed cron, storage
  paths outside the app directory) must be rejected, not silently clamped or corrupted.

Findings from a probe pass belong in `docs/test-reports/` (gitignored — this repo keeps
that local rather than public until anything found is actually fixed; don't commit
findings that amount to disclosing an open vulnerability).

---

## Layer 3 — Browser-driven testing

Use the Browser pane tools: `preview_start` → `read_page` / `computer` (click/type) /
`read_console_messages` / `read_network_requests` / `resize_window`. `get_page_text` is
usually faster than a screenshot for confirming rendered content; reach for a real
screenshot when the question is genuinely visual (layout, spacing, contrast, theme).

Per page, worth checking: the console is clean (no JS errors, no CSP violations, no
failed XHRs), no horizontal scroll at mobile width (375px), dark mode has no unstyled
white blocks, forms actually submit and show a result, destructive actions are
confirmed before firing.

Scripted journeys worth running end-to-end in a real browser rather than via raw HTTP
(these exercise the frontend JS, not just the backend contract): first-run admin setup,
anonymous upload → share → download → limit exhaustion, password-protected file with a
wrong-then-right password attempt, folder upload, a full admin settings sweep (change a
setting, confirm the UI actually reflects it without a restart — see the Layer 2 note
above on verifying persistence, the same caution applies here).

---

## i18n testing

See [`i18n.md`](i18n.md) for the full guide. Quick local check: switch locale via
`?lang=<code>` on any page (`en`, `bg`, `de`, `es`, `fr`, `it`, `ja`, `zh`), and run
either checker for missing/unused keys — `node check-i18n.js` (fast, local, flags keys
present in `messages.properties` but unreferenced anywhere) or
`python .github/scripts/check_i18n_keys.py` (cross-locale key-diff, also runs in CI as
a non-blocking check).

---

## What NOT to test against

Real S3/Azure/SFTP/WebDAV credentials, real SMTP delivery, real Discord webhooks. Mock
these or skip with a loud, logged reason — a silent skip that looks like a pass is
itself a bug in the test harness. `DelegatingStorageServiceTest` mocks all four backends
at the SDK boundary; follow that pattern rather than reaching for real credentials.
