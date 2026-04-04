(ns cli
  "CLI entry points for unsquash commands."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [core.diff-parser :as dp]
            [core.hunk-splitter :as hs]
            [core.preflight :as pf]
            [core.graph :as g]
            [core.sequencer :as seq]
            [llm.classifier :as llm]
            [oracle.compile :as oracle]
            [consolidate.smart-squash :as sq]))

;; --- Config ---

(defn load-config
  "Load unsquash.edn config from the current directory."
  []
  (let [f (clojure.java.io/file "unsquash.edn")]
    (if (.exists f)
      (read-string (slurp f))
      {})))

;; --- Git helpers ---

(defn- git [& args]
  (let [result @(p/process {:cmd (into ["git"] args)
                            :out :string
                            :err :string})]
    (str/trim (:out result))))

(defn- get-diff
  "Get diff for a ref or range. Supports --diff-algorithm."
  ([ref] (get-diff ref nil))
  ([ref diff-algorithm]
   (let [algo-flag (when diff-algorithm (str "--diff-algorithm=" diff-algorithm))]
     (if (str/includes? ref "..")
       (if algo-flag
         (git "diff" algo-flag ref)
         (git "diff" ref))
       (if algo-flag
         (git "diff" algo-flag (str ref "~1") ref)
         (git "diff" (str ref "~1") ref))))))

;; --- Analyze (T020) ---

(defn analyze
  "Analyze a commit or range. Build dependency graph."
  [& args]
  (let [opts (apply hash-map args)
        ref (or (get opts "--ref") (get opts :ref) "HEAD")
        config (load-config)
        oracle-cmd (or (get opts "--oracle") (get-in config [:oracle :command]))
        llm-cmd (or (get opts "--llm") (get-in config [:llm :command]))
        diff-algo (or (get opts "--diff-algorithm") (get-in config [:diff :algorithm]))
        diff-text (get-diff ref diff-algo)
        ;; Parse and split
        file-diffs (dp/parse-diff diff-text)
        split-diffs (hs/split-all-hunks file-diffs)
        all-hunks (vec (mapcat (fn [fd]
                                 (mapcat hs/effective-hunks (:hunks fd)))
                               split-diffs))
        ;; Strip whitespace-only hunks (R8)
        clean-hunks (vec (pf/non-whitespace-hunks all-hunks))
        ;; Preflight edge discovery
        preflight-edges (pf/discover-edges clean-hunks)
        ;; Create initial atomic units (one per hunk)
        initial-units (mapv #(g/make-atomic-unit (:id %) (:id %) #{%})
                            clean-hunks)
        ;; Build initial graph
        initial-graph (g/make-graph initial-units (set preflight-edges))
        ;; LLM edge discovery (if LLM configured)
        final-graph (if llm-cmd
                      (let [llm-fn (llm/llm-from-config {:command llm-cmd})
                            max-rounds (get config :max-rounds 4)
                            result (llm/run-iterative-discovery
                                    llm-fn clean-hunks preflight-edges initial-units max-rounds)
                            all-edges (set (map (fn [e]
                                                  (if (string? (:kind e))
                                                    (update e :kind keyword)
                                                    e))
                                                (:edges result)))]
                        (g/make-graph initial-units all-edges))
                      initial-graph)
        ;; Contract co-occurrences
        contracted (g/contract-co-occurrences final-graph)]

    ;; Output
    (let [stats {:total-hunks (count all-hunks)
                 :whitespace-stripped (- (count all-hunks) (count clean-hunks))
                 :preflight-edges (count preflight-edges)
                 :total-atomic-units (count (:nodes contracted))
                 :total-edges (count (:edges contracted))}]
      (println (json/generate-string
                {:graph {:atomic-units (mapv (fn [[id unit]]
                                              {:id id
                                               :identity (:identity unit)
                                               :hunk-count (count (:hunks unit))})
                                            (:nodes contracted))
                         :edges (mapv #(select-keys % [:from :to :kind :reason])
                                      (:edges contracted))
                         :stats stats}}
                {:pretty true}))
      ;; Store graph in atom for propose/apply
      contracted)))

;; --- Propose (T021) ---

(defn propose
  "Compute and display valid orderings for the analyzed graph."
  [& args]
  (let [opts (apply hash-map args)
        max-n (parse-long (or (get opts "--max") "3"))
        ;; For now, re-analyze to get the graph
        ;; In a real implementation, this would load cached state
        ref (or (get opts "--ref") "HEAD")
        config (load-config)
        graph (analyze "--ref" ref
                       "--oracle" (get-in config [:oracle :command] "true")
                       "--llm" (get-in config [:llm :command]))]
    (let [orderings (g/all-toposorts graph max-n)]
      (println "\n--- Valid orderings ---")
      (doseq [[i ordering] (map-indexed vector orderings)]
        (println (str "\n[" i "] "
                      (str/join " → "
                                (map #(:identity (get-in graph [:nodes %])) ordering)))))
      ;; Interactive selection (T032)
      (when (> (count orderings) 1)
        (print "\nChoose ordering [0]: ")
        (flush)
        (let [choice (try (parse-long (str/trim (or (read-line) "0")))
                          (catch Exception _ 0))]
          (println (str "Selected ordering " choice))
          choice)))))

;; --- Consolidate (T027) ---

(defn consolidate
  "Smart-squash a commit range."
  [& args]
  (let [opts (apply hash-map args)
        ref (or (get opts "--ref") "HEAD~5..HEAD")
        config (load-config)
        llm-cmd (or (get opts "--llm") (get-in config [:llm :command]))
        llm-fn (when llm-cmd (llm/llm-from-config {:command llm-cmd}))
        dir "."
        commits (sq/parse-commit-range dir ref)]
    (println (str "Found " (count commits) " commits in " ref))
    (let [groups (if llm-fn
                   (sq/group-commits llm-fn commits)
                   ;; Without LLM, each commit stays alone
                   (mapv (fn [c] {:shas [(:sha c)]
                                  :reason "standalone"
                                  :proposed_message (:message c)})
                         commits))]
      (println (json/generate-string
                {:groups groups
                 :original-count (count commits)
                 :proposed-count (count groups)}
                {:pretty true}))
      groups)))

;; --- Apply (T022) ---

(defn apply-ordering
  "Apply a chosen ordering, creating commits."
  [& args]
  (let [opts (apply hash-map args)
        ordering-idx (parse-long (or (get opts "--ordering") "0"))
        ref (or (get opts "--ref") "HEAD")
        config (load-config)
        oracle-cmd (or (get opts "--oracle") (get-in config [:oracle :command] "true"))
        oracle-fn (oracle/oracle-from-config {:command oracle-cmd})
        dir "."
        ;; Get the tree SHA of the target state before we start
        original-tree (git "rev-parse" (str ref "^{tree}"))
        ;; Reset to before the commit(s) to replay
        _ (git "reset" "--hard" (str ref "~1"))
        ;; Re-analyze
        graph (analyze "--ref" ref "--oracle" oracle-cmd)
        orderings (g/all-toposorts graph 10)
        ordering (nth orderings ordering-idx)]
    (let [result (seq/apply-ordering ordering graph oracle-fn dir original-tree)]
      (println (json/generate-string result {:pretty true}))
      result)))
