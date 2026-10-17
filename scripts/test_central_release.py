"""Offline release-contract checks; signature fixtures do not prove PGP validity."""
import base64
from collections import deque
from contextlib import contextmanager, redirect_stderr, redirect_stdout
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import urllib.parse
import urllib.request
import warnings
from zipfile import ZipFile

_SCRIPT = Path(__file__).with_name("central-release.py")
_SPEC = importlib.util.spec_from_file_location("siftell_central_release", _SCRIPT)
release = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(release)

GROUP = "io.github.sabino"
ARTIFACT = "siftell"
VERSION = "1.2.3"
TAG = "v1.2.3"
PREFIX = "io/github/sabino/siftell/1.2.3/"
DEPLOYMENT = "7f05d966-a40b-4dfa-9948-70ec01caebf6"
PURL = "pkg:maven/io.github.sabino/siftell@1.2.3"
SIGNATURE = b"-----BEGIN PGP SIGNATURE-----\nfixture-only\n-----END PGP SIGNATURE-----\n"
USERNAME = "test-release-account"
PASSWORD = "test-release-password"
TOKEN = base64.b64encode((USERNAME + ":" + PASSWORD).encode()).decode()


def jar_bytes(name):
    output = io.BytesIO()
    with ZipFile(output, "w") as archive:
        archive.writestr(name, "independent test fixture")
    return output.getvalue()


def make_release(root, version=VERSION, group=GROUP):
    directory = root.joinpath(*group.split("."), ARTIFACT, version)
    directory.mkdir(parents=True)
    stem = ARTIFACT + "-" + version
    for suffix, member in ((".jar", "siftell/api.clj"),
                           ("-sources.jar", "siftell/api.clj"),
                           ("-javadoc.jar", "README.md")):
        (directory / (stem + suffix)).write_bytes(jar_bytes(member))
    pom = ("<project xmlns='http://maven.apache.org/POM/4.0.0'>"
           "<modelVersion>4.0.0</modelVersion><groupId>" + group + "</groupId>"
           "<artifactId>siftell</artifactId><version>" + version + "</version></project>")
    (directory / (stem + ".pom")).write_text(pom)
    return directory


class FakeOpener:
    """An in-memory transport records requests and cannot access the network."""
    def __init__(self, *responses):
        self.responses = deque(responses)
        self.requests = []

    def open(self, request, timeout):
        self.requests.append((request, timeout))
        if not self.responses:
            raise AssertionError("Unexpected HTTP request")
        result = self.responses.popleft()
        if isinstance(result, Exception):
            raise result
        if not isinstance(result, bytes):
            result = json.dumps(result).encode()
        return io.BytesIO(result)


