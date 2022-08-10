(ns vl-docsrv.core
  ^{:author "Thomas Bock <thomas.bock@ptb.de>"
    :doc "Start/Stop system."}
  (:require [vl-docsrv.system :as system])
  (:gen-class))

(comment
  (system/start)
  (system/stop))
