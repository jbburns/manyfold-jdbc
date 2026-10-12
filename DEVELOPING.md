# Developing manyfold-jdbc

A practical guide for someone who has just cloned the repository. The design rules live in
[CLAUDE.md](CLAUDE.md). The pull request process lives in [CONTRIBUTING.md](CONTRIBUTING.md).

## Prerequisites

You need JDK 21. Nothing else.

The Gradle wrapper (`./gradlew`) downloads Gradle itself. Gradle downloads JDKs 11 and 17 by
itself for the `test11` and `test17` tasks if you have none. The bytecode targets Java 11, but the
build runs on 21.

Check your JDK:

```
java -version
```

The GUI validation harness has extra requirements. They are listed in its own section below and
are not needed for anything else.

## Building

```
./gradlew build
```

This is everything CI runs. In order, it:

1. Compiles the code with Error Prone and NullAway. Warnings are errors.
2. Checks formatting with Spotless (google-java-format). It does not fix anything.
3. Runs the unit tests on the build JDK (`test`) and again on JDK 11 (`test11`) and JDK 17 (`test17`).
4. Writes the JaCoCo coverage report.
5. Builds the Javadoc with warnings as errors.

Where things land:

| Command | Output |
|---|---|
| `./gradlew jar` | `build/libs/manyfold-jdbc-<version>.jar`, the driver jar with no dependencies. |
| `./gradlew clientBundle` | `build/client/`, holding the driver jar and the H2 jar, ready to load into a SQL client. |
| `./gradlew test` | `build/reports/tests/test/index.html` and `build/reports/jacoco/test/html/index.html`. |

The version is `0.1.0-SNAPSHOT` until a release. Pass `-Pversion=X.Y.Z` to change it for one
build.

## Running only the tests

```
./gradlew test                      # build JDK only, the fast loop
./gradlew test11                    # the same tests on a JDK 11 runtime
./gradlew test17                    # the same tests on a JDK 17 runtime
./gradlew test --tests '*ReadOnly*' # one class or a name pattern
```

The tests use H2 and SQLite in memory as fake backends. They need no network, no Docker and no
files outside `build/`.

## Fixing formatting

```
./gradlew spotlessApply
```

Run it before you commit. `./gradlew build` fails on a formatting difference and tells you to
run this. Spotless also trims trailing whitespace and enforces a final newline in Markdown,
TOML, properties and Gradle Kotlin files.

## Reading Error Prone and NullAway output

The compiler runs with `-Xlint:all -Werror`, so a warning fails the build. A message looks like
this:

```
src/main/java/io/github/jbburns/manyfold/jdbc/internal/Example.java:42: error: [NullAway] dereferenced expression name is @Nullable
    return name.length();
                ^
```

The name in square brackets is the check. The caret shows the expression. Common cases:

- `[NullAway]` means a value that may be null is used without a check. Add the check, or change
  the type to `@Nullable` where null is legal and handle it at the use site. Packages are
  `@NullMarked`, so everything is non-null unless you say otherwise. Use
  `org.jspecify.annotations.Nullable`.
- Other bracketed names are Error Prone bug patterns. Each has a page at
  `https://errorprone.info/bugpattern/<Name>` that explains it and shows the fix.
- `[-Xlint:...]` warnings come from javac itself, for example unchecked casts or unclosed
  resources.

Prefer fixing the code. If you must suppress, do it on the smallest scope with
`@SuppressWarnings("CheckName")` and a comment saying why. Test code has NullAway switched
off, so tests may pass null freely.

## Changing a dependency version

Dependency versions live in `gradle/libs.versions.toml`. Every artifact is pinned by checksum in
`gradle/verification-metadata.xml`, so a version change fails the build until that file is
regenerated:

```
./gradlew --write-verification-metadata sha256 build
```

Commit the changed `gradle/verification-metadata.xml` with the version change. Review the diff:
it should only add and remove entries for the artifacts you changed.

Dependabot pull requests need this step before CI can pass. Dependabot only edits the version
file. Check out the branch, run the command above, and push the result to the same branch.

Do not add a runtime dependency. The published POM must list none (see CLAUDE.md, invariant 1).

## Publishing dry run

To exercise the release build without touching Maven Central:

