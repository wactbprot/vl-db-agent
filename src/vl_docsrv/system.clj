(ns vl-docsrv.system
  ^{:author "Thomas Bock <thomas.bock@ptb.de>"
    :doc "Starts a http-server at localhost:9992."}
  (:require
   [vl-docsrv.db :as db]
   [vl-docsrv.handler :as h]
   [compojure.core :refer [defroutes GET POST]]
   [compojure.route :as route]
   [compojure.handler :as handler]
   [org.httpkit.server :refer [run-server]]
   [integrant.core :as ig]
   [ring.util.response :as res]
   [ring.middleware.json :as middleware]
   [com.brunobonacci.mulog :as µ]))

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
   :system/agent {:ini {}}
   :endpoint/results {:db (ig/ref :db/couch)
                      :agnt (ig/ref :system/agent)}
   :server/http-kit {:port 9992
                   :join? false
                   :handler (ig/ref :endpoint/results)}})

(defn proc [db agnt]
  (POST "/:id" [id :as req]
        (send agnt (fn [m]
                     (let [doc (db/get-doc id db)
                           put-fn (fn [doc] (db/put-doc doc db))
                           data   (-> req :body)]
                       (assoc m id (h/store-data doc data put-fn)))))
        (await agnt)
        (res/response (get @agnt id))))

(defmethod ig/init-key :endpoint/results [_ {:keys [db agnt]}]
  (proc db agnt))

(defmethod ig/init-key :log/mulog [_ opts]  
  (µ/set-global-context! (:log-context opts))
  (µ/start-publisher! opts))

(defmethod ig/init-key :db/couch [_ opts]
  (db/config opts))


(defmethod ig/init-key :system/agent [_ {:keys [ini]}]
  (agent ini))

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

(defmethod ig/halt-key! :system/agent [_ a]
  (send a (fn [_] {})))


(defonce sys (atom nil))

(defn start []
  (keys (reset! sys (ig/init config))))

(defn stop []
  (ig/halt! @sys)
  (reset! sys {}))
