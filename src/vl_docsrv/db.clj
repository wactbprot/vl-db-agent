(ns vl-docsrv.db
  (:require [libcdb.core :as db]
            [libcdb.configure :as cf]))

(defn config [opts] (cf/config opts))

(defn get-doc [id db]
  (db/get-doc id db))
