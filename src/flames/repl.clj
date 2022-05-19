(ns flames.repl
  (:import java.io.File
           java.net.URLEncoder
           java.awt.Desktop)
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [flames.core :as fcore]
            [org.httpkit.client :as http]))

(defn- url-encode [^String s]
  (URLEncoder/encode s "UTF-8"))

(defn- ->flames-uri [{:keys [host port filter remove]}]
  (str (cond-> "http://"
         host (str host)
         port (str ":" port)
         :always (str "/flames.svg")
         (or filter remove) (str "?")
         filter (str "filter=" (-> filter str url-encode) (when remove "&"))
         remove (str "remove=" (-> remove str url-encode))
         )))

(defn- open-svg [opts]
  (let [{keep-on-exit :keep-svg-on-exit
         method :open-method} opts
        flames-uri (->flames-uri opts)
        {:keys [body status error]} @(http/get flames-uri)]
    (condp = status
      200 (let [outfile (File/createTempFile "flames" ".svg")
            file-uri (-> outfile .toURI)]
            (spit outfile body)

            (println "opening " (str file-uri) " using " method)
            (condp = method
              :xdg-open (let [{:keys [exit out err]} (shell/sh "xdg-open" (str file-uri))]
                          (println (if (zero? exit) out err)))
              :browse (doto (Desktop/getDesktop) (.browse file-uri)))
            (when-not keep-on-exit (println "deleteonexit" outfile)(.deleteOnExit outfile))
            file-uri)
      404 (println "404 - Not Found")
      503 (println "Not available - did you wait for data to be collected (default 5s)?")
      (println "Unknown error:" error))))

(defn- identify-command [m k & cmd]
  (let [{:keys [exit out err]} (try (apply shell/sh cmd)
                                    (catch Throwable t {:exit 255 :err (.getMessage t)}))]
    (assoc m k (str/trim-newline (if (zero? exit) out err)))))

(defn doctor
  "Perform checks that the required executables are installed"
  []
  (-> {}
      (identify-command :perl "perl" "-e" "print $]")
      (identify-command :riemann "riemann" "-v")))

(def flames (atom nil))

(defn flames-start!
  ([] (flames-start! nil))
  ([opts]
   (let [defaults {:host "localhost" :port 54321}
         opts (merge defaults opts)
         {:keys [host port]} opts]
     ;; We resolve explicitly here, to avoid warnings when not working
     ;; with flamegraphs
     (if-not @flames
       (do
         (reset! flames ((requiring-resolve 'flames.core/start!) opts))
         (printf "View flames graph at %s\nIn case of error consult the output of (doctor)\n" (->flames-uri opts)))
       (throw (IllegalStateException. "Stop the existing capture first!"))))))

(defn flames-stop!
  ([] (flames-stop! nil))
  ([opts]
   (when @flames
     (when (:open-method opts)
       (open-svg (merge (:opts @flames) opts)))
     ;; We resolve explicitly here, to avoid warnings when not working
     ;; with flamegraphs
     (reset! flames ((requiring-resolve 'flames.core/stop!) @flames)))))

(defmacro with-flames
  "
  Somewhat like (do ...) but capturing a flamegraph whilst executing
  the body. The first arg is a map of options to be passed to
  flames-stop!

  (with-flames {:host \"localhost\"
                :port 54322
                :open-method :xdg-open}
      (f arg1 arg2)
      (g arg3 arg4))
  "
  [options & forms]
  (let []
    `(try
      (reset! flames# (fcore/flames-start! ~options))
      ~@forms
      (finally (fcore/flames-stop! @flames# ~options)))))
