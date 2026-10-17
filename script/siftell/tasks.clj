(ns siftell.tasks
  "Local build tasks and GitHub Actions release preparation."
  (:require [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.util UUID]))

(defn project [] (edn/read-string (slurp "build.edn")))

(defn clojure! [& arguments]
  (process/check @(process/process (into ["clojure" "-Srepro"] arguments)
                                  {:inherit true})))

(defn- git [& arguments]
  (str/trim (:out (process/check
                  @(process/process (into ["git"] arguments)
                                    {:out :string :err :string})))))

(defn- ensure! [condition message]
  (when-not condition (throw (ex-info message {:task :ci-release}))))

(defn release-version
  "Validate the configured version, existing tag and checked-out commit."
  [{:keys [version scm]} tag head tag-head]
  (ensure! (and (string? version)
                (re-matches #"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9][A-Za-z0-9.-]*)?" version)
                (not (str/includes? (str/upper-case version) "SNAPSHOT")))
           "build.edn must contain a release version")
  (ensure! (= tag (:tag scm) (str "v" version))
           "The release tag must match the version and SCM tag in build.edn")
  (ensure! (and (not (str/blank? head)) (= head tag-head))
           "The release checkout must match the existing release tag")
  version)

(defn ci-release!
  "Prepare JReleaser using an existing Git tag and the committed public key.
  Credentials are passed directly to the release action through its environment."
  []
  (let [config (project)
        tag (System/getenv "RELEASE_TAG")
        env-file (System/getenv "GITHUB_ENV")]
    ;; Validate before using a workflow input as a Git revision.
    (ensure! (= tag (:tag (:scm config)) (str "v" (:version config)))
             "RELEASE_TAG must match build.edn")
    (ensure! (not (str/blank? env-file)) "GITHUB_ENV is required for release preparation")
    (let [version (release-version config tag (git "rev-parse" "HEAD")
                                   (git "rev-parse" "--verify" (str "refs/tags/" tag "^{commit}")))
          public-key (str/trim (slurp "signing-key.asc"))
          delimiter (str "SIFTELL_PUBLIC_KEY_" (UUID/randomUUID))]
      (ensure! (and (str/starts-with? public-key "-----BEGIN PGP PUBLIC KEY BLOCK-----")
                    (str/ends-with? public-key "-----END PGP PUBLIC KEY BLOCK-----"))
               "signing-key.asc must contain the release public key")
      (spit env-file
            (str "JRELEASER_PROJECT_VERSION=" version "\n"
                 "JRELEASER_GPG_PUBLIC_KEY<<" delimiter "\n" public-key "\n" delimiter "\n")
            :append true)
      (println "Verified release" tag "at" (git "rev-parse" "--short" "HEAD")))))
