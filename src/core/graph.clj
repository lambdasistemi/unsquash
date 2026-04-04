(ns core.graph
  "Dependency graph: nodes (AtomicUnit), edges (depends/co-occurs),
   contraction of co-occurrence edges, topological sort.")

;; --- Constructors ---

(defn make-edge
  "Create an edge between two nodes."
  [from to kind reason & {:keys [confidence rule]}]
  {:from from
   :to to
   :kind kind
   :reason reason
   :confidence (or confidence :llm)
   :rule rule})

(defn make-atomic-unit
  "Create an atomic unit from a set of hunks."
  [id identity hunks & {:keys [original-edges]}]
  {:id id
   :identity identity
   :hunks (set hunks)
   :original-edges (set (or original-edges []))})

(defn make-graph
  "Create a dependency graph from atomic units and edges."
  [units edges]
  {:nodes (into {} (map (juxt :id identity) units))
   :edges (set edges)})

;; --- Co-occurrence contraction ---

(defn- co-occurrence-groups
  "Find connected components of co-occurrence edges.
   Returns a list of sets, each set containing node IDs that co-occur."
  [edges node-ids]
  (let [co-edges (filter #(= :co-occurs (:kind %)) edges)
        ;; Build adjacency map for co-occurrence
        adj (reduce (fn [m {:keys [from to]}]
                      (-> m
                          (update from (fnil conj #{}) to)
                          (update to (fnil conj #{}) from)))
                    {}
                    co-edges)
        ;; BFS to find connected components
        visited (atom #{})
        components (atom [])]
    (doseq [nid node-ids]
      (when-not (@visited nid)
        (let [component (atom #{})
              queue (atom [nid])]
          (while (seq @queue)
            (let [current (first @queue)]
              (swap! queue rest)
              (when-not (@visited current)
                (swap! visited conj current)
                (swap! component conj current)
                (doseq [neighbor (get adj current)]
                  (when-not (@visited neighbor)
                    (swap! queue conj neighbor))))))
          (swap! components conj @component))))
    @components))

(defn contract-co-occurrences
  "Contract co-occurrence edges in a graph.
   Merges co-occurring nodes into single AtomicUnits.
   Returns a new graph with contracted nodes and updated edges."
  [graph]
  (let [{:keys [nodes edges]} graph
        node-ids (set (keys nodes))
        groups (co-occurrence-groups edges node-ids)
        co-edges (set (filter #(= :co-occurs (:kind %)) edges))
        dep-edges (filter #(= :depends (:kind %)) edges)
        ;; Build old-id -> new-unit-id mapping
        id-map (reduce (fn [m group]
                         (let [unit-id (str "unit-" (hash group))]
                           (reduce #(assoc %1 %2 unit-id) m group)))
                       {}
                       groups)
        ;; Build new atomic units
        new-units (for [group groups]
                    (let [unit-id (get id-map (first group))
                          merged-hunks (apply clojure.set/union
                                              (map #(:hunks (get nodes %)) group))
                          ;; Use identity of the first node, or combine
                          identities (map #(:identity (get nodes %)) group)
                          identity (if (= 1 (count identities))
                                     (first identities)
                                     (clojure.string/join "+" identities))
                          co-es (filter (fn [e]
                                          (and (= :co-occurs (:kind e))
                                               (contains? group (:from e))
                                               (contains? group (:to e))))
                                        co-edges)]
                      (make-atomic-unit unit-id identity merged-hunks
                                        :original-edges co-es)))
        ;; Remap dependency edges
        new-dep-edges (->> dep-edges
                           (map (fn [e]
                                  (let [new-from (get id-map (:from e))
                                        new-to (get id-map (:to e))]
                                    (when (not= new-from new-to)
                                      (assoc e :from new-from :to new-to)))))
                           (remove nil?)
                           ;; deduplicate
                           (set))]
    (make-graph new-units new-dep-edges)))

;; --- Topological sort ---

(defn- build-adjacency
  "Build adjacency list and in-degree map from directed edges.
   Edge {:from X :to Y :kind :depends} means 'X depends on Y',
   so Y must come before X. Adjacency: Y -> X, in-degree of X increases."
  [node-ids edges]
  (let [adj (reduce (fn [m {:keys [from to]}]
                      ;; from depends on to, so to -> from in the DAG
                      (update m to (fnil conj []) from))
                    (zipmap node-ids (repeat []))
                    edges)
        in-degree (reduce (fn [m {:keys [from]}]
                            ;; from is the dependent, so it has an incoming edge
                            (update m from (fnil inc 0)))
                          (zipmap node-ids (repeat 0))
                          edges)]
    [adj in-degree]))

(defn toposort
  "Kahn's algorithm for topological sort.
   Returns a vector of node IDs in topological order,
   or nil if the graph has cycles."
  [graph]
  (let [{:keys [nodes edges]} graph
        node-ids (set (keys nodes))
        [adj in-degree] (build-adjacency node-ids edges)
        ;; Start with nodes that have no incoming edges
        queue (filterv #(zero? (get in-degree %)) node-ids)]
    (loop [q queue
           in-deg in-degree
           result []]
      (if (empty? q)
        ;; Check if all nodes are in result
        (when (= (count result) (count node-ids))
          result)
        (let [node (first q)
              neighbors (get adj node [])
              new-in-deg (reduce (fn [d n] (update d n dec))
                                 in-deg
                                 neighbors)
              new-ready (filterv #(zero? (get new-in-deg %)) neighbors)]
          (recur (into (vec (rest q)) new-ready)
                 new-in-deg
                 (conj result node)))))))

(defn has-cycles?
  "Check if the dependency graph has cycles."
  [graph]
  (nil? (toposort graph)))

(defn all-toposorts
  "Generate up to max-n distinct topological sorts.
   Uses a modified Kahn's with backtracking."
  [graph max-n]
  (let [{:keys [nodes edges]} graph
        node-ids (vec (keys nodes))
        [adj in-degree] (build-adjacency (set node-ids) edges)
        results (atom [])]
    (letfn [(generate [in-deg visited result]
              (when (< (count @results) max-n)
                (let [available (filterv #(and (zero? (get in-deg %))
                                              (not (visited %)))
                                        node-ids)]
                  (if (empty? available)
                    (when (= (count result) (count node-ids))
                      (swap! results conj result))
                    (doseq [node available]
                      (when (< (count @results) max-n)
                        (let [new-in-deg (reduce (fn [d n] (update d n dec))
                                                 in-deg
                                                 (get adj node []))]
                          (generate new-in-deg
                                    (conj visited node)
                                    (conj result node)))))))))]
      (generate in-degree #{} [])
      @results)))
