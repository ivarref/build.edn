(ns my-clj-build.core
  (:require
    [clojure.tools.build.api :as b]
    [deps-deploy.deps-deploy :as deploy]
    [malli.core :as m]
    [malli.error :as me]
    [malli.util :as mu]))

(def ^:private ?scm
  [:map
   [:connection string?]
   [:developerConnection string?]
   [:url string?]])

(def ^:private ?build-config
  [:map
   [:lib qualified-symbol?]
   [:version string?]
   [:source-dir {:optional true} string?]
   [:class-dir {:optional true} string?]
   [:jar-file {:optional true} string?]
   [:scm {:optional true} ?scm]])

(def ^:private ?uber-build-config
  (mu/merge ?build-config
            [:map
             [:uber-file string?]
             [:main {:optional true} symbol?]]))

(defn- validate-config!
  ([config]
   (validate-config! ?build-config config))
  ([?schema config]
   (when-let [e (m/explain ?schema config)]
     (let [m (me/humanize e)]
       (throw (ex-info (str "Invalid config: " m) m))))))

(defn- get-defaults
  [config]
  (let [lib-name (name (:lib config))]
    {:class-dir "target/classes"
     :jar-file (format "target/%s.jar" lib-name)
     :uber-file (format "target/%s-standalone.jar" lib-name)}))

(defn- gen-config
  [arg]
  (or (:config arg)
      (let [git-count (or (b/git-count-revs nil) 0)
            config (update arg :version #(format % git-count))
            config (cond-> config
                     (contains? config :scm)
                     (assoc-in [:scm :tag] (:version config)))]
        (merge (get-defaults config) config))))

(defn- get-basis
  [arg]
  (or (:basis arg)
      (-> (select-keys arg [:aliases])
          (b/create-basis))))

(defn- get-src-dirs [config basis]
  (or (:src-dirs config)
      (:paths basis)))

(defn pom
  [arg]
  (let [config (gen-config arg)
        basis (get-basis arg)]
    (validate-config! config)
    (-> config
        (select-keys [:lib :version :class-dir :scm])
        (assoc :basis basis
               :src-dirs (get-src-dirs config basis))
        (b/write-pom))))

(defn jar
  [arg]
  (let [{:as config :keys [class-dir jar-file]} (gen-config arg)
        basis (get-basis arg)
        arg (assoc arg :config config :basis basis)]
    (validate-config! config)
    (pom arg)
    (b/copy-dir {:src-dirs (get-src-dirs config basis)
                 :target-dir class-dir})
    (b/jar {:class-dir class-dir
            :jar-file jar-file})))

(defn uberjar
  [arg]
  (let [{:as config :keys [class-dir uber-file main]} (gen-config arg)
        basis (get-basis arg)
        src-dirs (get-src-dirs config basis)
        arg (assoc arg :config config :basis basis)]
    (validate-config! ?uber-build-config config)
    (pom arg)
    (b/copy-dir {:src-dirs src-dirs
                 :target-dir class-dir})
    (b/compile-clj {:basis basis
                    :src-dirs src-dirs
                    :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis basis
             :main main})))

(defn install
  [arg]
  (let [{:as config :keys [lib class-dir jar-file]} (gen-config arg)
        arg (assoc arg :config config)]
    (validate-config! config)
    (jar arg)
    (deploy/deploy {:artifact jar-file
                    :installer :local
                    :pom-file (b/pom-path {:lib lib :class-dir class-dir})})))

(defn print-version
  [arg]
  (let [config (gen-config arg)]
    (println (str "::set-output name=version::" (:version config)))))

(defn deploy
  [arg]
  (assert (and (System/getenv "CLOJARS_USERNAME")
               (System/getenv "CLOJARS_PASSWORD")))
  (let [{:as config :keys [lib class-dir jar-file]} (gen-config arg)
        arg (assoc arg :config config)]
    (validate-config! config)
    (jar arg)
    (deploy/deploy {:artifact jar-file
                    :installer :remote
                    :pom-file (b/pom-path {:lib lib :class-dir class-dir})})
    (print-version arg)))