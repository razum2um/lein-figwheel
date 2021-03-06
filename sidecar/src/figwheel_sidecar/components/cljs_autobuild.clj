(ns figwheel-sidecar.components.cljs-autobuild
  (:require
   [figwheel-sidecar.config :refer [add-compiler-env prep-build prepped?]]   
   [figwheel-sidecar.watching :as watching]
   [figwheel-sidecar.utils :as utils]

      ;; build hooks
   [figwheel-sidecar.build-hooks.injection :as injection] 
   [figwheel-sidecar.build-hooks.notifications :as notifications]
   [figwheel-sidecar.build-hooks.clj-reloading :as clj-reloading]
   [figwheel-sidecar.build-hooks.javascript-reloading :as javascript-reloading]   
   
   [com.stuartsierra.component :as component]
   [cljs.closure]
   [cljs.build.api :as bapi]
   [clojure.java.io :as io]))

;; TODO can I run this without a figwheel server??
;; that would make this component much more useful

(defn cljs-build [{:keys [build-config]}]
  (bapi/build
   (apply bapi/inputs (:source-paths build-config))
   (:build-options build-config)
   (:compiler-env build-config)))

(let [reset-color "\u001b[0m"
      foreground-green "\u001b[32m"
      elapsed
      (fn [started-at]
        (let [elapsed-us (- (System/currentTimeMillis) started-at)]
          (with-precision 2
            (str (/ (double elapsed-us) 1000) " seconds"))))]
  (defn figwheel-start-and-end-messages [build-fn]
    (fn [{:keys [figwheel-server build-config changed-files] :as build-state}]
      (let [started-at (System/currentTimeMillis)
            {:keys [build-options source-paths]} build-config
            {:keys [output-to]} build-options]
        ;; print start message
        (println (str reset-color "Compiling \""
                      output-to
                      "\" from " (pr-str source-paths) "..."))
        (flush)
                                        ; build
        (build-fn build-state)
                                        ; print end message
        (println (str foreground-green
                      "Successfully compiled \""
                      output-to
                      "\" in " (elapsed started-at) "." reset-color))
        (flush)))))

(def figwheel-build
  (-> cljs-build
      injection/build-hook
      notifications/build-hook
      clj-reloading/build-hook
      javascript-reloading/build-hook
      figwheel-start-and-end-messages))

(def figwheel-build-without-javascript-reloading
  (-> cljs-build
      injection/build-hook
      notifications/build-hook
      clj-reloading/build-hook
      figwheel-start-and-end-messages))

(def figwheel-build-without-clj-reloading
  (-> cljs-build
      injection/build-hook
      notifications/build-hook
      javascript-reloading/build-hook
      figwheel-start-and-end-messages))

(defn source-paths-that-affect-build [{:keys [build-options source-paths]}]
  (let [{:keys [libs foreign-libs]} build-options]
    (concat
     source-paths
     libs
     (not-empty (mapv :file foreign-libs)))))

(defrecord CLJSAutobuild [build-config figwheel-server]
  component/Lifecycle
  (start [this]
    (if-not (:file-watcher this)
      (do
        (println "Figwheel: Watching build -" (:id build-config))
        (flush)
        ;; setup
        (injection/delete-connect-scripts! [build-config])
        ;; TODO this should be conditional based on a flag
        #_(clean-cljs-build* (:build-options build-config))
        ;; initial build only needs the injection and the
        ;; start and end messages

        (let [log-writer (or (:log-writer this)
                             (:log-writer figwheel-server)
                             (io/writer "figwheel_server.log" :append true))
              cljs-build-fn (or (:cljs-build-fn this)
                                (:cljs-build-fn figwheel-server)
                                ;; if no figwheel server
                                ;; default build should be standard
                                ;; cljs build
                                (if figwheel-server
                                  figwheel-build
                                  (figwheel-start-and-end-messages cljs-build)))]
          ;; build once before watching
          ;; tiny experience tweak
          ;; first build shouldn't send notifications
          ((if (= cljs-build-fn figwheel-build)
            (-> cljs-build
                injection/build-hook
                figwheel-start-and-end-messages)
            cljs-build-fn) this)
          (assoc this
                 ;; for simple introspection
                 :cljs-autobuild true
                 :file-watcher
                 (watching/watch! (source-paths-that-affect-build build-config)
                          (fn [files]
                            (utils/sync-exec
                             (fn []
                               (binding [*out* log-writer
                                         *err* log-writer]
                                 (cljs-build-fn
                                  (assoc this
                                         :changed-files (map str files)))))))))))
      this))
  (stop [this]
    (when (:file-watcher this)
      (println "Figwheel: Stopped watching build -" (:id build-config))
      (flush)
      (watching/stop! (:file-watcher this)))
    (dissoc this :file-watcher)))

(defn cljs-autobuild
  "  Creates a ClojureScript autobuilding component that watches
  ClojureScript source files for changes and then compiles them."
  [{:keys [build-config] :as opts}]
  ;; do a little preparation of the build config just in case
  (let [build-config (if-not (prepped? build-config)
                       (prep-build build-config)
                       build-config)
        build-config (if-not (:compiler-env build-config)
                       (add-compiler-env build-config)
                       build-config)]
    (map->CLJSAutobuild (assoc opts :build-config build-config))))
