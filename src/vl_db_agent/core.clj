;; [Metis](https://gitlab1.ptb.de/vaclab/metis)
;; and [DevProxy](https://gitlab1.ptb.de/vaclab/devproxy) both write
;; data direct to calibration documents. With this setup there is a
;; small chance that conflicts occur due to uncoordinated writing. The
;; workaround so far was sequencing related steps.
;;
;; **vl-db-agent** provides an endpoint for coordinated writing of
;; vaclab style measurement results to calibration documents.

(ns vl-db-agent.core
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
             [vl-data-insert.core :as i])
  (:gen-class))


;; ## Configuration

;; **vl-db-agent** uses [integrant](https://github.com/weavejester/integrant).
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

;; The handler receives the `doc`ument and the `data` stores the results
;; by means of [vl-data-insert](https://github.com/wactbprot/vl-data-insert)
;; and calls `put-fn`. 

(defn store-data [doc {:keys [DocPath Result] :as data} put-fn]
  (µ/trace ::store-data [:function "core/store-data"]
           (put-fn
            (reduce
             (fn [d [r p]] (i/store-results d [r] p))
             doc (zipmap Result (if (string? DocPath) (repeat DocPath) DocPath))))))

;; ## Checks

;; Some simple checks about the shape of the `data` and the `doc`

(defn doc-ok? [{:keys [_id _rev]}] (and _id _rev))

(defn results-ok? [v]
  (let [n (count v)
        m (count (filter map? v))
        o (count (filter empty? v))]
    (and (vector? v) (= n m) (zero? o))))


;; **vl-db-agent** provides the opportunity to store `Result` to
;; different locations in one request. This is done by means of a
;; using a vector for `DocPath` instead of a string. If `DocPath` is a
;; vector it must have the same length as `Result`.

(defn docpath-ok? [p r]
  (if (string? p)
    (not (empty? p))
    (and (vector? p)
         (vector? r)
         (= (count p) (count r))
         (empty? (filterv empty? p)))))

;; The new key `DocPath` is evaluated with preference. 
(defn data-ok? [{:keys [DocPath Result]}]
    (and (docpath-ok? DocPath Result)
         (results-ok? Result)))

;; ## Route and agent

;; **vl-db-agent** provides one `POST` route: `/<database document id>`.
;; The *README* contains curl examples. 
(defn proc [db a]
  (POST "/:id" [id :as req]
        (let [doc    (get-doc id db)
              data   (-> req :body)
              put-fn (fn [doc] (put-doc doc db))]
          (µ/log ::proc :doc-id id :data data)
          (if (and (data-ok? data) (doc-ok? doc))
            (do
              (send a (fn [m] (assoc m id (store-data doc data put-fn))))
              (await a)
              (res/response (get @a id)))
            (do
              (let [msg "missing database doc or maleformed request data"]
                (µ/log ::proc :error msg)
                (-> {:error msg}
                    (res/response)
                    (res/status 412))))))))

;; The first `if` clause (the happy path) contains the central idea:
;; the request is send to
;; an [agent](https://clojure.org/reference/agents) `a` followed by
;; `await`. This queues up the write requests and avoids *write
;; conflicts*

;; ## System

;; The entire system is stored in an `atom` that is build up by the
;; following `init-key` multimethods and shut down by `halt-key!`
;; multimethods.


;; ### System up multimethods
(defonce system (atom nil))

;; The `init-key`s methods **read a configuration** and **return an implementation**.
(defmethod ig/init-key :endpoint/results [_ {:keys [db agnt]}]
  (proc db agnt))

(defmethod ig/init-key :log/mulog [_ opts]  
  (µ/set-global-context! (:log-context opts))
  (µ/start-publisher! opts))

(defmethod ig/init-key :db/couch [_ opts]
  (db-config opts))

;; Initialization of the agent. The error handler function logs the
;; error and the **state** of the agent **before the error**
;; occured so that the agent can be restarted by means
;; of [restart-agent](https://clojuredocs.org/clojure.core/restart-agent)
(defmethod ig/init-key :system/agent [_ {:keys [ini]}]
  
  (agent ini :error-handler (fn [a e] (µ/log ::agent-error-handler
                                            :error e
                                            :agent @a))))

(defmethod ig/init-key :server/http-kit [_ {:keys [handler json-opt] :as opts}]
  (run-server (-> handler
                  (middleware/wrap-json-body json-opt)
                  (middleware/wrap-json-response))
              (-> opts
                  (dissoc :handler))))

;; ### System down multimethods
;;
;; The `halt-keys!` methods **read in the implementation** and shut down
;; this implementation in a contolled way. This is a pure side effect.

(defmethod ig/halt-key! :server/http-kit [_ server]
  (server))

(defmethod ig/halt-key! :log/mulog [_ logger]
  (logger))

(defmethod ig/halt-key! :system/agent [_ a]
  (send a (fn [_] {})))

;; ### Start, stop and restart
;; The following functions are intended for REPL usage.

(defn start []
  (keys (reset! system (ig/init config))))

(defn stop []
  (µ/log ::start :message "halt system")
  (ig/halt! @system)
  (reset! system {}))

(defn restart [] (stop) (start))

;; ## Main
;; The `-main` function is called when `java -jar vl-db-agent.jar`
;; is executed. No arguments are implemented so far.
(defn -main [& args] (start))


;; ## Playground
(comment
  (start)
  (stop)
  (restart))
