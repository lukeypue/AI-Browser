# Synthetic Android curriculum fixtures

These four presentation styles use the same deterministic 12-item synthetic catalogue.
This fixture-only compatibility repair does not change the Android app, core verifier,
authentication boundaries, or release candidate.

The curriculum can now exercise recognized description expansion (>40 new visible
characters), categorical Condition selection with real filtering and URL persistence,
physical price sorting immediately after selection, and bounded new cards after scrolling. Load more remains a
separate explicit control. No task completion or retained learning is claimed until a
fresh native run measures it.

## Verification

Run the normal browser suite with the repository's Playwright runtime:

```sh
node --test --test-concurrency=1 ai-browser/brain-core/src/test/harness/*.test.mjs scripts/tests/*.test.mjs
```

For additional real Kotlin parser/verifier proof, supply all three variables:

- `FIXTURE_CORE_CHECKOUT`: checkout whose existing compiled core will be read.
- `FIXTURE_CORE_REVISION`: full pinned commit of that checkout.
- `FIXTURE_CORE_CLASSPATH`: its `ai-browser/brain-core/build/classes/kotlin/main`,
  followed by the existing Kotlin stdlib jar, separated by `:`.

The Java17 source launcher runs with a 128 MiB heap and a 20-second deadline.
Supplying missing classes, a different revision, modified core source, or classes
older than the parser/verifier sources fails the test. It never invokes Gradle.
Test names explicitly identify the additional pinned Kotlin proof; ordinary browser
runs measure DOM behavior and bundled content.js observations.

The 16 curriculum cases cover all four styles. Additional Kotlin checks use
real LearningOpportunities targets, the builtin sort postcondition, TextExpanded,
ConstraintApplied(condition, Good), and NewResults with unchanged
observation negative controls. Sorting asserts actual card order and identities.

## Separate bundle deployment

Generate into a new, inactive directory only:

```sh
node scripts/android-lab/fixture_builder.mjs /absolute/path/to/new-inactive-bundle
sha256sum /absolute/path/to/new-inactive-bundle/manifest.json
```

`manifest.json` contains SHA-256 hashes for the four HTML files and shared JavaScript.
Record the fixture commit and manifest hash alongside the exact final candidate
app source. This compatibility proof used the existing compiled core at
`61e8a0f34b012ac68b4cbf8071a0af1483f2d20a`. Promotion must wait until the
current native journal and restart evidence are finalized. Preserve the old bundle
for rollback. A native acceptance run must then qualify the final candidate and
fixture bundle together before any retained learning gain is claimed.
