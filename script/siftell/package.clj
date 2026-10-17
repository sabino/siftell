(ns siftell.package
  "Verify Maven artifacts and load the packaged library from an isolated consumer."
  (:require [babashka.http-client]
            [clojure.edn]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.data.xml :as xml]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import [java.nio.file Files]
           [java.util.zip ZipFile]))

(defn- ensure! [condition message data]
  (when-not condition
    (throw (ex-info message (assoc data :siftell.package/error :invalid-package)))))

(defn- read-bytes [file] (Files/readAllBytes (fs/path file)))

(defn- same-bytes? [a b] (= (seq a) (seq b)))

(defn- archive [file]
  (ensure! (fs/regular-file? file) "A required JAR is missing" {:file (str file)})
  (with-open [zip (ZipFile. (str file))]
    (into {}
          (for [entry (enumeration-seq (.entries zip))
                :when (not (.isDirectory entry))]
            [(.getName entry)
             (with-open [input (.getInputStream zip entry)]
               (.readAllBytes input))]))))

(defn- children [node tag]
  (filter #(and (map? %) (= (name tag) (some-> (:tag %) name))) (:content node)))

(defn- nodes-at [node path]
  (reduce (fn [parents tag] (mapcat #(children % tag) parents)) [node] path))

(defn- text [node] (str/trim (apply str (filter string? (:content node)))))

(defn- field [pom path]
  (let [matches (nodes-at pom path)
        value (some-> (first matches) text)]
    (ensure! (and (= 1 (count matches)) (not (str/blank? value)))
             "Required Maven metadata is missing or duplicated" {:path path})
    value))

(defn- coordinate [pom]
  (let [group (field pom [:groupId])
        artifact (field pom [:artifactId])
        version (field pom [:version])]
    (ensure! (= "Siftell" (field pom [:name])) "The POM project name must be Siftell" {})
    (doseq [path [[:description] [:url] [:licenses :license :name]
                  [:licenses :license :url] [:scm :url] [:scm :connection]
                  [:scm :developerConnection] [:scm :tag]
                  [:developers :developer :id] [:developers :developer :name]
                  [:developers :developer :email] [:developers :developer :url]]]
      (field pom path))
    (let [dependencies (set (for [dependency (nodes-at pom [:dependencies :dependency])]
                              [(field dependency [:groupId]) (field dependency [:artifactId])]))
          repositories (set (map #(field % [:url]) (nodes-at pom [:repositories :repository])))]
      (ensure! (= #{["org.clojure" "clojure"] ["metosin" "malli"]} dependencies)
               "The runtime artifact declares unexpected dependencies" {:dependencies dependencies})
      (ensure! (empty? repositories)
               "Published POMs must leave repository selection to consumers" {}))
    {:group group :artifact artifact :version version :lib (symbol group artifact)}))

(defn- source-files [root]
  (let [source (fs/path root "src")
        files (into {} (for [file (fs/glob source "**/*.clj")]
                         [(str/replace (str (fs/relativize source file)) "\\" "/")
                          (read-bytes file)]))]
    (ensure! (seq files) "No Clojure source was found" {:root (str root)})
    files))

(defn- matches-files! [contents expected label]
  (ensure! (set/subset? (set (keys expected)) (set (keys contents)))
           (str label " is missing expected files")
           {:missing (set/difference (set (keys expected)) (set (keys contents)))})
  (doseq [[name expected-bytes] expected]
    (ensure! (same-bytes? expected-bytes (get contents name))
             (str label " contains outdated content") {:entry name})))

(defn- check-library! [root jar pom-bytes {:keys [group artifact]}]
  (let [contents (archive jar)
        names (keys contents)
        expected (assoc (source-files root) "LICENSE" (read-bytes (fs/path root "LICENSE"))
                        (str "META-INF/maven/" group "/" artifact "/pom.xml") pom-bytes)]
    (ensure! (not-any? #(or (some (fn [prefix] (str/starts-with? % prefix))
                                      ["test/" "examples/" "doc/"])
                            (str/ends-with? % ".class")) names)
             "The runtime JAR contains development files or compiled classes" {})
    (matches-files! contents expected "The runtime JAR")))

(defn- check-documentation! [root file]
  (let [contents (archive file)
        files ["README.md" "doc/guide.md" "doc/reference.md" "LICENSE"]
        expected (into {} (map (fn [name] [name (read-bytes (fs/path root name))])) files)
        index-bytes (get contents "index.html")]
    (matches-files! contents expected "The documentation JAR")
    (ensure! (some #(and (str/starts-with? % "examples/") (str/ends-with? % ".clj"))
                   (keys contents)) "The documentation JAR contains no executable examples" {})
    (ensure! (some? index-bytes) "The documentation JAR is missing its API index" {})
    (let [index (String. ^bytes index-bytes "UTF-8")]
      (ensure! (and (str/includes? index "Siftell Clojure API")
                    (str/includes? index "read-definition"))
               "The documentation index is missing public API documentation" {})
      (doseq [[_ link] (re-seq #"href=\"([^\"]+)\"" index)
              :when (not (re-find #"^(?:[A-Za-z][A-Za-z0-9+.-]*:|#)" link))
              :let [target (first (str/split link #"[?#]"))]]
        (ensure! (contains? contents target)
                 "The documentation index has a broken local link" {:link link})))))

(defn- check-classifiers! [root {:keys [group artifact version] :as coordinate} pom-bytes]
  (let [target (fs/path root "target")
        base (str artifact "-" version)
        sources (fs/path target (str base "-sources.jar"))
        documentation (fs/path target (str base "-javadoc.jar"))]
    (when (or (fs/exists? sources) (fs/exists? documentation))
      (ensure! (and (fs/regular-file? sources) (fs/regular-file? documentation))
               "Release classifier artifacts are incomplete" {})
      (matches-files! (archive sources)
                      (assoc (source-files root) "LICENSE" (read-bytes (fs/path root "LICENSE")))
                      "The sources JAR")
      (check-documentation! root documentation)
      (ensure! (same-bytes? pom-bytes (read-bytes (fs/path target (str base ".pom"))))
               "The standalone POM differs from the packaged metadata" {})
      (let [stage (apply fs/path root "target" "central"
                         (concat (str/split group #"\.") [artifact version]))
            names (map #(str base %) [".jar" "-sources.jar" "-javadoc.jar" ".pom"])]
        ;; Detached signatures and checksums may also exist after release signing.
        (doseq [name names]
          (let [file (fs/path stage name)]
            (ensure! (fs/regular-file? file) "The staged release is incomplete" {:file (str file)})
            (ensure! (same-bytes? (read-bytes file) (read-bytes (fs/path target name)))
                     "A staged artifact differs from its build output" {:file (str file)})))))))

(defn- check-consumer! [jar lib clojure-command]
  (let [directory (fs/create-temp-dir {:prefix "siftell-consumer-"})
        expression
        "(require '[siftell.api :as s])
         (let [plan (s/compile
                     {:schema [:map [:name :string]]
                      :fragments {:greeting {:template \"Hello {{name}}\"}}
                      :rules [{:id :greet :emit [:greeting]}]})]
           (assert (= \"Hello Avery\"
                      (get-in (s/render plan {:name \"Avery\"}) [:fragments 0 :text])))
           (println \"Packaged library consumer passed\"))"]
    (try
      (spit (str (fs/path directory "deps.edn"))
            (str (pr-str {:deps {lib {:local/root (str jar)}}}) "\n"))
      (process/check
       @(process/process [clojure-command "-Srepro" "-M" "-e" expression]
                         {:dir (str directory) :inherit true}))
      (finally (fs/delete-tree directory)))))

(defn check!
  "Verify built Maven artifacts and exercise only the JAR and declared dependencies.
  Options: :root (repository directory, default current directory), and
  :clojure-command (Clojure CLI executable, default clojure)."
  ([] (check! {}))
  ([{:keys [root clojure-command] :or {root "." clojure-command "clojure"}}]
   (let [root (fs/canonicalize root)
         poms (fs/glob (fs/path root "target/classes/META-INF/maven") "*/*/pom.xml")]
     (ensure! (= 1 (count poms)) "Build the library before checking its package" {})
     (let [pom-bytes (read-bytes (first poms))
           pom (xml/parse-str (String. ^bytes pom-bytes "UTF-8"))
           {:keys [group artifact version lib] :as coordinate} (coordinate pom)
           jar (fs/path root "target" (str artifact "-" version ".jar"))]
       (check-library! root jar pom-bytes coordinate)
       (check-classifiers! root coordinate pom-bytes)
       (check-consumer! jar lib clojure-command)
       (println "Verified" (str group ":" artifact ":" version) "package metadata and contents")
       (assoc coordinate :jar-file (str jar))))))

(defn verify-central!
  "Verify the four immutable Central artifacts match this release build.
  Retry briefly when publication visibility lags deployment."
  []
  (let [{:keys [lib version]} (clojure.edn/read-string (slurp "build.edn"))
        base (str (name lib) "-" version)
        repository (str "https://repo.maven.apache.org/maven2/"
                        (str/replace (namespace lib) "." "/") "/" (name lib) "/" version "/")
        files (mapv #(str base %) [".jar" "-sources.jar" "-javadoc.jar" ".pom"])
        expected (into {} (for [file files] [file (read-bytes (fs/path "target" file))]))]
    (loop [attempt 1]
      (let [available
            (for [file files
                  :let [response (try
                                   (babashka.http-client/get (str repository file)
                                                            {:as :bytes :throw false :timeout 10000})
                                   (catch Exception _ nil))]]
              (if (= 200 (:status response))
                (do (ensure! (same-bytes? (get expected file) (:body response))
                             "A published Central artifact differs from this build" {:file file})
                    true)
                false))]
        (if (every? true? available)
          (println "Verified all four published Maven Central artifacts match the build")
          (do
            (ensure! (< attempt 20) "Maven Central artifacts are not yet available"
                     {:coordinate (str lib) :version version})
            (println "Waiting for Maven Central visibility; attempt" attempt "of 20")
            (Thread/sleep 15000)
            (recur (inc attempt))))))))
