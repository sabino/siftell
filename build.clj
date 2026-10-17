(ns build
  "Build tasks for a source-only library JAR with declared Maven dependencies."
  (:require [clojure.edn :as edn]
            [clojure.tools.build.api :as b]))

(defn clean "Remove build output." [_] (b/delete {:path "target"}))

(defn jar "Package source, dependency metadata and license without AOT compilation." [_]
  (let [{:keys [lib version description]} (edn/read-string (slurp "build.edn"))
        classes "target/classes"
        jar-file (format "target/%s-%s.jar" (name lib) version)
        basis (b/create-basis {:project "deps.edn" :user nil :aliases []})]
    (b/delete {:path classes})
    (b/copy-dir {:src-dirs ["src"] :target-dir classes})
    (b/write-pom {:class-dir classes :lib lib :version version :basis basis
                  :src-pom :none :src-dirs ["src"]
                  :pom-data [[:description description]
                             [:licenses [:license [:name "MIT License"]
                                         [:url "https://opensource.org/license/mit"]
                                         [:distribution "repo"]]]]})
    (b/copy-file {:src "LICENSE" :target (str classes "/LICENSE")})
    (b/jar {:class-dir classes :jar-file jar-file
            :manifest {"Implementation-Title" (str lib)
                       "Implementation-Version" version}})
    (println "Built" jar-file)
    {:jar-file jar-file}))
