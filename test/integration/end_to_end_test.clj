(ns integration.end-to-end-test
  "End-to-end integration test: create a git repo with a known squashed commit,
   run the analysis pipeline, verify the graph structure."
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.process :as p]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [core.diff-parser :as dp]
            [core.hunk-splitter :as hs]
            [core.preflight :as pf]
            [core.graph :as g]))

(defn- sh [dir & args]
  (let [proc (p/process {:cmd args :dir dir :out :string :err :string})
        _ @proc]
    (str/trim (:out @proc))))

(defn- setup-test-repo
  "Create a temp git repo with a squashed commit containing:
   - A new module with a type definition
   - An export for the type
   - An import in another file
   - A usage of the type
   Returns the temp dir path."
  []
  (let [dir (str (fs/create-temp-dir {:prefix "unsquash-test-"}))]
    ;; Init repo
    (sh dir "git" "init")
    (sh dir "git" "config" "user.name" "Test")
    (sh dir "git" "config" "user.email" "test@test.com")

    ;; Create initial files
    (spit (str dir "/Types.hs")
           (str "module Types (\n"
                "  OldType (..)\n"
                ") where\n\n"
                "data OldType = OldType { value :: Int }\n"))
    (spit (str dir "/Main.hs")
           (str "module Main where\n\n"
                "import Types (OldType (..))\n\n"
                "main :: IO ()\n"
                "main = print (value (OldType 42))\n"))
    (sh dir "git" "add" "-A")
    (sh dir "git" "commit" "-m" "initial")

    ;; Create a squashed commit with multiple concerns:
    ;; 1. New type NewType (addition)
    ;; 2. Export NewType (addition wiring)
    ;; 3. Import NewType in Main (addition wiring)
    ;; 4. Use NewType in Main (addition usage)
    ;; 5. Remove unused OldType field (deletion)
    (spit (str dir "/Types.hs")
           (str "module Types (\n"
                "  OldType (..),\n"
                "  NewType (..)\n"
                ") where\n\n"
                "data OldType = OldType { value :: Int }\n\n"
                "data NewType = NewType { label :: String }\n"))
    (spit (str dir "/Main.hs")
           (str "module Main where\n\n"
                "import Types (OldType (..), NewType (..))\n\n"
                "main :: IO ()\n"
                "main = do\n"
                "  print (value (OldType 42))\n"
                "  putStrLn (label (NewType \"hello\"))\n"))
    (sh dir "git" "add" "-A")
    (sh dir "git" "commit" "-m" "squashed: add NewType and use it")

    dir))

(deftest e2e-analyze-squashed-commit
  (testing "Analyze a squashed commit and verify graph structure"
    (let [dir (setup-test-repo)
          ;; Get the diff for HEAD
          diff-text (sh dir "git" "diff" "HEAD~1" "HEAD")
          ;; Parse
          file-diffs (dp/parse-diff diff-text)
          split-diffs (hs/split-all-hunks file-diffs)
          all-hunks (vec (mapcat (fn [fd]
                                   (mapcat hs/effective-hunks (:hunks fd)))
                                 split-diffs))
          clean-hunks (vec (pf/non-whitespace-hunks all-hunks))
          ;; Preflight
          edges (pf/discover-edges clean-hunks)
          ;; Build graph
          units (mapv #(g/make-atomic-unit (:id %) (:id %) #{%}) clean-hunks)
          graph (g/make-graph units (set edges))
          contracted (g/contract-co-occurrences graph)]

      ;; Basic checks
      (is (>= (count all-hunks) 2) "Should have multiple hunks")
      (is (>= (count clean-hunks) 2) "Should have non-whitespace hunks")

      ;; Graph structure
      (is (some? contracted) "Should produce a contracted graph")
      (is (pos? (count (:nodes contracted))) "Graph should have nodes")

      ;; Topological sort should succeed (no cycles)
      (let [order (g/toposort contracted)]
        (is (some? order) "Should produce a valid topological ordering")
        (is (= (count (:nodes contracted)) (count order))
            "Ordering should include all nodes"))

      ;; Cleanup
      (fs/delete-tree dir))))

(deftest e2e-preflight-detects-export-wiring
  (testing "Preflight discovers R1 edge for export + definition"
    (let [dir (setup-test-repo)
          diff-text (sh dir "git" "diff" "HEAD~1" "HEAD")
          file-diffs (dp/parse-diff diff-text)
          split-diffs (hs/split-all-hunks file-diffs)
          all-hunks (vec (mapcat (fn [fd]
                                   (mapcat hs/effective-hunks (:hunks fd)))
                                 split-diffs))
          clean-hunks (vec (pf/non-whitespace-hunks all-hunks))
          edges (pf/discover-edges clean-hunks)]

      ;; Should find at least one preflight edge
      (is (pos? (count edges))
          "Preflight should discover edges in a commit with exports + definitions")

      (fs/delete-tree dir))))
