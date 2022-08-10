(ns vl-docsrv.handler
  (:require [vl-data-insert.core :as i]))


(defn store-data [doc {:keys [DocPath Results]} put-fn]
  (-> doc
      (i/store-results doc Results DocPath)
      (put-fn))