```
./gradlew publishToMavenLocal -Pversion=0.0.1 \
  -PsigningInMemoryKey="$KEY" -PsigningInMemoryKeyPassword=''
ls ~/.m2/repository/io/github/jbburns/manyfold-jdbc/0.0.1/
```

`$KEY` is an ASCII-armoured PGP secret key. [RELEASING.md](RELEASING.md) shows how to generate a
throwaway one with `gpg`. You should see the jar, sources jar, Javadoc jar, POM and Gradle module
file, each with an `.asc` signature.

Without signing properties the build fails. Signing is always on in `build.gradle.kts`
(`signAllPublications()`), so this happens:

```
> Task :signMavenPublication FAILED
No configured signatory
```

You can skip signing for a local check by excluding the task:

```
./gradlew publishToMavenLocal -Pversion=0.0.1 -x signMavenPublication
```

That publishes the jar, sources jar, Javadoc jar, POM and module file without new signatures.
Run `./gradlew clean` first if you have signed before, because leftover `.asc` files in `build/`
can be copied along. Check that the POM has no `<dependency>` element. Delete
`~/.m2/repository/io/github/jbburns/manyfold-jdbc/0.0.1` when you are done so it does not shadow
a real release.

## The GUI validation harness

The unit tests never open a SQL client. The harness does. It installs SQuirreL SQL 5.1.0, loads
the driver into it, and drives the real window with synthetic mouse and keyboard events while
taking screenshots. It is not part of CI, and it is on demand only.

### What it needs

A Linux machine with JDK 17 or newer, Xvfb (a virtual display), xdotool, ImageMagick, curl and
the DejaVu fonts. Nothing needs a real screen. On Debian or Ubuntu:

```
sudo apt-get install -y --no-install-recommends xvfb xdotool imagemagick x11-utils fonts-dejavu curl
```

Paths must not contain spaces. The first run downloads a 65 MB SQuirreL installer from GitHub
into `validation/.cache/`, verifies its SHA-256 and installs it silently. Later runs reuse it.

### Mode h2: two in-memory databases

```
validation/run.sh
```

This is the default. It builds `build/client/` if needed, then connects to two H2 in-memory
databases and runs `SELECT * FROM orders ORDER BY id`, `SELECT count(*) FROM orders` and
`DELETE FROM orders`. The `DELETE` must be refused by the read-only guard. It needs no database
server.

It then runs the schema-directive demonstration from the README: the two-line statement
`-- manyfold dev: zone1_prod=zone1_dev2` / `SELECT * FROM zone1_prod.orders ORDER BY id`
(`07-schema-directive.png`, expect prod 1 and 2, dev 3), and the same `SELECT` without the comment
(`08-schema-directive-missing.png`, expect H2's `Schema "ZONE1_PROD" not found` from backend
`dev`, which is the point; it does not fail the run). SQuirreL prints only the deepest cause of an
exception, so the driver's `Backend 'dev' failed:` prefix is not visible in that screenshot. The
alias URL is the one in "Try it in five minutes", which also creates schema `ZONE1_PROD` on prod
and `ZONE1_DEV2` on dev, so change the two together. The harness writes `prefs.xml` with SQuirreL's
*Remove line comment* and *Remove multi line comment* options off, because by default SQuirreL
strips a directive before the driver sees it.

### Mode multi: PostgreSQL, MariaDB and H2

```
MODE=multi validation/run.sh
```

This runs the same client against three different products behind one URL, in the order
PostgreSQL, MariaDB, H2. The URL is the one shown in the README section "One SQL, three
databases". The harness reads it from there, so the two cannot drift apart.

It also downloads the two vendor JDBC jars into `validation/.cache/drivers/`, from Maven Central,
with pinned versions and SHA-256 checksums set at the top of `validation/run.sh`. They are not
Gradle dependencies. The MariaDB driver is LGPL-2.1, the dependency review job in CI denies that
license, and the manyfold driver does not depend on either jar.

Before it launches SQuirreL, the harness waits up to 60 seconds until both servers accept TCP
connections, and fails with a clear message if they do not. Start the servers one of two ways.

**With Docker.** Start only the databases, then run the harness on the host:

```
docker compose -f validation/docker-compose.yml up -d --wait mariadb postgres
MODE=multi validation/run.sh
docker compose -f validation/docker-compose.yml down
```