class FakeClock:
    def __init__(self):
        self.now = 0.0

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class ReleaseFixture(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="siftell-release-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.staging = self.root / "staging"
        self.directory = make_release(self.staging)
        self.bundle_path = self.root / "central-bundle.zip"

    def signed_bundle(self):
        def fake_sign(files, output):
            return "F" * 40, {item.name: SIGNATURE for item in files}
        with patch.object(release, "sign", side_effect=fake_sign) as signer:
            receipt = release.bundle(self.staging, self.bundle_path, TAG)
        self.assertEqual(signer.call_count, 1)
        return receipt

    def replace_entries(self, replacements=None, removed=()):
        with ZipFile(self.bundle_path) as archive:
            entries = {name: archive.read(name) for name in archive.namelist()}
        entries.update(replacements or {})
        for name in removed:
            del entries[name]
        with ZipFile(self.bundle_path, "w") as archive:
            for name, content in entries.items():
                archive.writestr(name, content)


class LayoutTests(ReleaseFixture):
    def test_exact_four_primary_artifacts_and_maven_layout(self):
        metadata, files = release.release_files(self.staging, TAG)
        self.assertEqual(metadata, {"group": GROUP, "artifact": ARTIFACT,
                                    "version": VERSION, "tag": TAG,
                                    "directory": PREFIX[:-1]})
        self.assertEqual({item.name for item in files}, {
            "siftell-1.2.3.jar", "siftell-1.2.3-sources.jar",
            "siftell-1.2.3-javadoc.jar", "siftell-1.2.3.pom"})
        self.assertEqual(len(files), 4)

    def test_tag_must_match_pom_version_exactly(self):
        for tag in ("1.2.3", "v1.2.4", "v1.2.3/anything", "v1.2.3\n"):
            with self.subTest(tag=tag), self.assertRaises(release.ReleaseError):
                release.release_files(self.staging, tag)

    def test_snapshots_and_non_semantic_versions_cannot_publish(self):
        for index, version in enumerate(("1.2.3-SNAPSHOT", "1.2.3-snapshot", "1.2", "latest")):
            with self.subTest(version=version):
                staging = self.root / ("version-" + str(index))
                make_release(staging, version=version)
                with self.assertRaises(release.ReleaseError):
                    release.release_files(staging, "v" + version)

    def test_each_primary_artifact_is_required_and_nonempty(self):
        for item in list(self.directory.iterdir()):
            original = item.read_bytes()
            with self.subTest(file=item.name, kind="absent"):
                item.unlink()
                with self.assertRaises(release.ReleaseError):
                    release.release_files(self.staging, TAG)
                item.write_bytes(original)
            with self.subTest(file=item.name, kind="empty"):
                item.write_bytes(b"")
                with self.assertRaises((release.ReleaseError, release.ET.ParseError)):
                    release.release_files(self.staging, TAG)
                item.write_bytes(original)

    def test_extra_files_second_pom_and_symlink_are_rejected(self):
        extra = self.directory / "private-key.asc"
        extra.write_text("must not be bundled")
        with self.assertRaises(release.ReleaseError):
            release.release_files(self.staging, TAG)
        extra.unlink()
        second = self.directory / "other.pom"
        second.write_text("other")
        with self.assertRaises(release.ReleaseError):
            release.release_files(self.staging, TAG)
        second.unlink()
        source = self.directory / "siftell-1.2.3.jar"
        content = source.read_bytes()
        outside = self.root / "outside.jar"
        outside.write_bytes(content)
        source.unlink()
        source.symlink_to(outside)
        with self.assertRaises(release.ReleaseError):
            release.release_files(self.staging, TAG)

    def test_incorrect_maven_directory_is_rejected(self):
        replacement = self.directory.with_name("wrong-version")
        self.directory.rename(replacement)
        with self.assertRaises(release.ReleaseError):
            release.release_files(self.staging, TAG)


class BundleTests(ReleaseFixture):
    def test_signed_bundle_exact_contents_checksums_and_metadata(self):
        receipt = self.signed_bundle()
        self.assertTrue(receipt["signed"])
        self.assertEqual(receipt["fingerprint"], "F" * 40)
        expected_primary = {PREFIX + "siftell-1.2.3" + suffix
                            for suffix in (".jar", "-sources.jar", "-javadoc.jar", ".pom")}
        expected_names = {name + suffix for name in expected_primary
                          for suffix in ("", ".asc", ".md5", ".sha1", ".sha256")}
        with ZipFile(self.bundle_path) as archive:
            self.assertEqual(set(archive.namelist()), expected_names)
            self.assertEqual(len(archive.namelist()), 20)
            self.assertEqual(archive.namelist(), sorted(expected_names))
            for name in expected_primary:
                content = archive.read(name)
                self.assertEqual(archive.read(name + ".asc"), SIGNATURE)
                for algorithm in ("md5", "sha1", "sha256"):
                    self.assertEqual(archive.read(name + "." + algorithm).decode(),
                                     hashlib.new(algorithm, content).hexdigest())
        metadata = release.verify_bundle(self.bundle_path, TAG)
        self.assertEqual(metadata, {"group": GROUP, "artifact": ARTIFACT,
                                    "version": VERSION, "tag": TAG})

    def test_unsigned_assembly_never_signs_and_cannot_be_uploaded(self):
        with patch.object(release, "sign", side_effect=AssertionError("Signing forbidden")):
            receipt = release.bundle(self.staging, self.bundle_path, TAG, unsigned=True)
        self.assertFalse(receipt["signed"])
        with ZipFile(self.bundle_path) as archive:
            self.assertEqual(len(archive.namelist()), 16)
            self.assertFalse(any(name.endswith(".asc") for name in archive.namelist()))
        with self.assertRaises(release.ReleaseError):
            release.verify_bundle(self.bundle_path, TAG)
        with patch.object(release, "Publisher", side_effect=AssertionError("Network forbidden")):
            with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as failure:
                release.main(["publish", "--bundle", str(self.bundle_path), "--tag", TAG])
        self.assertNotEqual(failure.exception.code, 0)

    def test_tampered_primary_content_is_rejected(self):
        self.signed_bundle()
        self.replace_entries({PREFIX + "siftell-1.2.3.jar": b"modified"})
        with self.assertRaisesRegex(release.ReleaseError, "checksum"):
            release.verify_bundle(self.bundle_path, TAG)

    def test_tampered_checksum_missing_signature_and_extra_file_are_rejected(self):
        cases = [({PREFIX + "siftell-1.2.3.jar.sha1": b"0" * 40}, ()),
                 ({}, (PREFIX + "siftell-1.2.3.jar.asc",)),
                 ({"../../private-key": b"unexpected"}, ())]
        for replacements, removed in cases:
            with self.subTest(replacements=replacements, removed=removed):
                self.signed_bundle()
                self.replace_entries(replacements, removed)
                with self.assertRaises(release.ReleaseError):
                    release.verify_bundle(self.bundle_path, TAG)

    def test_duplicate_entries_and_non_signature_armor_are_rejected(self):
        self.signed_bundle()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with ZipFile(self.bundle_path, "a") as archive:
                archive.writestr(PREFIX + "siftell-1.2.3.jar", b"duplicate")
        with self.assertRaisesRegex(release.ReleaseError, "unique"):
            release.verify_bundle(self.bundle_path, TAG)
        self.signed_bundle()
        self.replace_entries({PREFIX + "siftell-1.2.3.jar.asc": b"not a signature"})
        with self.assertRaisesRegex(release.ReleaseError, "signature"):
            release.verify_bundle(self.bundle_path, TAG)

    def test_identical_artifacts_and_signatures_produce_identical_zip(self):
        self.signed_bundle()
        original = self.bundle_path.read_bytes()
        self.signed_bundle()
        self.assertEqual(self.bundle_path.read_bytes(), original)


class PublisherTests(unittest.TestCase):
    def test_upload_uses_official_host_auth_and_exact_multipart_part(self):
        opener = FakeOpener(DEPLOYMENT.encode())
        publisher = release.Publisher(USERNAME, PASSWORD, opener=opener)
        with tempfile.TemporaryDirectory() as temporary:
            bundle = Path(temporary) / "bundle.zip"
            payload = b"zip fixture\x00\xff"
            bundle.write_bytes(payload)
            result = publisher.upload(bundle, "siftell 1.2.3 / release")
        self.assertEqual(result, DEPLOYMENT)
        self.assertEqual(len(opener.requests), 1)
        request, timeout = opener.requests[0]
        parsed = urllib.parse.urlsplit(request.full_url)
        self.assertEqual((parsed.scheme, parsed.netloc, parsed.path),
                         ("https", "central.sonatype.com", "/api/v1/publisher/upload"))
        self.assertEqual(urllib.parse.parse_qs(parsed.query),
                         {"name": ["siftell 1.2.3 / release"], "publishingType": ["AUTOMATIC"]})
        self.assertEqual(request.method, "POST")
        self.assertEqual(request.get_header("Authorization"), "Bearer " + TOKEN)
        self.assertEqual(timeout, 30)
        content_type = request.get_header("Content-type")
        self.assertTrue(content_type.startswith("multipart/form-data; boundary="))
        boundary = content_type.split("boundary=", 1)[1].encode()
        self.assertEqual(request.data.count(b'Content-Disposition: form-data; name="bundle";'), 1)
        self.assertIn(b'filename="central-bundle.zip"\r\nContent-Type: application/octet-stream\r\n\r\n' + payload,
                      request.data)
        self.assertTrue(request.data.startswith(b"--" + boundary + b"\r\n"))
        self.assertTrue(request.data.endswith(b"\r\n--" + boundary + b"--\r\n"))
        self.assertNotIn(PASSWORD.encode(), request.data)

    def test_default_transport_refuses_redirects(self):
        publisher = release.Publisher(USERNAME, PASSWORD)
        handlers = [handler for handler in publisher._opener.handlers
                    if isinstance(handler, urllib.request.HTTPRedirectHandler)]
        self.assertEqual(len(handlers), 1)
        self.assertIsInstance(handlers[0], release.NoRedirect)
        request = urllib.request.Request("https://central.sonatype.com/api/v1/publisher/upload")
        for target in ("https://attacker.example/capture", "http://central.sonatype.com/"):
            self.assertIsNone(handlers[0].redirect_request(request, None, 302, "Found", {}, target))

    def test_only_published_is_success_and_status_polling_never_uploads(self):
        opener = FakeOpener(*({"deploymentState": state, "deploymentId": DEPLOYMENT}
                              for state in ("PENDING", "VALIDATING", "VALIDATED", "PUBLISHING", "PUBLISHED")))
        publisher = release.Publisher(USERNAME, PASSWORD, opener=opener)
        clock = FakeClock()
        with patch.object(release.time, "monotonic", side_effect=clock.monotonic), \
             patch.object(release.time, "sleep", side_effect=clock.sleep), \
             redirect_stdout(io.StringIO()):
            result = publisher.wait(DEPLOYMENT, timeout=60, interval=10)
        self.assertEqual(result["deploymentState"], "PUBLISHED")
        self.assertEqual(len(opener.requests), 5)
        self.assertEqual(clock.now, 40)
        for request, _ in opener.requests:
            self.assertEqual(request.full_url,
                             "https://central.sonatype.com/api/v1/publisher/status?id=" + DEPLOYMENT)
            self.assertEqual(request.method, "POST")
            self.assertEqual(request.data, b"")

    def test_failed_validation_redacts_all_credentials(self):
        errors = {"reason": " / ".join((USERNAME, PASSWORD, TOKEN))}
        publisher = release.Publisher(USERNAME, PASSWORD,
                                      opener=FakeOpener({"deploymentState": "FAILED", "errors": errors}))
        output = io.StringIO()
        with redirect_stdout(output), self.assertRaises(release.ReleaseError) as failure:
            publisher.wait(DEPLOYMENT)
        combined = output.getvalue() + str(failure.exception)
        for secret in (USERNAME, PASSWORD, TOKEN):
            self.assertNotIn(secret, combined)
        self.assertIn("validation failed", combined)
        self.assertIn("[redacted]", combined)

    def test_unknown_state_is_rejected_without_logging_server_payload(self):
        publisher = release.Publisher(USERNAME, PASSWORD,
                                      opener=FakeOpener({"deploymentState": PASSWORD}))
        output = io.StringIO()
        with redirect_stdout(output), self.assertRaises(release.ReleaseError):
            publisher.wait(DEPLOYMENT)
        self.assertNotIn(PASSWORD, output.getvalue())

    def test_non_object_and_non_json_statuses_fail_as_release_errors(self):
        for response in (b"not json", b"[]", b"null", b'"text"', b'{"deploymentState": []}', b'{"deploymentState": null}'):
            publisher = release.Publisher(USERNAME, PASSWORD, opener=FakeOpener(response))
            with self.subTest(response=response), redirect_stdout(io.StringIO()), \
                 self.assertRaises(release.ReleaseError):
                publisher.wait(DEPLOYMENT)

    def test_timeout_preserves_deployment_id_and_never_reuploads(self):
        opener = FakeOpener({"deploymentState": "PENDING"}, {"deploymentState": "PUBLISHING"})
        publisher = release.Publisher(USERNAME, PASSWORD, opener=opener)
        clock = FakeClock()
        with patch.object(release.time, "monotonic", side_effect=clock.monotonic), \
             patch.object(release.time, "sleep", side_effect=clock.sleep), \
             redirect_stdout(io.StringIO()), self.assertRaises(release.ReleaseError) as failure:
            publisher.wait(DEPLOYMENT, timeout=20, interval=10)
        self.assertIn(DEPLOYMENT, str(failure.exception))
        self.assertIn("resume", str(failure.exception))
        self.assertEqual(len(opener.requests), 2)
        self.assertTrue(all("/status?" in req.full_url for req, _ in opener.requests))

    def test_bad_credentials_id_and_poll_arguments_fail_before_network(self):
        for credentials in (("", PASSWORD), (USERNAME, ""), (None, PASSWORD)):
            with self.subTest(credentials=credentials), self.assertRaises(release.ReleaseError):
                release.Publisher(*credentials)
        opener = FakeOpener()
        publisher = release.Publisher(USERNAME, PASSWORD, opener=opener)
        for kwargs in ({"deployment_id": "not-an-id"},
                       {"deployment_id": DEPLOYMENT, "timeout": 0},
                       {"deployment_id": DEPLOYMENT, "interval": 0}):
            with self.subTest(kwargs=kwargs), self.assertRaises(release.ReleaseError):
                publisher.wait(**kwargs)
        self.assertEqual(opener.requests, [])

    def test_http_and_connection_errors_do_not_expose_credentials_or_bodies(self):
        errors = [urllib.error.HTTPError("https://central.sonatype.com/", 403, PASSWORD,
                                         {}, io.BytesIO((USERNAME + TOKEN).encode())),
                  urllib.error.URLError(USERNAME + PASSWORD + TOKEN)]
        for error in errors:
            publisher = release.Publisher(USERNAME, PASSWORD, opener=FakeOpener(error))
            with self.subTest(error_type=type(error).__name__), self.assertRaises(release.ReleaseError) as failure:
                publisher.wait(DEPLOYMENT)
            for secret in (USERNAME, PASSWORD, TOKEN):
                self.assertNotIn(secret, str(failure.exception))

    def test_invalid_upload_id_fails_without_retry(self):
        opener = FakeOpener(b"not an id")
        publisher = release.Publisher(USERNAME, PASSWORD, opener=opener)
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "bundle.zip"
            path.write_bytes(b"fixture")
            with self.assertRaises(release.ReleaseError):
                publisher.upload(path, "release")
        self.assertEqual(len(opener.requests), 1)


class PublicationIdentityTests(unittest.TestCase):
    def setUp(self):
        self.metadata = {"group": GROUP, "artifact": ARTIFACT,
                         "version": VERSION, "tag": TAG}
        self.result = {"deploymentState": "PUBLISHED", "deploymentId": DEPLOYMENT,
                       "purls": [PURL]}

    def test_published_expected_coordinates_and_deployment_are_accepted(self):
        release.verify_publication(self.result, self.metadata, DEPLOYMENT)

    def test_wrong_project_group_or_version_cannot_prove_publication(self):
        for purl in ("pkg:maven/org.example/unrelated@1.2.3",
                     "pkg:maven/io.github.sabino/another-library@1.2.3",
                     "pkg:maven/io.github.sabino/siftell@1.2.4",
                     "pkg:maven/io.github.other/siftell@1.2.3",
                     PURL + "-SNAPSHOT", PURL + "?classifier=sources"):
            result = dict(self.result, purls=[purl])
            with self.subTest(purl=purl), self.assertRaises(release.ReleaseError):
                release.verify_publication(result, self.metadata, DEPLOYMENT)

    def test_missing_empty_or_malformed_package_url_collection_is_rejected(self):
        results = [{key: value for key, value in self.result.items() if key != "purls"}]
        results += [dict(self.result, purls=value)
                    for value in (None, [], PURL, {"purl": PURL}, 12,
                                  [None], [12], [PURL, None], [PURL, {"other": "value"}])]
        for result in results:
            with self.subTest(purls=result.get("purls", "absent")), self.assertRaises(release.ReleaseError):
                release.verify_publication(result, self.metadata, DEPLOYMENT)

    def test_different_missing_or_malformed_deployment_id_is_rejected(self):
        results = [{key: value for key, value in self.result.items() if key != "deploymentId"}]
        results += [dict(self.result, deploymentId=value)
                    for value in (None, "not-a-uuid", 12, [], {},
                                  "7f05d966-a40b-4dfa-9948-70ec01caebf7")]
        for result in results:
            with self.subTest(deployment_id=result.get("deploymentId", "absent")), \
                 self.assertRaises(release.ReleaseError):
                release.verify_publication(result, self.metadata, DEPLOYMENT)

    def test_only_published_state_proves_publication(self):
        for state in (None, "PENDING", "VALIDATING", "VALIDATED", "PUBLISHING", "FAILED", "unknown"):
            with self.subTest(state=state), self.assertRaises(release.ReleaseError):
                release.verify_publication(dict(self.result, deploymentState=state),
                                           self.metadata, DEPLOYMENT)


class ResumeTests(ReleaseFixture):
    @contextmanager
    def working_directory(self):
        previous = Path.cwd()
        os.chdir(self.root)
        try:
            yield
        finally:
            os.chdir(previous)

    def test_saved_same_release_resumes_without_reupload(self):
        self.signed_bundle()
        receipt_path = self.root / "target" / "central-deployment.json"
        receipt_path.parent.mkdir()
        receipt_path.write_text(json.dumps({"group": GROUP, "artifact": ARTIFACT,
                                            "version": VERSION, "tag": TAG,
                                            "deployment_id": DEPLOYMENT}))
        opener = FakeOpener({"deploymentState": "PUBLISHED", "deploymentId": DEPLOYMENT,
                             "purls": [PURL]})
        publisher = release.Publisher(USERNAME, PASSWORD, opener=opener)
        with self.working_directory(), patch.object(release, "Publisher", return_value=publisher), \
             redirect_stdout(io.StringIO()):
            release.main(["publish", "--bundle", str(self.bundle_path), "--tag", TAG])
        self.assertEqual(len(opener.requests), 1)
        self.assertIn("/api/v1/publisher/status?id=" + DEPLOYMENT,
                      opener.requests[0][0].full_url)
        self.assertEqual(json.loads(receipt_path.read_text())["state"], "PUBLISHED")

    def test_explicit_deployment_id_never_uploads(self):
        self.signed_bundle()
        opener = FakeOpener({"deploymentState": "PUBLISHED", "deploymentId": DEPLOYMENT,
                             "purls": [PURL]})
        publisher = release.Publisher(USERNAME, PASSWORD, opener=opener)
        with self.working_directory(), patch.object(release, "Publisher", return_value=publisher), \
             redirect_stdout(io.StringIO()):
            release.main(["publish", "--bundle", str(self.bundle_path), "--tag", TAG,
                          "--deployment-id", DEPLOYMENT])
        self.assertEqual(len(opener.requests), 1)
        self.assertIn("/status?id=" + DEPLOYMENT, opener.requests[0][0].full_url)

    def test_resuming_unrelated_deployment_cannot_mark_current_release_published(self):
        self.signed_bundle()
        opener = FakeOpener({"deploymentState": "PUBLISHED", "deploymentId": DEPLOYMENT,
                             "purls": ["pkg:maven/org.example/unrelated@9.9.9"]})
        publisher = release.Publisher(USERNAME, PASSWORD, opener=opener)
        output = io.StringIO()
        with self.working_directory(), patch.object(release, "Publisher", return_value=publisher), \
             redirect_stdout(output), redirect_stderr(io.StringIO()), \
             self.assertRaises(SystemExit) as failure:
            release.main(["publish", "--bundle", str(self.bundle_path), "--tag", TAG,
                          "--deployment-id", DEPLOYMENT])
        self.assertNotEqual(failure.exception.code, 0)
        self.assertNotIn("Published " + GROUP + ":" + ARTIFACT, output.getvalue())
        receipt = json.loads((self.root / "target" / "central-deployment.json").read_text())
        self.assertNotEqual(receipt.get("state"), "PUBLISHED")
        self.assertEqual(receipt["deployment_id"], DEPLOYMENT)
        self.assertEqual(len(opener.requests), 1)
        self.assertIn("/status?id=" + DEPLOYMENT, opener.requests[0][0].full_url)

    def test_timeout_receipt_keeps_the_original_deployment_id(self):
        self.signed_bundle()
        opener = FakeOpener(DEPLOYMENT.encode(), {"deploymentState": "PENDING"})
        publisher = release.Publisher(USERNAME, PASSWORD, opener=opener)
        clock = FakeClock()
        with self.working_directory(), patch.object(release, "Publisher", return_value=publisher), \
             patch.object(release.time, "monotonic", side_effect=clock.monotonic), \
             patch.object(release.time, "sleep", side_effect=clock.sleep), \
             redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()), \
             self.assertRaises(SystemExit) as failure:
            release.main(["publish", "--bundle", str(self.bundle_path), "--tag", TAG,
                          "--timeout", "1"])
        self.assertNotEqual(failure.exception.code, 0)
        receipt = json.loads((self.root / "target" / "central-deployment.json").read_text())
        self.assertEqual(receipt["deployment_id"], DEPLOYMENT)
        self.assertNotEqual(receipt.get("state"), "PUBLISHED")
        self.assertEqual(len(opener.requests), 2)


class SecretEncodingTests(unittest.TestCase):
    def test_nested_errors_redact_password_before_json_escaping(self):
        password = 'quoted"slash\\newline\n\t:é'
        token = base64.b64encode((USERNAME + ":" + password).encode()).decode()
        response = {"deploymentState": "FAILED",
                    "errors": {password: [{"reason": password, "token": token,
                                             "account": USERNAME}]}}
        publisher = release.Publisher(USERNAME, password, opener=FakeOpener(response))
        output = io.StringIO()
        with redirect_stdout(output), self.assertRaises(release.ReleaseError) as failure:
            publisher.wait(DEPLOYMENT)
        combined = output.getvalue() + str(failure.exception)
        for secret in (USERNAME, password, token, json.dumps(password)[1:-1]):
            self.assertNotIn(secret, combined)
        self.assertIn("[redacted]", combined)


if __name__ == "__main__":
    unittest.main()
