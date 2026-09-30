# manyfold-jdbc

A pass-through JDBC driver that runs one SQL statement against several databases and returns
the rows as a single result set, with a `source_database` column telling you where each row
came from.

> **Status:** under construction. Nothing is published yet.

## Why

You have the same schema in more than one place, prod and dev, or one database per region, and
you want to run a query against all of them from a normal SQL client and see the results side by
side. manyfold-jdbc sits between your client and the real JDBC drivers. It is a single jar with
no dependencies, it does not run a server, and it does not parse or rewrite your SQL.

## How it works

```
jdbc:manyfold:prod=jdbc:postgresql://prod-host:5432/app || dev=jdbc:postgresql://dev-host:5432/app
```

1. The driver opens one real connection per backend using the vendor drivers already on the
   client's driver classpath.
2. Every statement is forwarded verbatim to every backend, concurrently.
3. The rows come back concatenated in URL order. Column 1 is `source_database` and holds the
   logical name (`prod`, `dev`). The remaining columns are the backend's columns, unchanged.
4. Metadata calls that need a single answer, such as the table list a SQL client shows in its
   tree, are answered by the first backend.

By default the driver is **read-only**: anything that is not a query is refused before it
reaches a backend. Set `readOnly=false` in the URL options to fan writes out to every backend.

## URL syntax

```
jdbc:manyfold:[option=value;...][name=]<jdbc-url> || [name=]<jdbc-url> [|| ...]
```

| Part | Meaning |
|---|---|
| ` \|\| ` | Separates backends. Chosen because it never appears inside a JDBC URL. |
| `name=` | Optional logical name for the backend, shown in `source_database`. Without it the name is derived from the URL, for example `postgresql://prod-host:5432/app`. |
| `sourceColumn=` | Name of the injected column. Default `source_database`. |
| `readOnly=` | `true` (default) refuses statements that can modify data. `false` forwards everything. |

The username and password given to the connection apply to every backend. To override them for
one backend, set the driver properties `manyfold.<name>.user` and `manyfold.<name>.password`.
SQL clients expose driver properties in their connection dialog.

## Setting up a SQL client

The vendor jars must be in the **same driver definition** as the manyfold jar. Both DBeaver and
SQuirreL SQL build one classloader per driver definition, and manyfold finds the vendor drivers
through that classloader.

A step-by-step walkthrough for SQuirreL SQL and DBeaver will be added here once the first
release is out.

## Requirements

- Java 17 or newer. SQuirreL SQL 5.x and current DBeaver both qualify.
- The vendor JDBC driver for each backend.

## Building

```
./gradlew build
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow and
[CLAUDE.md](CLAUDE.md) for the design invariants.

## Maintenance status

Maintained on a best-effort basis with no response-time commitment. Issues and pull requests are
welcome; see [SUPPORT.md](SUPPORT.md) and [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[Apache License 2.0](LICENSE).
