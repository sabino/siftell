# Publishing Siftell

Siftell uses Maven Central coordinates `io.github.sabino:siftell`. The release
build produces a source-only library JAR, its sources JAR, a documentation JAR
containing the actual Clojure guide and API reference, and a complete POM.

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

The public release key is [signing-key.asc](../signing-key.asc), fingerprint
`ED32C23E4F4B0A7D985C1B9FC9B90D37EEFF34B7`. Its public key
must be available through a supported keyserver, such as
`hkps://keyserver.ubuntu.com`, before Central validates the signatures.
The signing script imports its key into a temporary keyring and removes that
keyring after use. Secret key material is never included in release artifacts.

For key rotation, generate a new dedicated signing key, export it with
`gpg --armor --export-secret-keys FINGERPRINT`, update the two signing secrets,
replace the public key file, and publish that public key to the keyserver.
Never commit the secret key or passphrase.

## Prepare a release

1. Set the version in `build.edn` and its SCM tag to `vVERSION`. Update the
   changelog and installation examples for that version.
2. Run the local checks:

   ```sh
   clojure -Srepro -M:test
   clojure -Srepro -M:examples
   python3 -m unittest discover -s scripts -p 'test_*.py'
   clojure -Srepro -T:build release
   python3 scripts/check-package.py
   python3 scripts/central-release.py bundle --unsigned
   ```

   Unsigned bundling checks layout and checksums without credentials. The
   publisher rejects unsigned bundles.

3. Commit the release preparation, then create and push its version tag:

   ```sh
   git tag -a vVERSION -m "Siftell VERSION"
   git push origin main
   git push origin vVERSION
   ```

The Release workflow checks that its checkout matches the tag, reruns the
tests, builds and checks all artifacts, signs them, and uploads the signed
bundle through the Central Publisher API. It waits for `PUBLISHED` before
creating a GitHub release with downloadable artifacts. A validated deployment
alone is not treated as published.

Central release versions are immutable. Publish a new version for changes.
The library's runtime dependencies are Clojure and Malli. Malli is resolved
through Clojars, which the generated POM declares; Clojure CLI also includes
Clojars in its default repositories.

## Inspect or resume a deployment

The workflow preserves the bundle, checksums, public signatures and
`central-deployment.json` as a GitHub Actions artifact, including when a
deployment fails or exceeds the polling deadline.

For a pending deployment, run Release manually with the original tag and the
existing deployment ID. It polls that deployment without uploading again.
Locally, the publisher also reuses a matching saved deployment receipt.

```sh
python3 scripts/central-release.py publish \
  --tag vVERSION --deployment-id DEPLOYMENT_UUID
```

Set the two Central credential environment variables before running that
command. It verifies `target/central-bundle.zip` before contacting Central.
For a failed validation, inspect Central's errors and correct the cause;
do not repeatedly upload a deployment whose status is unknown.

The implementation follows the official
[artifact requirements](https://central.sonatype.org/publish/requirements/),
[namespace registration](https://central.sonatype.org/register/namespace/),
and [Publisher API](https://central.sonatype.org/publish/publish-portal-api/).
