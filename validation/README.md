# GUI validation

An on-demand check that the driver works inside a real SQL client (SQuirreL SQL under a virtual
display). It is not part of CI.

Everything about it is in [DEVELOPING.md](../DEVELOPING.md#the-gui-validation-harness): what it
needs, how to run it in h2 mode, in multi mode against PostgreSQL, MariaDB and H2, with Docker
Compose or without Docker, where the screenshots land, and what breaks it.

The quick version:

```
validation/run.sh                 # two H2 databases, no server needed
MODE=multi validation/run.sh      # PostgreSQL, MariaDB and H2, start the servers first
```

Files here:

| File | Purpose |
|---|---|
| `run.sh` | The harness. Pinned SQuirreL and vendor driver versions and checksums are at the top. |
| `auto-install.xml` | Silent install answers for the SQuirreL installer. |
| `Dockerfile` | Image with the harness and all downloads baked in. |
| `docker-compose.yml` | MariaDB, PostgreSQL and the harness in multi mode. |
| `db/*-init.sql` | Seed tables for the two servers. |
| `db/mariadb-zone1-grants.sql` | Docker Compose only: grants the demo user on the `zone1_dev2` database. |
| `db/start-local.sh` | Native, non-Docker start and seed of both servers. |
