(ns vl-docsrv.system
  ^{:author "Thomas Bock <thomas.bock@ptb.de>"
    :doc "Starts a http-server at localhost:9992."}
  (:require
   [vl-docsrv.db :as db]
   [compojure.core :refer [defroutes GET POST]]
   [compojure.route :as route]
   [compojure.handler :as handler]
   [org.httpkit.server :refer [run-server]]
   [integrant.core :as ig]
   [ring.util.response :as res]
   [ring.middleware.json :as middleware]
   [com.brunobonacci.mulog :as µ])
  (:gen-class))


(def config
  {:log/mulog {:type :multi
               :log-context {:facility (System/getenv "DEVHUB_FACILITY")}
               :publishers[{:type :elasticsearch
                            :url  "http://a75438:9200/"
                            :els-version  :v7.x
                            :publish-delay 1000
                            :data-stream  "vl-log-stream"
                            :name-mangling false}]}
   :db/couch {:prot "http",
              :host "localhost",
              :port 5984,
              :usr (System/getenv "CAL_USR")
              :pwd (System/getenv "CAL_PWD")
              :name "vl_db_work"}
   :endpoint/results {:db (ig/ref :db/couch)}
   :server/http-kit {:port 9992
                   :join? false
                   :handler (ig/ref :endpoint/results)}})

(defn proc [db]
  (POST "/:id" [id :as req]
        (res/response (db/get-doc id db))))

(defmethod ig/init-key :endpoint/results [_ {:keys [db]}]
  (proc db))

(defmethod ig/init-key :log/mulog [_ opts]  
  (µ/set-global-context! (:log-context opts))
  (µ/start-publisher! opts))

(defmethod ig/init-key :db/couch [_ opts]
  (db/config opts))

(defmethod ig/init-key :server/http-kit [_ {:keys [handler] :as opts}]
  (run-server (-> handler
                  (middleware/wrap-json-body {:keywords? true})
                  (middleware/wrap-json-response))
              (-> opts
                  (dissoc :handler))))

(defmethod ig/halt-key! :server/http-kit [_ server]
  (server))

(defmethod ig/halt-key! :log/mulog [_ logger]
  (logger))


(def sys (atom nil))

(defn start [ids]
  (keys (reset! sys (ig/init config))))

(defn stop []
  (ig/halt! @sys)
  (reset! sys {}))
