(ns oracle.compile
  "Compile oracle interface — shell out to configured command,
   capture exit code and stderr, return OracleResult."
  (:require [babashka.process :as p]))

(defn run-oracle
  "Run the compile oracle command in the given directory.
   Returns an OracleResult map:
   {:success bool :stderr str :duration-ms int}"
  [command & {:keys [dir timeout]
              :or {timeout 120000}}]
  (let [start (System/currentTimeMillis)
        result (try
                 (let [proc (p/process {:cmd ["sh" "-c" command]
                                        :dir (or dir ".")
                                        :out :string
                                        :err :string})
                       r @proc]
                   {:success (zero? (:exit r))
                    :stderr (:err r)
                    :stdout (:out r)
                    :exit (:exit r)})
                 (catch Exception e
                   {:success false
                    :stderr (str "Oracle error: " (.getMessage e))
                    :exit -1}))
        duration (- (System/currentTimeMillis) start)]
    (assoc result :duration-ms duration)))

(defn oracle-from-config
  "Create an oracle function from a config map.
   Config: {:command str :timeout int}
   Returns a function that takes an optional :dir and returns OracleResult."
  [config]
  (fn [& {:keys [dir]}]
    (run-oracle (:command config)
                :dir dir
                :timeout (get config :timeout 120000))))
