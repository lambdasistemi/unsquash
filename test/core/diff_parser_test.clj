(ns core.diff-parser-test
  (:require [clojure.test :refer [deftest testing is]]
            [core.diff-parser :as dp]
            [core.hunk-splitter :as hs]))

(def r1-fixture (slurp "test/fixtures/hunk-zoo/r1-export-with-definition.diff"))
(def r3-fixture (slurp "test/fixtures/hunk-zoo/r3-import-superset.diff"))
(def r4-fixture (slurp "test/fixtures/hunk-zoo/r4-trailing-comma.diff"))
(def r5-fixture (slurp "test/fixtures/hunk-zoo/r5-wildcard-count.diff"))
(def r8-fixture (slurp "test/fixtures/hunk-zoo/r8-whitespace-only.diff"))
(def mixed-fixture (slurp "test/fixtures/hunk-zoo/mixed-new-function-and-constraint.diff"))

(deftest parse-r1-export-with-definition
  (testing "R1: export + definition produces two hunks for one file"
    (let [result (dp/parse-diff r1-fixture)]
      (is (= 1 (count result)) "Should parse one file")
      (is (= "src/Foo.hs" (:file (first result))))
      (is (= 2 (count (:hunks (first result)))) "Should have two hunks")
      ;; First hunk: export addition
      (let [h1 (first (:hunks (first result)))]
        (is (some #(and (= :add (:type %))
                        (= "  newThing," (:content %)))
                  (:lines h1)))))))

(deftest parse-r3-import-superset
  (testing "R3: import list superset parsed correctly"
    (let [result (dp/parse-diff r3-fixture)
          hunks (dp/all-hunks result)]
      (is (= 1 (count result)))
      (is (pos? (count hunks)))
      ;; Should contain both remove and add lines for the import change
      (let [lines (mapcat :lines hunks)]
        (is (some #(and (= :remove (:type %))
                        (.contains (:content %) "existing"))
                  lines))
        (is (some #(and (= :add (:type %))
                        (.contains (:content %) "newThing"))
                  lines))))))

(deftest parse-r4-trailing-comma
  (testing "R4: trailing comma change parsed"
    (let [result (dp/parse-diff r4-fixture)
          hunks (dp/all-hunks result)]
      (is (= 1 (count hunks)))
      (let [lines (:lines (first hunks))]
        ;; Should have a remove (without comma) and add (with comma)
        (is (some #(= :remove (:type %)) lines))
        (is (some #(= :add (:type %)) lines))))))

(deftest parse-r5-wildcard-count
  (testing "R5: wildcard count change parsed"
    (let [result (dp/parse-diff r5-fixture)
          hunks (dp/all-hunks result)]
      (is (= 1 (count hunks)))
      (let [lines (:lines (first hunks))]
        (is (some #(and (= :remove (:type %))
                        (.contains (:content %) "_ _ _)"))
                  lines))
        (is (some #(and (= :add (:type %))
                        (.contains (:content %) "_ _ _ _)"))
                  lines))))))

(deftest parse-r8-whitespace-only
  (testing "R8: whitespace-only change parsed"
    (let [result (dp/parse-diff r8-fixture)
          hunks (dp/all-hunks result)]
      (is (= 1 (count hunks)))
      ;; Lines should have remove and add that are both blank/whitespace
      (let [removes (filter #(= :remove (:type %)) (:lines (first hunks)))
            adds (filter #(= :add (:type %)) (:lines (first hunks)))]
        (is (= (count removes) (count adds)))))))

(deftest parse-mixed-hunk
  (testing "Mixed hunk: new function + constraint change"
    (let [result (dp/parse-diff mixed-fixture)
          hunks (dp/all-hunks result)]
      (is (pos? (count hunks)))
      ;; Should contain adds (new function) and removes (constraint change)
      (let [all-lines (mapcat :lines hunks)]
        (is (some #(and (= :add (:type %))
                        (.contains (:content %) "helper"))
                  all-lines)
            "Should contain the new helper function")
        (is (some #(and (= :remove (:type %))
                        (.contains (:content %) "BabbageEraTxBody"))
                  all-lines)
            "Should contain the removed constraint")))))

(deftest hunk-splitter-on-mixed
  (testing "Hunk splitter splits mixed hunk at blank line"
    (let [result (dp/parse-diff mixed-fixture)
          split (hs/split-all-hunks result)
          hunks (dp/all-hunks split)]
      ;; At least some hunks should have sub-hunks
      (is (some :sub-hunks hunks)
          "Mixed hunk should be split into sub-hunks"))))

(deftest parse-empty-diff
  (testing "Empty diff produces empty result"
    (is (= [] (dp/parse-diff "")))
    (is (= [] (dp/parse-diff "\n\n")))))

(deftest all-hunks-extracts-flat-list
  (testing "all-hunks flattens file-diffs"
    (let [result (dp/parse-diff r1-fixture)]
      (is (= 2 (count (dp/all-hunks result)))))))
