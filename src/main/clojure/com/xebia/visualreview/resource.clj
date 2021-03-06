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

(ns com.xebia.visualreview.resource
  (:import [java.util Map])
  (:require [liberator.core :refer [resource]]
            [liberator.representation :as representation]
            [com.xebia.visualreview.validation :as v]
            [com.xebia.visualreview.util :as util]
            [com.xebia.visualreview.persistence :as p]
            [com.xebia.visualreview.analysis.core :as analysis]
            [com.xebia.visualreview.io :as io]
            [slingshot.slingshot :as ex]))

(defn- camelize-response [x]
  (cond
    (vector? x) (mapv camelize-response x)
    (map? x) (reduce (fn [acc [k v]] (conj acc [k (camelize-response v)])) {} ((util/map-keys util/camelize) x))
    :else x))

(defn json-resource [& args]
  (apply liberator.core/resource
         :available-media-types ["application/json"]
         :as-response (fn [d ctx] (representation/as-response (camelize-response d) ctx))
         args))

(defn- tx-conn [ctx]
  (-> ctx :request :tx-conn))
(defn- get-request? [ctx] (= (-> ctx :request :request-method) :get))

(defn- parse-longs [xs] (mapv #(Long/parseLong %) xs))

(defmacro handle-invalid [validation & cs]
  (assert (even? (count cs)))
  `(let [err# (:error ~validation)]
     (case (:subtype err#)
       ~@cs
       (:message err#))))

;;;;;;;;; Projects ;;;;;;;;;;;
(def ^:private project-schema
  {:name [String [::v/non-empty]]})

(defn project-resource [project-name]
  (json-resource
    :allowed-methods [:get :put]
    :processable? (fn [ctx]
                    (or (get-request? ctx)
                        (let [v (v/validations project-schema {:name project-name})]
                          (if (:valid? v)
                            {::project-name project-name}
                            [false {::error-msg (handle-invalid v
                                                  ::v/non-empty "Name can not be empty")}]))))
    :handle-unprocessable-entity ::error-msg
    :exists? (fn [ctx]
               (or (get-request? ctx)
                   (when-let [project-id (p/get-project-by-name (tx-conn ctx) project-name :id)]
                     {::project-id project-id})))
    :conflict? (fn [ctx] (::project-id ctx))
    :handle-conflict (fn [_] (format "A project with name: '%s' already exists." project-name))
    :put! (fn [ctx]
            (let [new-project-id (p/create-project! (tx-conn ctx) project-name)
                  project (p/get-project (tx-conn ctx) new-project-id)]
              (io/create-project-directory! new-project-id)
              {::project project}))
    :handle-created ::project
    :handle-ok (fn [ctx] (p/get-projects (tx-conn ctx)))))
(defn get-project [project-id]
  (json-resource
    :exists? (fn [ctx]
               (try
                 (let [project-id (Long/parseLong project-id)]
                   (when-let [project (p/get-project (tx-conn ctx) project-id)]
                     {::project project}))
                 (catch NumberFormatException _)))
    :handle-ok ::project))

;;;;;;;;;; Suites ;;;;;;;;;;;
(defn suites-resource [project-id]
  (json-resource
    :allowed-methods [:get]
    :exists? (fn [ctx]
               (try
                 (let [[project-id] (parse-longs [project-id])]
                   (and (p/get-project-by-id (tx-conn ctx) project-id)
                        (when-let [suites (p/get-suites (tx-conn ctx) project-id)]
                          {::suites suites})))
                 (catch NumberFormatException _)))
    :handle-ok ::suites))

(defn suite-resource [project-id suite-id]
  (json-resource
    :allowed-methods [:get]
    :exists? (fn [ctx]
               (try
                 (let [[project-id suite-id] (parse-longs [project-id suite-id])]
                   (when-let [suite (p/get-suite (tx-conn ctx) project-id suite-id)]
                     {::suite suite}))
                 (catch NumberFormatException _)))
    :handle-ok ::suite))

;;;;;;;;;;; Runs ;;;;;;;;;;;
(def ^:private run-create-schema
  {:project-name [String []]
   :suite-name   [String [::v/non-empty]]})

(defn run-resource [run-id]
  (json-resource
    :allowed-methods [:get]
    :exists? (fn [ctx]
               (try
                 (let [[run-id] (parse-longs [run-id])]
                   (when-let [run (p/get-run (tx-conn ctx) run-id)]
                     {::run run}))
                 (catch NumberFormatException _)))
    :handle-ok ::run))

(def runs-resource
  (json-resource
    :allowed-methods [:get :post]
    :processable? (fn [ctx]
                    (let [v (v/validations run-create-schema (-> ctx :request :params))]
                      (if (:valid? v)
                        {::data (:data v)}
                        [false {::error-msg (handle-invalid v
                                              ::v/non-empty "Suite name can not be empty")}])))
    :handle-unprocessable-entity ::error-msg
    :exists? (fn [ctx]
               (if (get-request? ctx)
                 (when-let [suite (p/get-suite-by-name (tx-conn ctx) (-> ctx ::data :project-name) (-> ctx ::data :suite-name))]
                   (let [runs (:runs (p/get-suite (tx-conn ctx) (:project-id suite) (:id suite)))]
                     {::runs runs ::suite suite}))
                 (when-let [project-id (p/get-project-by-name (tx-conn ctx) (-> ctx ::data :project-name) :id)]
                   {::project-id project-id})))
    :can-post-to-missing? false
    :post! (fn [ctx]
             (let [new-run-id (p/create-run! (tx-conn ctx) (::data ctx))
                   run (p/get-run (tx-conn ctx) new-run-id)]
               (io/create-run-directory! (::project-id ctx) (:suite-id run) (:id run))
               {::run run}))
    :handle-created ::run
    :handle-ok ::runs))

;;;;;;;;;;; Screenshots ;;;;;;;;;;;;;;
(defn- update-diff-status! [conn {:keys [id before after status]} new-status]
  (case [status new-status]
    (["accepted" "pending"] ["accepted" "rejected"]) (p/set-baseline! conn id after before)
    (["pending" "accepted"] ["rejected" "accepted"]) (p/set-baseline! conn id before after)
    :no-op)
  (p/update-diff-status! conn id new-status))

;; The screenshot resource has been split up into separate handler for each http method.
(def ^:private upload-screenshot-schema
  {:file            [Map [::v/screenshot]]
   :screenshot-name [String []]
   :meta            [Map [::v/screenshot-meta]]})
(defn- update-screenshot-path [screenshot]
  (update-in screenshot [:path] #(str "/screenshots/" % "/" (:id screenshot) ".png")))

(defn get-screenshots [run-id]
  (json-resource
    :allowed-methods [:get]
    :exists? (fn [ctx]
               (try (let [[run-id] (parse-longs [run-id])
                          run (p/get-run (tx-conn ctx) run-id)]
                      (when run {::run run}))
                    (catch NumberFormatException _)))
    :handle-ok (fn [ctx] (let [screenshots (p/get-screenshots (tx-conn ctx) (-> ctx ::run :id))]
                           (mapv update-screenshot-path screenshots)))))

(defn- process-screenshot [conn project-id suite-id run-id screenshot-name path meta {:keys [tempfile size]}]
  (let [screenshot-id (p/save-screenshot! conn run-id screenshot-name path (assoc meta :size size))
        screenshot (p/get-screenshot-by-id conn screenshot-id)
        baseline (p/get-baseline conn suite-id)
        [new-screenshot? baseline-screenshot] (if-let [bs (p/get-baseline-screenshot conn suite-id screenshot-name meta)]
                                                [false bs]
                                                (do
                                                  (p/create-baseline-screenshot! conn (:id baseline) (:id screenshot))
                                                  [true screenshot]))
        analysis (p/get-analysis conn run-id)
        after-file-id (:id screenshot)
        after-file tempfile
        before-file-id (or (:id baseline-screenshot) after-file-id)
        before-file (if (= screenshot baseline-screenshot) after-file (io/get-file (:path baseline-screenshot) (:id baseline-screenshot)))
        diff-report (analysis/diff-report before-file after-file)
        new-diff-id (p/save-diff! conn (str project-id "/" suite-id "/diffs") before-file-id after-file-id (:percentage diff-report) (:id analysis))
        diff (p/get-diff conn run-id new-diff-id)]
    (when (and (not new-screenshot?) (zero? (:percentage diff-report)))
      (update-diff-status! conn diff "accepted"))
    (io/store-screenshot! project-id suite-id run-id (:id screenshot) tempfile)
    (io/store-diff! project-id suite-id new-diff-id (:diff diff-report))
    screenshot))

(defn upload-screenshot [run-id]
  (json-resource
    :allowed-methods [:post]
    :processable? (fn [ctx]
                    (let [v (v/validations upload-screenshot-schema (-> ctx :request :params))]
                      (if (:valid? v)
                        (let [data (:data v)
                              run (p/get-run (tx-conn ctx) (Long/parseLong run-id))]
                          (if (or (= (:status run) "running") (nil? run))
                            {::data data ::run run}
                            [false {::error-msg (format "Run status must be 'running' to upload screenshots. Status is: %s" (:status run))}]))
                        [false {::error-msg (handle-invalid v
                                              ::v/screenshot "'file' is not a valid PNG file"
                                              ::v/screenshot-meta "Invalid meta data. Does it have valid 'os', 'browser', 'version' and 'resolution'?")}])))
    :handle-unprocessable-entity ::error-msg
    :exists? ::run
    :can-post-to-missing? false
    :post! (fn [ctx]
             (ex/try+
               (let [{meta :meta file :file screenshot-name :screenshot-name} (::data ctx)
                     {project-id :project-id suite-id :suite-id run-id :id} (::run ctx)
                     path (apply str (interpose \/ [project-id suite-id run-id]))
                     screenshot (process-screenshot (tx-conn ctx) project-id suite-id run-id screenshot-name path meta file)]
                 {::screenshot screenshot})
               (catch [:subtype ::p/unique-constraint-violation] _
                 {::screenshot {:error "Screenshot name already exists in this run"}})))
    :handle-created ::screenshot))

;; Analysis
(defn screenshots-resource [run-id]
  (fn [req]
    (if (get-request? {:request req})
      (get-screenshots run-id)
      (upload-screenshot run-id))))
(defn- full-path [path id & {:keys [prefix] :or {prefix "/screenshots"}}]
  (str prefix "/" path "/" id ".png"))

(defn- transform-diff [diff]
  {:id         (:id diff)
   :before     {:id             (:before diff)
                :path           (full-path (:before-path diff) (:before diff))
                :size           (:before-size diff)
                :os             (:before-os diff)
                :browser        (:before-browser diff)
                :version        (:before-version diff)
                :screenshotName (:before-name diff)
                :resolution     (:before-resolution diff)}
   :after      {:id             (:after diff)
                :path           (full-path (:after-path diff) (:after diff))
                :size           (:after-size diff)
                :os             (:after-os diff)
                :browser        (:after-browser diff)
                :version        (:after-version diff)
                :screenshotName (:after-name diff)
                :resolution     (:after-resolution diff)}
   :status     (:status diff)
   :percentage (:percentage diff)
   :path       (full-path (:path diff) (:id diff))})

(defn- transform-analysis [full-analysis]
  (update-in full-analysis [:diffs] #(mapv transform-diff %)))

;; Diff Status
(defn analysis-resource [run-id]
  (json-resource
    :allowed-methods [:get]
    :processable? (fn [ctx]
                    (try
                      (let [[run-id] (parse-longs [run-id])
                            analysis (p/get-full-analysis (tx-conn ctx) run-id)]
                        {::analysis analysis})
                      (catch NumberFormatException _)))
    :exists? (fn [ctx] (not (empty? (-> ctx ::analysis :analysis))))
    :handle-ok (comp transform-analysis ::analysis)))

(def ^:private update-diff-status-schema
  {:status [String [::v/diff-status]]})

(defn diff-status-resource [run-id diff-id]
  (json-resource
    :allowed-methods [:post]
    :processable? (fn [ctx]
                    (try
                      (let [[run-id diff-id] (parse-longs [run-id diff-id])
                            v (v/validations update-diff-status-schema (-> ctx :request :params))]
                        (if (:valid? v)
                          {::run-id run-id ::diff-id diff-id ::new-status (-> v :data :status)}
                          [false {::error-msg (handle-invalid v
                                                ::v/diff-status "'status' must be 'pending', 'accepted' or 'rejected'")}]))
                      (catch NumberFormatException _)))
    :handle-unprocessable-entity ::error-msg
    :exists? (fn [ctx]
               (when-let [diff (p/get-diff (tx-conn ctx) (::run-id ctx) (::diff-id ctx))]
                 {::diff diff}))
    :can-post-to-missing? false
    :post! (fn [ctx]
             (update-diff-status! (tx-conn ctx) (::diff ctx) (::new-status ctx))
             {::updated-diff (p/get-diff (tx-conn ctx) (::run-id ctx) (::diff-id ctx))})
    :handle-created ::updated-diff))