# SemanticDB Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Enable SemanticDB generation for normal compile runs across the Scala 3 multi-project build.

**Architecture:** Add one shared build-level compile setting in `build.sbt` using `semanticdbEnabled` so every module emits SemanticDB metadata during compilation. Keep the scope limited to `Compile` and avoid adding any sbt plugin.

**Tech Stack:** sbt 1.12.5, Scala 3.3.7, Metals, SemanticDB

---

### Task 1: Add Compile-Scoped SemanticDB Flag

**Files:**
- Modify: `build.sbt`

**Step 1: Update build configuration**

Add `ThisBuild / Compile / semanticdbEnabled := true` near the existing shared Scala build settings.

**Step 2: Verify the build still compiles**

Run: `sbt compile`
Expected: Build succeeds and SemanticDB metadata is generated during compile.

**Step 3: Confirm no plugin changes were introduced**

Check: `project/plugins.sbt`
Expected: No SemanticDB plugin added.

**Step 4: Keep the change minimal**

Do not modify `Test` settings or unrelated project definitions.
