# Hunk Zoo: Problematic Git Diff Patterns

Test fixtures for unsquash's semantic hunk classifier. Each entry uses real diffs from actual merged PRs.

Sources: cardano-ledger (PRs #5676, #5671, #5645, #5546, #5530), lens (#1095), aeson (#1106), pandoc (#11523).

---

## Category 1: Mixed Hunks

Git merges unrelated changes into one hunk because they are adjacent (within 3 lines of context).

### 1.1 Export list addition + nearby import change

**Source:** cardano-ledger PR #5676, `Cardano.Ledger.Conway.Rules.Utxo`

```diff
@@ -21,6 +21,7 @@ module Cardano.Ledger.Conway.Rules.Utxo (
   alonzoToConwayUtxoPredFailure,
   ConwayUtxoPredFailure (..),
   UtxoEnv (..),
+  updateTreasuryDonation,
 ) where
 
 import Cardano.Ledger.Allegra.Rules (AllegraUtxoPredFailure, shelleyToAllegraUtxoPredFailure)
```

**What git shows:** One hunk adding an export.

**Semantic meaning:** Wiring for a new function (`updateTreasuryDonation`) defined later in the same file.

**Why it's hard:** Looks like a standalone modification but is the "wiring" half of an addition. The classifier needs the function definition hunk (elsewhere) to know this is wiring, not an independent API change.

**Pre-classification rule:** Export list additions should be checked against new definitions in the same diff. If the exported name matches a new definition, tag both as `addition:wiring` and `addition:definition`.

---

### 1.2 Import additions merged with import modifications

**Source:** cardano-ledger PR #5676, `Cardano.Ledger.Conway.Rules.Utxo`

```diff
@@ -64,10 +65,11 @@ import Cardano.Ledger.Coin (Coin, DeltaCoin)
 import Cardano.Ledger.Conway.Core
 import Cardano.Ledger.Conway.Era (ConwayEra, ConwayUTXO, ConwayUTXOS)
 import Cardano.Ledger.Conway.Rules.Utxos (
+  ConwayUtxosEnv (..),
   ConwayUtxosPredFailure (..),
  )
 import Cardano.Ledger.Plutus (ExUnits)
-import Cardano.Ledger.Shelley.LedgerState (UTxOState (..))
+import Cardano.Ledger.Shelley.LedgerState (UTxOState (..), utxosDonationL)
```

**What git shows:** A single hunk with 3 changes: (1) add `ConwayUtxosEnv` import, (2) add `utxosDonationL` to an existing import, (3) leave surrounding imports unchanged.

**Semantic meaning:** Two independent wiring changes for two different aspects of the same refactor.

**Why it's hard:** Git merges these because they are within 3 lines. A classifier sees one hunk but there are two distinct semantic reasons. Splitting requires understanding that each `import` statement is an independent unit.

**Pre-classification rule:** Import block hunks should be split at import statement boundaries. Each modified import statement gets its own semantic tag.

---

### 1.3 New function definition adjacent to constraint change

**Source:** cardano-ledger PR #5676, `Cardano.Ledger.Conway.Rules.Utxo`

```diff
@@ -214,12 +217,23 @@ instance
   ) =>
   NFData (ConwayUtxoPredFailure era)
 
+-- | Accumulate treasury donation for valid transactions
+updateTreasuryDonation ::
+  (AlonzoEraTx era, ConwayEraTxBody era) =>
+  Tx TopTx era ->
+  UTxOState era ->
+  UTxOState era
+updateTreasuryDonation tx utxos =
+  case tx ^. isValidTxL of
+    IsValid True -> utxos & utxosDonationL <>~ tx ^. bodyTxL . treasuryDonationTxBodyL
+    IsValid False -> utxos
+
 conwayUtxoTransition ::
   forall era.
   ( EraUTxO era
   , EraCertState era
-  , BabbageEraTxBody era
   , AlonzoEraTx era
+  , ConwayEraTxBody era
```

**What git shows:** One hunk containing a brand-new function AND a constraint change on an existing function.

**Semantic meaning:** Two distinct changes: (1) addition of `updateTreasuryDonation`, (2) modification of `conwayUtxoTransition`'s constraint set.

**Why it's hard:** The new function and the constraint change are separated by only a blank line + function signature, within git's context window.

**Pre-classification rule:** If a hunk contains both `+` lines forming a complete new definition AND `-`/`+` lines modifying an existing definition, split at the definition boundary (blank line between top-level declarations).

---

## Category 2: Addition-Enabling Modifications

Changes that look like modifications but are semantically wiring for additions.

### 2.1 Adding an item to an import list

**Source:** cardano-ledger PR #5676

```diff
-import Cardano.Ledger.Shelley.LedgerState (UTxOState (..))
+import Cardano.Ledger.Shelley.LedgerState (UTxOState (..), utxosDonationL)
```

**What git shows:** A modification (one `-` line, one `+` line).

**Semantic meaning:** An addition. The import of `utxosDonationL` is wiring for the new `updateTreasuryDonation` function.

**Why it's hard:** Git has no concept of "appending to a list inside a line". It sees the whole line changed.

**Pre-classification rule:** For import modifications, diff the import lists. If the old list is a strict subset of the new list, classify as `addition:wiring` not `modification`.

---

### 2.2 Adding to an export list

**Source:** cardano-ledger PR #5676, `Cardano.Ledger.Conway.Rules.Utxos`

```diff
 module Cardano.Ledger.Conway.Rules.Utxos (
   ConwayUTXOS,
+  ConwayUtxosEnv (..),
   ConwayUtxosPredFailure (..),
```

**Semantic meaning:** Wiring. `ConwayUtxosEnv` is a new data type defined later in this file.

**Pre-classification rule:** Export additions where the name also appears as a new definition in the same diff are `addition:wiring`.

---

### 2.3 Adding a field to a record type

**Source:** cardano-ledger PR #5546, `DijkstraTxBodyRaw`

```diff
     , dtbrTreasuryDonation :: !Coin
     , dtbrSubTransactions :: !(OMap TxId (Tx SubTx era))
     , dtbrDirectDeposits :: !DirectDeposits
+    , dtbrAccountBalanceIntervals :: !(AccountBalanceIntervals era)
     } ->
     DijkstraTxBodyRaw TopTx era
```

**Why it's hard:** The field addition is clean, but it triggers cascading modifications everywhere the record is constructed or pattern-matched (NFData, pattern synonyms, encoder, decoder). A classifier must recognize those downstream hunks as caused by this addition.

**Pre-classification rule:** When a record field is added, all hunks that modify pattern matches on that record's constructor should be tagged `addition:wiring:mechanical`.

---

### 2.4 NFData/pattern match adjustments for new field

**Source:** cardano-ledger PR #5546

```diff
 instance (EraTxBody era, NFData (Tx SubTx era)) => NFData (DijkstraTxBodyRaw l era) where
-  rnf txBodyRaw@(DijkstraTxBodyRaw _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _) =
+  rnf txBodyRaw@(DijkstraTxBodyRaw _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _) =
```

**What git shows:** A modification (one wildcard added).

**Semantic meaning:** Purely mechanical. The pattern match must account for the new field.

**Why it's hard:** Without knowing a field was added, this looks like a mysterious edit.

**Pre-classification rule:** If a hunk only changes the number of wildcards in a pattern match, and the matched constructor had a field added elsewhere in the diff, classify as `addition:wiring:mechanical`.

---

### 2.5 Adding a case branch for a new constructor

**Source:** cardano-ledger PR #5645, `AlonzoScript` decoder

```diff
         1 -> decodeAnnPlutus SPlutusV1
         2 -> decodeAnnPlutus SPlutusV2
         3 -> decodeAnnPlutus SPlutusV3
+        4 -> decodeAnnPlutus SPlutusV4
         n -> Invalid n
```

**Why it's hard:** Structurally identical to a bug fix (adding a missing case) or a refactor. Only broader PR context reveals it is part of "add PlutusV4".

**Pre-classification rule:** Case branch additions where the new pattern uses a constructor from the same diff are `addition:wiring`.

---

### 2.6 Adding a comma/separator to accommodate new items

**Source:** cardano-ledger PR #5546, export list

```diff
-    dstbDirectDeposits
+    dstbDirectDeposits,
+    dstbAccountBalanceIntervals
   ),
```

**What git shows:** A modification of one line (adding comma) plus an addition.

**Semantic meaning:** The comma change is pure syntax noise.

**Pre-classification rule:** If a line's only change is adding/removing a trailing comma and the adjacent line is an addition/deletion, merge them into a single event.

---

### 2.7 Conditional CPP export for new prism

**Source:** lens PR #1095, `Language.Haskell.TH.Lens`

```diff
 #if MIN_VERSION_template_haskell(2,22,0)
   , _SCCP
+#endif
+#if MIN_VERSION_template_haskell(2,24,0)
+  , _SpecialiseEP
 #endif
```

**What git shows:** A new CPP block inserted. The `#endif` line appears as both deleted and added (the old `#endif` is replaced by the new block).

**Why it's hard:** The CPP interleaving makes it look like the existing `_SCCP` export was modified, when actually a completely independent conditional export was added after it. A classifier unaware of CPP will misread the hunk boundaries.

**Pre-classification rule:** CPP `#if`/`#endif` blocks should be treated as structural delimiters. New conditional blocks are additions even when they share a boundary with existing blocks.

---

## Category 3: Context-Dependent Hunks

Hunks that look structurally identical but have different semantic reasons.

### 3.1 Changing a type constraint (multiple possible reasons)

**Source:** cardano-ledger PR #5676

```diff
-  , BabbageEraTxBody era
   , AlonzoEraTx era
+  , ConwayEraTxBody era
```

Possible reasons: API evolution, bug fix, refactor, wiring for addition. The hunk is identical regardless. Only the PR description or surrounding hunks reveal the intent.

**Pre-classification rule:** Constraint changes should be tagged with confidence. If the same diff adds new methods to the replacing class, lean toward `addition:wiring`. If the replacing class is a strict superclass, lean toward `refactor`.

---

### 3.2 Removing a constraint (cleanup vs dependency removal)

**Source:** cardano-ledger PR #5671, removing `NoThunks` from `EraPlutusContext`

```diff
   , Eq (ContextError era)
   , Show (ContextError era)
   , NFData (ContextError era)
-  , NoThunks (ContextError era)
   , EncCBOR (ContextError era)
```

**Why it's hard:** A single `-` line. The reason only becomes clear when you see the same removal repeated across 15+ files (systematic dependency removal).

**Pre-classification rule:** If the same constraint is removed from 3+ locations in the same diff, classify as `removal:systematic`.

---

### 3.3 Modifying an import (three different reasons)

Identical-looking hunk:

```diff
-import Foo (bar)
+import Foo (bar, baz)
```

- In PR adding feature X: `baz` is needed by new code. Tag: `addition:wiring`.
- In PR cleaning up imports: `baz` was used but not imported. Tag: `cleanup`.
- In PR refactoring module Y: `baz` moved from Y to Foo. Tag: `refactor:wiring`.

**Pre-classification rule:** Cross-reference import additions with: (1) new call sites in the same file, (2) removals of the same name from other imports, (3) module restructuring in the same diff.

---

## Category 4: Hunk Boundary Problems

### 4.1 Blank line removal merging two semantic hunks

**Synthetic, based on cardano-ledger patterns:**

```diff
@@ -10,12 +10,10 @@
 import Data.Map.Strict (Map)
 import Data.Set (Set)
-import Data.Word (Word8)
-
-import qualified Data.ByteString as BS
+import qualified Data.ByteString as BS
 import qualified Data.ByteString.Lazy as LBS
```

**Semantic meaning:** Two independent changes: (1) remove unused `Word8` import, (2) remove blank line between import groups. The `ByteString` import did not change. The blank line removal is the "glue" that merged them.

**Pre-classification rule:** If removing a blank line between two hunks merges them but they have no semantic relationship, split at the blank line boundary and tag the blank line removal as `formatting`.

---

### 4.2 Indentation change mixed with logic change

**Source:** cardano-ledger PR #5676, `conwayUtxoTransition`

```diff
-  updatedUtxos <- trans @(EraRule "UTXOS" era) $ TRC (pp, utxos, tx)
-  updateUTxOStateByTxValidity pp certState (utxosGovState utxos) tx updatedUtxos
+  () <- trans @(EraRule "UTXOS" era) $ TRC (ConwayUtxosEnv pp (utxosUtxo utxos), (), tx)
+  updateUTxOStateByTxValidity
+    pp
+    certState
+    (utxosGovState utxos)
+    tx
+    (updateTreasuryDonation tx utxos)
```

**What git shows:** Two lines replaced by seven. Looks like a major rewrite.

**Semantic meaning:** The real changes are: different environment tuple, `()` instead of `updatedUtxos`, and `updateTreasuryDonation tx utxos` as last argument. The reformatting obscures this.

**Pre-classification rule:** Before semantic classification, normalize formatting (collapse multi-line expressions to single-line). Then re-diff to isolate logic changes from formatting.

---

### 4.3 Code movement (delete + add across files)

**Source:** cardano-ledger PR #5676

In `Utxos.hs`:
```diff
-import Cardano.Ledger.Shelley.LedgerState (UTxOState (..), utxosDonationL)
```

In `Utxo.hs`:
```diff
+import Cardano.Ledger.Shelley.LedgerState (UTxOState (..), utxosDonationL)
```

Git has no cross-file move detection for lines within files. A classifier processing hunks independently tags one as `deletion` and the other as `addition`.

**Pre-classification rule:** After collecting all hunks, look for deleted lines that appear (modulo whitespace) as additions in other files. Tag both as `move:source` and `move:destination`.

---

### 4.4 Systematic removal across many files

**Source:** cardano-ledger PR #5671, removing NoThunks instances across 10+ files

Each file has:
```diff
-import NoThunks.Class (NoThunks)
```
and:
```diff
-instance
-  ( NoThunks (TxOut era)
-  , NoThunks (Value era)
-  ) =>
-  NoThunks (SomeType era)
```

**What git shows:** 30+ independent deletion hunks.

**Semantic meaning:** One semantic change expressed as 30+ hunks.

**Pre-classification rule:** Group hunks by pattern. If N hunks across M files all delete the same import or the same instance shape, merge into a single semantic unit tagged `removal:systematic`.

---

### 4.5 Trailing whitespace change glued to real change

**Source:** aeson PR #1106

```diff
 instance value ~ Value => KeyValue Value (KM.KeyMap value) where
     (.=) = explicitToField toJSON
     {-# INLINE (.=) #-}
-    
+
     explicitToField f name value = KM.singleton name (f value)
```

**Semantic meaning:** Zero. Trailing space removed. But if another real change were within 3 lines, this would merge them into one hunk.

**Pre-classification rule:** Strip whitespace-only changes before semantic classification. Re-run diff without them to get clean hunk boundaries.

---

## Category 5: Composite Pattern — Feature Addition Cascade

### 5.1 New feature with cascading wiring

**Source:** cardano-ledger PR #5546, adding `AccountBalanceIntervals`

This single feature produces:
1. **Data type definition** (pure addition, 30 lines)
2. **CBOR encoder/decoder** (pure addition, 25 lines)
3. **CDDL schema** (pure addition, 10 lines)
4. **Record field addition** (1 line each, 2 records)
5. **Export list additions** (1 line each, 3 modules)
6. **Import additions** (1 line each, 5 modules)
7. **Pattern match adjustments** (wildcard count changes, 4 locations)
8. **NFData rnf chain extension** (modify deepseq chain, 2 locations)
9. **Encoder/decoder field wiring** (add serialization case, 2 locations)
10. **Pattern synonym extension** (add field to pattern, 1 location)
11. **CDDL HuddleSpec wiring** (constraint + rule instance, 3 locations)
12. **Test adjustments** (tangential)
13. **CHANGELOG entry** (documentation)

**Total: ~35 hunks for 1 semantic change.**

A good classifier should group hunks 2-13 under one semantic unit.

---

## Summary: Pre-Classification Rules

Rules are divided into **universal** (language-agnostic, always active) and **language plugin** (require language-specific knowledge, opt-in).

### Universal Rules (Layer 1 — structural preflight)

| Rule | Pattern | Tag |
|------|---------|-----|
| R4 | Trailing comma added adjacent to new item | `addition:formatting` (merge with item) |
| R6 | Same change repeated in 3+ files | `removal:systematic` or `addition:systematic` |
| R7 | Deleted line appears as addition in other file | `move` |
| R8 | Whitespace-only line change | `formatting` (strip before classification) |
| R10 | Blank line removal between unrelated changes | Split at boundary |
| R11 | Reformatted expression with changed arguments | Normalize formatting, then re-diff |
| R12 | N hunks sharing same deletion/addition pattern | Group into single semantic unit |

### Language Plugin Rules (Haskell — reference implementation)

These rules are handled by the LLM semantic preflight (Layer 2) when no plugin is active. With the Haskell plugin, they become deterministic.

| Rule | Pattern | Tag |
|------|---------|-----|
| R1 | Export of name defined in same diff (`module...where`) | `addition:wiring` |
| R2 | Import of name used only in new code (`import`) | `addition:wiring` |
| R3 | Import list is strict superset of old | `addition:wiring` (not `modification`) |
| R5 | Wildcard count change in pattern match | `addition:wiring:mechanical` |
| R9 | New case branch with constructor from same diff | `addition:wiring` |
