# QuickDrop — Agent Guide

Self-hosted file sharing: Spring Boot 3.5 + Thymeleaf + SQLite (Flyway) + Tailwind. Java 21.

## Build & run
- Build: `./mvnw clean package` (Windows: `mvnw.cmd clean package`). Output: `target/quickdrop.jar`.
- Run: `java -jar target/quickdrop.jar` → http://localhost:8080.
- Tailwind CSS: `npm run tw:build` rebuilds `src/main/resources/static/css/tailwind.css` from `tailwind-input.css`.
- Test suite: `./mvnw test` (JUnit/MockMvc, ~313 tests under `src/test/java/`) — runs on every CI build via the Jenkinsfile's `Build and Test` stage and gates Docker publish. Test env details: `docs/AGENTIC_TEST_PLAN.md`. HTTP/security probes and browser exploratory testing are separate, on-demand passes (not part of the automatic CI gate) — see the same doc plus `scripts/seed_e2e.py` and the `quickdrop-e2e*` configs in `.claude/launch.json` for isolated live instances (ports 8081–8084). Known open findings from the last full pass: `docs/test-reports/SUMMARY.md`.
- Logs at `log/quickdrop.log`, DB at `db/quickdrop.db`, uploads under `files/` (all auto-created by [`QuickdropApplication`](src/main/java/org/rostislav/quickdrop/QuickdropApplication.java)).
- Docker: `roastslav/quickdrop:latest`, exposes 8080; mount `/app/db`, `/app/files`, `/app/log`.

## Architecture (follow the layers)
- Controllers (`controller/`): `FileViewController` (`/file/**` — upload form, list, detail, download, preview, history), `AdminViewController` (`/admin/**` — dashboard, file/paste management, settings), `FileRestController` (`/api/file/**` — chunked upload, upload status/abort, share APIs), `PasteViewController` (`/file/paste/**` — paste create/edit forms; paste viewing is still `FileViewController`), `ShareViewController` (`/share/{token}` — share landing page + key auth), `StorageMigrationController` (`/admin/storage-migration**` — storage backend migration UI/API), `PasswordViewController`, `IndexViewController`. Thymeleaf views in `src/main/resources/templates/`.
- Services (`service/`) own all business logic — there is no single do-everything service anymore. **Route file mutations through [`FileLifecycleService`](src/main/java/org/rostislav/quickdrop/service/FileLifecycleService.java)** (save/delete/extend/hide/share-token issuance) so cache eviction, history logging, and notifications fire via its `@CacheEvict`s and private `logHistory` helper. Reads/authorization go through [`FileQueryService`](src/main/java/org/rostislav/quickdrop/service/FileQueryService.java) (`@Cacheable` list/detail queries). Streaming downloads and share-token redemption go through [`FileDownloadService`](src/main/java/org/rostislav/quickdrop/service/FileDownloadService.java), which does its own history logging/cache eviction for download events.
- Upload pipeline is async: `POST /api/file/upload-chunk` → [`AsyncFileMergeService`](src/main/java/org/rostislav/quickdrop/service/AsyncFileMergeService.java) queues the chunk on a background thread pool (`submitChunk(..., waitForCompletion=false)`) and the controller returns immediately — `null` body for intermediate chunks, `202 Accepted {"status":"processing","uploadId":...}` on the last chunk. The merge task writes chunks in order, running the stream through [`EncryptionService`](src/main/java/org/rostislav/quickdrop/service/EncryptionService.java) when password + encryption flag are set, then calls `FileLifecycleService.saveFile` on completion. Frontend must poll `GET /api/file/upload-status/{uploadId}` (`AsyncFileMergeService.getUploadStatus`, returns status/uuid/error/processedChunks/totalChunks) rather than expect a synchronous result; `POST /api/file/upload-abort` cancels an in-flight upload. Metadata fields: `description`, `keepIndefinitely`, `password`, `hidden`, `fileSize`, folder fields, `isPaste`.
- Download: `/file/download/{uuid}` streams with on-the-fly decryption and logs to `activity_log` (renamed from `file_history_log`) via `ActivityLog`/`ActivityLogRepository`. `EventType` now spans several categories (FILE: `UPLOAD`/`DOWNLOAD`/`RENEWAL`/`DELETION`, PASTE, SHARE, ADMIN, SYSTEM), not just upload/renewal/download. Share links: `POST /api/file/share/{uuid}` issues a token, redeemed at `GET /share/{token}` (landing page, `ShareViewController`) with actual bytes served from `GET /api/file/download/{token}` (`FileRestController`, decrements/expires, redirects back to `/share/{token}` if invalid).
- Storage/model: UUID filenames on disk at `fileStoragePath` (from settings); `Upload` is an abstract JOINED-inheritance base entity (table `upload`) holding shared metadata + optional BCrypt password hash + `encrypted` flag, extended by `StoredFile` (adds folder fields for ZIP folder uploads) and `Paste` (adds `immutable`, `editOnly`).

