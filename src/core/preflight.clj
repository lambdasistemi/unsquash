(ns core.preflight
  "Mechanical edge discovery from the hunk-zoo codebook.
   Discovers edges between hunks without LLM involvement.
   Language-specific rules (R1, R3, R5, R13, R14) are parameterized
   by a language profile. Universal rules (R4, R6, R8) work on any diff."
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

(defn- extract-names-from-lines
  "Extract identifiers from lines using the given regex pattern."
  [lines pattern]
  (->> lines
       (mapcat #(re-seq pattern (:content %)))
       ;; re-seq returns strings or vectors depending on capture groups
       (map #(if (string? %) % (first %)))
       (set)))

(defn- extract-names-from-export
  "Extract names added to an export/import list from add lines."
  [hunk name-pattern]
  (extract-names-from-lines (add-lines hunk) name-pattern))

(defn- extract-names-from-removes
  "Extract names removed from lines."
  [hunk name-pattern]
  (extract-names-from-lines (remove-lines hunk) name-pattern))

(defn- hunk-defines-name?
  "Does this hunk define a new top-level name matching the definition pattern?"
  [hunk name definition-re]
  (some #(and (= :add (:type %))
              (when-let [m (re-find definition-re (:content %))]
                (let [defined (if (string? m) m (second m))]
                  (= defined name))))
        (:lines hunk)))

(defn- is-export-hunk?
  "Does this hunk modify an export/visibility list?"
  [hunk export-re]
  (some #(re-find export-re (or (:content %) ""))
        (:lines hunk)))

(defn- is-import-hunk?
  "Does this hunk modify an import statement?"
  [hunk import-re]
  (some #(re-find import-re (or (:content %) ""))
        (:lines hunk)))

(defn- import-is-superset?
  "R3: Is the new import list a strict superset of the old?"
  [hunk name-pattern]
  (let [old-names (extract-names-from-removes hunk name-pattern)
        new-names (extract-names-from-export hunk name-pattern)]
    (and (seq old-names)
         (seq new-names)
         (every? new-names old-names)
         (not= old-names new-names))))

;; --- Universal rules (no profile needed) ---

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
  [hunk wildcard-char]
  (let [removes (remove-lines hunk)
        adds (add-lines hunk)
        wc-re (re-pattern (java.util.regex.Pattern/quote wildcard-char))]
    (and (= 1 (count removes))
         (= 1 (count adds))
         (let [old (:content (first removes))
               new (:content (first adds))
               old-wc (count (re-seq wc-re old))
               new-wc (count (re-seq wc-re new))]
           (and (> old-wc 2) (> new-wc 2)
                (not= old-wc new-wc)
                (= (str/replace old wc-re "")
                   (str/replace new wc-re "")))))))

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
        patterns (group-by (fn [h]
                             (set (map #(str/trim (:content %)) (remove-lines h))))
                           removal-hunks)]
    (->> patterns
         (filter (fn [[_ hs]]
                   (and (>= (count hs) 3)
                        (> (count (set (map :file hs))) 1))))
         (mapcat val))))

;; --- Profile-aware helpers ---

(defn- file-path-to-module
  "Convert file path to module name using profile's module_to_path.
   Delegates to core.profile/file-path-to-module."
  [profile file-path]
  (require 'core.profile)
  ((resolve 'core.profile/file-path-to-module) profile file-path))

(defn- matches-manifest?
  "Does this file match any of the profile's manifest_files globs?"
  [file-path manifest-globs]
  (some (fn [glob]
          (let [;; Simple glob: *.ext -> ends-with, exact match otherwise
                pattern (if (str/starts-with? glob "*.")
                          #(str/ends-with? % (subs glob 1))
                          #(= % glob))]
            (pattern (or file-path ""))))
        manifest-globs))

;; --- Main preflight engine ---

(defn discover-edges
  "Apply all preflight rules to a set of hunks.
   Profile is optional — when nil, only universal rules (R4, R6, R8) fire.
   Returns a sequence of Edge maps."
  [hunks & {:keys [profile]}]
  (let [edges (atom [])
        ;; Pre-compile profile patterns
        export-re (or (:export_pattern_re profile)
                      (when (:export_pattern profile)
                        (re-pattern (:export_pattern profile))))
        name-re (when (:export_name_pattern profile)
                  (re-pattern (:export_name_pattern profile)))
        definition-re (or (:definition_pattern_re profile)
                          (when (:definition_pattern profile)
                            (re-pattern (:definition_pattern profile))))
        import-re (or (:import_pattern_re profile)
                      (when (:import_pattern profile)
                        (re-pattern (:import_pattern profile))))
        wildcard-char (:wildcard profile)
        manifest-globs (:manifest_files profile)
        manifest-mod-re (or (:manifest_module_pattern_re profile)
                            (when (:manifest_module_pattern profile)
                              (re-pattern (:manifest_module_pattern profile))))]

    ;; R1: Export of name defined in same diff → co-occurs
    ;; Requires: export_pattern, export_name_pattern, definition_pattern
    (when (and export-re name-re definition-re)
      (doseq [h hunks
              :when (is-export-hunk? h export-re)]
        (let [exported-names (extract-names-from-export h name-re)]
          (doseq [other hunks
                  :when (and (not= (:id h) (:id other))
                             (= (:file h) (:file other))
                             (some #(hunk-defines-name? other % definition-re) exported-names))]
            (swap! edges conj
                   (g/make-edge (:id h) (:id other) :co-occurs
                                (str "R1: export of name defined in " (:id other))
                                :confidence :preflight :rule "R1"))))))

    ;; R3: Import additions → co-occurs with usage
    ;; Requires: import_pattern, export_name_pattern
    (when (and import-re name-re)
      (doseq [h hunks
              :when (and (is-import-hunk? h import-re) (import-is-superset? h name-re))]
        (let [new-names (clojure.set/difference
                         (extract-names-from-export h name-re)
                         (extract-names-from-removes h name-re))]
          (doseq [other hunks
                  :when (and (not= (:id h) (:id other))
                             (= (:file h) (:file other))
                             (some (fn [n]
                                     (some #(str/includes? (or (:content %) "") n)
                                           (add-lines other)))
                                   new-names))]
            (swap! edges conj
                   (g/make-edge (:id h) (:id other) :co-occurs
                                (str "R3: import superset enables usage in " (:id other))
                                :confidence :preflight :rule "R3"))))))

    ;; R4: Trailing comma → co-occurs (universal — no profile needed)
    (doseq [h hunks
            :when (trailing-comma-only? h)]
      (let [adds (add-lines h)]
        (when (> (count adds) 1)
          (swap! edges conj
                 (g/make-edge (:id h) (:id h) :co-occurs
                              "R4: trailing comma for new item"
                              :confidence :preflight :rule "R4")))))

    ;; R5: Wildcard count change → co-occurs with field addition
    ;; Requires: wildcard
    (when wildcard-char
      (doseq [h hunks
              :when (wildcard-count-change? h wildcard-char)]
        (doseq [other hunks
                :when (and (not= (:id h) (:id other))
                           (some #(and (= :add (:type %))
                                       (re-find #"::\s*!" (:content %)))
                                 (:lines other)))]
          (swap! edges conj
                 (g/make-edge (:id h) (:id other) :co-occurs
                              (str "R5: wildcard count change for field in " (:id other))
                              :confidence :preflight :rule "R5")))))

    ;; R6: Systematic removal across files (universal)
    (let [systematic (systematic-removal? hunks)]
      (when (seq systematic)
        (let [ids (map :id systematic)]
          (doseq [[a b] (partition 2 1 ids)]
            (swap! edges conj
                   (g/make-edge a b :co-occurs
                                "R6: systematic removal across files"
                                :confidence :preflight :rule "R6"))))))

    ;; R13: Cross-file import → depends on new module
    ;; Requires: import_pattern, module_to_path
    (when (and import-re (:module_to_path profile))
      (let [new-file-hunks (filter #(zero? (or (:old-count %) 0)) hunks)
            new-modules (into {}
                              (for [h new-file-hunks
                                    :let [mod-name (file-path-to-module profile (:file h))]
                                    :when mod-name]
                                [mod-name (:id h)]))]
        (when (seq new-modules)
          (doseq [h hunks
                  :when (not (zero? (or (:old-count h) 0)))]
            (doseq [line (add-lines h)
                    :let [content (:content line)]
                    :let [m (re-find import-re content)]
                    :when m
                    :let [imported (if (string? m) m (second m))]
                    :when imported
                    :let [target-id (get new-modules imported)]
                    :when target-id]
              (swap! edges conj
                     (g/make-edge (:id h) target-id :depends
                                  (str "R13: imports " imported " from new module")
                                  :confidence :preflight :rule "R13")))))))

    ;; R14: Manifest module registration → co-occurs with new module file
    ;; Requires: manifest_files, manifest_module_pattern, module_to_path
    (when (and manifest-globs manifest-mod-re (:module_to_path profile))
      (let [new-file-hunks (filter #(zero? (or (:old-count %) 0)) hunks)
            new-modules (into {}
                              (for [h new-file-hunks
                                    :let [mod-name (file-path-to-module profile (:file h))]
                                    :when mod-name]
                                [mod-name (:id h)]))]
        (when (seq new-modules)
          (doseq [h hunks
                  :when (matches-manifest? (:file h) manifest-globs)]
            (doseq [line (add-lines h)
                    :let [content (str/trim (:content line))]
                    :let [m (re-find manifest-mod-re content)]
                    :when m
                    :let [mod-name (if (string? m) m (second m))]
                    :when mod-name
                    :let [target-id (get new-modules (str/trim mod-name))]
                    :when target-id]
              (swap! edges conj
                     (g/make-edge (:id h) target-id :co-occurs
                                  (str "R14: manifest registers module " mod-name)
                                  :confidence :preflight :rule "R14")))))))

    ;; R15: All manifest changes become the first commit
    ;; Requires: manifest_files + manifest_first = true
    ;; All manifest hunks co-occur into one atomic unit. Every source hunk depends
    ;; on it. Result: one "dependency changes" commit, always first in the ordering.
    (when (and manifest-globs (:manifest_first profile))
      (let [manifest-hunks (filter #(matches-manifest? (:file %) manifest-globs) hunks)
            source-hunks (remove #(matches-manifest? (:file %) manifest-globs) hunks)]
        (when (seq manifest-hunks)
          ;; Co-occur all manifest hunks into one unit
          (doseq [[a b] (partition 2 1 manifest-hunks)]
            (swap! edges conj
                   (g/make-edge (:id a) (:id b) :co-occurs
                                "R15: all manifest changes in one commit"
                                :confidence :preflight :rule "R15")))
          ;; Every source hunk depends on the first manifest hunk (representative)
          (let [rep (:id (first manifest-hunks))]
            (doseq [sh source-hunks]
              (swap! edges conj
                     (g/make-edge (:id sh) rep :depends
                                  "R15: manifest changes first"
                                  :confidence :preflight :rule "R15")))))))

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
