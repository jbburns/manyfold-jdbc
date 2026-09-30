# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `jdbc:manyfold:` driver that runs each statement against every backend named in the URL and
  returns one result set with a `source_database` column first.
- Logical backend names (`prod=jdbc:...`), defaulting to a credential-free form of the URL.
- Read-only mode, on by default, refusing statements that can modify data; `readOnly=false`
  fans writes out to every backend and sums update counts.
- Per-backend `manyfold.<name>.user`, `manyfold.<name>.password` and `manyfold.<name>.driver`
  connection properties.
- Vendor driver discovery through the driver's own class loader, so a single SQL-client driver
  definition holding the manyfold jar and the vendor jars is enough.
- Release workflow publishing signed artifacts to Maven Central from a tag.
- Project scaffold: Gradle 9 build, formatting and static analysis, CI, secret scanning,
  dependency review, CodeQL, and community documents.

### Changed

- A failure on one backend is rethrown as the same `java.sql` exception subtype the vendor
  raised, with the backend name in the message and the vendor exception as the cause, so code
  that catches `SQLFeatureNotSupportedException`, `BatchUpdateException` and similar keeps working.
- Read-only mode refuses multi-statement SQL and quoting forms that dialects read differently
  (dollar quoting, `#` comments, backslash escapes, MySQL executable comments).
- Streaming parameter setters buffer the stream once so every backend receives the full data.
- Result sets returned by `DatabaseMetaData` no longer expose the vendor statement or connection.

[Unreleased]: https://github.com/jbburns/manyfold-jdbc/commits/main
