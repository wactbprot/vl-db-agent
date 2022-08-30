;; # Rationale
;;
;; In some cases [Metis](https://gitlab1.ptb.de/vaclab/metis) (A)
;; and [DevProxy](https://gitlab1.ptb.de/vaclab/devproxy) (B) both
;; write data direct to the same calibration document. With this setup
;; there is a chance that conflicts occur due to uncoordinated
;; writing. A and B try to store the document with `rev 1`.  If A
;; writes first a `rev 2` is generated. The attempt of B to write `rev 1`
;; fails. Same if B writes first.

;; <pre>
;;     ┌─────┐        ┌─────┐
;;     │     │        │     │
;;     │  A  │        │  B  │
;;     │     │        │     │
;;     └─┬──▲┘        └▲──┬─┘
;;       │  │          │  │
;; rev 1 │  ✓ ┌──────┐ ✓  │ rev 1
;;       │  └─┤      ├─┘  │
;;       ✓    │  DB  │    x
;;       └────►      ◄────┘
;;            └──────┘
;; </pre>
;;   
;;
;; The workaround so far was sequencing related steps.
;;
;; **vl-db-agent** provides an endpoint for coordinated writing of
;; vaclab style measurement results (`data) to calibration
;; documents. This is realized by means of a
;; clojure [agent](https://clojure.org/reference/agents).
;;
;; <pre>
;;       ┌─────┐        ┌─────┐
;;       │     │        │     │
;;       │  A  │        │  B  │
;;       │     │        │     │
;;       └┬────┘        └────┬┘
;;        │                  │
;;  data  │ ┌──────────────┐ │  data
;;        │ │              │ │
;;        └─►   db-agent   ◄─┘
;;          │              │
;;          │ rev 1  rev 2 │
;;          └─▲──┬───▲──┬──┘
;;            |  |   |  |
;;            ✓  ✓   ✓  ✓
;;            |  |   |  |
;;          ┌─┴──▼───┴──▼──┐
;;          │              │
;;          │      DB      │
;;          │              │
;;          └──────────────┘
;; </pre>
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

;; # Configuration
;; **vl-db-agent** uses [integrant](https://github.com/weavejester/integrant).
;; This enables a controlled way to start and stop the system.
;; **integrant** style configuration map.
(def config
  {:log/mulog {:type :multi
               :log-context {:app-name "vl-db-agent"
                             :facility (or (System/getenv "DEVPROXY_FACILITY")
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

;; # Database io functions
;; The three functions used here are simply passed through from
;; library [libcdb](https://gitlab1.ptb.de/vaclab/vl-db)
(defn db-config [opts] (cf/config opts))
(defn get-doc [id db] (db/get-doc id db))
(defn put-doc [doc db] (db/put-doc doc db))

;; # Handler
;; The handler receives the `doc`ument and the `data` stores the results
;; by means of [vl-data-insert](https://github.com/wactbprot/vl-data-insert)
;; and calls `put-fn`. 
(defn store-data [get-fn {:keys [DocPath Result] :as data} put-fn]
  (µ/trace ::store-data [:function "core/store-data"]
           (let [doc (get-fn)]
             (put-fn
              (reduce
               (fn [d [r p]] (i/store-results d [r] p))
               doc (zipmap Result (if (string? DocPath) (repeat DocPath) DocPath)))))))

;; # Checks
;; Some simple checks about the shape of the `data`
(defn results-ok? [v] (and (vector? v) (empty? (filter empty? v))))

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

(defn data-ok? [{:keys [DocPath Result]}]
    (and (docpath-ok? DocPath Result)
         (results-ok? Result)))

;; # Route and agent
;; **vl-db-agent** provides one `POST` route: `/<database document id>`
;; (the README contains curl examples).
(defn proc [db a]
   (POST "/:id" [id :as req]
         (let [data   (-> req :body)
               get-fn (fn [] (get-doc id db))
               put-fn (fn [doc] (put-doc doc db))]
           (if (data-ok? data)
             (do
               (µ/log ::proc :doc-id id :message "doc and data ok")
               (send a (fn [m] (assoc m
                                     id {:res (store-data get-fn data put-fn)
                                         :data data})))
               (-> {:ok true}
                   (res/status 202)
                   (res/response)))
             (do
               (let [msg "missing database doc or maleformed request data"]
                 (µ/log ::proc :error msg)
                 (-> {:error msg}
                     (res/status 412)
                     (res/response))))))))

;; The first `if` clause (the happy path) contains the central idea:
;; the request is send to
;; an [agent](https://clojure.org/reference/agents) `a`. This queues
;; up the write requests and avoids *write conflicts*. The **important thing**
;; is: reading **and** writing of the database document must be
;; placed **inside** the agents `send` function.

;; # System
;; The entire system is stored in an `atom` that is build up by the
;; following `init-key` multimethods and shut down by `halt-key!`
;; multimethods.
;; ### System up multimethods
(defonce system (atom nil))

;; The `init-key`s methods **read a configuration** and **return an
;; implementation**.
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

;; ## System down multimethods
;; The `halt-keys!` methods **read in the implementation** and shut down
;; this implementation in a contolled way. This is a pure side effect.
(defmethod ig/halt-key! :server/http-kit [_ server]
  (server))

(defmethod ig/halt-key! :log/mulog [_ logger]
  (logger))

(defmethod ig/halt-key! :system/agent [_ a]
  (send a (fn [_] {})))

;; ## Start, stop and restart The following functions are intended
;; for [REPL](https://clojure.org/guides/repl/introduction) usage.
(defn start []
  (keys (reset! system (ig/init config))))

(defn stop []
  (µ/log ::start :message "halt system")
  (ig/halt! @system)
  (reset! system {}))

(defn restart []
  (stop)
  (Thread/sleep 1000)
  (start))

;; # Main
;; The `-main` function is called when `java -jar vl-db-agent.jar`
;; is executed. No arguments are implemented so far.
(defn -main [& args] (start))


;; # Playground
(comment
  (start)
  (stop)
  (restart)
  (:system/agent @system)
  (agent-error (:system/agent @system))
  (map (comp :ok :res) (vals @(:system/agent @system))))