The compose file uses `mariadb:11` and `postgres:17`. Both create database `demo`, user `demo`,
password `demo`, and load the seed scripts from `validation/db/`. Ports 3306 and 5432 must be
free on the host.

**Without Docker.** `validation/db/start-local.sh` is the native path, for machines that cannot
run Docker. Run it as root. It is safe to run again.

```
sudo validation/db/start-local.sh
MODE=multi validation/run.sh
```

It installs `mariadb-server` and `postgresql` with apt if they are missing, starts both servers,
creates database `demo` and user `demo` with password `demo` on each, allows password login from
127.0.0.1, loads the seed scripts and prints the rows it loaded. It changes the machine, so use a
throwaway or development one. It leaves both servers running. Stop them with
`sudo service mariadb stop` and `sudo pg_ctlcluster <version> main stop`, where `<version>` is
the number shown by `pg_lsclusters`.

Set `DB_HOST_POSTGRES` and `DB_HOST_MARIADB` if the servers are not on `localhost`.

### SQuirreL SQL 4.2.0 on Java 14

The driver's floor is Java 11, but SQuirreL SQL 5.x itself needs Java 17. To prove the driver in
the oldest SQuirreL it supports, on a JVM that SQuirreL 4.2 accepts (Java 8 to 16, and the driver
needs 11 or newer), set two environment variables:

```
SQUIRREL_VERSION=4.2.0 SQUIRREL_JAVA_HOME=/path/to/jdk14 validation/run.sh
```

`SQUIRREL_VERSION` is `5.1.0` by default (and `4.2.0` is the only other value);
`SQUIRREL_JAVA_HOME` is the JDK that installs and launches SQuirreL, and defaults to the `java`
on the `PATH`. `run.sh` refuses a JVM outside the range the chosen version supports (17 or newer
for 5.1.0, 11 to 16 for 4.2.0). Screenshots are `s42-NN-<label>.png`, h2 mode only.

The 4.2.0 installer is not on GitHub. `run.sh` downloads `squirrel-sql-4.2.0-standard.jar` from
SourceForge and verifies its SHA-256, and installs it with `validation/auto-install-4.2.xml`
(an older IzPack, so the panel class names differ from `auto-install.xml`). JDK 14 was never
published by Temurin. Take it from the Foojay Disco API, the source Gradle's toolchain plugin
uses, for example Azul Zulu:

```
curl -sS "https://api.foojay.io/disco/v3.0/packages?version=14&distribution=zulu&operating_system=linux&architecture=x64&archive_type=tar.gz&package_type=jdk&javafx_bundled=false&latest=available"
```

Download the `links.pkg_download_redirect` of the entry whose `lib_c_type` is `glibc`, unpack it,
for example under `validation/.cache/jdk14/` (git-ignored), and point `SQUIRREL_JAVA_HOME` at it.
Any JDK from 11 to 16 works, for example the JDK 11 that Gradle provisions under `~/.gradle/jdks`.

What differs from 5.1.0, all handled by the version block at the top of `run.sh`: the download
and checksum, the installer answers, the main window title (`SQuirreL SQL Client Version 4.2.0`),
the 4.2.0 launcher script (it does not `exec` java, so the harness kills the process group, and it
takes the JVM from `JAVA_HOME`), a settings key (4.2.0 has no `removeLineComment`), a startup
ERROR line about the Windows look and feel that the log check ignores as a baseline, the column
header row used to widen the first column, and the schema-directive step. 4.2.0 always strips
`--` comments before the driver sees them, so the harness sends the block form
`/* manyfold dev: zone1_prod=zone1_dev2 */` with *Remove multi line comment* off. The run ends with
Help > About (`s42-09-about.png`) and its System tab (`s42-10-about-system.png`), because without a
window manager no title bar shows the version or the JVM. `RESULT.txt` records the SQuirreL version
and the `java.version` that SQuirreL's own log reports.

### The Docker way

The image installs the tools, the driver bundle, SQuirreL and both vendor jars at build time, so
the container itself needs no network. In h2 mode:

```
docker build -f validation/Dockerfile -t manyfold-gui-validation .
docker run --rm -v "$PWD/validation/out:/out" manyfold-gui-validation
```

