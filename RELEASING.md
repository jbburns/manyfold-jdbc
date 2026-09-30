# Releasing

Releases are cut by pushing a tag. The workflow in `.github/workflows/release.yml` builds and
tests the tagged commit, signs it, uploads it to the Maven Central Portal, and attaches the jar to
a GitHub Release. No version number lives in the repository: the tag is the version.

## One-time setup

Everything below is done once. None of it goes into the repository.

### 1. Central Portal account and namespace

1. Sign in at <https://central.sonatype.com> **using your GitHub account**. Sonatype then
   verifies the `io.github.jbburns` namespace automatically in most cases.
2. If the namespace is not listed as verified under your user menu, choose *View Namespaces*,
   add `io.github.jbburns`, copy the verification key, create a temporary **public** GitHub
   repository named exactly that key, press *Verify Namespace*, then delete the repository.
3. Open <https://central.sonatype.com/usertoken> and generate a user token. It shows a username
   and a password once. These become the `CENTRAL_USERNAME` and `CENTRAL_PASSWORD` secrets.

### 2. Signing key

Maven Central requires PGP signatures. Sigstore is accepted only in addition, not instead.

```
gpg --full-generate-key            # RSA 4096, your name, the email on your GitHub account
gpg --list-secret-keys --keyid-format long
#   sec   rsa4096/ABCDEF1234567890 ...   <- the 16 hex characters are the key id
gpg --keyserver keyserver.ubuntu.com --send-keys ABCDEF1234567890
gpg --armor --export-secret-keys ABCDEF1234567890 > /tmp/signing-key.asc
```

- `SIGNING_KEY` is the entire contents of `signing-key.asc`, including the BEGIN and END lines.
- `SIGNING_KEY_ID` is the **last 8** characters of the key id, `34567890` in the example.
- `SIGNING_PASSWORD` is the passphrase you chose.

Delete `/tmp/signing-key.asc` after uploading it. Keys expire after two years by default; put a
reminder in your calendar, because an expired key makes every release fail at the signing step.

### 3. GitHub environment and secrets

1. In the repository go to *Settings → Environments → New environment* and name it
   `maven-central`.
2. Under *Deployment protection rules* enable *Required reviewers* and add yourself. Every release
   then waits for you to press *Approve* in the Actions tab before any secret is used.
3. Under *Deployment branches and tags* choose *Selected branches and tags* and add the tag rule
   `v*`. A workflow on any other ref cannot reach these secrets.
4. Under *Environment secrets* add the five secrets: `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`,
   `SIGNING_KEY`, `SIGNING_KEY_ID`, `SIGNING_PASSWORD`.

### 4. Repository settings worth switching on

- *Settings → Code security*: enable Dependabot alerts, Dependabot security updates, secret
  scanning and push protection. All are free for public repositories.
- *Settings → Branches*: protect `main`, require the `Build and test` and `Secret scan` checks,
  and require pull requests. This keeps the release tag pointing at reviewed, green code.

## Cutting a release

1. Add a section to `CHANGELOG.md` for the version, for example `## [0.1.0] - 2026-10-15`, and
   move the entries out of *Unreleased*. Commit and merge it to `main`. The release job refuses
   to run without this section.
2. Tag and push:

   ```
   git checkout main && git pull
   git tag v0.1.0
   git push origin v0.1.0
   ```

3. In the *Actions* tab, approve the `maven-central` deployment when the job pauses.
4. The job uploads a validated deployment. Open <https://central.sonatype.com/publishing> and
   press *Publish*. Artifacts appear on Maven Central within a few minutes and are searchable
   within a few hours.
5. Check the GitHub Release the job created and edit the notes if you like.

Once a release has gone through cleanly, you can make step 4 automatic by changing the Gradle
task in the workflow from `publishToMavenCentral` to `publishAndReleaseToMavenCentral`.

## Limits to keep in mind

- Central's free tier allows **seven releases per calendar month** per namespace. Batch small
  fixes rather than tagging each one.
- A published version can never be changed or deleted. If a release is broken, publish a new
  version.
- The tag pattern is `vMAJOR.MINOR.PATCH`. Pre-release tags such as `v0.2.0-rc1` are not
  matched and do not publish.

## Trying the release build locally

To exercise signing and the published layout without touching Central, generate a throwaway
key and publish to your local Maven repository:

```
gpg --batch --passphrase '' --quick-generate-key throwaway@example.com rsa2048 sign 1d
KEY=$(gpg --armor --export-secret-keys throwaway@example.com)
./gradlew publishToMavenLocal -Pversion=0.0.1 \
  -PsigningInMemoryKey="$KEY" -PsigningInMemoryKeyPassword=''
ls ~/.m2/repository/io/github/jbburns/manyfold-jdbc/0.0.1/
```

You should see the jar, sources jar, javadoc jar, POM and an `.asc` signature next to each.
