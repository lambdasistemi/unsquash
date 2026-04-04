(ns mcp.server
  "MCP server — stdio JSON-RPC transport.
   Exposes unsquash tools for Claude Code integration."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

;; --- JSON-RPC helpers ---

(defn- send-response [id result]
  (println (json/generate-string {:jsonrpc "2.0" :id id :result result}))
  (flush))

(defn- send-error [id code message]
  (println (json/generate-string {:jsonrpc "2.0" :id id
                                  :error {:code code :message message}}))
  (flush))

;; --- Config loading ---

(defn- load-config
  "Load unsquash.edn from the current directory."
  []
  (let [f (clojure.java.io/file "unsquash.edn")]
    (if (.exists f)
      (read-string (slurp f))
      {})))

;; --- Tool definitions ---

(def tools
  [{:name "unsquash-analyze"
    :description "Build a dependency graph from a commit or commit range. Returns atomic units, edges, and stats."
    :inputSchema {:type "object"
                  :properties {:ref {:type "string"
                                     :description "Git ref or range (e.g. HEAD, HEAD~3..HEAD, main..feature)"}
                               :oracle_command {:type "string"
                                                :description "Compile oracle command (e.g. 'cabal build all -O0')"}
                               :llm_command {:type "string"
                                             :description "LLM CLI command"}
                               :diff_algorithm {:type "string"
                                                :description "Diff algorithm: patience, histogram, or myers"}}
                  :required ["ref"]}}
   {:name "unsquash-propose"
    :description "Compute valid topological orderings with commit messages for each unit."
    :inputSchema {:type "object"
                  :properties {:ref {:type "string" :description "Git ref to analyze"}
                               :max_orderings {:type "integer"
                                               :description "Maximum orderings to generate (default 3)"}
                               :oracle_command {:type "string"
                                                :description "Compile oracle command"}
                               :llm_command {:type "string"
                                             :description "LLM CLI command"}
                               :diff_algorithm {:type "string"
                                                :description "Diff algorithm: patience, histogram, or myers"}}
                  :required ["ref"]}}
   {:name "unsquash-apply"
    :description "Apply a chosen ordering, creating compile-validated commits on the current branch."
    :inputSchema {:type "object"
                  :properties {:ref {:type "string" :description "Git ref to unfold"}
                               :ordering_index {:type "integer"
                                                :description "Index of the ordering to apply (from propose)"}
                               :max_retries {:type "integer"
                                             :description "Maximum retry attempts on oracle failure (default 3)"}}
                  :required ["ref" "ordering_index"]}}
   {:name "unsquash-consolidate"
    :description "Phase 1: Smart-squash related commits in a range, preserving order."
    :inputSchema {:type "object"
                  :properties {:ref {:type "string"
                                     :description "Git ref range (e.g. main..feature)"}
                               :llm_command {:type "string"
                                             :description "LLM CLI command"}}
                  :required ["ref"]}}])

;; --- Tool handlers ---

(defn- build-analyze-args
  "Build CLI args for analyze from tool arguments and config."
  [arguments config]
  (let [{:keys [ref oracle_command llm_command diff_algorithm]} arguments]
    (cond-> ["--ref" (or ref "HEAD")]
      (or oracle_command (get-in config [:oracle :command]))
      (into ["--oracle" (or oracle_command (get-in config [:oracle :command]))])

      (or llm_command (get-in config [:llm :command]))
      (into ["--llm" (or llm_command (get-in config [:llm :command]))])

      (or diff_algorithm (get-in config [:diff :algorithm]))
      (into ["--diff-algorithm" (or diff_algorithm (get-in config [:diff :algorithm]))]))))

(defn handle-tool-call [name arguments]
  (let [config (load-config)]
    (case name
      "unsquash-analyze"
      (do
        (require '[cli])
        (let [args (build-analyze-args arguments config)
              graph (apply (resolve 'cli/analyze) args)]
          {:content [{:type "text"
                      :text (json/generate-string
                             {:atomic_units (count (:nodes graph))
                              :edges (count (:edges graph))
                              :node_ids (vec (keys (:nodes graph)))
                              :units (mapv (fn [[id unit]]
                                             {:id id
                                              :identity (:identity unit)
                                              :hunk_count (count (:hunks unit))})
                                           (:nodes graph))}
                             {:pretty true})}]}))

      "unsquash-propose"
      (do
        (require '[cli] '[core.graph] '[core.message])
        (let [args (build-analyze-args arguments config)
              graph (apply (resolve 'cli/analyze) args)
              max-n (or (:max_orderings arguments) 3)
              orderings ((resolve 'core.graph/all-toposorts) graph max-n)]
          {:content [{:type "text"
                      :text (json/generate-string
                             {:orderings
                              (mapv (fn [ordering]
                                      {:sequence ordering
                                       :units (mapv #(:identity (get-in graph [:nodes %])) ordering)
                                       :messages (mapv (fn [id]
                                                         (let [unit (get-in graph [:nodes id])]
                                                           ((resolve 'core.message/generate-message)
                                                            (vec (:hunks unit))
                                                            (:identity unit))))
                                                       ordering)})
                                    orderings)}
                             {:pretty true})}]}))

      "unsquash-apply"
      (do
        (require '[cli] '[core.graph] '[core.sequencer] '[oracle.compile] '[llm.classifier])
        (let [ref (or (:ref arguments) "HEAD")
              ordering-idx (or (:ordering_index arguments) 0)
              max-retries (or (:max_retries arguments) 3)
              oracle-cmd (or (get-in config [:oracle :command]) "true")
              oracle-fn ((resolve 'oracle.compile/oracle-from-config) {:command oracle-cmd})
              llm-cmd (get-in config [:llm :command])
              llm-fn (when llm-cmd ((resolve 'llm.classifier/llm-from-config) {:command llm-cmd}))
              dir "."
              ;; Get the tree SHA of the target state
              original-tree (str/trim (:out @(babashka.process/process
                                              {:cmd ["git" "rev-parse" (str ref "^{tree}")]
                                               :out :string})))
              ;; Reset to before the commit(s)
              _ @(babashka.process/process {:cmd ["git" "reset" "--hard" (str ref "~1")]})
              ;; Analyze
              args (build-analyze-args arguments config)
              graph (apply (resolve 'cli/analyze) args)
              orderings ((resolve 'core.graph/all-toposorts) graph 10)
              ordering (nth orderings ordering-idx)
              ;; Apply with retry logic and LLM messages
              result ((resolve 'core.sequencer/apply-ordering)
                      ordering graph oracle-fn dir original-tree
                      :max-retries max-retries
                      :llm-fn llm-fn)]
          {:content [{:type "text"
                      :text (json/generate-string result {:pretty true})}]}))

      "unsquash-consolidate"
      (let [{:keys [ref llm_command]} arguments]
        (require '[consolidate.smart-squash :as sq] '[llm.classifier :as llm-c])
        (let [llm-cmd (or llm_command (get-in config [:llm :command]))
              llm-fn (when llm-cmd ((resolve 'llm-c/llm-from-config) {:command llm-cmd}))
              commits ((resolve 'sq/parse-commit-range) "." (or ref "HEAD~5..HEAD"))
              groups (if llm-fn
                       ((resolve 'sq/group-commits) llm-fn commits)
                       (mapv (fn [c] {:shas [(:sha c)]
                                      :reason "no LLM"
                                      :proposed_message (:message c)})
                             commits))]
          {:content [{:type "text"
                      :text (json/generate-string
                             {:groups groups
                              :original_count (count commits)
                              :proposed_count (count groups)}
                             {:pretty true})}]}))

      ;; Unknown tool
      (throw (ex-info (str "Unknown tool: " name) {:name name})))))

;; --- MCP protocol ---

(defn handle-request [msg]
  (let [method (:method msg)
        id (:id msg)
        params (:params msg)]
    (case method
      "initialize"
      (send-response id {:protocolVersion "2024-11-05"
                         :capabilities {:tools {}}
                         :serverInfo {:name "unsquash"
                                      :version "0.2.0"}})

      "notifications/initialized"
      nil ;; no response needed

      "tools/list"
      (send-response id {:tools tools})

      "tools/call"
      (try
        (let [result (handle-tool-call (:name params) (:arguments params))]
          (send-response id result))
        (catch Exception e
          (send-error id -32603 (.getMessage e))))

      ;; Unknown method
      (send-error id -32601 (str "Unknown method: " method)))))

;; --- Main loop ---

(defn start
  "Start the MCP server on stdio."
  [& _args]
  (binding [*in* (clojure.java.io/reader System/in)]
    (loop []
      (when-let [line (try (read-line) (catch Exception _ nil))]
        (when-not (str/blank? line)
          (try
            (let [msg (json/parse-string line true)]
              (handle-request msg))
            (catch Exception e
              (send-error nil -32700 (str "Parse error: " (.getMessage e))))))
        (recur)))))
