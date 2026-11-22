# Results scroll context evidence

## What was reproduced
The current phone baseline is report33d1b6ba-6da5-4993-b0fe-233aac21ba00 from861/source9fa:89tasks,81verifications,5summaryfailures,0newlessons. It contains11KSLscroll and3load-more unmet-expectation events. No862physicalphone report has been received. Those phone events are symptoms; the following reproduction does not prove the live-site root cause.

Production content.js had a deterministic gap: if the document was scrollable, scrollBy ignored a nested results scroller. If the document was fixed, its fallback could choose a larger unrelated div and missed a section results panel. Two real Chromium regressions failed on actual panel movement before the change; the ordinary root-scroll control passed. An extra listing-navigation command was considered and rejected because the existing reader already directly opens canonical observed listing anchors in TRAIN mode.

## Change
The reader chooses the innermost visible scrollable ancestor shared by the observed canonical listing anchors. Unrelated sidebar size cannot override their ownership. Root/fallback behavior remains when no results panel is observed. A separate typed ScrollContext carries a closed ROOT/RESULTS_CONTAINER/UNKNOWN kind and bounded geometry while preserving the original window viewport/bbox meanings. Opaque document/surface IDs exist only in transient observations, never model summaries or stored learning. Invalid geometry cannot fall back into false root-success credit; matching surface identity is required for movement/bottom comparison.

Old ROOTfailure identities remain byte-compatible. A RESULTS_CONTAINER SCROLL gets one stable new execution-contract identity; changing opaque surface IDs or listing contents does not create endless fresh retries. The old failed approach remains saved and held. Existing geometry diagnostic counters now describe the selected scroll surface; local typed context omits identity. Automatic phone reports use the existing3allowlisted counters and schema2, with no new fields or validator/uploader migration. Private text, URLs, queries and arbitrary extras remain excluded.

Bridge version advances3.1.10→3.1.11 so retained Gecko profiles refresh the reader. Android version is still allocated by the existing release automation. Signing and workflows are untouched.

## Test-first evidence
- Browser RED:2expected failures/3tests on old actual reader, then3/3GREEN; extended8/8 including real newitems, root/sidebar isolation, stale document/panel, detail-description scrolling and no-op negatives. Added detail-related-card RED (1expected falseSTALE assertion) then fixed cached results eligibility before any publication; all8cases passed.
- Typed RED:8tests,7expected assertion failures/errors0; GREEN8/8. Additional diagnostic RED:11tests,1expected assertion failure because targety280 was incorrectly logged as root0; updated projection passed focused semantic/phone-export tests.
- Whole-engine Chromium test: existing old-root failure retained; training produced genuinely new item identities, completed the scrolling lesson in1action with0teacher calls, and saved it. Two freshJVM/layout replays retained the completed lesson and verified newitems in1action each, no newlesson falsely claimed during reuse. Old-root failure count stayed1before/after everypositivecase. Root/sidebar offsets stayed0.
- End/no-op/human negatives earned0newlessons; human0actions. These use independent DOM new-item state and actual file-backed reload, not command acknowledgment as reward.
- Pinned862reader ablation (SHAe65bf1fb6ee2999e9324a6f091071d88a9a0b5fe3f4d40ee2aa6589edf78c55e) with the same current core and retained root failure made0actions, gained0items/lessons and preserved the hold. It is a renderer/context ablation, not an entire old APK comparison.

These are generated Chromium/current-core tests, not physicalphone or real-site mastery. No actual AI request was needed for this deterministic actuator fix. The fixture network RPC is a stub; separate existing tests retain network-interlock coverage. This update does not claim to fix OfferUp listing navigation or the separate intermittent emulator restart ANR.

## Runtime baseline and release gates
Build862 full Android cycle572f9822-b2c0-4df9-afc0-af829b26211d completed07:35:52UTC:12verified,0failed,0newlessons; app/emulator restart fingerprints matched0673624dc1c317f1ac02d8158695260434cc8303bae35b400c3b9382aa7b226f. That is retained reuse. The older9cb79b39… emulator-restart ANR remains recorded; one succeeding cycle does not establish its cause or cure.

Private evidence: /root/projects/ai-browser-live-verification/results-scroll-context-20261122. Fullsource/Android/City checks, independent exactsource/operations reviews and unchanged stable signed pipeline remain required before publication. Preserve the existing City/uploader/review automation and human phone installation.
