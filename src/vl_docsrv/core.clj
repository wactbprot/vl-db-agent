(ns vl-docsrv.core
  ^{:author "Thomas Bock <thomas.bock@ptb.de>"
    :doc "Starts a http-server at localhost:9992."}
  (:require  [compojure.core :refer [defroutes GET POST]]
             [compojure.route :as route]
             [compojure.handler :as handler]
             [com.brunobonacci.mulog :as µ]
             [integrant.core :as ig]
             [libcdb.core :as db]
             [libcdb.configure :as cf]
             [org.httpkit.server :refer [run-server]]            
             [ring.util.response :as res]
             [ring.middleware.json :as middleware]
             [vl-data-insert.core :as i]))


;; ...................................................
;; system
;; ...................................................
(defonce sys (atom nil))


;; ...................................................
;; configuration
;; ...................................................
(def config
  {:log/mulog {:type :multi
               :log-context {:facility (or (System/getenv "DEVPROXY_FACILITY")
                                            (System/getenv "DEVHUB_FACILITY")
                                            (System/getenv "METIS_FACILITY"))}
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


;; ...................................................
;; database io
;; ...................................................
(defn db-config [opts] (cf/config opts))

(defn get-doc [id db] (db/get-doc id db))

(defn put-doc [doc db] (db/put-doc doc db))


;; ...................................................
;; handler
;; ...................................................
(defn store-data [doc {:keys [DocPath Results]} put-fn]
  (-> doc
      (i/store-results Results DocPath)
      (put-fn)))

;; ...................................................
;; ok?
;; ...................................................
(defn doc-ok? [{:keys [_id _rev]}] (and _id _rev))

(defn results-ok? [v]
  (let [n (count v)
        m (count (filter map? v))
        o (count (filter empty? v))]
    (and (vector? v) (= n m) (zero? o))))

(defn docpath-ok? [s] (and (string? s) (not (empty? s))))

(defn data-ok? [{:keys [DocPath Results]}]
  (and (docpath-ok? DocPath)
       (results-ok? Results)))


;; ...................................................
;; route
;; ...................................................
(defn proc [db agnt]
  (POST "/:id" [id :as req]
        (let [doc (get-doc id db)
              put-fn (fn [doc] (put-doc doc db))
              data   (-> req :body)]
          (if (and (data-ok? data) (doc-ok? doc))
            (do         
              (send agnt (fn [m] (assoc m id (store-data doc data put-fn))))
              (await agnt)
              (res/response (get @agnt id)))
            (do
              (-> {:error "missing database doc or maleformed request data"}
                  (res/response)
                  (res/status 412)))))))


;; ...................................................
;; system up
;; ...................................................
(defmethod ig/init-key :endpoint/results [_ {:keys [db agnt]}]
  (proc db agnt))

(defmethod ig/init-key :log/mulog [_ opts]  
  (µ/set-global-context! (:log-context opts))
  (µ/start-publisher! opts))

(defmethod ig/init-key :db/couch [_ opts]
  (db-config opts))

(defmethod ig/init-key :system/agent [_ {:keys [ini]}]
  ;; add error function
  (agent ini))

(defmethod ig/init-key :server/http-kit [_ {:keys [handler] :as opts}]
  (run-server (-> handler
                  (middleware/wrap-json-body {:keywords? true})
                  (middleware/wrap-json-response))
              (-> opts
                  (dissoc :handler))))


;; ...................................................
;; system down
;; ...................................................
(defmethod ig/halt-key! :server/http-kit [_ server]
  (server))

(defmethod ig/halt-key! :log/mulog [_ logger]
  (logger))

(defmethod ig/halt-key! :system/agent [_ a]
  (send a (fn [_] {})))


;; ...................................................
;; start system
;; ...................................................
(defn start []
  (keys (reset! sys (ig/init config))))


;; ...................................................
;; stop system
;; ...................................................
(defn stop []
  (ig/halt! @sys)
  (reset! sys {}))

(comment
  (start)
  (stop))
