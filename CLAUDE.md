# manyfold-jdbc

A pass-through JDBC driver. One `jdbc:manyfold:` URL names several real JDBC URLs. Every
statement is forwarded verbatim to every backend, the rows are concatenated in URL order, and a
`source_database` column is prepended with the logical name of the backend each row came from.

This file is for anyone, human or AI, making changes. It states the invariants and the build
commands. Read it before opening a pull request.

## Commands

```
./gradlew build                      # everything CI runs: format check, static analysis, tests on 11, 17 and 21
./gradlew spotlessApply              # fix formatting (google-java-format)
./gradlew test                       # tests on the build JDK only
./gradlew --write-verification-metadata sha256 build   # after any dependency version change
./gradlew publishToMavenLocal -Pversion=0.0.1         # dry-run the publishable artifacts
```

Releases: push a tag `vX.Y.Z` after adding a `## [X.Y.Z]` section to CHANGELOG.md. See
RELEASING.md. Never put credentials anywhere in the repository; the release job reads them from
GitHub environment secrets only.

Requires JDK 21 to build. Bytecode targets Java 11. Gradle provisions JDKs 11 and 17 for test11 and test17.

## Invariants

1. **Zero runtime dependencies.** `compileOnly` and test scopes only. The published POM must
   list no dependencies. The jar is loaded by SQL clients on a flat classpath.
2. **SQL is forwarded verbatim** except for identifier substitutions requested by a leading
   `-- manyfold <backend>: ...` directive. Substitutions are identifier-only and validated: only
   an identifier in qualifier position (followed directly by `.`) is replaced, never text inside
   a string, comment or bracket, and a malformed or unknown directive fails the statement. The
   read-only guard sees the original text. Never otherwise parse, rewrite or reformat a
   statement.
3. **Read-only by default.** With `readOnly=true` (the default) the driver refuses
   `executeUpdate`, `executeLargeUpdate`, batches, and any `execute`/`executeQuery` whose
   first keyword is not one of the read-only allowlist. It also calls `setReadOnly(true)`
   on every backend connection. Any change to this path needs a test that proves a write is
   still refused.
4. **Fail loudly.** If any backend fails, the whole operation fails with one exception that
   names the logical source and wraps the vendor exception, keeping the vendor's `java.sql`
   exception subtype, SQL state and vendor code. Never return partial results.
5. **First backend is primary.** Anything that must return a single value, including
   `DatabaseMetaData`, comes from the first backend in the URL.
6. **Never leak credentials.** Passwords must not appear in exception messages, logs,
   `toString()`, or `getPropertyInfo` results.
7. **Column 1 is `source_database`.** Every other column index is the backend's index plus one.

## Layout

```
io.github.jbburns.manyfold.jdbc            public API: ManyfoldDriver and constants only
io.github.jbburns.manyfold.jdbc.internal   everything else; may change without notice
  url/        URL parsing and per-source options
  backend/    vendor driver resolution, backend connections, fan-out execution
  proxy/      JDK dynamic-proxy handlers for Connection, Statement, PreparedStatement, ResultSet
  guard/      read-only classification
  lex/        the SQL lexer shared by guard/ and rewrite/ (quotes, comments, brackets)
  rewrite/    leading `manyfold` directives and per-backend identifier substitution
```

## URL syntax

```
jdbc:manyfold:[option=value;...][name=]jdbc:vendor://... || [name=]jdbc:vendor://...
```

Backends are separated by ` || `. Each may be prefixed with `name=` to set the logical name
shown in `source_database`; unnamed backends get a name derived from their URL. Driver options
before the first backend: `sourceColumn` (default `source_database`), `readOnly` (default
`true`). Per-backend credentials go in the connection `Properties` as `manyfold.<name>.user`
and `manyfold.<name>.password`; the plain `user` and `password` properties apply to all.

## Proving a feature with screenshots

Unit tests are not the finish line for a feature that changes what a user sees. The GUI
validation harness in `validation/` drives a real SQuirreL SQL against the driver and saves
screenshots. See DEVELOPING.md for the setup. For any change that affects results, columns,
errors, URL syntax or options:

1. Extend the harness so it exercises the change: add or adjust a statement and a screenshot
   step in `validation/run.sh`, and in the seed SQL under `validation/db/` if the demo data
   needs it. Keep the existing steps working.
2. Run `validation/run.sh` (two H2 backends, no other software) and, when the change could
   behave differently on a real database, `MODE=multi validation/run.sh` as well. In a
   container without Docker, `validation/db/start-local.sh` installs and seeds MariaDB and
   PostgreSQL natively first.
3. Look at every new screenshot yourself before claiming it passed, then send the ones that
   show the feature to the person you are working with and attach them to the pull request.
4. If the grid at the top of the README no longer matches what the driver does, refresh
   `docs/images/squirrel-prod-dev.png` from the new `03-select-all.png` (crop to the editor
   and grid, about 1600 by 475).

The harness is deliberately not in CI. Do not add it there.

## Testing conventions

- JUnit 5, AssertJ, Mockito. H2 in-memory databases are the primary fake backends; sqlite-jdbc
  is the second because its URL and driver behave differently.
- Every bug fix adds a test that fails without the fix.
- Tests must not need network access, Docker, or files outside the build directory.

## Style

google-java-format, enforced. Error Prone and NullAway run at compile time and warnings are
errors. Public and internal packages are `@NullMarked`; use `@Nullable` where null is legal.
