(ns llm.classifier
  "LLM CLI interface — shell out to configured command,
   pass JSON on stdin, parse JSON response."
  (:require [babashka.process :as p]
            [cheshire.core :as json]))

(defn call-llm
  "Call the LLM CLI with a request map.
   The request is serialized as JSON and passed on stdin.
   Returns the parsed JSON response, or throws on failure.

   Options:
     :command  - the LLM CLI command (string)
     :request  - the request map (will be JSON-encoded)
     :timeout  - timeout in ms (default 60000)"
  [{:keys [command request timeout]
    :or {timeout 60000}}]
  (let [input-json (json/generate-string request)
        proc (p/process {:cmd ["sh" "-c" command]
                         :in input-json
                         :out :string
                         :err :string})
        _ (deref proc timeout nil)
        exit (:exit @proc)]
    (if (zero? exit)
      (let [output (slurp (:out proc))]
        (try
          (json/parse-string output true)
          (catch Exception e
            (throw (ex-info "Failed to parse LLM response as JSON"
                            {:output output
                             :error (.getMessage e)})))))
      (throw (ex-info "LLM CLI failed"
                      {:exit exit
                       :stderr (slurp (:err proc))})))))

(defn llm-from-config
  "Create an LLM function from a config map.
   Config: {:command str}
   Returns a function that takes a request map and returns parsed response."
  [config]
  (fn [request]
    (call-llm {:command (:command config)
               :request request})))

(defn make-edge-discovery-request
  "Build an LLM request for edge discovery.
   hunks: sequence of hunk maps
   existing-edges: sequence of edge maps
   existing-units: sequence of atomic unit maps"
  [hunks existing-edges existing-units]
  {:system "You are a code change classifier. Given a set of diff hunks and an existing dependency graph, discover edges between hunks. Edges are either :depends (A must come before B) or :co-occurs (A and B must be in the same commit). Return JSON."
   :prompt (str "Analyze these hunks and discover dependency and co-occurrence relationships.\n\n"
                "Hunks:\n" (json/generate-string hunks {:pretty true}) "\n\n"
                "Existing edges:\n" (json/generate-string existing-edges {:pretty true}) "\n\n"
                "Existing atomic units:\n" (json/generate-string existing-units {:pretty true}) "\n\n"
                "For each edge, explain WHY these hunks are related.")
   :response_schema {:type "object"
                     :properties
                     {:edges {:type "array"
                              :items {:type "object"
                                      :properties {:from {:type "string"}
                                                   :to {:type "string"}
                                                   :kind {:enum ["depends" "co-occurs"]}
                                                   :reason {:type "string"}}}}
                      :reclassifications {:type "array"
                                          :items {:type "object"
                                                  :properties {:hunk_id {:type "string"}
                                                               :new_unit {:type "string"}
                                                               :reason {:type "string"}}}}}}})

(defn discover-edges
  "Run one round of LLM edge discovery.
   Returns {:edges [...] :reclassifications [...]}."
  [llm-fn hunks existing-edges existing-units]
  (let [request (make-edge-discovery-request hunks existing-edges existing-units)]
    (llm-fn request)))

(defn run-iterative-discovery
  "Run iterative LLM edge discovery until convergence or max-rounds.
   Returns the accumulated edges and units."
  [llm-fn hunks initial-edges initial-units max-rounds]
  (loop [round 1
         edges initial-edges
         units initial-units]
    (if (> round max-rounds)
      {:edges edges :units units :rounds (dec round) :converged false}
      (let [result (discover-edges llm-fn hunks edges units)
            new-edges (concat edges (:edges result))
            reclassifications (:reclassifications result)
            changed? (or (seq (:edges result))
                         (seq reclassifications))]
        (if (not changed?)
          {:edges edges :units units :rounds round :converged true}
          (recur (inc round) new-edges units))))))