## Settings (single-row, id=1)
- [`ApplicationSettingsService`](src/main/java/org/rostislav/quickdrop/service/ApplicationSettingsService.java) seeds defaults on startup (max 1 GB, 30-day life, `files`/`logs` paths, cron `0 0 2 * * *`, encryption on, default home = upload, SMTP/Discord off, etc.) and re-schedules cleanup when cron changes. **Never insert a second settings row.**
- Template-level feature flags (file list enabled, app password set, admin button, encryption on, previews, pastebin) are injected by [`GlobalControllerAdvice`](src/main/java/org/rostislav/quickdrop/config/GlobalControllerAdvice.java) — read from there; don't duplicate.

## Security & interceptors
- [`SecurityConfig`](src/main/java/org/rostislav/quickdrop/config/SecurityConfig.java): optional whole-app password at `/password/login`, BCrypt, CSRF cookie, permissive CORS. Session timeout applied per-session by [`SessionService#sessionCreated`](src/main/java/org/rostislav/quickdrop/service/SessionService.java), reading the current setting live so a change takes effect for the next session created without a restart.
- In-memory admin + file session tokens live in [`SessionService`](src/main/java/org/rostislav/quickdrop/service/SessionService.java) (not persisted — restarts invalidate).
- Add new protected routes via the right interceptor: `AdminPasswordSetupInterceptor` (forces `/admin/setup` until admin pwd exists), `AdminPasswordInterceptor` (`/admin/**` + file history), `FilePasswordInterceptor` (redirects to `/file/password/{uuid}` when the file has a password and no valid session token).

## Migrations & persistence
- Flyway migrations in `src/main/resources/db/migration/V*__*.sql` (currently up to V37 — check the directory for the latest). Baseline-on-migrate is enabled — **append a new `V{n+1}__...sql`, never edit existing files.** SQLite dialect is `org.hibernate.community.dialect.SQLiteDialect`.
- Entities in `entity/`, Spring Data repos in `repository/`. Use `@Transactional` on service methods that mutate.

## Pagination, search & cache
- Public list (`/file/list`) and admin dashboard use `page`/`size`/`query` params backed by `Page<Upload>`-returning service methods on `FileQueryService` — reuse them and pass all three params through to templates.
- Spring Cache keys are `publicFiles`, `adminFiles`, `adminDeletedFiles`, `adminPastes`, `adminDeletedPastes`, `analytics`, `applicationSettings` (see [`CacheConfig`](src/main/java/org/rostislav/quickdrop/config/CacheConfig.java)). Any upload, deletion, renewal, visibility/keep toggle, or download **must evict** the relevant ones — copy the `@CacheEvict` pattern from `FileLifecycleService`/`FileDownloadService`.

## Scheduling & notifications
- `@EnableScheduling` + [`ScheduleService`](src/main/java/org/rostislav/quickdrop/service/ScheduleService.java) handles expiry deletion (cron from settings), missing-file row cleanup, share token expiry. Reschedule by calling its `rescheduleCleanup(...)` when cron changes.
- [`NotificationService`](src/main/java/org/rostislav/quickdrop/service/NotificationService.java) batches email + Discord webhook events per configured interval; call it from `FileLifecycleService`/`FileDownloadService`, not controllers.

## i18n
- Bundles: `messages.properties` (default/English), `messages_bg.properties`, `messages_de.properties`, `messages_es.properties`, `messages_fr.properties`, `messages_it.properties`, `messages_ja.properties`, `messages_zh.properties`. Add every new user-facing string to all eight (see `docs/i18n.md`). Resolver wired in [`I18nConfig`](src/main/java/org/rostislav/quickdrop/config/I18nConfig.java).

