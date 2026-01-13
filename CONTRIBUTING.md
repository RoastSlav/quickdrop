# Contributing to QuickDrop

Thanks for wanting to help. QuickDrop is maintained in spare time, so clear reports + small focused PRs get merged fastest.

## Ground rules
- Be respectful. See `CODE_OF_CONDUCT.md`.
- For **security issues**, do NOT open a public issue. See `SECURITY.md`.
- If you’re planning a bigger change, open an issue first so we can align.

## What to work on
- Check issues labeled **good first issue** or **help wanted**.
- Tests, the app definetly needs tests.
- Docs improvements are always welcome (README, setup, screenshots, FAQ).

## Dev setup (local)
### Prereqs
- Use the JDK version required by quickdrop (see `pom.xml` / README).
- Maven (or use the included Maven wrapper: `./mvnw`)

### Run
Pick one:
- **Docker**: use `docker-compose.yml` (recommended for consistent env)
- **Local**: `./mvnw spring-boot:run`

If you add a new env var/config option, document it in README.

## Tests & quality
Before opening a PR:
- Run: `./mvnw test`
- If you changed behavior: add/adjust tests.
- Keep PRs small and focused. One PR = one logical change.

## Branch / PR workflow
1. Fork the repo
2. Create a branch from `master`:
   - `fix/<short-name>` or `feat/<short-name>` or `docs/<short-name>`
3. Commit with clear messages.
4. Open a PR using the template.

## Code style
- Follow existing patterns in the codebase.
- Prefer simple, readable code over cleverness.
- Avoid introducing heavy new dependencies unless there’s a strong reason.

## Review expectations
- Small PRs: usually fastest.
- Big PRs: expect iteration.
- If you haven’t heard back after ~7 days, a polite ping is fine.

## Becoming a maintainer
If you consistently contribute high-quality PRs and help others, you can be added as a maintainer over time.
