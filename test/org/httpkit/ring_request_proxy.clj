(ns org.httpkit.ring-request-proxy
  (:require [clj-http.client :as clj-http]))

;;
;; code from: https://github.com/FundingCircle/ring-request-proxy
;; Copyright (c) 2015, Funding Circle
;; All rights reserved.
;;
;; Redistribution and use in source and binary forms, with or without modification,
;; are permitted provided that the following conditions are met:
;;
;; 1. Redistributions of source code must retain the above copyright notice, this
;;    list of conditions and the following disclaimer.
;;
;; 2. Redistributions in binary form must reproduce the above copyright notice,
;;    this list of conditions and the following disclaimer in the documentation
;;    and/or other materials provided with the distribution.
;;
;; 3. Neither the name of the copyright holder nor the names of its contributors
;;    may be used to endorse or promote products derived from this software without
;;    specific prior written permission.
;;
;; THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
;; ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
;; WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
;; IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
;; INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
;; BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
;; DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
;; LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
;; OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
;; ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

(def ^:private not-found-response {:status 404
                                   :body "{\"message\":\"Not found\"}"})

(defn- build-url [host path query-string]
  (println host path query-string)
  (let [url (.toString (java.net.URL. (java.net.URL. host) path))]
    (if (not-empty query-string)
      (str url "?" query-string)
      url)))

(defn- handle-not-found [request]
  not-found-response)

(defn- create-proxy-fn [handler opts]
  (let [identifier-fn (get opts :identifier-fn identity)
        server-mapping (get opts :host-fn {})
        insecure (get opts :allow-insecure-ssl false)]
    (fn [request]
      (println request)
      (let [request-key (identifier-fn request)
            host (server-mapping request-key)
            stripped-headers (dissoc (:headers request) "content-length")
            method (:request-method request)]
        (cond
          (= method :connect)
          {:status 200}

          host
          (select-keys (clj-http/request {:url              (build-url host (:uri request) (:query-string request))
                                          :method           method
                                          :body             (:body request)
                                          :headers          stripped-headers
                                          :throw-exceptions false
                                          :as               :stream
                                          :insecure?        insecure})
                       [:status :headers :body])

          :else
          (handler request))))))

(defn proxy-request
  ([opts]
   (proxy-request handle-not-found opts))
  ([handler opts]
   (create-proxy-fn handler opts)))
