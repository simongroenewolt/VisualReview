;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Copyright 2015 Xebia B.V.
;
; Licensed under the Apache License, Version 2.0 (the "License")
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;     http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(ns com.xebia.visualreview.starter
  (:import [org.eclipse.jetty.server Server])
  (:require [ring.middleware
             [params :as params]
             [keyword-params :refer [wrap-keyword-params]]
             [nested-params :refer [wrap-nested-params]]
             [multipart-params :refer [wrap-multipart-params]]]
            [taoensso.timbre :as timbre]
            [com.xebia.visualreview.routes :as routes]
            [ring.adapter.jetty :refer [run-jetty]]
            [com.xebia.visualreview.middleware :as middleware]))

(def app (-> routes/main-router
             wrap-keyword-params
             wrap-nested-params
             wrap-multipart-params
             params/wrap-params
             middleware/wrap-exception))

(defonce server (atom nil))

(defn stop-server []
  (when-let [ws @server]
    (.stop ^Server ws)
    (reset! server nil)
    (timbre/log :info "VisualReview server stopped")))

(defn start-server [port]
  (try
    (reset! server (run-jetty #'app {:join? false :port port}))
    (timbre/log :info (str "VisualReview server started at port " port))
    (catch Exception e (timbre/log :fatal (str "Could not start server on port " port ": " (.getMessage e))))))
