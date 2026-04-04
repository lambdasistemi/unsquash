(ns core.hunk-splitter
  "Split hunks at natural boundaries (blank lines, top-level definitions).")

(defn- blank-line?
  "Is this a line that is blank (empty or whitespace-only)?
   Blank lines can be :context, :add, or :remove — any type counts
   as a natural boundary for splitting purposes."
  [line]
  (or (empty? (:content line))
      (re-matches #"^\s*$" (or (:content line) ""))))

(defn- split-at-blank-lines
  "Split a sequence of diff lines into groups separated by blank lines.
   Any blank line (context, add, or remove) acts as a boundary.
   The blank line itself is kept in the preceding group.
   Each group is a non-empty sequence of lines."
  [lines]
  (let [groups (reduce
                (fn [acc line]
                  (if (blank-line? line)
                    ;; Blank line — keep in current group, start new group after
                    (let [acc' (update acc (dec (count acc)) conj line)]
                      (conj acc' []))
                    (update acc (dec (count acc)) conj line)))
                [[]]
                lines)]
    (filterv seq groups)))

(defn- has-changes?
  "Does this group of lines contain at least one :add or :remove?"
  [lines]
  (some #{:add :remove} (map :type lines)))

(defn- recalculate-header
  "Recalculate hunk header fields from a group of lines."
  [lines file]
  (let [removes (filter #(= :remove (:type %)) lines)
        adds (filter #(= :add (:type %)) lines)
        contexts (filter #(= :context (:type %)) lines)
        ;; Find the starting line numbers from the first line
        old-start (or (some :old-line-no (concat contexts removes))
                      (some :line-no removes)
                      1)
        new-start (or (some :new-line-no (concat contexts adds))
                      (some :line-no adds)
                      1)]
    {:old-start old-start
     :old-count (+ (count removes) (count contexts))
     :new-start new-start
     :new-count (+ (count adds) (count contexts))
     :id (str file ":" old-start "-" (+ old-start (count removes) (count contexts)))}))

(defn split-hunk
  "Split a hunk into sub-hunks at blank line boundaries.
   Returns the original hunk with :sub-hunks populated if splitting occurred.
   If the hunk can't be split (single group), returns it unchanged."
  [hunk]
  (let [groups (split-at-blank-lines (:lines hunk))
        change-groups (filterv has-changes? groups)]
    (if (<= (count change-groups) 1)
      ;; No split possible or only one group with changes
      hunk
      ;; Multiple groups with changes — create sub-hunks
      (let [sub-hunks (mapv (fn [lines]
                              (merge (recalculate-header lines (:file hunk))
                                     {:file (:file hunk)
                                      :lines (vec lines)}))
                            change-groups)]
        (assoc hunk :sub-hunks sub-hunks)))))

(defn split-all-hunks
  "Split all hunks in file-diffs, returning updated file-diffs."
  [file-diffs]
  (mapv (fn [fd]
          (update fd :hunks #(mapv split-hunk %)))
        file-diffs))

(defn effective-hunks
  "Get the effective hunks for processing — sub-hunks if available, otherwise the hunk itself."
  [hunk]
  (if-let [subs (:sub-hunks hunk)]
    subs
    [hunk]))
