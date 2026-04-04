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

;; --- Tool definitions ---

(def tools
  [{:name "unsquash-analyze"
    :description "Build a dependency graph from a commit or commit range. Returns atomic units and edges."
    :inputSchema {:type "object"
                  :properties {:ref {:type "string"
                                     :description "Git ref or range (e.g. HEAD, HEAD~3..HEAD, main..feature)"}
                               :oracle_command {:type "string"
                                                :description "Compile oracle command (e.g. 'cabal build all -O0')"}
                               :llm_command {:type "string"
                                             :description "LLM CLI command"}}
                  :required ["ref"]}}
   {:name "unsquash-propose"
    :description "Compute valid topological orderings for an analyzed graph."
    :inputSchema {:type "object"
                  :properties {:ref {:type "string" :description "Git ref to analyze"}
                               :max_orderings {:type "integer"
                                               :description "Maximum orderings to generate (default 3)"}}
                  :required ["ref"]}}
   {:name "unsquash-apply"
    :description "Apply a chosen ordering, creating commits on the current branch."
    :inputSchema {:type "object"
                  :properties {:ref {:type "string" :description "Git ref to unfold"}
                               :ordering_index {:type "integer"
                                                :description "Index of the ordering to apply (from propose)"}}
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

(defn handle-tool-call [name arguments]
  (case name
    "unsquash-analyze"
    (let [{:keys [ref oracle_command llm_command]} arguments]
      (require '[cli])
      (let [graph ((resolve 'cli/analyze)
                   "--ref" (or ref "HEAD")
                   "--oracle" (or oracle_command "true")
                   "--llm" llm_command)]
        {:content [{:type "text"
                    :text (json/generate-string
                           {:atomic_units (count (:nodes graph))
                            :edges (count (:edges graph))
                            :node_ids (vec (keys (:nodes graph)))}
                           {:pretty true})}]}))

    "unsquash-propose"
    (let [{:keys [ref max_orderings]} arguments
          max-n (or max_orderings 3)]
      (require '[cli] '[core.graph])
      (let [graph ((resolve 'cli/analyze) "--ref" (or ref "HEAD"))
            orderings ((resolve 'core.graph/all-toposorts) graph max-n)]
        {:content [{:type "text"
                    :text (json/generate-string
                           {:orderings
                            (mapv (fn [ordering]
                                    {:sequence ordering
                                     :units (mapv #(:identity (get-in graph [:nodes %])) ordering)})
                                  orderings)}
                           {:pretty true})}]}))

    "unsquash-apply"
    (let [{:keys [ref ordering_index]} arguments]
      (require '[cli])
      (let [result ((resolve 'cli/apply-ordering)
                    "--ref" (or ref "HEAD")
                    "--ordering" (str (or ordering_index 0)))]
        {:content [{:type "text"
                    :text (json/generate-string result {:pretty true})}]}))

    "unsquash-consolidate"
    (let [{:keys [ref llm_command]} arguments]
      (require '[consolidate.smart-squash :as sq] '[llm.classifier :as llm-c])
      (let [llm-fn (when llm_command ((resolve 'llm-c/llm-from-config) {:command llm_command}))
            commits ((resolve 'sq/parse-commit-range) "." (or ref "HEAD~5..HEAD"))
            groups (if llm-fn
                     ((resolve 'sq/group-commits) llm-fn commits)
                     ;; Without LLM, each commit is its own group
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
    (throw (ex-info (str "Unknown tool: " name) {:name name}))))

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
                                      :version "0.1.0"}})

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
