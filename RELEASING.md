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
gpg --full-generate-key
#   RSA and RSA, 4096 bits, expires in 2y, your name, your GitHub noreply address, a passphrase
gpg --list-secret-keys --keyid-format long
#   sec   rsa4096/3F2A9B1C7D8E4F50 2026-09-30 [SC] [expires: 2028-09-30]
#   the 16 characters after the slash are YOUR key id; the value above is only an example
gpg --keyserver keyserver.ubuntu.com --send-keys 3F2A9B1C7D8E4F50
gpg --armor --export-secret-keys 3F2A9B1C7D8E4F50 > /tmp/signing-key.asc
gpg --gen-revoke 3F2A9B1C7D8E4F50 > /tmp/signing-key-revoke.asc
```

- The email on the key can be your GitHub noreply address, such as
  `9023993+jbburns@users.noreply.github.com`. Central never checks it, and it keeps your real
  address out of the public key metadata. Use `keyserver.ubuntu.com`, which publishes without
  emailing you; `keys.openpgp.org` would try to verify the address first.
- `SIGNING_KEY` is the entire contents of `signing-key.asc`, including the BEGIN and END lines.
- `SIGNING_KEY_ID` is the **last 8** characters of the key id, `7D8E4F50` in the example.
- `SIGNING_PASSWORD` is the passphrase you chose.

Store the passphrase, the exported key and the revocation certificate together in a password
manager, then delete the two files from `/tmp`. GitHub secrets cannot be read back, so that
entry is your only copy. If the passphrase is lost the key is unusable: releases stop at the
signing step until you generate a new key and replace the three signing secrets. Nothing already
published is affected. Keys expire after two years by default; put a reminder in your calendar,
because an expired key also fails every release at the signing step.

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

## Cutting a release, each time

1. **Check main is green.** The Actions tab must show the latest CI run on `main` passing.
   The release job runs the full build again and refuses a tag that is not on `main`.
2. **Write the changelog section.** In `CHANGELOG.md`, add `## [0.1.0] - 2026-10-15` under
   `[Unreleased]`, move the entries into it, and add the version link at the bottom. Open a
   pull request for this change and merge it. The release job refuses to run without a
   `## [0.1.0]` heading, so this step cannot be skipped.
3. **Tag the merge commit and push the tag.** The tag is the version; nothing in the
   repository holds a version number.

   ```
   git checkout main && git pull
   git tag v0.1.0
   git push origin v0.1.0
   ```

4. **Approve the deployment.** Open the *Actions* tab, click the *Release* run, and press
   *Review deployments* then *Approve*. The job pauses here until you do, so nothing is signed
   or uploaded without a person pressing the button.
5. **Wait for Central.** The job uploads, validates and releases the deployment in one go.
   Artifacts are on Maven Central within minutes and searchable within hours at
   <https://central.sonatype.com/artifact/io.github.jbburns/manyfold-jdbc>.
6. **Check the GitHub Release** the job created under *Releases*. It carries the jar, its
   SHA-256, and the sources and javadoc jars. Edit the notes if you like.

If step 4 fails, fix the cause on `main` and tag the next patch version. A tag can be reused
only if the deployment never reached the Portal. To inspect a deployment before it goes live,
change the Gradle task in the workflow from `publishAndReleaseToMavenCentral` back to
`publishToMavenCentral`; the job then stops after upload and you press *Publish* in the Portal.

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
