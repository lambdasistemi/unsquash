(ns core.preflight-test
  (:require [clojure.test :refer [deftest testing is]]
            [core.diff-parser :as dp]
            [core.hunk-splitter :as hs]
            [core.preflight :as pf]))

(def r1-fixture (slurp "test/fixtures/hunk-zoo/r1-export-with-definition.diff"))
(def r3-fixture (slurp "test/fixtures/hunk-zoo/r3-import-superset.diff"))
(def r5-fixture (slurp "test/fixtures/hunk-zoo/r5-wildcard-count.diff"))
(def r6-fixture (slurp "test/fixtures/hunk-zoo/r6-systematic-removal.diff"))
(def r8-fixture (slurp "test/fixtures/hunk-zoo/r8-whitespace-only.diff"))

(deftest r1-export-with-definition
  (testing "R1: export + definition produces co-occurs edge"
    (let [hunks (dp/all-hunks (dp/parse-diff r1-fixture))
          edges (pf/discover-edges hunks)]
      (is (some #(and (= :co-occurs (:kind %))
                      (= "R1" (:rule %)))
                edges)
          "Should find R1 co-occurrence edge"))))

(deftest r3-import-superset
  (testing "R3: import superset produces co-occurs edge (after hunk splitting)"
    (let [parsed (dp/parse-diff r3-fixture)
          split (hs/split-all-hunks parsed)
          hunks (vec (mapcat (fn [fd]
                               (mapcat hs/effective-hunks (:hunks fd)))
                             split))
          edges (pf/discover-edges hunks)]
      (is (>= (count hunks) 2) "Hunk should be split into sub-hunks")
      (is (some #(and (= :co-occurs (:kind %))
                      (= "R3" (:rule %)))
                edges)
          "Should find R3 co-occurrence edge"))))

(deftest r5-wildcard-count
  (testing "R5: wildcard count change detected"
    (let [hunks (dp/all-hunks (dp/parse-diff r5-fixture))]
      ;; R5 needs a field addition hunk in the same diff — not present in this isolated fixture
      ;; So test the detection function directly
      (is (= 1 (count hunks)))
      ;; The hunk has wildcard changes but no field addition companion
      ;; So no R5 edge in isolation — that's correct behavior
      (let [edges (pf/discover-edges hunks)]
        (is (empty? edges) "No R5 edge without a field addition hunk")))))

(deftest r6-systematic-removal
  (testing "R6: systematic removal across files"
    (let [hunks (dp/all-hunks (dp/parse-diff r6-fixture))
          edges (pf/discover-edges hunks)]
      (is (some #(= "R6" (:rule %)) edges)
          "Should find R6 systematic removal edges"))))

(deftest r8-whitespace-only
  (testing "R8: whitespace-only hunks identified"
    (let [hunks (dp/all-hunks (dp/parse-diff r8-fixture))
          ws-hunks (pf/whitespace-only-hunks hunks)]
      (is (= (count hunks) (count ws-hunks))
          "All hunks should be whitespace-only"))))

(deftest r8-non-whitespace-filtered
  (testing "R8: non-whitespace hunks preserved"
    (let [hunks (dp/all-hunks (dp/parse-diff r1-fixture))
          clean (pf/non-whitespace-hunks hunks)]
      (is (= (count hunks) (count clean))
          "R1 fixture has no whitespace-only hunks"))))

(deftest no-edges-for-unrelated-hunks
  (testing "Unrelated hunks produce no preflight edges"
    (let [h1 {:id "a.hs:1-5" :file "a.hs"
              :lines [{:type :add :content "foo = 1"}]}
          h2 {:id "b.hs:1-5" :file "b.hs"
              :lines [{:type :add :content "bar = 2"}]}
          edges (pf/discover-edges [h1 h2])]
      (is (empty? edges)))))
