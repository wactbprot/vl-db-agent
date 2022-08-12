;; [Metis](https://gitlab1.ptb.de/vaclab/metis)
;; and [DevProxy](https://gitlab1.ptb.de/vaclab/devproxy) both write
;; data direct to vaclab style calibration documents. With this setup
;; there is a small chance that conflicts occur due to uncoordinated
;; writing. The workaround so far was sequencing related
;; steps.
;;
;; **vl-docsrv** provides an endpoint for coordinated writing of
;; vaclab style measurement results to calibration documents.

(ns vl-docsrv.core
  ^{:author "Thomas Bock <thomas.bock@ptb.de>"}
  (:require  [compojure.core :refer [POST]]
             [compojure.handler :as handler]
             [com.brunobonacci.mulog :as µ]
             [integrant.core :as ig]
             [libcdb.core :as db]
             [libcdb.configure :as cf]
             [org.httpkit.server :refer [run-server]]            
             [ring.util.response :as res]
             [ring.middleware.json :as middleware]
             [vl-data-insert.core :as i]))


;; ## Configuration

;; **vl-docsrv** uses [integrant](https://github.com/weavejester/integrant).
;; This enables a controlled way to start and stop the system.
;; **integrant** style configuration map.

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
                     :json-opt {:keywords? true}
                     :handler (ig/ref :endpoint/results)}})

;; ## Database io functions

;; The three functions used here are simply passed through from
;; library [libcdb](https://gitlab1.ptb.de/vaclab/vl-db)

(defn db-config [opts] (cf/config opts))

(defn get-doc [id db] (db/get-doc id db))

(defn put-doc [doc db] (db/put-doc doc db))

;; ## Handler

;; The handler gets the `doc`ument and the `data` stores the results
;; by means of [vl-data-insert](https://github.com/wactbprot/vl-data-insert)
;; and puts the result back to the database.

(defn store-data [doc {:keys [DocPath Results] :as data} put-fn]
  (-> doc
      (i/store-results Results DocPath)
      (put-fn)))

;; ## Checks

;; Some simple checks about the shape of the `data` and the `doc`

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

;; ## Route and agent

;;  **vl-docsrv** provides one `POST` route: `/<database document id>`.
;; An example usage would be:
;;
;; Request header and url:
;;
;; `H="Content-Type: application/json"`
;;
;; `URL="http://localhost:9992"`
;; 
;; The body of the request (the data) may look like this
;;
;; `B='{"DocPath":"Calibration.Measurement.Values.Pressure", "Results":[{"Value":100, "Type":"ind", "Unit":"Pa"}]}'`
;;
;; Send the body to the id `cal-2022-se3-pn-4025_0007` 
;;
;; `curl -H "$H" -d "$B" -X POST $URL/cal-2022-se3-pn-4025_0007`
;;
(defn proc [db a]
  (POST "/:id" [id :as req]
        (let [doc (get-doc id db)
              put-fn (fn [doc] (put-doc doc db))
              data   (-> req :body)]
          (if (and (data-ok? data) (doc-ok? doc))
            (do
              (send a (fn [m] (assoc m id (store-data doc data put-fn))))
              (await a)
              (res/response (get @a id)))
            (do
              (-> {:error "missing database doc or maleformed request data"}
                  (res/response)
                  (res/status 412)))))))

;; The first `if` clause (the happy path) contains the central idea:
;; the request is send to
;; an [agent](https://clojure.org/reference/agents) `a` followed by
;; `await`. This queues up the write requests and avoids *write
;; conflicts*

;; ## System

;; The entire system is stored in an `atom` that is build up by the
;; following `init-key` multimethods and shut down by `halt-key!`
;; multimethods.

(defonce system (atom nil))

;; ### System up multimethods

;; The `init-key`s methods read a confoguration and return an implementation.

(defmethod ig/init-key :endpoint/results [_ {:keys [db a]}]
  (proc db a))

(defmethod ig/init-key :log/mulog [_ opts]  
  (µ/set-global-context! (:log-context opts))
  (µ/start-publisher! opts))

(defmethod ig/init-key :db/couch [_ opts]
  (db-config opts))

(defmethod ig/init-key :system/agent [_ {:keys [ini]}]
  ;; ToDo: add error function
  (agent ini))

(defmethod ig/init-key :server/http-kit [_ {:keys [handler json-opt] :as opts}]
  (run-server (-> handler
                  (middleware/wrap-json-body json-opt)
                  (middleware/wrap-json-response))
              (-> opts
                  (dissoc :handler))))

;; ### System down multimethods

;; The `halt-keys!` methods read in an implementation and shut down
;; this implementation in a contolled way. This are pure side effects.

(defmethod ig/halt-key! :server/http-kit [_ server]
  (server))

(defmethod ig/halt-key! :log/mulog [_ logger]
  (logger))

(defmethod ig/halt-key! :system/agent [_ a]
  (send a (fn [_] {})))

;; ### Start system

(defn start []
  (keys (reset! system (ig/init config))))

;; ### Stop system

(defn stop []
  (ig/halt! @system)
  (reset! system {}))

(defn restart [] (stop) (start))

;; ## Playground
(comment
  (start)
  (stop)
  (restart))
