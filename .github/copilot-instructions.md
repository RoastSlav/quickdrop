# QuickDrop — Agent Guide

Self-hosted file sharing: Spring Boot 3.5 + Thymeleaf + SQLite (Flyway) + Tailwind. Java 21.

## Build & run

- Build: `./mvnw clean package` (Windows: `mvnw.cmd clean package`). Output: `target/quickdrop.jar`.
- Run: `java -jar target/quickdrop.jar` → http://localhost:8080.
- Tailwind CSS: `npm run tw:build` rebuilds `src/main/resources/static/css/tailwind.css` from `tailwind-input.css`.
- No test suite — verify via manual package + smoke test. Logs at `log/quickdrop.log`, DB at `db/quickdrop.db`, uploads
  under `files/` (all auto-created by [
  `QuickdropApplication`](src/main/java/org/rostislav/quickdrop/QuickdropApplication.java)).
- Docker: `roastslav/quickdrop:latest`, exposes 8080; mount `/app/db`, `/app/files`, `/app/log`.

## Architecture (follow the layers)

- Controllers (`controller/`): `FileViewController`, `AdminViewController`, `FileRestController` (chunked upload + share
  APIs), `ShareViewController`, `PasswordViewController`, `IndexViewController`. Thymeleaf views in
  `src/main/resources/templates/`.
- Services (`service/`) own all business logic. **Route every file mutation
  through [`FileService`](src/main/java/org/rostislav/quickdrop/service/FileService.java)** so history logging, cache
  eviction, encryption and notifications fire.
- Upload pipeline: `POST /api/file/upload-chunk` → [
  `AsyncFileMergeService`](src/main/java/org/rostislav/quickdrop/service/AsyncFileMergeService.java) buffers chunks → [
  `FileEncryptionService`](src/main/java/org/rostislav/quickdrop/service/FileEncryptionService.java) encrypts when
  password + encryption flag set → `FileService` persists. Intermediate chunk calls return `null`; frontend must handle.
  Metadata fields: `description`, `keepIndefinitely`, `password`, `hidden`, `fileSize`, folder fields, `isPaste`.
- Download: `/file/download/{uuid}` streams with on-the-fly decryption and logs `DOWNLOAD` into `file_history_log` (
  types: `UPLOAD`, `RENEWAL`, `DOWNLOAD`). Share links via `/api/file/share/{uuid}` issue tokens redeemed at
  `/api/file/download/{uuid}/{token}` (decrement/delete).
- Storage/model: UUID filenames on disk at `fileStoragePath` (from settings); `file_entity` row holds metadata +
  optional BCrypt password hash + `encrypted` flag + folder + `isPaste`.

## Settings (single-row, id=1)

- [`ApplicationSettingsService`](src/main/java/org/rostislav/quickdrop/service/ApplicationSettingsService.java) seeds
  defaults on startup (max 1 GB, 30-day life, `files`/`logs` paths, cron `0 0 2 * * *`, encryption on, default home =
  upload, SMTP/Discord off, etc.) and re-schedules cleanup when cron changes. **Never insert a second settings row.**
- Template-level feature flags (file list enabled, app password set, admin button, encryption on, previews, pastebin)
  are injected by [`GlobalControllerAdvice`](src/main/java/org/rostislav/quickdrop/config/GlobalControllerAdvice.java) —
  read from there; don't duplicate.

## Security & interceptors

- [`SecurityConfig`](src/main/java/org/rostislav/quickdrop/config/SecurityConfig.java): optional whole-app password at
  `/password/login`, BCrypt, CSRF cookie, permissive CORS. Session timeout configured in [
  `WebConfig`](src/main/java/org/rostislav/quickdrop/config/WebConfig.java).
- In-memory admin + file session tokens live in [
  `SessionService`](src/main/java/org/rostislav/quickdrop/service/SessionService.java) (not persisted — restarts
  invalidate).
- Add new protected routes via the right interceptor: `AdminPasswordSetupInterceptor` (forces `/admin/setup` until admin
  pwd exists), `AdminPasswordInterceptor` (`/admin/**` + file history), `FilePasswordInterceptor` (redirects to
  `/file/password/{uuid}` when the file has a password and no valid session token).

## Migrations & persistence

- Flyway migrations in `src/main/resources/db/migration/V*__*.sql` (currently V1–V20). Baseline-on-migrate is enabled —
  **append a new `V{n+1}__...sql`, never edit existing files.** SQLite dialect is
  `org.hibernate.community.dialect.SQLiteDialect`.
- Entities in `entity/`, Spring Data repos in `repository/`. Use `@Transactional` on service methods that mutate.

## Pagination, search & cache

- Public list (`/files`) and admin dashboard use `page`/`size`/`query` params backed by `Page<FileEntity>` service
  methods — reuse them and pass all three params through to templates.
- Spring Cache keys are `publicFiles`, `adminFiles`, `analytics` (see [
  `CacheConfig`](src/main/java/org/rostislav/quickdrop/config/CacheConfig.java)). Any upload, deletion, renewal,
  visibility/keep toggle, or download **must evict** these — copy the `@CacheEvict` pattern from `FileService`.

## Scheduling & notifications

- `@EnableScheduling` + [`ScheduleService`](src/main/java/org/rostislav/quickdrop/service/ScheduleService.java) handles
  expiry deletion (cron from settings), missing-file row cleanup, share token expiry. Reschedule by calling its
  `rescheduleCleanup(...)` when cron changes.
- [`NotificationService`](src/main/java/org/rostislav/quickdrop/service/NotificationService.java) batches email +
  Discord webhook events per configured interval; call it from `FileService`, not controllers.

## i18n

- Bundles: `messages.properties`, `messages_bg.properties`, `messages_de.properties`. Add every new user-facing string
  to all three (see `docs/i18n.md`). Resolver wired in [
  `I18nConfig`](src/main/java/org/rostislav/quickdrop/config/I18nConfig.java).