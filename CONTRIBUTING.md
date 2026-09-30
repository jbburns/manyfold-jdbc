# Contributing

Contributions are welcome, including changes written with the help of AI tools. The maintainer
has limited time, so the process is designed to need as little of it as possible.

## Ground rules

- **Small, focused pull requests get merged.** One behaviour change per PR, with a test.
  Large unrequested rewrites may be closed without review.
- **CI must be green.** The build runs formatting, static analysis and the tests on Java 17
  and 21. Run `./gradlew build` locally first.
- **No new runtime dependencies.** The jar is dropped into SQL clients next to vendor drivers,
  where nothing resolves transitive dependencies. Test and build dependencies are fine.
- **Never commit credentials.** Not in tests, not in fixtures, not in URLs in documentation.
  A pre-commit hook and a CI job both run gitleaks; install the hook with `pre-commit install`.
- **AI-assisted changes are fine** as long as you have run the tests, read the diff, and take
  responsibility for it as your own. Say so in the PR if you like; it is not required.

## Licensing

By submitting a contribution you agree that it is licensed under the Apache License 2.0, as
provided in section 5 of that license. There is no separate contributor agreement to sign.

## Building

```
./gradlew build            # format check, Error Prone + NullAway, tests on 17 and 21, coverage
./gradlew spotlessApply    # fix formatting
./gradlew test             # tests on the build JDK only, faster during development
```

Gradle downloads a Java 17 runtime for the `test17` task if none is installed.

See [DEVELOPING.md](DEVELOPING.md) for the full developer guide, including the GUI validation
harness.

## Updating dependencies

Dependency checksums are pinned in `gradle/verification-metadata.xml`. After changing a version
in `gradle/libs.versions.toml`, regenerate the file and commit it:

```
./gradlew --write-verification-metadata sha256 build
```

## Design invariants

See [CLAUDE.md](CLAUDE.md) for the architecture and the rules every change must keep. The two
that matter most:

1. The driver forwards SQL text verbatim. It never parses or rewrites a statement.
2. In read-only mode, the default, no statement that can modify a backend is ever forwarded.
