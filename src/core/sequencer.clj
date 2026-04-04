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
                         :err :string})]
    (deref proc 30000 nil)
    (let [exit (:exit @proc)]
      (if (zero? exit)
        (str/trim (slurp (:out proc)))
        (throw (ex-info (str "git failed: " (str/join " " args))
                        {:exit exit
                         :stderr (slurp (:err proc))}))))))

(defn- apply-hunk-lines
  "Generate a patch string from a hunk that can be applied with git apply."
  [hunk]
  (let [{:keys [file old-start old-count new-start new-count lines]} hunk
        header (str "--- a/" file "\n+++ b/" file "\n"
                    "@@ -" old-start "," old-count " +" new-start "," new-count " @@\n")
        body (str/join "\n"
                       (map (fn [line]
                              (case (:type line)
                                :add     (str "+" (:content line))
                                :remove  (str "-" (:content line))
                                :context (str " " (:content line))))
                            lines))]
    (str header body "\n")))

(defn- make-patch
  "Generate a full patch string from an atomic unit's hunks."
  [atomic-unit]
  (let [hunks-by-file (group-by :file (:hunks atomic-unit))]
    (str/join "\n"
              (for [[file hunks] hunks-by-file]
                (str "diff --git a/" file " b/" file "\n"
                     (str/join "" (map apply-hunk-lines
                                      (sort-by :old-start hunks))))))))

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
        {:sha nil
         :message message
         :compiles false
         :error (str "Failed to apply: " (.getMessage e))}))))

(defn apply-ordering
  "Apply a full ordering of atomic units as sequential commits.
   Returns {:commits [...] :final-state-matches bool}."
  [ordering graph oracle-fn dir original-tree-sha]
  (let [commits (reduce
                 (fn [acc unit-id]
                   (let [unit (get-in graph [:nodes unit-id])
                         result (apply-atomic-unit unit oracle-fn dir)]
                     (conj acc result)))
                 []
                 ordering)
        ;; Check final state matches original
        final-tree (try (git dir "rev-parse" "HEAD^{tree}") (catch Exception _ nil))
        matches (= final-tree original-tree-sha)]
    {:commits commits
     :final-state-matches matches}))

(defn verify-final-state
  "Verify the working tree matches the expected tree SHA (FR-009)."
  [dir expected-tree-sha]
  (let [actual (git dir "rev-parse" "HEAD^{tree}")]
    (= actual expected-tree-sha)))
