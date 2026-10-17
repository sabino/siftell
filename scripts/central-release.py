#!/usr/bin/env python3
"""Sign a built Maven release, assemble its bundle, and publish through Central."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
from zipfile import BadZipFile, ZipFile, ZIP_DEFLATED, ZipInfo

CENTRAL = "https://central.sonatype.com"
MAVEN_NS = {"m": "http://maven.apache.org/POM/4.0.0"}
VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?")
COORDINATE = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.-]*")


class ReleaseError(Exception):
    """A release failed without disclosing authentication material."""


def release_files(staging, tag=None):
    """Require one complete release in Maven layout and a matching version tag."""
    staging = Path(staging)
    poms = list(staging.rglob("*.pom"))
    if len(poms) != 1:
        raise ReleaseError("Build exactly one release with clojure -T:build release")
    pom = poms[0]
    tree = ET.parse(pom)
    values = [tree.findtext("m:" + key, namespaces=MAVEN_NS)
              for key in ("groupId", "artifactId", "version")]
    group, artifact, version = values
    if not all(isinstance(v, str) and COORDINATE.fullmatch(v) for v in values):
        raise ReleaseError("Invalid Maven coordinates")
    if not VERSION.fullmatch(version) or version.upper().endswith("-SNAPSHOT"):
        raise ReleaseError("Central releases require a non-SNAPSHOT semantic version")
    if tag is None:
        tag = "v" + version
    if tag != "v" + version:
        raise ReleaseError("Release tag must be v" + version)
    relative = Path(*group.split(".")) / artifact / version
    if pom.relative_to(staging) != relative / (artifact + "-" + version + ".pom"):
        raise ReleaseError("Release files must use Maven repository layout")
    stem = artifact + "-" + version
    files = [pom.parent / (stem + suffix)
             for suffix in (".jar", "-sources.jar", "-javadoc.jar", ".pom")]
    for item in files:
        if not item.is_file() or item.is_symlink() or not item.stat().st_size:
            raise ReleaseError("Missing or invalid release artifact: " + item.name)
    for item in staging.rglob("*"):
        if item.is_symlink():
            raise ReleaseError("Release staging must not contain symlinks")
        if item.is_file() and item not in files:
            raise ReleaseError("Unexpected staged file: " + item.name)
    return {"group": group, "artifact": artifact, "version": version,
            "tag": tag, "directory": relative.as_posix()}, files


def _gpg(arguments, home, data=None):
    environment = {k: v for k, v in os.environ.items()
                   if k not in {"GPG_PRIVATE_KEY", "GPG_PASSPHRASE",
                                "CENTRAL_USERNAME", "CENTRAL_PASSWORD"}}
    environment["GNUPGHOME"] = str(home)
    result = subprocess.run(["gpg", "--batch", "--no-tty"] + arguments,
                            input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            env=environment, timeout=60)
    if result.returncode:
        raise ReleaseError("GPG operation failed; check the release key and passphrase")
    return result.stdout


def sign(files, output):
    """Use a temporary keyring; never read or modify the user's signing keyring."""
    private_key = os.environ.get("GPG_PRIVATE_KEY")
    passphrase = os.environ.get("GPG_PASSPHRASE", "")
    if not private_key:
        raise ReleaseError("Set GPG_PRIVATE_KEY for a signed release")
    if "\n" in passphrase or "\r" in passphrase:
        raise ReleaseError("The signing passphrase must be a single line")
    with tempfile.TemporaryDirectory(prefix="siftell-gpg-") as temporary:
        home = Path(temporary)
        home.chmod(0o700)
        try:
            _gpg(["--import"], home, private_key.encode())
            listing = _gpg(["--with-colons", "--list-secret-keys", "--fingerprint"], home).decode()
            fingerprints, primary = [], False
            for line in listing.splitlines():
                columns = line.split(":")
                if columns[0] == "sec":
                    primary = True
                elif columns[0] == "fpr" and primary:
                    fingerprints.append(columns[9])
                    primary = False
                elif columns[0] == "ssb":
                    primary = False
            if len(fingerprints) != 1:
                raise ReleaseError("Import exactly one release signing key")
            fingerprint = fingerprints[0]
            public_key = _gpg(["--armor", "--export", fingerprint], home)
            output.mkdir(parents=True, exist_ok=True)
            (output / "siftell-signing-key.asc").write_bytes(public_key)
            signatures = {}
            for item in files:
                signature = output / (item.name + ".asc")
                _gpg(["--yes", "--pinentry-mode", "loopback", "--passphrase-fd", "0",
                      "--local-user", fingerprint + "!", "--armor", "--detach-sign",
                      "--output", str(signature), str(item)], home, (passphrase + "\n").encode())
                _gpg(["--verify", str(signature), str(item)], home)
                signatures[item.name] = signature.read_bytes()
            return fingerprint, signatures
        finally:
            subprocess.run(["gpgconf", "--homedir", str(home), "--kill", "gpg-agent"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)


def bundle(staging, destination, tag, unsigned=False):
    """Assemble only artifacts, signatures and checksums, with stable ZIP ordering."""
    metadata, files = release_files(staging, tag)
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    fingerprint, signatures = (None, {}) if unsigned else sign(files, destination.parent / "signatures")
    entries, checksums = {}, {}
    for item in files:
        content = item.read_bytes()
        relative = metadata["directory"] + "/" + item.name
        entries[relative] = content
        # Central requires MD5/SHA1; SHA256 also supports artifact verification.
        for algorithm in ("md5", "sha1", "sha256"):
            digest = hashlib.new(algorithm, content, usedforsecurity=False).hexdigest()
            entries[relative + "." + algorithm] = digest.encode("ascii")
            if algorithm == "sha256":
                checksums[item.name] = digest
        if not unsigned:
            entries[relative + ".asc"] = signatures[item.name]
    with ZipFile(destination, "w", compression=ZIP_DEFLATED) as archive:
        for name, content in sorted(entries.items()):
            entry = ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = ZIP_DEFLATED
            entry.external_attr = 0o644 << 16
            archive.writestr(entry, content)
    receipt = dict(metadata, signed=not unsigned, fingerprint=fingerprint, sha256=checksums)
    destination.with_suffix(".json").write_text(json.dumps(receipt, indent=2) + "\n")
    return receipt


def verify_bundle(path, tag):
    """Reject incomplete, unsigned or altered bundles before any upload."""
    with ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ReleaseError("Bundle entries must be unique")
        poms = [name for name in names if name.endswith(".pom")]
        if len(poms) != 1:
            raise ReleaseError("Bundle must contain exactly one POM")
        tree = ET.fromstring(archive.read(poms[0]))
        group, artifact, version = [tree.findtext("m:" + key, namespaces=MAVEN_NS)
                                    for key in ("groupId", "artifactId", "version")]
        if not all(isinstance(v, str) and COORDINATE.fullmatch(v) for v in (group, artifact, version)):
            raise ReleaseError("Invalid bundle coordinates")
        if not VERSION.fullmatch(version) or version.upper().endswith("-SNAPSHOT") or tag != "v" + version:
            raise ReleaseError("Bundle version must match a non-SNAPSHOT release tag")
        prefix = group.replace(".", "/") + "/" + artifact + "/" + version + "/"
        stem = prefix + artifact + "-" + version
        primary = [stem + suffix for suffix in (".jar", "-sources.jar", "-javadoc.jar", ".pom")]
        expected = {name + suffix for name in primary for suffix in ("", ".asc", ".md5", ".sha1", ".sha256")}
        if set(names) != expected:
            raise ReleaseError("A signed bundle requires exactly four artifacts, signatures and checksums")
        for name in primary:
            content = archive.read(name)
            if not content:
                raise ReleaseError("Bundle artifacts must not be empty")
            signature = archive.read(name + ".asc")
            if not (signature.startswith(b"-----BEGIN PGP SIGNATURE-----")
                    and b"-----END PGP SIGNATURE-----" in signature):
                raise ReleaseError("Missing armored signature")
            for algorithm in ("md5", "sha1", "sha256"):
                actual = hashlib.new(algorithm, content, usedforsecurity=False).hexdigest()
                if archive.read(name + "." + algorithm).decode("ascii").strip() != actual:
                    raise ReleaseError("Bundle checksum mismatch: " + name)
        return {"group": group, "artifact": artifact, "version": version, "tag": tag}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


class Publisher:
    def __init__(self, username, password, opener=None):
        if not username or not password:
            raise ReleaseError("Set CENTRAL_USERNAME and CENTRAL_PASSWORD")
        self._token = base64.b64encode((username + ":" + password).encode()).decode()
        self._secrets = (username, password, self._token)
        self._opener = opener or urllib.request.build_opener(NoRedirect)

    def _redact(self, value):
        for secret in self._secrets:
            value = value.replace(secret, "[redacted]")
        return value

    def _scrub(self, value):
        if isinstance(value, str):
            return self._redact(value)
        if isinstance(value, dict):
            return {self._redact(str(k)): self._scrub(v) for k, v in value.items()}
        if isinstance(value, list):
            return [self._scrub(v) for v in value]
        return value

    def _request(self, route, body=b"", content_type="application/octet-stream"):
        request = urllib.request.Request(CENTRAL + route, data=body, method="POST",
                                         headers={"Authorization": "Bearer " + self._token,
                                                  "Content-Type": content_type})
        try:
            with self._opener.open(request, timeout=30) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            raise ReleaseError("Central HTTP " + str(error.code) + "; check account access and publishing settings") from None
        except urllib.error.URLError:
            raise ReleaseError("Could not reach Central; retain the deployment ID before retrying") from None

    def upload(self, path, name):
        boundary = "siftell-" + uuid.uuid4().hex
        body = ("--" + boundary + '\r\nContent-Disposition: form-data; name="bundle"; filename="central-bundle.zip"\r\n'
                "Content-Type: application/octet-stream\r\n\r\n").encode()
        body += Path(path).read_bytes() + ("\r\n--" + boundary + "--\r\n").encode()
        route = "/api/v1/publisher/upload?" + urllib.parse.urlencode({"name": name, "publishingType": "AUTOMATIC"})
        raw = self._request(route, body, "multipart/form-data; boundary=" + boundary).decode().strip()
        try:
            return str(uuid.UUID(raw))
        except ValueError:
            raise ReleaseError("Central returned an invalid deployment ID") from None

    def wait(self, deployment_id, timeout=1200, interval=10):
        try:
            deployment_id = str(uuid.UUID(deployment_id))
        except ValueError:
            raise ReleaseError("Supply a valid deployment UUID") from None
        if timeout <= 0 or interval <= 0:
            raise ReleaseError("Polling timeout and interval must be positive")
        started = time.monotonic()
        while time.monotonic() - started < timeout:
            try:
                result = json.loads(self._request("/api/v1/publisher/status?" + urllib.parse.urlencode({"id": deployment_id})))
            except (ValueError, UnicodeDecodeError):
                raise ReleaseError("Central returned an invalid deployment status") from None
            if not isinstance(result, dict):
                raise ReleaseError("Central returned an invalid deployment status")
            state = result.get("deploymentState")
            if not isinstance(state, str) or state not in {"PENDING", "VALIDATING", "VALIDATED", "PUBLISHING", "PUBLISHED", "FAILED"}:
                raise ReleaseError("Central returned an unsupported deployment state")
            print("Central:", state, flush=True)
            if state == "PUBLISHED":
                return result
            if state == "FAILED":
                details = json.dumps(self._scrub(result.get("errors", {})))
                raise ReleaseError("Central validation failed: " + details)
            if state not in {"PENDING", "VALIDATING", "VALIDATED", "PUBLISHING"}:
                raise ReleaseError("Central returned an unsupported deployment state")
            time.sleep(interval)
        raise ReleaseError("Central is still processing deployment " + deployment_id + "; resume polling this ID")


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    assemble = commands.add_parser("bundle", help="Assemble and sign built release artifacts")
    assemble.add_argument("--staging", type=Path, default=Path("target/central"))
    assemble.add_argument("--output", type=Path, default=Path("target/central-bundle.zip"))
    assemble.add_argument("--tag", help="Match this release tag; defaults to the staged POM version")
    assemble.add_argument("--unsigned", action="store_true", help="Local/CI layout check only; cannot be published")
    upload = commands.add_parser("publish", help="Upload once and wait for PUBLISHED, or resume a deployment")
    upload.add_argument("--bundle", type=Path, default=Path("target/central-bundle.zip"))
    upload.add_argument("--tag", required=True)
    upload.add_argument("--deployment-id", help="Resume polling without uploading again")
    upload.add_argument("--timeout", type=int, default=1200)
    options = parser.parse_args(arguments)
    try:
        if options.command == "bundle":
            result = bundle(options.staging, options.output, options.tag, options.unsigned)
            print("Built", options.output, "(signed)" if result["signed"] else "(unsigned validation only)")
        else:
            metadata = verify_bundle(options.bundle, options.tag)
            publisher = Publisher(os.environ.get("CENTRAL_USERNAME"), os.environ.get("CENTRAL_PASSWORD"))
            receipt = Path("target/central-deployment.json")
            saved = json.loads(receipt.read_text()) if receipt.exists() else {}
            if not isinstance(saved, dict):
                raise ReleaseError("Invalid deployment receipt; retain its deployment ID before replacing it")
            same_release = all(saved.get(k) == metadata[k] for k in ("group", "artifact", "version", "tag"))
            deployment = options.deployment_id or (saved.get("deployment_id") if same_release else None)
            if not deployment:
                deployment = publisher.upload(options.bundle, "siftell-" + metadata["version"])
            receipt.parent.mkdir(parents=True, exist_ok=True)
            receipt.write_text(json.dumps(dict(metadata, deployment_id=deployment), indent=2) + "\n")
            print("Deployment:", deployment, flush=True)
            result = publisher.wait(deployment, options.timeout)
            receipt.write_text(json.dumps(dict(metadata, deployment_id=deployment, state=result["deploymentState"]), indent=2) + "\n")
            print("Published", metadata["group"] + ":" + metadata["artifact"] + ":" + metadata["version"])
    except (ReleaseError, OSError, ET.ParseError, subprocess.TimeoutExpired) as error:
        parser.exit(1, "Release failed: " + str(error) + "\n")
    except (BadZipFile, ValueError, KeyError):
        parser.exit(1, "Release failed: invalid bundle, checksum or deployment receipt\n")


if __name__ == "__main__":
    main()
