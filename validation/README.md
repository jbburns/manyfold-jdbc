# GUI validation

An on-demand check that the driver works inside a real SQL client. It is deliberately **not** part
of CI: it downloads a 65 MB installer, needs a display server, and drives a GUI with synthetic
mouse and keyboard events, which is slower and less reliable than the unit tests.

## What it does

1. Builds the client bundle (`./gradlew clientBundle`) unless `build/client/` already has the jars.
2. Downloads SQuirreL SQL 5.1.0 from the official GitHub releases, verifies its SHA-256 and
   installs it silently with an IzPack auto-install file ([auto-install.xml](auto-install.xml)).
3. Writes a SQuirreL settings directory with a `manyfold` driver definition (both jars on its
   class path) and an alias `manyfold-h2-demo` that connects at startup. The alias URL is taken
   from the "Try it in five minutes" section of the main README, so it cannot drift.
4. Starts Xvfb, launches SQuirreL with `-userdir <tmp> -nos`, and through xdotool:
   - takes a screenshot right after the automatic connect,
   - expands the object tree,
   - runs `SELECT * FROM orders ORDER BY id`, `SELECT count(*) FROM orders` and
     `DELETE FROM orders` in the SQL tab, with a screenshot after each,
   - runs the count again to show the refused `DELETE` removed nothing.
5. Writes `RESULT.txt` and exits 0 on PASS, 1 on FAIL, 2 if it could not set up.

PASS is a simple check: SQuirreL's log shows the alias connected at startup, the session tab was
drawn, no error window or log error appeared after the two SELECTs, every screenshot exists and
differs from the previous one, and SQuirreL was still running at the end. That only catches gross
failures. **Open the screenshots**; they are the evidence. The refused `DELETE` is reported but
never decides PASS or FAIL.

## Run it directly

Needs a Linux machine with JDK 17+, Xvfb, xdotool, curl, DejaVu fonts, and ImageMagick or scrot.
On Debian or Ubuntu:

```
sudo apt-get install -y --no-install-recommends xvfb xdotool imagemagick x11-utils fonts-dejavu curl
validation/run.sh
```

Paths must not contain spaces. Useful environment variables are listed at the top of
[run.sh](run.sh): `OUT_DIR`, `CACHE_DIR`, `SQUIRREL_HOME`, `BUNDLE_DIR` and `SKIP_BUILD`.

## Run it in Docker

The image installs the tools, builds the bundle in a first stage, and installs SQuirreL, so the
container needs no network. Build from the repository root:

```
docker build -f validation/Dockerfile -t manyfold-gui-validation .
docker run --rm -v "$PWD/validation/out:/out" manyfold-gui-validation
```

## Where the screenshots land

In `validation/out/` (or wherever `OUT_DIR` or the `/out` mount points). Files are numbered in
order: `01-connected.png`, `02-objects-tree.png`, `03-select-all.png`, `04-count.png`,
`05-delete-refused.png`, `06-count-after-delete.png`, plus `RESULT.txt` and SQuirreL's own
`squirrel-sql.log`. The folder is git-ignored apart from `.gitkeep`. The installer download is
cached in `validation/.cache/`, also git-ignored.

## Upgrading SQuirreL

Change `SQUIRREL_VERSION`, `SQUIRREL_SHA256` and (if the asset name changed) `SQUIRREL_URL` at the
top of [run.sh](run.sh), and `SQUIRREL_VERSION` in the [Dockerfile](Dockerfile). The mouse
coordinates in run.sh assume SQuirreL's 1600x1000 layout and may need adjusting after an upgrade.
