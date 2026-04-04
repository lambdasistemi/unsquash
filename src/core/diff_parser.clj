(ns core.diff-parser
  "Parse unified diffs into structured Hunk data."
  (:require [clojure.string :as str]))

(defn- parse-hunk-header
  "Parse a @@ -old-start,old-count +new-start,new-count @@ line.
   Returns {:old-start :old-count :new-start :new-count} or nil."
  [line]
  (when-let [m (re-matches #"^@@\s+-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s+@@.*$" line)]
    {:old-start (parse-long (nth m 1))
     :old-count (parse-long (or (nth m 2) "1"))
     :new-start (parse-long (nth m 3))
     :new-count (parse-long (or (nth m 4) "1"))}))

(defn- parse-file-header
  "Parse --- a/path and +++ b/path lines. Returns the file path."
  [minus-line plus-line]
  (let [path (or (second (re-matches #"^\+\+\+ b/(.+)$" plus-line))
                 (second (re-matches #"^\+\+\+ (.+)$" plus-line)))]
    (when (and path (not= path "/dev/null"))
      path)))

(defn- classify-line
  "Classify a diff line as :add, :remove, or :context."
  [line]
  (cond
    (str/starts-with? line "+") {:type :add :content (subs line 1)}
    (str/starts-with? line "-") {:type :remove :content (subs line 1)}
    (str/starts-with? line " ") {:type :context :content (subs line 1)}
    (= line "")                 {:type :context :content ""}
    :else                       {:type :context :content line}))

(defn- assign-line-numbers
  "Assign line numbers to classified diff lines."
  [lines old-start new-start]
  (loop [ls lines
         old-ln old-start
         new-ln new-start
         result []]
    (if (empty? ls)
      result
      (let [{:keys [type] :as line} (first ls)]
        (case type
          :context (recur (rest ls)
                          (inc old-ln)
                          (inc new-ln)
                          (conj result (assoc line :old-line-no old-ln :new-line-no new-ln)))
          :remove  (recur (rest ls)
                          (inc old-ln)
                          new-ln
                          (conj result (assoc line :line-no old-ln)))
          :add     (recur (rest ls)
                          old-ln
                          (inc new-ln)
                          (conj result (assoc line :line-no new-ln))))))))

(defn parse-diff
  "Parse a unified diff string into a sequence of file-diffs.
   Each file-diff is {:file path :hunks [hunk ...]}.
   Each hunk is {:id str :file str :old-start int :old-count int
                 :new-start int :new-count int :lines [line ...]}."
  [diff-text]
  (let [lines (str/split-lines diff-text)]
    (loop [ls lines
           current-file nil
           current-hunks []
           result []]
      (if (empty? ls)
        ;; flush last file
        (if current-file
          (conj result {:file current-file :hunks current-hunks})
          result)
        (let [line (first ls)]
          (cond
            ;; --- line: start of a file diff (must be "--- a/" or "--- /dev/null")
            (re-matches #"^--- (?:a/|/dev/null).*" line)
            (let [plus-line (second ls)
                  new-file (when plus-line (parse-file-header line plus-line))
                  ;; flush previous file
                  result' (if current-file
                            (conj result {:file current-file :hunks current-hunks})
                            result)]
              (recur (drop 2 ls)
                     (or new-file current-file)
                     []
                     result'))

            ;; diff --git line: skip
            (str/starts-with? line "diff --git")
            (recur (rest ls) current-file current-hunks result)

            ;; index line: skip
            (str/starts-with? line "index ")
            (recur (rest ls) current-file current-hunks result)

            ;; @@ hunk header
            (str/starts-with? line "@@")
            (let [header (parse-hunk-header line)]
              (if header
                ;; collect lines until next @@ or file header or EOF
                ;; Note: "--- " alone is ambiguous (could be a removed line like "--- comment")
                ;; File headers are "--- a/" or "--- /dev/null", not "--- " + arbitrary content
                (let [hunk-lines (take-while
                                  #(not (or (str/starts-with? % "@@")
                                            (str/starts-with? % "diff --git")
                                            (re-matches #"^--- (?:a/|/dev/null).*" %)))
                                  (rest ls))
                      classified (mapv classify-line hunk-lines)
                      with-nums (assign-line-numbers classified
                                                     (:old-start header)
                                                     (:new-start header))
                      hunk-id (str current-file ":" (:old-start header) "-"
                                   (+ (:old-start header) (:old-count header)))
                      hunk (merge header
                                  {:id hunk-id
                                   :file current-file
                                   :lines with-nums})]
                  (recur (drop (inc (count hunk-lines)) ls)
                         current-file
                         (conj current-hunks hunk)
                         result))
                ;; malformed header, skip
                (recur (rest ls) current-file current-hunks result)))

            ;; other lines: skip (e.g. "new file mode", "old mode", etc.)
            :else
            (recur (rest ls) current-file current-hunks result)))))))

(defn all-hunks
  "Extract all hunks from parsed file-diffs as a flat sequence."
  [file-diffs]
  (mapcat :hunks file-diffs))