In multi mode, let Compose start the databases and the harness together:

```
docker compose -f validation/docker-compose.yml up --abort-on-container-exit squirrel
```

The `squirrel` service waits until both databases are healthy, runs the harness with
`MODE=multi` against the hosts `mariadb` and `postgres`, and writes to `validation/out/`. Add
`--exit-code-from squirrel` to make the command's exit status follow the harness. Clean up with
`docker compose -f validation/docker-compose.yml down`.

### Where the results land

In `validation/out/`. It is git-ignored apart from `.gitkeep`.

| File | Content |
|---|---|
| `NN-<label>.png` | Screenshots from h2 mode, for example `03-select-all.png`. |
| `multi-NN-<label>.png` | Screenshots from multi mode, for example `multi-03-select-all.png`. |
| `s42-NN-<label>.png` | Screenshots from SQuirreL 4.2.0, for example `s42-03-select-all.png`. |
| `RESULT.txt` | `PASS` or `FAIL`, the mode, the jars used, the checks made and one line per screenshot. |
| `squirrel-sql.log` | SQuirreL's own log. |

Each run deletes the previous screenshots and `RESULT.txt` first. Run one mode, look at the
files, then run the other.

The exit status is 0 for PASS, 1 for FAIL and 2 if the harness could not set up.

PASS is a coarse check. It only catches gross failures: the alias did not connect, an error
window opened, a screenshot is missing or identical to the one before. Open the screenshots. In
multi mode `multi-03-select-all.png` should show five rows with `source_database` in column 1:
`postgres` (id 20), `mariadb` (10 and 11), `h2` (1 and 2), in URL order.

In multi mode the last step, `multi-08-schema-directive.png`, runs
`-- manyfold mariadb: zone1_prod=zone1_dev2` / `-- manyfold h2: zone1_prod=zone1_dev2` /
`SELECT * FROM zone1_prod.orders ORDER BY id` and should show four rows: `postgres` 20 (schema
`zone1_prod`), `mariadb` 10 and 11 (database `zone1_dev2`), `h2` 5 (schema `ZONE1_DEV2`). The
seeding for it: `validation/db/postgres-init.sql` creates schema `zone1_prod`,
`validation/db/mariadb-init.sql` creates database `zone1_dev2`, Docker Compose grants the demo
user on it with `validation/db/mariadb-zone1-grants.sql` (init scripts run as root there),
`validation/db/start-local.sh` grants it on its loopback-only accounts, and the harness appends
the `ZONE1_DEV2` schema to the H2 `INIT` of the README URL. Re-run `start-local.sh` after pulling
this change so an already seeded native server gets the new objects.

### How long it takes

About 40 to 60 seconds for a run once everything is cached. The first run adds the 65 MB
installer download, the SQuirreL install, the vendor jars, and a Gradle build of the client
bundle if `build/client/` is empty. Expect several minutes then. The Docker build takes longer
again.

### Known fragility

The harness clicks at fixed pixel positions on a 1600x1000 virtual screen, laid out for SQuirreL
SQL 5.1.0 and 4.2.0 (the positions are set per version at the top of `validation/run.sh`). There is no window manager and no accessibility hook. So:

- A different SQuirreL version, a different font, or a different screen size can move a button
  or a tab, and the clicks then land on the wrong thing. The symptoms are screenshots of the
  wrong tab and a run that fails or shows the wrong content. Adjust the coordinates in `validation/run.sh`.
- Timing is by `sleep`. A very slow machine may need longer waits.
- The object tree screenshot walks the tree with the keyboard. The multi-mode walk assumes the
  PostgreSQL tree layout (schemas `information_schema`, `pg_catalog`, `public`, and a fixed
  order of table types).
- Screenshots are compared only for "is not identical to the previous one". They do not prove
  the content is right. Read them.

### Upgrading SQuirreL or a vendor driver

Change the pinned version and SHA-256 variables at the top of `validation/run.sh`. For SQuirreL,
also change `SQUIRREL_VERSION` in `validation/Dockerfile`. Compute a checksum from a fresh
download and compare it with the `.sha256` or `.sha1` file that Maven Central publishes next to
the jar:

```
curl -fsSLO https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.13/postgresql-42.7.13.jar
sha256sum postgresql-42.7.13.jar
```
