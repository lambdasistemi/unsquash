(ns core.preflight
  "Mechanical edge discovery (R1–R12) from the hunk-zoo codebook.
   Discovers edges between hunks without LLM involvement."
  (:require [clojure.string :as str]
            [core.graph :as g]))

;; --- Helpers ---

(defn- add-lines [hunk]
  (filter #(= :add (:type %)) (:lines hunk)))

(defn- remove-lines [hunk]
  (filter #(= :remove (:type %)) (:lines hunk)))

(defn- only-adds? [hunk]
  (every? #{:add :context} (map :type (:lines hunk))))

(defn- only-removes? [hunk]
  (every? #{:remove :context} (map :type (:lines hunk))))

(defn- extract-names-from-export
  "Extract names added to an export/import list from add lines."
  [hunk]
  (->> (add-lines hunk)
       (mapcat #(re-seq #"[A-Za-z_][A-Za-z0-9_']*" (:content %)))
       (set)))

(defn- extract-names-from-removes
  "Extract names removed from lines."
  [hunk]
  (->> (remove-lines hunk)
       (mapcat #(re-seq #"[A-Za-z_][A-Za-z0-9_']*" (:content %)))
       (set)))

(defn- hunk-defines-name?
  "Does this hunk define a new top-level name (function/type)?"
  [hunk name]
  (some #(and (= :add (:type %))
              (re-find (re-pattern (str "^" (java.util.regex.Pattern/quote name) "\\s")) (:content %)))
        (:lines hunk)))

(defn- is-export-hunk?
  "Does this hunk modify an export list (module ... where)?"
  [hunk]
  (some #(or (str/includes? (or (:content %) "") "module ")
             (str/includes? (or (:content %) "") ") where"))
        (:lines hunk)))

(defn- is-import-hunk?
  "Does this hunk modify an import statement?"
  [hunk]
  (some #(str/includes? (or (:content %) "") "import ")
        (:lines hunk)))

(defn- import-is-superset?
  "R3: Is the new import list a strict superset of the old?"
  [hunk]
  (let [old-names (extract-names-from-removes hunk)
        new-names (extract-names-from-export hunk)]
    (and (seq old-names)
         (seq new-names)
         (every? new-names old-names)
         (not= old-names new-names))))

(defn- trailing-comma-only?
  "R4: Does this hunk only add a trailing comma to an existing line?"
  [hunk]
  (let [removes (remove-lines hunk)
        adds (add-lines hunk)]
    (and (= 1 (count removes))
         (>= (count adds) 1)
         (let [old (str/trim (:content (first removes)))
               new (str/trim (:content (first adds)))]
           (= (str old ",") new)))))

(defn- wildcard-count-change?
  "R5: Does this hunk only change the number of wildcards in a pattern match?"
  [hunk]
  (let [removes (remove-lines hunk)
        adds (add-lines hunk)]
    (and (= 1 (count removes))
         (= 1 (count adds))
         (let [old (:content (first removes))
               new (:content (first adds))
               old-wc (count (re-seq #"_" old))
               new-wc (count (re-seq #"_" new))]
           (and (> old-wc 2) (> new-wc 2)
                (not= old-wc new-wc)
                ;; Rest of the line should be the same modulo wildcards
                (= (str/replace old #"_\s*" "") (str/replace new #"_\s*" "")))))))

(defn- whitespace-only-change?
  "R8: Is this hunk a whitespace-only change?"
  [hunk]
  (let [removes (remove-lines hunk)
        adds (add-lines hunk)]
    (and (= (count removes) (count adds))
         (every? (fn [[r a]]
                   (= (str/trim (:content r)) (str/trim (:content a))))
                 (map vector removes adds)))))

(defn- systematic-removal?
  "R6: Are the same imports/constraints removed across multiple files?"
  [hunks]
  (let [removal-hunks (filter only-removes? hunks)
        ;; Group by the removed content pattern
        patterns (group-by (fn [h]
                             (set (map #(str/trim (:content %)) (remove-lines h))))
                           removal-hunks)]
    ;; Return groups with 3+ occurrences across different files
    (->> patterns
         (filter (fn [[_ hs]]
                   (and (>= (count hs) 3)
                        (> (count (set (map :file hs))) 1))))
         (mapcat val))))

;; --- Main preflight engine ---

(defn discover-edges
  "Apply all preflight rules to a set of hunks.
   Returns a sequence of Edge maps."
  [hunks]
  (let [edges (atom [])
        hunk-by-id (into {} (map (juxt :id identity) hunks))]

    ;; R1: Export of name defined in same diff → co-occurs
    (doseq [h hunks
            :when (is-export-hunk? h)]
      (let [exported-names (extract-names-from-export h)]
        (doseq [other hunks
                :when (and (not= (:id h) (:id other))
                           (= (:file h) (:file other))
                           (some #(hunk-defines-name? other %) exported-names))]
          (swap! edges conj
                 (g/make-edge (:id h) (:id other) :co-occurs
                              (str "R1: export of name defined in " (:id other))
                              :confidence :preflight :rule "R1")))))

    ;; R2/R3: Import additions → co-occurs with usage
    (doseq [h hunks
            :when (and (is-import-hunk? h) (import-is-superset? h))]
      (let [new-names (clojure.set/difference
                       (extract-names-from-export h)
                       (extract-names-from-removes h))]
        (doseq [other hunks
                :when (and (not= (:id h) (:id other))
                           (= (:file h) (:file other))
                           (some (fn [name]
                                   (some #(str/includes? (or (:content %) "") name)
                                         (add-lines other)))
                                 new-names))]
          (swap! edges conj
                 (g/make-edge (:id h) (:id other) :co-occurs
                              (str "R3: import superset enables usage in " (:id other))
                              :confidence :preflight :rule "R3")))))

    ;; R4: Trailing comma → co-occurs with adjacent addition
    (doseq [h hunks
            :when (trailing-comma-only? h)]
      (let [adds (add-lines h)]
        (when (> (count adds) 1)
          ;; The comma line and the new item are in the same hunk — mark as formatting
          (swap! edges conj
                 (g/make-edge (:id h) (:id h) :co-occurs
                              "R4: trailing comma for new item"
                              :confidence :preflight :rule "R4")))))

    ;; R5: Wildcard count change → co-occurs with field addition
    (doseq [h hunks
            :when (wildcard-count-change? h)]
      ;; Find record field additions in other hunks
      (doseq [other hunks
              :when (and (not= (:id h) (:id other))
                         (some #(and (= :add (:type %))
                                     (re-find #"::\s*!" (:content %)))
                               (:lines other)))]
        (swap! edges conj
               (g/make-edge (:id h) (:id other) :co-occurs
                            (str "R5: wildcard count change for field in " (:id other))
                            :confidence :preflight :rule "R5"))))

    ;; R6: Systematic removal across files
    (let [systematic (systematic-removal? hunks)]
      (when (seq systematic)
        (let [ids (map :id systematic)]
          (doseq [[a b] (partition 2 1 ids)]
            (swap! edges conj
                   (g/make-edge a b :co-occurs
                                "R6: systematic removal across files"
                                :confidence :preflight :rule "R6"))))))

    ;; R8: Whitespace-only changes — tag but no edges
    ;; (These hunks should be stripped, not connected)

    @edges))

(defn whitespace-only-hunks
  "Identify hunks that are whitespace-only changes (R8).
   These should be stripped before classification."
  [hunks]
  (filter whitespace-only-change? hunks))

(defn non-whitespace-hunks
  "Filter out whitespace-only hunks."
  [hunks]
  (remove whitespace-only-change? hunks))
