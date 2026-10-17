# Publishing Siftell

Siftell uses Maven Central coordinates `io.github.sabino:siftell`. The release
build produces the source-only library JAR, sources JAR, documentation JAR and
POM. The documentation JAR contains the Clojure guide, API reference and examples.

Babashka handles local checks and artifact preparation. The official
[JReleaser GitHub Action](https://github.com/jreleaser/release-action) handles
signing, Central deployment and the GitHub release. Its configuration is
[jreleaser.yml](../jreleaser.yml); the workflow pins JReleaser 1.26.0.

## Account and signing configuration

Sign in to [Central Portal](https://central.sonatype.com) with the owning GitHub
account and verify `io.github.sabino` under Namespaces. Generate publishing
credentials on the [user tokens page](https://central.sonatype.com/usertoken).
Central credentials are separate from GitHub credentials.

Configure these repository Actions secrets:

| Secret | Value |
| --- | --- |
| `CENTRAL_USERNAME` | Generated Central token username |
| `CENTRAL_PASSWORD` | Generated Central token password |
| `GPG_PRIVATE_KEY` | Armored export of the dedicated release signing key |
| `GPG_PASSPHRASE` | Its passphrase |

The workflow maps those secrets to JReleaser's environment variables. It reads
the public key from [signing-key.asc](../signing-key.asc), fingerprint
`ED32C23E4F4B0A7D985C1B9FC9B90D37EEFF34B7`.
[JReleaser signing](https://jreleaser.org/guide/latest/reference/signing.html)
uses `MEMORY` mode and verifies the generated signatures.

The public key must be available through a supported keyserver, such as
`hkps://keyserver.ubuntu.com`, before Central validates the signatures.
For rotation, replace the dedicated signing key and passphrase secrets,
update the tracked public key and its documented fingerprint, and publish the
new public key to the keyserver. Never commit private key material or a passphrase.

## Prepare a release

1. Set the version in `build.edn` and its SCM tag to `vVERSION`. Update the
   changelog and installation examples for that version.
2. Run the local checks:

   ```sh
   bb check
   bb version
   ```

   `bb check` runs tests, examples, the release build and isolated package
   verification. The four release artifacts are staged in Maven repository
   layout under `target/central`.

3. Commit the release preparation, then create and push its version tag:

   ```sh
   git tag -a vVERSION -m "Siftell VERSION"
   git push origin main
   git push origin vVERSION
   ```

The Release workflow runs `bb ci-release` to verify that HEAD matches the tag
and that the tag matches `build.edn`. That task exports the canonical project
version and tracked public key to the workflow. After `bb check`, publishing
runs in three steps:

1. JReleaser's `deploy --strict` signs the artifacts, checks Maven metadata and
   deploys the staged release to Central.
2. `bb verify-central` waits for the published library JAR, sources JAR,
   documentation JAR and POM to become available, then compares all four files
   byte for byte with the local release artifacts. Missing or different bytes
   fail verification and prevent the GitHub release.
3. JReleaser's `full-release --strict --exclude-deployer-name sonatype` signs
   the downloadable assets and creates the GitHub release after verification.

The GitHub release contains the three JARs, POM, checksums and signatures.
Existing version tags are preserved; releases are not overwritten. No issue
comments or release announcements are configured. See the maintained
[JReleaser workflow reference](https://jreleaser.org/guide/latest/concepts/workflow.html)
for its release stages.

Central release versions are immutable. Publish a new version for changes.
The runtime dependencies are Clojure and Malli. Malli is resolved through
Clojars, which Clojure CLI and Leiningen include by default. Maven and Gradle
consumers must add `https://repo.clojars.org/` to their repositories. The
published POM leaves repository selection to the consuming build.

## Inspect a deployment

The workflow preserves release artifacts, JReleaser logs and
`out/jreleaser/output.properties` as a GitHub Actions artifact, including when a
deployment fails or exceeds its polling deadline. The properties file records
the Central deployment identifier for inspection in Central Portal.

The Release workflow can also be started manually with an existing `tag`.
Manual runs execute the same checks and deployment sequence. Inspect the status
of an earlier deployment before starting another run. For failed validation,
correct Central's reported cause. Once a version has been published, use a new
version for subsequent changes.

See the maintained
[Maven Central deployer reference](https://jreleaser.org/guide/latest/reference/deploy/maven/maven-central.html)
for deployment diagnostics and publication checks.
