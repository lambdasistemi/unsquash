(ns core.sequencer
  "Flatten a dependency graph ordering into a commit sequence.
   Apply atomic units as git commits, validate each with oracle."
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(defn- git
  "Run a git command in the given directory. Returns stdout or throws."
  [dir & args]
  (let [proc (p/process {:cmd (into ["git"] args)
                         :dir dir
                         :out :string
                         :err :string})
        result @proc]
    (if (zero? (:exit result))
      (str/trim (:out result))
      (throw (ex-info (str "git failed: " (str/join " " args))
                      {:exit (:exit result)
                       :stderr (:err result)})))))

(defn- new-file? [hunk]
  (and (zero? (:old-count hunk))
       (every? #(= :add (:type %)) (filter #(not= :context (:type %)) (:lines hunk)))))

(defn- deleted-file? [hunk]
  (and (zero? (:new-count hunk))
       (every? #(= :remove (:type %)) (filter #(not= :context (:type %)) (:lines hunk)))))

(defn- hunk-body
  "Generate the @@ section for a single hunk (no --- +++ header)."
  [hunk]
  (let [{:keys [old-start old-count new-start new-count lines]} hunk
        header (str "@@ -" old-start "," old-count " +" new-start "," new-count " @@\n")
        body (str/join "\n"
                       (map (fn [line]
                              (case (:type line)
                                :add     (str "+" (:content line))
                                :remove  (str "-" (:content line))
                                :context (str " " (:content line))))
                            lines))]
    (str header body "\n")))

(defn- apply-hunk-lines
  "Generate a full patch string from a single hunk (with --- +++ header)."
  [hunk]
  (let [{:keys [file]} hunk
        from-path (if (new-file? hunk) "/dev/null" (str "a/" file))
        to-path (if (deleted-file? hunk) "/dev/null" (str "b/" file))]
    (str "--- " from-path "\n+++ " to-path "\n" (hunk-body hunk))))

(defn- make-new-file-patch
  "Generate a patch for a completely new file by combining all sub-hunks."
  [file hunks]
  (let [sorted (sort-by :new-start hunks)
        all-adds (mapcat (fn [h]
                           (map :content (filter #(= :add (:type %)) (:lines h))))
                         sorted)
        total-lines (count all-adds)
        body (str/join "\n" (map #(str "+" %) all-adds))]
    (str "diff --git a/" file " b/" file "\n"
         "new file mode 100644\n"
         "--- /dev/null\n"
         "+++ b/" file "\n"
         "@@ -0,0 +1," total-lines " @@\n"
         body "\n")))

(defn- make-patch
  "Generate a full patch string from an atomic unit's hunks."
  [atomic-unit]
  (let [hunks-by-file (group-by :file (:hunks atomic-unit))]
    (str/join "\n"
              (for [[file hunks] hunks-by-file]
                (let [is-new (every? new-file? hunks)
                      is-del (every? deleted-file? hunks)
                      sorted (sort-by :old-start hunks)]
                  (if is-new
                    (make-new-file-patch file hunks)
                    (str "diff --git a/" file " b/" file "\n"
                         (when is-del "deleted file mode 100644\n")
                         "--- " (if is-del "/dev/null" (str "a/" file)) "\n"
                         "+++ " (if is-del "/dev/null" (str "b/" file)) "\n"
                         (str/join "" (map hunk-body sorted)))))))))

(defn apply-atomic-unit
  "Apply an atomic unit as a git commit.
   Returns {:sha str :message str :compiles bool :error str}."
  [unit oracle-fn dir]
  (let [patch (make-patch unit)
        message (str (cond
                       (str/starts-with? (:identity unit) "define:") "feat: "
                       (str/starts-with? (:identity unit) "use:") "refactor: "
                       (str/starts-with? (:identity unit) "removal:") "refactor: "
                       (str/starts-with? (:identity unit) "api-change:") "refactor: "
                       :else "chore: ")
                     (:identity unit))]
    (try
      ;; Write patch to temp file and apply
      (let [patch-file (str dir "/.unsquash-patch.tmp")]
        (spit patch-file patch)
        (git dir "apply" "--allow-empty" patch-file)
        (clojure.java.io/delete-file patch-file true))
      ;; Stage and commit
      (git dir "add" "-A")
      (git dir "commit" "-m" message "--allow-empty")
      ;; Run oracle
      (let [oracle-result (oracle-fn :dir dir)
            sha (git dir "rev-parse" "HEAD")]
        {:sha sha
         :message message
         :compiles (:success oracle-result)
         :error (when-not (:success oracle-result) (:stderr oracle-result))})
      (catch Exception e
        ;; Clean up any partial apply (unstaged new files, etc.)
        (try (git dir "checkout" "--" ".") (catch Exception _ nil))
        (try (git dir "clean" "-fd") (catch Exception _ nil))
        {:sha nil
         :message message
         :compiles false
         :error (str "Failed to apply: " (.getMessage e))}))))

(defn- merge-units
  "Merge two atomic units into one, combining their hunks."
  [unit-a unit-b]
  (let [merged-hunks (clojure.set/union (:hunks unit-a) (:hunks unit-b))
        merged-id (str (:id unit-a) "+" (:id unit-b))
        merged-identity (str (:identity unit-a) "+" (:identity unit-b))]
    (assoc unit-a
           :id merged-id
           :identity merged-identity
           :hunks merged-hunks)))

(defn- find-merge-candidate
  "Find the best unit to merge with the failed unit.
   Prefers units that share files, then the next unit in the remaining ordering."
  [failed-unit remaining-ids graph]
  (let [failed-files (set (map :file (:hunks failed-unit)))]
    ;; First: find a remaining unit that touches the same files
    (or (first (filter (fn [uid]
                         (let [u (get-in graph [:nodes uid])
                               u-files (set (map :file (:hunks u)))]
                           (seq (clojure.set/intersection failed-files u-files))))
                       remaining-ids))
        ;; Fallback: just take the next one
        (first remaining-ids))))

;; --- Retry strategies ---

(defn- apply-merge-strategy
  "Dumb fallback: merge the failed unit with a same-file neighbor."
  [failed-unit failed-result remaining-ids graph]
  (let [candidate-id (find-merge-candidate failed-unit remaining-ids graph)]
    (when candidate-id
      (let [candidate (get-in graph [:nodes candidate-id])]
        {:action :merge
         :merge-with candidate-id
         :reason (str "Merging with " candidate-id " (shares files)")}))))

(defn- handle-merge
  "Execute a merge decision: combine two units, update graph, return new state."
  [unit unit-id candidate-id rest-ids graph]
  (let [candidate (get-in graph [:nodes candidate-id])
        merged (merge-units unit candidate)
        new-nodes (-> (:nodes graph)
                      (dissoc unit-id candidate-id)
                      (assoc (:id merged) merged))
        remap (fn [e]
                (cond-> e
                  (#{unit-id candidate-id} (:from e)) (assoc :from (:id merged))
                  (#{unit-id candidate-id} (:to e)) (assoc :to (:id merged))))
        new-edges (->> (:edges graph)
                       (map remap)
                       (remove #(= (:from %) (:to %)))
                       set)
        new-graph {:nodes new-nodes :edges new-edges}
        new-remaining (into [(:id merged)] (remove #{candidate-id} rest-ids))]
    {:remaining new-remaining :graph new-graph}))

(defn apply-ordering
  "Apply atomic units as commits. On failure, calls retry-fn for diagnosis.
   retry-fn receives context and returns {:action :merge/:skip, ...} or nil.
   Default retry-fn merges with a same-file neighbor."
  [ordering graph oracle-fn dir original-tree-sha
   & {:keys [max-retries retry-fn] :or {max-retries 3}}]
  (let [total-retries (atom 0)
        retry-fn (or retry-fn
                     (fn [{:keys [failed-unit failed-result remaining-ids graph]}]
                       (apply-merge-strategy failed-unit failed-result remaining-ids graph)))]
    (loop [remaining (vec ordering)
           graph graph
           commits []]
      (if (empty? remaining)
        ;; Done
        (let [final-tree (try (git dir "rev-parse" "HEAD^{tree}") (catch Exception _ nil))]
          {:commits commits
           :final-state-matches (= final-tree original-tree-sha)
           :retries @total-retries})
        ;; Try next unit
        (let [unit-id (first remaining)
              unit (get-in graph [:nodes unit-id])
              result (apply-atomic-unit unit oracle-fn dir)
              rest-ids (vec (rest remaining))]
          (if (:compiles result)
            (recur rest-ids graph (conj commits result))
            ;; Failed — roll back and clean up (including untracked new files)
            (do
              (when (:sha result)
                (try (git dir "reset" "--hard" "HEAD~1") (catch Exception _ nil)))
              (try (git dir "clean" "-fd") (catch Exception _ nil))
              (let [decision (when (and (< @total-retries max-retries) (seq rest-ids))
                               (retry-fn {:failed-unit unit
                                          :failed-result result
                                          :remaining-ids rest-ids
                                          :graph graph
                                          :commits-so-far commits}))]
                (cond
                  (= :merge (:action decision))
                  (do (swap! total-retries inc)
                      (let [new-state (handle-merge unit unit-id (:merge-with decision) rest-ids graph)]
                        (recur (:remaining new-state) (:graph new-state) commits)))

                  (= :skip (:action decision))
                  (do (swap! total-retries inc)
                      (recur rest-ids graph (conj commits (assoc result :skipped true))))

                  :else
                  (recur rest-ids graph (conj commits result)))))))))))

(defn apply-ordering-simple
  "Original apply-ordering without retry logic."
  [ordering graph oracle-fn dir original-tree-sha]
  (let [commits (reduce
                 (fn [acc unit-id]
                   (let [unit (get-in graph [:nodes unit-id])
                         result (apply-atomic-unit unit oracle-fn dir)]
                     (conj acc result)))
                 []
                 ordering)
        final-tree (try (git dir "rev-parse" "HEAD^{tree}") (catch Exception _ nil))
        matches (= final-tree original-tree-sha)]
    {:commits commits
     :final-state-matches matches}))

(defn verify-final-state
  "Verify the working tree matches the expected tree SHA (FR-009)."
  [dir expected-tree-sha]
  (let [actual (git dir "rev-parse" "HEAD^{tree}")]
    (= actual expected-tree-sha)))
