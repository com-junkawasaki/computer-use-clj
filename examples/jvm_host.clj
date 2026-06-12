(ns jvm-host
  "JVM host capabilities for the model adapters: an :http-fn and JSON
  via clojure.data.json. Injected into openai-model / anthropic-model so
  the portable .cljc libs do no I/O themselves.

  http-fn shells out to `curl`. java.net.http POSTs to Ollama's
  OpenAI-compat /v1 endpoint were rejected with a misleading 404
  \"model not found\" (byte-identical bodies succeed via curl, fail via
  java.net.http regardless of HTTP version / User-Agent / Accept — an
  Ollama request-framing quirk). curl is the reliable transport here."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]))

(defn http-fn [{:keys [url method headers body]}]
  (let [hdr-args (mapcat (fn [[k v]] ["-H" (str k ": " v)]) headers)
        ;; body via stdin (--data-binary @-) to avoid arg-length limits on
        ;; large screenshot payloads
        args (concat ["curl" "-sS" "-m" "300"
                      "-X" (-> method name clojure.string/upper-case)
                      "-w" "\n%{http_code}"]
                     hdr-args
                     (when body ["--data-binary" "@-"])
                     [url])
        pb (doto (ProcessBuilder. ^java.util.List (vec args))
             (.redirectErrorStream false))
        p (.start pb)]
    (when body
      (with-open [os (.getOutputStream p)]
        (.write os (.getBytes ^String body "UTF-8"))))
    (let [out (slurp (.getInputStream p))
          _ (.waitFor p)
          idx (.lastIndexOf out "\n")
          status (try (Integer/parseInt (subs out (inc idx))) (catch Exception _ 0))]
      {:status status :body (if (pos? idx) (subs out 0 idx) "")})))

(defn json-write [m] (json/write-str m))
(defn json-read [s] (json/read-str s :key-fn keyword))
