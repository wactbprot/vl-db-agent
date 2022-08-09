(ns vl-docsrv.core
  ^{:author "Thomas Bock <thomas.bock@ptb.de>"
    :doc "Starts a http-server at localhost:9992."}
  (:require
   [vl-docsrv.system :as system])
  (:gen-class))

(comment
  (system/start)
  (system/stop))
