(ns consolidate.smart-squash
  "Phase 1: Group and squash related commits preserving order."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [llm.classifier :as llm]))

;; --- Git helpers ---

(defn- git [dir & args]
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

;; --- T024: Commit range parser ---

(defn parse-commit-range
  "Extract commit metadata from a git ref range.
   Returns [{:sha str :message str :files [str] :stat str} ...]"
  [dir ref-range]
  (let [log (git dir "log" "--format=%H|%s" "--reverse" ref-range)
        shas (mapv #(first (str/split % #"\|" 2)) (str/split-lines log))
        messages (mapv #(second (str/split % #"\|" 2)) (str/split-lines log))]
    (mapv (fn [sha msg]
            (let [files (str/split-lines (git dir "diff-tree" "--no-commit-id" "-r" "--name-only" sha))
                  stat (git dir "diff" "--stat" (str sha "~1") sha)]
              {:sha sha
               :message msg
               :files (vec (remove empty? files))
               :stat stat}))
          shas messages)))

;; --- T025: LLM commit grouping ---

(defn make-grouping-request
  "Build an LLM request for commit grouping."
  [commits]
  {:system "You are a commit history analyst. Group related commits that should be squashed together. Preserve chronological order within groups. Return JSON with groups array."
   :prompt (str "Group these commits by shared concern. Commits that are fixups, "
                "WIP continuations, or address the same feature should be grouped.\n\n"
                "Commits:\n"
                (json/generate-string (mapv #(select-keys % [:sha :message :files :stat]) commits)
                                      {:pretty true}))
   :response_schema {:type "object"
                     :properties
                     {:groups {:type "array"
                               :items {:type "object"
                                       :properties {:shas {:type "array" :items {:type "string"}}
                                                    :reason {:type "string"}
                                                    :proposed_message {:type "string"}}}}}}})

(defn group-commits
  "Use LLM to group related commits."
  [llm-fn commits]
  (let [request (make-grouping-request commits)
        response (llm-fn request)]
    (:groups response)))

;; --- T026: Order-preserving squash ---

(defn squash-group
  "Squash a group of commits (by SHA) preserving order.
   Uses git reset --soft to combine them."
  [dir shas message]
  (let [;; Find the parent of the earliest commit
        first-sha (first shas)
        parent (git dir "rev-parse" (str first-sha "~1"))
        ;; Soft reset to before the group
        _ (git dir "reset" "--soft" parent)
        ;; Create a new commit with all changes
        _ (git dir "commit" "-m" message "--allow-empty")]
    (git dir "rev-parse" "HEAD")))

(defn apply-consolidation
  "Apply approved groups: squash each group preserving overall order.
   Groups must be non-overlapping and in chronological order.
   Returns the new commit sequence."
  [dir groups oracle-fn]
  ;; For simplicity, rebuild the branch from scratch:
  ;; 1. Remember the final state
  ;; 2. For each group, cherry-pick and squash
  ;; This is a simplified implementation — real version would use
  ;; interactive rebase or stgit under the hood
  (let [results (atom [])]
    (doseq [{:keys [shas proposed_message reason]} groups]
      (if (= 1 (count shas))
        ;; Single commit, just keep it
        (swap! results conj {:sha (first shas)
                             :message proposed_message
                             :squashed false})
        ;; Multiple commits, they need squashing
        (swap! results conj {:shas shas
                             :message proposed_message
                             :reason reason
                             :squashed true})))
    @results))
