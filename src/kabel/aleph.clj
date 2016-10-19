(ns kabel.aleph
  "Aleph specific IO operations."
  (:require [clojure.set :as set]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kabel.platform-log :refer [debug info warn error]]
            [incognito.transit :refer [incognito-read-handler incognito-write-handler]]
            [superv.async :refer [<? <?? go-try -error]]
            [superv.lab :refer [go-loop-super]]
            [clojure.core.async :as async
             :refer [>! timeout chan alt! put! close!]]
            [manifold.stream :as s]
            [aleph.http :as http]
            [cognitect.transit :as transit])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [com.cognitect.transit.impl WriteHandlers$MapWriteHandler]))



(defn client-connect!
  "Connects to url. Puts [in out] channels on return channel when ready.
  Only supports websocket at the moment, but is supposed to dispatch on
  protocol of url. read-handlers and write-handlers are atoms
  according to incognito."
  ([S url peer-id]
   (client-connect! S url peer-id (atom {}) (atom {})))
  ([S url peer-id read-handlers write-handlers]
   (let [in (chan)
         out (chan)
         host (.getHost (java.net.URL. (.replace url "ws" "http")))
         ws @(http/websocket-client url)]
     (info "ws-opened" ws)
     ;; TODO exert backpressure
     #_(s/->source in)
     (s/consume (fn [^bytes data]
                  (try
                    (debug "received byte message")
                    (with-open [bais (ByteArrayInputStream. data)]
                      (let [reader
                            (transit/reader bais :json
                                            {:handlers {"incognito" (incognito-read-handler read-handlers)}})
                            m (transit/read reader)]
                        (debug "client received transit blob from:" url (:type m))
                        (async/put! in (assoc m :host host))))
                    (catch Exception e
                      (put! (-error S)
                            (ex-info "Cannot receive data." {:url url
                                                             :data data
                                                             :error e}))
                      (close! in))))
                ws)

     (go-loop-super S [m (<? S out)]
                    (when m
                      (debug "client sending msg to:" url (:type m))
                      (with-open [baos (ByteArrayOutputStream.)]
                        (let [writer (transit/writer baos :json
                                                     {:handlers {java.util.Map (incognito-write-handler write-handlers)}})]
                          (transit/write writer (assoc m :sender peer-id) ))
                        @(s/put! ws (.toByteArray baos)))
                      (recur (<? S out))))
     (go-try S [in out]))))
