(ns figwheel.core
  (:require
   [cljs.compiler]
   [compojure.route :as route]
   [compojure.core :refer [routes GET]]
   [ring.util.response :refer [resource-response]]
   [ring.middleware.cors :as cors]
   [org.httpkit.server :refer [run-server with-channel on-close on-receive send! open?]]
   [watchtower.core :as wt :refer [watcher compile-watcher watcher* ignore-dotfiles file-filter extensions]]
   [clojure.core.async :refer [go-loop <!! <! chan put! sliding-buffer timeout]]
   [clojure.string :as string]
   [clojure.java.io :refer [as-file] :as io]
   [digest]
   [clj-stacktrace.core :refer [parse-exception]]
   [clj-stacktrace.repl :refer [pst-on]]
   [clojure.pprint :as p]))

;; get rid of fs dependancy

(defn split-ext
  "Returns a vector of `[name extension]`."
  [path]
  (let [base (.getName (io/file path))
        i (.lastIndexOf base ".")]
    (if (pos? i)
      [(subs base 0 i) (subs base i)]
      [base nil])))

(defn setup-file-change-sender [{:keys [file-change-atom compile-wait-time] :as server-state}
                                wschannel]
  (let [watch-key (keyword (gensym "message-watch-"))]
    (add-watch file-change-atom
               watch-key
               (fn [_ _ o n]
                 (let [msg (first n)]
                   (when msg
                     (<!! (timeout compile-wait-time))
                     (when (open? wschannel)
                       (send! wschannel (prn-str msg)))))))
    
    (on-close wschannel (fn [status]
                          (remove-watch file-change-atom watch-key)
                          (println "Figwheel: client disconnected " status)))
    
    ;; Keep alive!!
    (go-loop []
             (<! (timeout 5000))
             (when (open? wschannel)
               (send! wschannel (prn-str {:msg-name :ping}))
               (recur)))))

(defn reload-handler [server-state]
  (fn [request]
    (with-channel request channel
      (setup-file-change-sender server-state channel))))

(defn server
  "This is the server. It is complected and its OK. Its trying to be a basic devel server and
   also provides the figwheel websocket connection."
  [{:keys [ring-handler server-port http-server-root ring-handler] :as server-state}]
  (-> (routes
       (GET "/figwheel-ws" [] (reload-handler server-state))
       (route/resources "/" {:root http-server-root})
       (or ring-handler (fn [r] false))
       (GET "/" [] (resource-response "index.html" {:root http-server-root}))
       (route/not-found "<h1>Page not found</h1>"))
      ;; adding cors to support @font-face which has a strange cors error
      ;; super promiscuous please don't uses figwheel as a production server :)
      (cors/wrap-cors
       :access-control-allow-origin #".*"
       :access-control-allow-methods [:head :options :get])
      (run-server {:port server-port})))

(defn append-msg [q msg] (conj (take 30 q) msg))

(defn send-changed-files
  "Formats and sends a files-changed message to the file-change-atom.
   Also reports this event to the console."
  [{:keys [file-change-atom] :as st} files]
  (swap! file-change-atom append-msg { ;; this message name isn't good
                                       :msg-name :files-changed 
                                       :files files })
  (doseq [f files]
         (println "notifying browser that file changed: " (:file f))))

(defn underscore [s] (string/replace s "-" "_"))
(defn ns-to-path [nm] (string/replace nm "." "/"))
(defn norm-path
  "Normalize paths to a forward slash separator to fix windows paths"
  [p] (string/replace p  "\\" "/"))

(defn get-ns-from-source-file-path
  "Takes a project relative file path and returns an underscored clojure namespace.
  .ie a file that starts with (ns example.path-finder) -> example.path_finder"
  [file-path]
  (try
    (when (.exists (as-file file-path))
      (with-open [rdr (io/reader file-path)]
        (-> (java.io.PushbackReader. rdr)
            read
            second
            name
            underscore)))
    (catch java.lang.RuntimeException e
      nil)))

(defn get-changed-source-file-paths
  "Provided old-mtimes and new-mtimes are maps of file paths to mtimes this function
   returns collection of files that have changed grouped by their file extentions.
   .ie { :cljs [ ... changed cljs file paths ... ] }"
  [old-mtimes new-mtimes]
  (group-by
   #(keyword (subs (second (split-ext %)) 1))
   (filter
    #(not= (get new-mtimes %)
           (get old-mtimes %))
    (set (keep identity
               (mapcat keys [old-mtimes new-mtimes]))))))

(defn get-changed-source-file-ns [old-mtimes new-mtimes]
  "Returns a list of clojure namespaces that have changed. If a .clj source file has changed
   this returns all namespaces."
  (let [changed-source-file-paths (get-changed-source-file-paths old-mtimes new-mtimes)]
    (set (keep get-ns-from-source-file-path
               (if (not-empty (:clj changed-source-file-paths))
                 ;; we have to send all files if a macro changes right?
                 ;; need to test this
                 (filter #(= ".cljs" (second (split-ext %)))
                         (keys new-mtimes))
                 (:cljs changed-source-file-paths))))))

(defn resource-paths [{:keys [root resource-paths]}]
  (mapv #(string/replace-first (norm-path %)
                               (str (norm-path root) "/") "") resource-paths))

(defn resource-paths-pattern-str [state]
  (str "(" (string/join "|" (resource-paths state)) ")/"
       (:http-server-root state)))

(def resource-paths-pattern (comp re-pattern resource-paths-pattern-str))

(defn server-relative-root-path [state]
  (string/replace-first
   (:output-dir state)
   (resource-paths-pattern state) ""))

(defn make-server-relative-path
  "Given the state and a namespace makes a path relative to the server root.
  This is a path that a client can request. A caveat is that only works on
  compiled javascript namespaces."
  [state nm]
  (str
   (server-relative-root-path state)
   "/" (ns-to-path nm) ".js"))

