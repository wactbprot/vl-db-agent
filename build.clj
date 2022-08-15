(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'de.ptb.gitlab1.vaclab/vl-docsrv)
(def version (format "0.3.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn" :aliases [:dev]}))
(def uber-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn prep [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src"]})
  (b/copy-dir {:src-dirs ["src"]
               :target-dir class-dir}))

(defn uber [_]
  (b/compile-clj {:basis basis
                  :src-dirs ["src"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :main 'vl-docsrv.core
           :uber-file uber-file
           :basis basis}))

(defn all [_]
  (clean nil)
  (prep nil)
  (uber nil))
