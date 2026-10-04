# AI Browser 7.2.4 release record

Release goal: make the completed simulation-tested engine corrections available
through the established in-app updater, starting from 7.2.3 (code 88).

Source: simulation branch revision 56c8a51dccc86dd1fe2fde4fb853c636c0fad641.
Rollback: 38eccb4cf702002fea0893c33c6c1a6d5f51bde6, retained on
rollback/site-brain-7.2.3-simulation-base.

The simulation implementation is already complete; this release does not redo
those tasks or import synthetic memory. The user's instruction to finish the next
installable update authorizes release allocation and publication. Version 89 is
allocated in ai-browser-release.json; the existing preparation script remains
the sole writer of generated Android version declarations. Signing configuration,
keys, secrets, permissions, and build workflows are unchanged.

Acceptance checks:
- Core regression tests and the frozen-memory simulation experiment.
- Document-boundary fixtures and absence of lab classes in the production jar.
- CI browser-perception fixtures and Android unit tests.
- Release APK build, stable signing certificate verification, and updater publish.
- Published metadata must identify version 89 and the release commit.

Phone testing remains necessary for live-site behavior; synthetic results are not
claims of real-world site mastery or overnight stability.
