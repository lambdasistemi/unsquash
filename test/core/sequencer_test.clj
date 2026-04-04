(ns core.sequencer-test
  (:require [clojure.test :refer [deftest testing is]]
            [core.sequencer :as seq]
            [core.graph :as g]))

(deftest merge-units-combines-hunks
  (testing "merge-units combines hunks and identities"
    (let [h1 {:id "h1" :file "a.hs" :lines []}
          h2 {:id "h2" :file "a.hs" :lines []}
          u1 (g/make-atomic-unit "u1" "define:Foo" #{h1})
          u2 (g/make-atomic-unit "u2" "use:Foo" #{h2})
          merged (#'seq/merge-units u1 u2)]
      (is (= "u1+u2" (:id merged)))
      (is (= "define:Foo+use:Foo" (:identity merged)))
      (is (= 2 (count (:hunks merged)))))))

(deftest find-merge-candidate-prefers-same-file
  (testing "find-merge-candidate prefers units sharing files"
    (let [h1 {:id "h1" :file "a.hs" :lines []}
          h2 {:id "h2" :file "b.hs" :lines []}
          h3 {:id "h3" :file "a.hs" :lines []}
          u1 (g/make-atomic-unit "u1" "x" #{h1})
          u2 (g/make-atomic-unit "u2" "y" #{h2})
          u3 (g/make-atomic-unit "u3" "z" #{h3})
          graph (g/make-graph [u1 u2 u3] #{})
          candidate (#'seq/find-merge-candidate u1 ["u2" "u3"] graph)]
      (is (= "u3" candidate) "Should prefer u3 which shares file a.hs"))))

(deftest find-merge-candidate-falls-back-to-first
  (testing "find-merge-candidate falls back to first remaining"
    (let [h1 {:id "h1" :file "a.hs" :lines []}
          h2 {:id "h2" :file "b.hs" :lines []}
          u1 (g/make-atomic-unit "u1" "x" #{h1})
          u2 (g/make-atomic-unit "u2" "y" #{h2})
          graph (g/make-graph [u1 u2] #{})
          candidate (#'seq/find-merge-candidate u1 ["u2"] graph)]
      (is (= "u2" candidate) "Should fall back to u2"))))

(deftest handle-merge-updates-graph
  (testing "handle-merge produces a valid smaller graph"
    (let [h1 {:id "h1" :file "a.hs" :lines []}
          h2 {:id "h2" :file "a.hs" :lines []}
          h3 {:id "h3" :file "b.hs" :lines []}
          u1 (g/make-atomic-unit "u1" "x" #{h1})
          u2 (g/make-atomic-unit "u2" "y" #{h2})
          u3 (g/make-atomic-unit "u3" "z" #{h3})
          e1 (g/make-edge "u3" "u1" :depends "z needs x")
          graph (g/make-graph [u1 u2 u3] #{e1})
          result (#'seq/handle-merge u1 "u1" "u2" ["u3"] graph)]
      (is (= 2 (count (:nodes (:graph result)))) "Should have 2 nodes after merge")
      (is (some? (first (:remaining result))) "Should have the merged unit in remaining"))))
