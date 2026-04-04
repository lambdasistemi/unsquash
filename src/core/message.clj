(ns core.message
  "Commit message generation — from diff content or via LLM."
  (:require [clojure.string :as str]))

;; --- Mechanical message generation (no LLM) ---

(defn- detect-change-type
  "Detect the type of change from hunk content."
  [hunks]
  (let [all-new (every? #(zero? (or (:old-count %) 0)) hunks)
        all-del (every? #(zero? (or (:new-count %) 0)) hunks)
        files (set (map :file hunks))
        src-files (remove #(or (str/ends-with? % ".cabal") (str/includes? % ".github/")) files)
        has-new-src (some #(zero? (or (:old-count %) 0)) (filter #(not (str/ends-with? (:file %) ".cabal")) hunks))
        has-test (some #(or (str/includes? % "test/") (str/includes? % "spec/") (str/includes? % "Test")) src-files)
        has-doc (some #(or (str/includes? % "docs/") (str/includes? % ".md") (str/includes? % "README")) src-files)
        has-ci (some #(str/includes? % ".github/") files)
        only-cabal (every? #(str/ends-with? % ".cabal") files)]
    (cond
      ;; New source module (even if cabal wiring is included)
      has-new-src (if has-test "test" "feat")
      all-new (if has-test "test" (if has-doc "docs" (if has-ci "ci" "feat")))
      all-del "refactor"
      has-test "test"
      has-doc "docs"
      has-ci "ci"
      only-cabal "chore"
      :else "refactor")))

(defn- extract-module-names
  "Extract Haskell/PureScript module names from new file hunks."
  [hunks]
  (->> hunks
       (filter #(zero? (or (:old-count %) 0)))
       (keep (fn [h]
               (some (fn [l]
                       (when (= :add (:type l))
                         (second (re-find #"^module\s+([A-Za-z][A-Za-z0-9.]*)" (:content l)))))
                     (:lines h))))
       (vec)))

(defn- extract-added-imports
  "Extract newly imported module names."
  [hunks]
  (->> hunks
       (mapcat :lines)
       (filter #(= :add (:type %)))
       (keep #(second (re-find #"^import\s+(?:qualified\s+)?([A-Za-z][A-Za-z0-9.]*)" (:content %))))
       (distinct)
       (vec)))

(defn- extract-changed-functions
  "Extract function names that were modified (appear in both add and remove lines)."
  [hunks]
  (let [removes (->> hunks (mapcat :lines) (filter #(= :remove (:type %)))
                      (keep #(second (re-find #"^([a-z][a-zA-Z0-9_']*)\s" (:content %)))) set)
        adds (->> hunks (mapcat :lines) (filter #(= :add (:type %)))
                   (keep #(second (re-find #"^([a-z][a-zA-Z0-9_']*)\s" (:content %)))) set)]
    (vec (clojure.set/intersection removes adds))))

(defn- summarize-files
  "Summarize which files changed, compactly."
  [hunks]
  (let [files (distinct (map :file hunks))
        n (count files)]
    (if (<= n 2)
      (str/join ", " (map #(last (str/split % #"/")) files))
      (let [first-file (last (str/split (first files) #"/"))]
        (str first-file " and " (dec n) " more")))))

(defn generate-message
  "Generate a commit message from an atomic unit's hunks.
   Uses diff content analysis — no LLM needed."
  [hunks identity-str]
  (let [change-type (detect-change-type hunks)
        modules (extract-module-names hunks)
        imports (extract-added-imports hunks)
        changed-fns (extract-changed-functions hunks)
        files-summary (summarize-files hunks)
        ;; Build the subject line
        subject (cond
                  ;; New module
                  (seq modules)
                  (str change-type ": add " (str/join ", " modules))

                  ;; Import additions (wiring)
                  (and (seq imports) (empty? changed-fns))
                  (str change-type ": import " (str/join ", " imports) " in " files-summary)

                  ;; Function changes
                  (seq changed-fns)
                  (str change-type ": update " (str/join ", " (take 3 changed-fns))
                       (when (> (count changed-fns) 3) " et al")
                       " in " files-summary)

                  ;; Cabal changes
                  (some #(str/ends-with? % ".cabal") (map :file hunks))
                  (str "chore: update cabal configuration")

                  ;; Fallback: use file summary
                  :else
                  (str change-type ": update " files-summary))]
    subject))

;; --- LLM message generation ---

(defn make-message-request
  "Build an LLM request for commit message generation."
  [diff-text files]
  {:system "You are a commit message writer. Given a diff, write a single-line conventional commit message (type: description). Types: feat, fix, refactor, test, docs, chore, ci. Focus on WHY, not WHAT. Be concise — under 72 characters."
   :prompt (str "Write a conventional commit message for this change:\n\n"
                "Files: " (str/join ", " files) "\n\n"
                "Diff:\n" (subs diff-text 0 (min 3000 (count diff-text))))
   :response_schema {:type "object"
                     :properties {:message {:type "string"
                                            :description "The commit message, e.g. 'feat: add SAID verification'"}}}})

(defn generate-message-llm
  "Generate a commit message via LLM."
  [llm-fn diff-text files]
  (try
    (let [request (make-message-request diff-text files)
          response (llm-fn request)]
      (or (:message response) (str response)))
    (catch Exception _
      nil)))
