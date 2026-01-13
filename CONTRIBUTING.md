# Contributing to QuickDrop

Thanks for wanting to help. QuickDrop is maintained in spare time, so **clear reports + small focused PRs** get merged fastest.

QuickDrop aims to be a community project. That said: we keep the workflow simple — **Issues + PRs** (no project boards / todo management).

---

## Ground rules

- Be respectful. See `CODE_OF_CONDUCT.md`.
- **Security issues:** do **NOT** open a public issue. See `SECURITY.md`.
- If you’re planning a bigger change, open an issue first so we can align (avoid wasted work).

---

## Where to start

- Pick issues labeled **good first issue** or **help wanted**
- **Tests** are especially welcome
- Docs improvements are always welcome (README, setup, screenshots, FAQ)

If you’re unsure, open an issue with:

- what you want to change
- why
- what files/areas you expect to touch

---

## Branch policy (IMPORTANT)

QuickDrop uses two main branches:

- **`dev`** = active development / integration branch (PRs go here)
- **`master`** = stable release branch

Rules:

1. ✅ **All contributor PRs must target `dev`** as the base branch.
2. ❌ PRs targeting `master` will be closed or retargeted to `dev`.
3. ✅ **`master` is only updated via a maintainer PR from `dev` → `master`** (release merges).

This keeps `master` stable for users while `dev` stays open for community contributions.

---

## Dev setup & running QuickDrop

QuickDrop supports 2 practical ways to run locally:

- **Docker Compose** (recommended; closest to real deployments)
- **Local run from source** (best for debugging in IDE)

Default runtime facts:

- Port: **8080**
- DB: **SQLite**, stored at `db/quickdrop.db`
- Logs: `log/quickdrop.log`
- Migrations: Flyway runs automatically on startup; migrations live under `src/main/resources/db/migration`

### Option A — Docker Compose (recommended)

Your repo already includes a compose file. It maps persistent volumes so DB/files/logs survive container recreation.

Start:

```bash
docker compose up -d
```

Open:

- App: http://localhost:8080

Persistence directories created locally (relative to the compose file):

- ./db → /app/db
- ./files → /app/files
- ./log → /app/log

Notes:

- The compose includes PUID, PGID, and TZ env vars.
- View logs:

```bash
docker compose logs -f quickdrop
```

or check `./log/quickdrop.log`.

Stop:

```bash
docker compose down
```

### Option B — Local run (from source)

Prereqs:

- Java 21
- Maven (or use the included wrapper: `./mvnw`)

Build + run:

```bash
./mvnw clean package
java -jar target/quickdrop.jar
```

Or run via Spring Boot:

```bash
./mvnw spring-boot:run
```

First run will create required local directories (including `./db`) and use SQLite at `db/quickdrop.db`.

IDE run: run the Spring Boot main class (`QuickdropApplication`) with Java 21.

First-run / admin access: QuickDrop enforces admin-password setup flow. If no admin password exists yet, admin routes are gated until `/admin/setup` is completed. After starting the app:

- Visit the app normally at `/`.
- For admin features, go to http://localhost:8080/admin/setup.

Making changes safely (QuickDrop specifics):

- Database changes: add a new Flyway migration under `src/main/resources/db/migration`. Do not rewrite old migrations.
- Settings: application settings are stored as a single-row settings record (id=1). Don’t create more settings rows in code or migrations.
- File mutations: route file changes through the existing service layer (avoids missing logs/cleanup/encryption behaviors).

Tests & quality:

```bash
./mvnw test
```

Rules:

- If you changed behavior: add/adjust tests.
- Keep PRs small and focused. One PR = one logical change.

High-value areas for new tests:

- service layer (unit tests)
- controller endpoints (MockMvc)
- upload / download flows
- share-token behaviors

Branch / PR workflow:

1. Fork the repo.
2. Clone your fork and add upstream:

```bash
git clone https://github.com/<you>/quickdrop.git
cd quickdrop
git remote add upstream https://github.com/RoastSlav/quickdrop.git
```

3. Create a branch from dev:

```bash
git fetch upstream
git checkout dev
git pull
git checkout -b feat/<short-name>
```

4. Push and open a PR into dev:

```bash
git push -u origin feat/<short-name>
```

PR requirements:

- Base branch must be `dev`.
- Explain what and why.
- Add screenshots for UI changes.
- Add migration notes if you touched DB.

Code style & dependencies:

- Follow existing patterns in the codebase.
- Prefer simple, readable code over cleverness.
- Avoid introducing heavy new dependencies unless there’s a strong reason (mention the reason in the PR).

Review expectations:

- Small PRs: usually fastest.
- Big PRs: expect iteration.
- If you haven’t heard back after ~7 days, a polite ping is fine.

Becoming a maintainer: if you consistently contribute high-quality PRs and help others, you can be added as a maintainer over time.
