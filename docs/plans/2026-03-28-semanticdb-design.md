# SemanticDB Build Design

## Goal

Enable SemanticDB generation during normal `Compile` runs so Metals in Zed can consume compiler-produced metadata for this Scala 3 multi-project build.

## Chosen Approach

Add a single shared `ThisBuild` setting in `build.sbt` that enables SemanticDB for `Compile` via `semanticdbEnabled`.

## Why

- Applies consistently to `user-api`, `user-service`, and `user-client`.
- Avoids per-project duplication.
- Uses the sbt-native SemanticDB setting already present in `zio-nats`.
- Avoids adding an sbt plugin, per request.
- Limits the change to compile-time indexing and does not affect `Test`.

## Expected Result

Running `sbt compile` emits SemanticDB data under each module's `target` directory, which should improve Metals navigation and indexing in Zed.
