(ns fetch.core
  (:require [clojure.walk :refer [stringify-keys]]
            [org.httpkit.client :as http]
            [taoensso.timbre :as timbre]
            [clojure.data.json :as json]))

(def default-important-headers ["authorization"])

(defn- default-headers [req important-headers]
  (-> (:headers req)
      (select-keys important-headers)
      (merge {"content-type" "application/json"})
      stringify-keys))

(defn- merge-request [options orig-req important-headers]
  (update options
    :headers
    merge
    (default-headers orig-req important-headers)))

(defn- send-request [req]
  (timbre/debug "Fetch - Sending: " req)
  (http/request req))

(defn- process-response [resp]
  (timbre/debug "Trying to parse http response: " resp)
  (if (and (string? (:body resp))
           (not-empty (:body resp))
           (re-find #"application/json" (-> resp :headers :content-type)))
    (update resp :body json/read-str :key-fn keyword)
    resp))

(defn- handle-error
  [{:keys [status error]
    {:keys [method url]} :opts}]
  (do (timbre/error "Failed to send " method " to " url " because of "
                    (or error (str "status: " status)))
      (throw (ex-info (str "Unable to send " method " to " url)
                      {:type   ::fetch
                       :url    url
                       :status status
                       :error  error}))))

(defn- fetch
  "executes a http request, reuses authorization header from incoming request"
  [options important-headers orig-req]
  (let [req (merge-request options orig-req important-headers)
        {:keys [status error] :as resp} @(send-request req)]
    ;by default http-kit handles redirects for you.
    (if (or error (not (<= 200 status 399)))
      (handle-error resp)
      (process-response resp))))

;; definitions are good both for compiling, and also for docstring lookup in the editor
(defn ^:dynamic get
  "Does async GET on url and passes all the relevant headers along" [url])
(defn ^:dynamic post
  "Does async POST on url and passes all the relevant headers along" [url body])
(defn ^:dynamic request
  "Does an async request and merges all the relevant headers along" [url options])
(defn ^:dynamic get-sync
  "Does sync GET on url and passes all the relevant headers along" [url])
(defn ^:dynamic post-sync
  "Does sync POST on url and passes all the relevant headers along" [url body])
(defn ^:dynamic request-sync
  "Does a sync request and merges all the relevant headers along" [url options])

(defn wrap-add-fetch
  "Middleware that lets you call fetch/get or fetch/post
  passing on headers to the subservice"
  ([handler]
   (wrap-add-fetch default-important-headers))
  ([handler important-headers]
   (fn [req]
     ;; disable dynamic binding if midje tests redefine get or post
     ;; could maybe be more targeted but likely this covers most cases
     (let [midje-background-fakes (-> *ns* meta :midje/background-fakes first)]
       (if (some #{#'get #'post #'get-sync #'post-sync #'request #'request-sync}
                 (map :var midje-background-fakes))
         (handler req)
         (binding [get          (fn [url] (future (get-sync url)))
                   post         (fn [url body] (future (post-sync url body)))
                   request      (fn [options]
                                  (future (request-sync options)))
                   get-sync     (fn [url]
                                  (fetch {:url url :method :get}
                                         important-headers
                                         req))
                   post-sync    (fn [url body]
                                  (fetch {:url url :method :post :body body}
                                         important-headers
                                         req))
                   request-sync (fn [options]
                                  (fetch options req))]
           (handler req)))))))