(defn file-changed?
  "Standard md5 check to see if a file actually changed."
  [{:keys [file-md5-atom]} filepath]  
  (let [file (as-file filepath)]
    (when (.exists file)
      (let [check-sum (digest/md5 file)
            changed? (not= (get @file-md5-atom filepath)
                           check-sum)]
        (swap! file-md5-atom assoc filepath check-sum)
        changed?))))

(defn dependency-files [{:keys [output-to output-dir]}]
   [output-to (str output-dir "/goog/deps.js")])

(defn get-dependency-files
  "Handling dependency files is different they don't have namespaces and their mtimes
   change on every compile even though their content doesn't. So we only want to include them
   when they change. This returns map representations that are ready to be sent to the client."
  [st]
  (keep
   #(when (file-changed? st %)
      { :dependency-file true
        :file (string/replace-first % (resource-paths-pattern st) "") })
   (dependency-files st)))

(defn make-sendable-file
  "Formats a namespace into a map that is ready to be sent to the client."
  [st nm]
  { :file (make-server-relative-path st nm)
    :namespace (cljs.compiler/munge nm) })

;; I would love to just check the compiled javascript files to see if
;; they changed and then just send them to the browser. There is a
;; great simplicity to that strategy. But unfortunately we can't speak
;; for the reloadability of 3rd party libraries. For this reason I am
;; only realoding files that are in the scope of the current project.

;; I also treat the 'goog.addDependancy' files as a different case.
;; These are checked for explicit changes and sent only when their
;; content changes.

;; This is the main API it is currently highly influenced by cljsbuild
;; expect this to change soon

;; after reading finally reading the cljsbuild source code, it is
;; obvious that I was doing way to much work here.

(defn check-for-changes
  "This is the main api it should be called when a compile run has completed.
   It takes the current state of the system and a couple of mtime maps of the form
   { file-path -> mtime }.
   If changed have occured a message is appended to the :file-change-atom in state.
   Consumers of this info can add a listener to the :file-change-atom."
  [state old-mtimes new-mtimes]
  (->> (get-changed-source-file-ns old-mtimes new-mtimes) 
       (map (partial make-sendable-file state))
       (concat (get-dependency-files state))
       (send-changed-files state)))

;; css changes

;; watchtower css file change detection
(defn compile-css-filewatcher [{:keys [css-dirs] :as server-state}]
  (compile-watcher (-> css-dirs
                       (watcher*)
                       (file-filter ignore-dotfiles)
                       (file-filter (extensions :css)))))

(defn get-changed-css-files [{:keys [last-pass css-last-pass] :as state}]
  ;; this uses watchtower change detection
  (binding [wt/*last-pass* css-last-pass]
    (let [{:keys [updated?]} (compile-css-filewatcher state)]
      (map (fn [x] (.getPath x)) (updated?)))))

(defn make-server-relative-css-path [state nm]
  (string/replace-first nm (resource-paths-pattern state) ""))

(defn make-css-file [state path]
  { :file (make-server-relative-css-path state path)
    :type :css } )

(defn send-css-files [{:keys [file-change-atom]} files]
  (swap! file-change-atom append-msg { :msg-name :css-files-changed
                                      :files files})
  (doseq [f files]
    (println "sending changed CSS file:" (:file f))))

(defn check-for-css-changes [state]
  (when (:css-dirs state)
    (let [changed-css-files (get-changed-css-files state)]
      (when (not-empty changed-css-files)
        (send-css-files state (map (partial make-css-file state) 
                                   changed-css-files))))))

;; end css changes

;; compile error occured

(defn compile-error-occured [{:keys [file-change-atom]} exception]
  (let [parsed-exception (parse-exception exception)
        formatted-exception (let [out (java.io.ByteArrayOutputStream.)]
                              (pst-on (io/writer out) false exception)
                              (.toString out))]
      (swap! file-change-atom append-msg { :msg-name :compile-failed
                                           :exception-data parsed-exception
                                           :formatted-exception formatted-exception })))

(defn compile-warning-occured [{:keys [file-change-atom]} message]
  (swap! file-change-atom append-msg { :msg-name :compile-warning :message message }))

(defn initial-check-sums [state]
  (doseq [df (dependency-files state)]
    (file-changed? state df))
  (:file-md5-atom state))

(defn create-initial-state [{:keys [root resource-paths
                                    js-dirs css-dirs ring-handler http-server-root
                                    server-port output-dir output-to]}]
  { :root root
    :resource-paths resource-paths
    :css-dirs css-dirs
    :js-dirs js-dirs
    :http-server-root (or http-server-root "public")
    :output-dir output-dir
    :output-to output-to
    :ring-handler ring-handler
    :server-port (or server-port 3449)
    :css-last-pass (atom (System/currentTimeMillis))   
    :compile-wait-time 10
    :file-md5-atom (initial-check-sums {:output-to output-to
                                        :output-dir output-dir
                                        :file-md5-atom (atom {})})
    :file-change-atom (atom (list))})

(defn resolve-ring-handler [{:keys [ring-handler] :as opts}]
  (when ring-handler (require (symbol (namespace (symbol ring-handler)))))
  (assoc opts :ring-handler
         (when ring-handler
           (eval (symbol ring-handler)))))

(defn start-server [opts]
  (let [state (create-initial-state (resolve-ring-handler opts))]
    (println (str "Figwheel: Starting server at http://localhost:" (:server-port state)))
    (println (str "Figwheel: Serving files from '" (resource-paths-pattern-str state) "'"))
    (assoc state :http-server (server state))))

(defn stop-server [{:keys [http-server]}]
  (http-server))
