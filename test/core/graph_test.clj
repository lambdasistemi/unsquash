(ns core.graph-test
  (:require [clojure.test :refer [deftest testing is]]
            [core.graph :as g]))

(deftest toposort-linear
  (testing "Linear graph: A -> B -> C"
    (let [a (g/make-atomic-unit "a" "define:Foo" #{})
          b (g/make-atomic-unit "b" "use:Foo:Bar" #{})
          c (g/make-atomic-unit "c" "cleanup" #{})
          e1 (g/make-edge "b" "a" :depends "b uses Foo from a")
          e2 (g/make-edge "c" "b" :depends "c cleans up after b")
          graph (g/make-graph [a b c] #{e1 e2})
          order (g/toposort graph)]
      (is (some? order) "Should produce a valid order")
      (is (= 3 (count order)))
      ;; a must come before b, b before c
      (is (< (.indexOf order "a") (.indexOf order "b")))
      (is (< (.indexOf order "b") (.indexOf order "c"))))))

(deftest toposort-parallel
  (testing "Parallel graph: A -> C, B -> C (A and B independent)"
    (let [a (g/make-atomic-unit "a" "define:Foo" #{})
          b (g/make-atomic-unit "b" "define:Bar" #{})
          c (g/make-atomic-unit "c" "use:both" #{})
          e1 (g/make-edge "c" "a" :depends "c uses Foo")
          e2 (g/make-edge "c" "b" :depends "c uses Bar")
          graph (g/make-graph [a b c] #{e1 e2})
          order (g/toposort graph)]
      (is (some? order))
      (is (= 3 (count order)))
      ;; c must come after both a and b
      (is (< (.indexOf order "a") (.indexOf order "c")))
      (is (< (.indexOf order "b") (.indexOf order "c"))))))

(deftest toposort-cycle-detection
  (testing "Cyclic graph returns nil"
    (let [a (g/make-atomic-unit "a" "x" #{})
          b (g/make-atomic-unit "b" "y" #{})
          e1 (g/make-edge "a" "b" :depends "a needs b")
          e2 (g/make-edge "b" "a" :depends "b needs a")
          graph (g/make-graph [a b] #{e1 e2})]
      (is (nil? (g/toposort graph)))
      (is (true? (g/has-cycles? graph))))))

(deftest toposort-single-node
  (testing "Single node graph"
    (let [a (g/make-atomic-unit "a" "only" #{})
          graph (g/make-graph [a] #{})]
      (is (= ["a"] (g/toposort graph))))))

(deftest contract-co-occurrences-merges-nodes
  (testing "Co-occurring nodes are merged into one"
    (let [a (g/make-atomic-unit "a" "import:Foo" #{"h1"})
          b (g/make-atomic-unit "b" "use:Foo" #{"h2"})
          c (g/make-atomic-unit "c" "other" #{"h3"})
          co-edge (g/make-edge "a" "b" :co-occurs "import enables usage")
          dep-edge (g/make-edge "c" "a" :depends "c depends on Foo")
          graph (g/make-graph [a b c] #{co-edge dep-edge})
          contracted (g/contract-co-occurrences graph)]
      ;; Should have 2 nodes (a+b merged, c separate)
      (is (= 2 (count (:nodes contracted))))
      ;; The merged node should contain hunks from both a and b
      (let [merged (first (filter #(> (count (:hunks (val %))) 1)
                                  (:nodes contracted)))]
        (is (some? merged) "Should have a merged node with multiple hunks"))
      ;; Should still have a dependency edge
      (is (= 1 (count (:edges contracted)))))))

(deftest contract-no-co-occurrences
  (testing "Graph without co-occurrences stays the same"
    (let [a (g/make-atomic-unit "a" "x" #{})
          b (g/make-atomic-unit "b" "y" #{})
          e (g/make-edge "b" "a" :depends "b needs a")
          graph (g/make-graph [a b] #{e})
          contracted (g/contract-co-occurrences graph)]
      (is (= 2 (count (:nodes contracted))))
      (is (= 1 (count (:edges contracted)))))))

(deftest all-toposorts-generates-multiple
  (testing "Parallel graph produces multiple orderings"
    (let [a (g/make-atomic-unit "a" "x" #{})
          b (g/make-atomic-unit "b" "y" #{})
          c (g/make-atomic-unit "c" "z" #{})
          e1 (g/make-edge "c" "a" :depends "c needs a")
          e2 (g/make-edge "c" "b" :depends "c needs b")
          graph (g/make-graph [a b c] #{e1 e2})
          orderings (g/all-toposorts graph 10)]
      (is (>= (count orderings) 2) "Should produce at least 2 orderings")
      ;; All orderings should have c last
      (doseq [order orderings]
        (is (= "c" (last order)))))))

(deftest all-toposorts-respects-max
  (testing "all-toposorts respects max-n limit"
    (let [nodes (mapv #(g/make-atomic-unit (str %) (str "n" %) #{}) (range 5))
          graph (g/make-graph nodes #{})
          orderings (g/all-toposorts graph 3)]
      (is (<= (count orderings) 3)))))
