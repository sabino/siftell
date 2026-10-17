#!/usr/bin/env python3
"""Exercise the packaged library from an isolated consumer directory."""
from pathlib import Path
from tempfile import TemporaryDirectory
import subprocess
from zipfile import ZipFile

root = Path(__file__).resolve().parents[1]
jars = sorted((root / "target").glob("siftell-*.jar"))
if len(jars) != 1:
    raise SystemExit("Build exactly one library JAR before checking the package")
jar = jars[0]
with ZipFile(jar) as archive:
    names = archive.namelist()
    assert "siftell/api.clj" in names
    assert "LICENSE" in names
    assert not any(n.startswith(("test/", "examples/")) or n.endswith(".class") for n in names)
    pom = archive.read("META-INF/maven/siftell/siftell/pom.xml").decode()
    assert "clojure" in pom and "malli" in pom

with TemporaryDirectory(prefix="siftell-consumer-") as directory:
    deps = '{:deps {siftell/siftell {:local/root ' + '"' + str(jar) + '"}}}'
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
