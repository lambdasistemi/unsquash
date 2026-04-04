(ns core.message-test
  (:require [clojure.test :refer [deftest testing is]]
            [core.message :as msg]))

(deftest new-module-message
  (testing "New Haskell module gets feat: add ModuleName"
    (let [hunks [{:file "lib/Keri/Crypto/SAID.hs" :old-start 0 :old-count 0
                  :new-start 1 :new-count 68
                  :lines [{:type :add :content "module Keri.Crypto.SAID"}
                          {:type :add :content "    ( verifySaid"}
                          {:type :add :content "    ) where"}]}]
          msg (msg/generate-message hunks "lib/Keri/Crypto/SAID.hs:0-0")]
      (is (clojure.string/starts-with? msg "feat:"))
      (is (clojure.string/includes? msg "Keri.Crypto.SAID")))))

(deftest import-change-message
  (testing "Import addition gets refactor: import X in Y"
    (let [hunks [{:file "src/AgentDaemon/Branch.hs" :old-start 12 :old-count 24
                  :new-start 12 :new-count 25
                  :lines [{:type :add :content "import AgentDaemon.Git qualified as Git"}
                          {:type :context :content "import Data.Map"}]}]
          msg (msg/generate-message hunks "import:Git")]
      (is (clojure.string/includes? msg "import"))
      (is (clojure.string/includes? msg "AgentDaemon.Git")))))

(deftest function-change-message
  (testing "Function modification mentions function name"
    (let [hunks [{:file "lib/Append.hs" :old-start 39 :old-count 44
                  :new-start 41 :new-count 54
                  :lines [{:type :remove :content "appendInception se = case event of"}
                          {:type :add :content "appendInception se = if not (verifySaid event)"}
                          {:type :remove :content "appendToExisting (Kel events) se = do"}
                          {:type :add :content "appendToExisting (Kel events) se = if not (verifySaid event)"}]}]
          msg (msg/generate-message hunks "Append.hs:39-83")]
      (is (clojure.string/includes? msg "appendInception")))))

(deftest test-file-message
  (testing "Test file gets test: prefix"
    (let [hunks [{:file "test/spec/Keri/KelSpec.hs" :old-start 28 :old-count 6
                  :new-start 28 :new-count 41
                  :lines [{:type :add :content "it \"rejects tampered SAID\" $ do"}]}]
          msg (msg/generate-message hunks "test")]
      (is (clojure.string/starts-with? msg "test:")))))

(deftest cabal-message
  (testing "Cabal file gets chore: prefix"
    (let [hunks [{:file "keri-hs.cabal" :old-start 50 :old-count 6
                  :new-start 50 :new-count 7
                  :lines [{:type :add :content "    Keri.Crypto.SAID"}]}]
          msg (msg/generate-message hunks "cabal")]
      (is (clojure.string/starts-with? msg "chore:")))))

(deftest docs-message
  (testing "Docs file gets docs: prefix"
    (let [hunks [{:file "docs/architecture.md" :old-start 46 :old-count 7
                  :new-start 46 :new-count 7
                  :lines [{:type :remove :content "old diagram"}
                          {:type :add :content "new mermaid diagram"}]}]
          msg (msg/generate-message hunks "docs")]
      (is (clojure.string/starts-with? msg "docs:")))))
