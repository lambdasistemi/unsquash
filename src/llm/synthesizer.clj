(ns llm.synthesizer
  "LLM intermediate state synthesis — for multi-edge hunks,
   rewrite as sequential changes (FR-007)."
  (:require [cheshire.core :as json]))

(defn make-synthesis-request
  "Build an LLM request for intermediate state synthesis."
  [hunk unit-a unit-b order]
  {:system "You are a code rewriter. Given a diff hunk that belongs to two atomic units, rewrite it as two sequential changes. The first change satisfies the first unit, the second satisfies the second. The final state after both changes must be identical to the original hunk."
   :prompt (str "This hunk belongs to two atomic units. Rewrite it as two sequential changes.\n\n"
                "Hunk:\n" (json/generate-string hunk {:pretty true}) "\n\n"
                "Unit A: " (:identity unit-a) " — " (:reason unit-a "first concern") "\n"
                "Unit B: " (:identity unit-b) " — " (:reason unit-b "second concern") "\n"
                "Order: " order "\n\n"
                "Return two diffs: hunk_a (applied first) and hunk_b (applied second).")
   :response_schema {:type "object"
                     :properties
                     {:hunk_a {:type "string" :description "diff for unit_a (applied first)"}
                      :hunk_b {:type "string" :description "diff for unit_b (applied second)"}
                      :explanation {:type "string"}}}})

(defn synthesize-intermediate
  "Ask the LLM to split a hunk into two sequential changes."
  [llm-fn hunk unit-a unit-b order]
  (let [request (make-synthesis-request hunk unit-a unit-b order)]
    (llm-fn request)))
