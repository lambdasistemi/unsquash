# Changelog

## 1.0.0 (2026-04-06)


### Features

* add R15 — manifest changes always in first commit ([61d9f3a](https://github.com/lambdasistemi/unsquash/commit/61d9f3a9a9839f69a5f9ab86e1020fd9f85421f8)), closes [#76](https://github.com/lambdasistemi/unsquash/issues/76)
* add R15 rule — manifest changes ordered before source ([7da254b](https://github.com/lambdasistemi/unsquash/commit/7da254bd8186a8ac7307fee8f0079587283b24e4))
* extract language-specific preflight patterns into JSON profiles ([ba41576](https://github.com/lambdasistemi/unsquash/commit/ba415764f3ad58ac40fe8979ae11617cafe1ad96)), closes [#73](https://github.com/lambdasistemi/unsquash/issues/73)
* foundational core (T007–T015) ([#47](https://github.com/lambdasistemi/unsquash/issues/47)) ([9270b8a](https://github.com/lambdasistemi/unsquash/commit/9270b8a31f8cfc25609f05512a7bd04e50ff705d))
* generate meaningful commit messages from diff content ([#64](https://github.com/lambdasistemi/unsquash/issues/64)) ([c65a232](https://github.com/lambdasistemi/unsquash/commit/c65a2324f79c129f6feb13db80e1bb893576ef70))
* oracle-driven retry loop in sequencer ([#57](https://github.com/lambdasistemi/unsquash/issues/57)) ([f02ce99](https://github.com/lambdasistemi/unsquash/commit/f02ce99d728201e7cd4310e6707be208fae26179))
* phases 4–7 — consolidation, MCP, synthesis, e2e test ([#50](https://github.com/lambdasistemi/unsquash/issues/50)) ([9ebe308](https://github.com/lambdasistemi/unsquash/commit/9ebe3089ff71232738d2285430882893164df3de))
* ship language profiles for Python, TypeScript, Go, Java, C#, Ruby ([66302b6](https://github.com/lambdasistemi/unsquash/commit/66302b6f9eb0e7c2e573e23a9fe31f3725dccd54))
* US1 MVP — preflight rules, sequencer, CLI (T014–T022) ([#49](https://github.com/lambdasistemi/unsquash/issues/49)) ([4c17d1d](https://github.com/lambdasistemi/unsquash/commit/4c17d1dcfc87a1d72b166841bf3bd855d628eb4d))
* wire MCP server with latest sequencer, preflight, and message APIs ([b3f78b2](https://github.com/lambdasistemi/unsquash/commit/b3f78b2356b6c02016537c0ae08a04ef62a78373)), closes [#71](https://github.com/lambdasistemi/unsquash/issues/71)


### Bug Fixes

* babashka process API and new-file patch generation ([af790b6](https://github.com/lambdasistemi/unsquash/commit/af790b6303b190d631ad9e67c8d76ff2a9a5949d))
* correct MCP server namespace in bb.edn ([cd6e154](https://github.com/lambdasistemi/unsquash/commit/cd6e1543e17ed25228185c2efda4cd2f6ecdd660))
* handle monorepo layouts in file-path-to-module ([f3b50c9](https://github.com/lambdasistemi/unsquash/commit/f3b50c935eec5b784b385b679f98ba6f9b6b7dc0))
* new-file patch generation combines sub-hunks in line order ([4bf265c](https://github.com/lambdasistemi/unsquash/commit/4bf265cf2b3f9855a9136223df3d59001fd1fed8))


### Reverts

* remove R15 manifest-first from this branch ([d76483c](https://github.com/lambdasistemi/unsquash/commit/d76483cbe0fc1dd8ccb5d30c0858a799ce347f13))
