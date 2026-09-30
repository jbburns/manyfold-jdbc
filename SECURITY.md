# Security policy

## Reporting a vulnerability

Report vulnerabilities privately through GitHub's
[private vulnerability reporting](https://github.com/jbburns/manyfold-jdbc/security/advisories/new).
Do not open a public issue for anything you believe is a security problem.

There is no guaranteed response time. This project is maintained on a best-effort basis.

## Scope

manyfold-jdbc is a pass-through layer. It forwards SQL text and connection properties, including
credentials, to the vendor JDBC drivers named in the URL. Vulnerabilities in those drivers or in the
databases behind them are out of scope here and should be reported to their maintainers.

In scope:

- The driver forwarding a statement to a backend it should not have, such as a write reaching a
  backend while read-only mode is enabled.
- Credentials or connection details leaking into logs, exceptions, or the merged result set.
- Unsafe handling of the JDBC URL or driver properties.

## Supported versions

Only the latest release receives fixes.
