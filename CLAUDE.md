# manyfold-jdbc

A pass-through JDBC driver. One `jdbc:manyfold:` URL names several real JDBC URLs. Every
statement is forwarded verbatim to every backend, the rows are concatenated in URL order, and a
`source_database` column is prepended with the logical name of the backend each row came from.

This file is for anyone, human or AI, making changes. It states the invariants and the build
commands. Read it before opening a pull request.

## Commands

```
./gradlew build                      # everything CI runs: format check, static analysis, tests on 17 and 21
./gradlew spotlessApply              # fix formatting (google-java-format)
./gradlew test                       # tests on the build JDK only
./gradlew --write-verification-metadata sha256 build   # after any dependency version change
./gradlew publishToMavenLocal -Pversion=0.0.1         # dry-run the publishable artifacts
```

Releases: push a tag `vX.Y.Z` after adding a `## [X.Y.Z]` section to CHANGELOG.md. See
RELEASING.md. Never put credentials anywhere in the repository; the release job reads them from
GitHub environment secrets only.

Requires JDK 21 to build. Bytecode targets Java 17. Gradle provisions a JDK 17 for `test17`.

## Invariants

1. **Zero runtime dependencies.** `compileOnly` and test scopes only. The published POM must
   list no dependencies. The jar is loaded by SQL clients on a flat classpath.
2. **SQL is forwarded verbatim.** Never parse, rewrite or reformat a statement.
3. **Read-only by default.** With `readOnly=true` (the default) the driver refuses
   `executeUpdate`, `executeLargeUpdate`, batches, and any `execute`/`executeQuery` whose
   first keyword is not one of the read-only allowlist. It also calls `setReadOnly(true)`
   on every backend connection. Any change to this path needs a test that proves a write is
   still refused.
4. **Fail loudly.** If any backend fails, the whole operation fails with one exception that
   names the logical source and wraps the vendor exception. Never return partial results.
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

## Testing conventions

- JUnit 5, AssertJ, Mockito. H2 in-memory databases are the primary fake backends; sqlite-jdbc
  is the second because its URL and driver behave differently.
- Every bug fix adds a test that fails without the fix.
- Tests must not need network access, Docker, or files outside the build directory.

## Style

google-java-format, enforced. Error Prone and NullAway run at compile time and warnings are
errors. Public and internal packages are `@NullMarked`; use `@Nullable` where null is legal.
