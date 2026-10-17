#!/usr/bin/env python3
"""Verify Maven metadata, artifact contents and an isolated packaged consumer."""
from pathlib import Path
from tempfile import TemporaryDirectory
import json
import subprocess
import xml.etree.ElementTree as ET
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
poms = list((ROOT / "target/classes/META-INF/maven").glob("*/*/pom.xml"))
if len(poms) != 1:
    raise SystemExit("Build the library before checking its package")
pom_bytes = poms[0].read_bytes()
pom = ET.fromstring(pom_bytes)


def field(path):
    value = pom.findtext(path, namespaces=NS)
    assert value and value.strip(), f"Missing Maven metadata: {path}"
    return value.strip()


group = field("m:groupId")
artifact = field("m:artifactId")
version = field("m:version")
assert len(pom.findall("m:name", NS)) == 1, "POM must have one project name"
assert field("m:name") == "Siftell"
base = f"{artifact}-{version}"
jar = ROOT / "target" / f"{base}.jar"
pom_path = f"META-INF/maven/{group}/{artifact}/pom.xml"
source_names = {p.relative_to(ROOT / "src").as_posix()
                for p in (ROOT / "src").rglob("*.clj")}
assert source_names, "No Clojure source found"
for path in ("m:name", "m:description", "m:url", "m:licenses/m:license/m:name",
             "m:licenses/m:license/m:url", "m:scm/m:url", "m:scm/m:connection",
             "m:scm/m:developerConnection", "m:scm/m:tag",
             "m:developers/m:developer/m:id", "m:developers/m:developer/m:name",
             "m:developers/m:developer/m:email", "m:developers/m:developer/m:url"):
    field(path)
dependencies = {(d.findtext("m:groupId", namespaces=NS),
                 d.findtext("m:artifactId", namespaces=NS))
                for d in pom.findall("m:dependencies/m:dependency", NS)}
assert dependencies == {("org.clojure", "clojure"), ("metosin", "malli")}
assert any(r.findtext("m:url", namespaces=NS) == "https://repo.clojars.org/"
           for r in pom.findall("m:repositories/m:repository", NS))
with ZipFile(jar) as archive:
    names = set(archive.namelist())
    assert source_names <= names
    assert "LICENSE" in names
    assert not any(n.startswith(("test/", "examples/", "doc/")) or n.endswith(".class")
                   for n in names)
    assert archive.read(pom_path) == pom_bytes

sources = ROOT / "target" / f"{base}-sources.jar"
documentation = ROOT / "target" / f"{base}-javadoc.jar"
if sources.exists() or documentation.exists():
    assert sources.is_file() and documentation.is_file(), "Incomplete release artifacts"
    with ZipFile(sources) as archive:
        assert source_names <= set(archive.namelist())
        assert "LICENSE" in archive.namelist()
        for name in source_names:
            assert archive.read(name) == (ROOT / "src" / name).read_bytes()
    with ZipFile(documentation) as archive:
        names = set(archive.namelist())
        assert {"index.html", "README.md", "doc/guide.md", "doc/reference.md", "LICENSE"} <= names
        assert any(name.startswith("examples/") and name.endswith(".clj") for name in names)
        index = archive.read("index.html").decode()
        assert "Siftell Clojure API" in index and "read-definition" in index
    published_pom = ROOT / "target" / f"{base}.pom"
    assert published_pom.read_bytes() == pom_bytes
    stage = ROOT / "target/central" / Path(*group.split(".")) / artifact / version
    expected = {f"{base}{suffix}" for suffix in (".jar", "-sources.jar", "-javadoc.jar", ".pom")}
    # Signing may have already added checksums and detached signatures.
    assert expected <= {p.name for p in stage.iterdir() if p.is_file()}
    for name in expected:
        assert (stage / name).read_bytes() == (ROOT / "target" / name).read_bytes()

with TemporaryDirectory(prefix="siftell-consumer-") as directory:
    coordinate = f"{group}/{artifact}"
    deps = "{:deps {" + coordinate + " {:local/root " + json.dumps(str(jar)) + "}}}"
    (Path(directory) / "deps.edn").write_text(deps + "\n")
    expression = """
    (require '[siftell.api :as s])
    (let [plan (s/compile
                {:schema [:map [:name :string]]
                 :fragments {:greeting {:template "Hello {{name}}"}}
                 :rules [{:id :greet :emit [:greeting]}]})]
      (assert (= "Hello Avery" (get-in (s/render plan {:name "Avery"})
                                     [:fragments 0 :text])))
      (println "Packaged library consumer passed"))
    """
    subprocess.run(["clojure", "-Srepro", "-M", "-e", expression],
                   cwd=directory, check=True)
print(f"Verified {group}:{artifact}:{version} package metadata and contents")
