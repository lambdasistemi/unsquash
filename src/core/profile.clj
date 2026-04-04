(ns core.profile
  "Language profile loading, validation, and auto-detection.
   Profiles provide regex patterns for language-specific preflight rules."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

;; --- Shipped profiles directory ---

(def ^:private lang-dir "lang")

;; --- Loading ---

(defn load-profile
  "Load a language profile from a JSON file. Returns a map."
  [path]
  (let [f (clojure.java.io/file path)]
    (when-not (.exists f)
      (throw (ex-info (str "Profile not found: " path) {:path path})))
    (json/parse-string (slurp f) true)))

(defn- shipped-profile-path
  "Path to a shipped profile by name."
  [name]
  (str lang-dir "/" name ".json"))

;; --- Validation ---

(defn- compile-pattern
  "Try to compile a regex pattern. Returns compiled pattern or throws."
  [field-name pattern-str]
  (try
    (re-pattern pattern-str)
    (catch Exception e
      (throw (ex-info (str "Invalid regex in profile field '" field-name "': " (.getMessage e))
                      {:field field-name :pattern pattern-str})))))

(defn validate-profile
  "Validate a loaded profile. Returns the profile with compiled regexes, or throws."
  [profile]
  ;; Required fields
  (when-not (and (:name profile) (string? (:name profile)) (seq (:name profile)))
    (throw (ex-info "Profile missing required field 'name'" {:profile profile})))
  (when-not (and (:file_extensions profile) (seq (:file_extensions profile)))
    (throw (ex-info "Profile missing required field 'file_extensions'" {:profile profile})))
  ;; Compile regex patterns
  (let [pattern-fields [:import_pattern :export_pattern :export_name_pattern
                        :definition_pattern :manifest_module_pattern]
        compiled (reduce (fn [p field]
                           (if-let [pat (get p field)]
                             (assoc p (keyword (str (name field) "_re"))
                                    (compile-pattern (name field) pat))
                             p))
                         profile
                         pattern-fields)]
    ;; Validate module_to_path if present
    (when-let [mtp (:module_to_path compiled)]
      (doseq [k [:strip_prefixes :separator :path_separator :suffix]]
        (when-not (get mtp k)
          (throw (ex-info (str "module_to_path missing required field '" (name k) "'")
                          {:module_to_path mtp})))))
    compiled))

;; --- Module-to-path transform ---

(defn file-path-to-module
  "Convert a file path to a module name using the profile's module_to_path transform.
   Returns nil if no module_to_path in profile or no prefix matches."
  [profile file-path]
  (when-let [mtp (:module_to_path profile)]
    (let [{:keys [strip_prefixes separator path_separator suffix]} mtp
          ;; Try each prefix, use first match
          stripped (some (fn [prefix]
                          (when (str/starts-with? file-path prefix)
                            (subs file-path (count prefix))))
                        strip_prefixes)]
      (when stripped
        (-> stripped
            (str/replace (re-pattern (str (java.util.regex.Pattern/quote suffix) "$")) "")
            (str/replace separator path_separator))))))

;; --- Auto-detection ---

(defn- list-shipped-profiles
  "List all shipped profile JSON files."
  []
  (let [d (clojure.java.io/file lang-dir)]
    (when (.isDirectory d)
      (->> (.listFiles d)
           (filter #(str/ends-with? (.getName %) ".json"))
           (mapv #(.getPath %))))))

(defn detect-language
  "Auto-detect language from file extensions in hunks.
   Returns the loaded+validated profile for the best match, or nil."
  [hunks]
  (let [;; Count extensions in the diff
        ext-counts (->> hunks
                        (map :file)
                        (filter some?)
                        (map #(let [idx (str/last-index-of % ".")]
                                (when (and idx (pos? idx))
                                  (subs % idx))))
                        (filter some?)
                        (frequencies))
        ;; Load all shipped profiles
        profiles (keep (fn [path]
                         (try
                           (validate-profile (load-profile path))
                           (catch Exception _ nil)))
                       (or (list-shipped-profiles) []))]
    ;; Find profile with highest matching file count
    (when (seq profiles)
      (->> profiles
           (map (fn [p]
                  (let [score (reduce + 0
                                      (map #(get ext-counts % 0)
                                           (:file_extensions p)))]
                    [score p])))
           (filter #(pos? (first %)))
           (sort-by first >)
           first
           second))))

;; --- Resolution ---

(defn resolve-profile
  "Resolve the active language profile from config and hunks.
   Priority: :language-profile (custom path) > :language (shipped name) > auto-detect.
   Returns validated profile or nil."
  [config hunks]
  (cond
    ;; Custom profile path
    (:language-profile config)
    (validate-profile (load-profile (:language-profile config)))

    ;; Shipped profile by name
    (:language config)
    (let [path (shipped-profile-path (:language config))]
      (validate-profile (load-profile path)))

    ;; Auto-detect from file extensions
    :else
    (detect-language hunks)))
