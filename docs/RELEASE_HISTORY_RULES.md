# Release History Rules

`backend/src/main/resources/release-notes.json` is the packaged, read-only source of truth for the environment-center history. It is loaded by `ReleaseNotesService` and served through `/api/system/release-notes`.

## One release sequence

- Application releases use only `x.y.z`: for example `2.2.5`.
- Do not use a `v` prefix, stage suffix, daily summary version, or a second version list.
- `id` must be `release-x-y-z`, matching its `version` exactly.
- `releasedAt` is the actual local release date and cannot be in the future.
- Records are ordered by date, then semantic version, newest first.
- `pom.xml` keeps the Maven/Jar build coordinate. It is not the application release version shown to operators.

## Required record content

Every completed feature, bug fix, configuration behavior change, UI behavior change, or release operation needs one record containing non-empty:

- `title`, `summary`, `kind`, and `compatibility`
- `changes`, `fixes`, `verification`, and `evidence`

Only record completed, verifiable work. Keep work on its actual release date. Do not repeat yesterday's changes in today's record. A same-day update gets its own next patch version rather than a daily summary. Do not include passwords, tokens, API keys, `.env` values, or sensitive logs.

## Mandatory release workflow

Do not edit `release-notes.json` by hand for a new release. Use the release tool from `backend/`:

```bash
python tools/release_notes.py new --title "本次更新标题"
# Fill backend/release-notes.pending.json with completed work and verification.
python tools/release_notes.py check
python tools/release_notes.py apply
mvn test
```

`apply` performs the required transition: it archives the previous current version at the top of history, increments exactly one patch version, creates the new current record with today's date, synchronizes `app.version`, and clears the pending file. A custom version is allowed only when it is that same next patch version.

`release-notes.pending.json` must be `{}` before any Maven test or package build. A non-empty file fails the test suite, so an unfinished update cannot silently ship without a record.

## One-time migration

The former mixed labels and daily summary record were converted once through:

```bash
python tools/release_notes.py migrate
```

The later date-correction pass rebuilt the baseline as current `2.2.9`, followed by yesterday's independently recorded `2.2.8`, `2.2.7`, `2.2.6`, `2.2.5`, then `2.2.4` and earlier records. Do not run `migrate` or `correct-history` again after this baseline is established.

## Verification gate

`ReleaseNotesSchemaTest` validates the packaged release file during `mvn test`; `ReleaseNotesPendingTest` rejects outstanding records; `ReleaseNotesService` performs the same release-file validation at application startup. Invalid versions, duplicate IDs, future dates, mixed record types, unordered history, or incomplete content block the build or startup.

The frontend must render `/api/system/release-notes` only. Do not add a hardcoded version list in Vue.
