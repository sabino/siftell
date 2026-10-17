(ns build
  "Build tasks for the source library and complete Maven release artifacts."
  (:require [clojure.data.xml :as xml]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(defn clean "Remove build output." [_] (b/delete {:path "target"}))

(defn- config [] (edn/read-string (slurp "build.edn")))

(defn- paths [{:keys [lib version]}]
  (let [base (str "target/" (name lib) "-" version)]
    {:classes "target/classes"
     :jar-file (str base ".jar")
     :sources-file (str base "-sources.jar")
     :javadoc-file (str base "-javadoc.jar")
     :pom-file (str base ".pom")
     :stage-dir (str "target/central/" (str/replace (namespace lib) "." "/")
                     "/" (name lib) "/" version)}))

(defn- pom-data [{:keys [description url scm developers]}]
  [[:description description]
   [:url url]
   [:licenses [:license [:name "MIT License"]
               [:url "https://opensource.org/license/mit"]
               [:distribution "repo"]]]
   [:scm [:url (:url scm)] [:connection (:connection scm)]
    [:developerConnection (:developer-connection scm)] [:tag (:tag scm)]]
   (into [:developers]
         (map (fn [{:keys [id name email url]}]
                [:developer [:id id] [:name name] [:email email] [:url url]]))
         developers)])

(defn jar "Package source, Maven dependency metadata and license without AOT compilation." [_]
  (let [{:keys [lib version] :as project} (config)
        {:keys [classes jar-file]} (paths project)
        basis (b/create-basis {:project "deps.edn" :user nil :aliases []})]
    (b/delete {:path classes})
    (b/copy-dir {:src-dirs ["src"] :target-dir classes})
    (b/write-pom {:class-dir classes :lib lib :version version :basis basis
                  :src-pom :none :src-dirs ["src"] :pom-data (pom-data project)})
    ;; tools.build supplies its artifact-name default; replace that single node.
    (let [pom-file (str classes "/META-INF/maven/" (namespace lib) "/" (name lib) "/pom.xml")
          pom (xml/parse-str (slurp pom-file) :skip-whitespace true)]
      (spit pom-file
            (xml/indent-str
             (update pom :content
                     (fn [nodes]
                       (mapv #(if (and (map? %) (= "name" (name (:tag %))))
                                (assoc % :content [(:name project)]) %) nodes))))))
    (b/copy-file {:src "LICENSE" :target (str classes "/LICENSE")})
    (b/jar {:class-dir classes :jar-file jar-file
            :manifest {"Implementation-Title" (str lib)
                       "Implementation-Version" version}})
    (println "Built" jar-file)
    {:jar-file jar-file}))

(defn- html [text]
  (str/escape (str text) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))

(defn- api-definitions []
  (with-open [reader (java.io.PushbackReader. (io/reader "src/siftell/api.clj"))]
    (binding [*read-eval* false]
      (let [eof (Object.)]
        (loop [definitions []]
          (let [form (read {:eof eof} reader)]
            (cond
              (identical? eof form) (sort-by (comp str :name) definitions)
              (= 'defn (first form))
              (let [[_ name doc & declaration] form
                    declaration (if (map? (first declaration)) (next declaration) declaration)
                    arglists (if (vector? (first declaration))
                               [(first declaration)]
                               (mapv first declaration))]
                (recur (conj definitions {:name name :doc doc :arglists arglists})))
              :else (recur definitions))))))))

(defn- api-documentation []
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width\">"
       "<title>Siftell Clojure API</title><style>"
       "body{max-width:70rem;margin:3rem auto;padding:0 1.5rem;font:1rem/1.6 system-ui,sans-serif}"
       "pre{white-space:pre-wrap;background:#f4f4f6;padding:1rem;border-radius:.5rem}"
       "section{margin:2.5rem 0}a{color:#4a3dad}</style></head><body>"
       "<h1>Siftell Clojure API</h1><p>Typed conditions and deterministic content composition.</p>"
       "<p><a href=\"README.md\">Introduction and complete example</a> · "
       "<a href=\"doc/guide.md\">Composition guide</a> · "
       "<a href=\"doc/reference.md\">Definition and template reference</a> · "
       "<a href=\"examples/siftell/examples.clj\">Executable examples</a></p>"
       "<p>Require <code>[siftell.api :as s]</code>. The following signatures and documentation "
       "come from the public namespace included in this release.</p>"
       (apply str
              (for [{:keys [name arglists doc]} (api-definitions)]
                (str "<section id=\"" (html name) "\"><h2>" (html name) "</h2><pre>"
                     (str/join "\n" (map #(html (str "(s/" name " " (str/join " " (map pr-str %)) ")")) arglists))
                     "</pre><p>" (html doc) "</p></section>")))
       "<hr><p>MIT License. See <a href=\"LICENSE\">LICENSE</a>.</p></body></html>"))

(defn- release-config! [{:keys [lib version name description url scm developers]}]
  (when-not (and (qualified-symbol? lib)
                 (string? version) (re-matches #"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9][A-Za-z0-9.-]*)?" version)
                 (not (str/includes? (str/upper-case version) "SNAPSHOT"))
                 (every? #(and (string? %) (not (str/blank? %)))
                         [name description url (:url scm) (:connection scm)
                          (:developer-connection scm) (:tag scm)])
                 (= (str "v" version) (:tag scm))
                 (seq developers)
                 (every? (fn [developer]
                           (every? #(and (string? %) (not (str/blank? %)))
                                   ((juxt :id :name :email :url) developer))) developers))
    (throw (ex-info "Release requires a stable version and complete Maven metadata"
                    {:task :release}))))

(defn release
  "Build main, sources and documentation JARs plus POM. Stage four unsigned Maven
  artifacts under target/central/<group>/<artifact>/<version>; signing and upload
  are separate release operations."
  [_]
  (let [{:keys [lib version] :as project} (config)
        {:keys [classes jar-file sources-file javadoc-file pom-file stage-dir]} (paths project)
        sources-dir "target/sources"
        documentation-dir "target/documentation"]
    (release-config! project)
    (jar nil)
    (b/delete {:path sources-dir})
    (b/copy-dir {:src-dirs ["src"] :target-dir sources-dir})
    (b/copy-file {:src "LICENSE" :target (str sources-dir "/LICENSE")})
    (b/jar {:class-dir sources-dir :jar-file sources-file})
    (b/delete {:path documentation-dir})
    (doseq [file ["README.md" "LICENSE" "CHANGELOG.md" "CONTRIBUTING.md" "signing-key.asc"]]
      (b/copy-file {:src file :target (str documentation-dir "/" file)}))
    (doseq [directory ["doc" "examples"]]
      (b/copy-dir {:src-dirs [directory] :target-dir (str documentation-dir "/" directory)}))
    (spit (str documentation-dir "/index.html") (api-documentation))
    (b/jar {:class-dir documentation-dir :jar-file javadoc-file})
    (b/copy-file {:src (str classes "/META-INF/maven/" (namespace lib) "/" (name lib) "/pom.xml")
                 :target pom-file})
    (b/delete {:path stage-dir})
    (doseq [file [jar-file sources-file javadoc-file pom-file]]
      (b/copy-file {:src file :target (str stage-dir "/" (.getName (io/file file)))}))
    (println "Staged release" (str lib) version "in" stage-dir)
    {:lib lib :version version :stage-dir stage-dir
     :files [jar-file sources-file javadoc-file pom-file]}))
