# Android / iOS review round — Android closeout (2026-09-27)

## Scope and inherited evidence

- This is a documentation and acceptance closeout only. It adds no product finding number, code change, version change, commit, or commit hash. Android production/test sources and iOS files were not changed by this package.
- Controller reports A-CORE, A-FILES and A-UI accepted in the shared Android working tree. The prior full JVM run was 336/336 and prior `assembleDebug` / `assembleRelease` passed. Their source changes remain uncommitted for controller review.
- I-EVID supplied the current Swift writer archive at `/Users/enene/Data/1919/PPGCollector/PPGCollectorTests/Resources/ReviewRound/swift_sessions.zip`: 6 sessions, 34 entries, SHA-256 `008ab9f12adb4c09d54903ec8ed106e23413e01c6569639751b8748eedd08d5e`. This path was used directly; the archive was not copied into Android.
- Android consumes this cross-package input through `-PppgReviewSwiftFixtures=<zip>`, mapped to the `ppgReviewSwiftFixtures` JVM system property read by `ReviewCrossPlatformContractTest`. The suite rejects a missing explicit override instead of silently substituting its checked-in baseline.

| Accepted package | Actual current implementation/API inspected | Fresh evidence in this round |
|---|---|---|
| A-CORE | `CaptureServiceLifecycle`, `CaptureForegroundService`, `CaptureRecordingController`, plus generation/freshness health monitor; lifecycle transaction owns startup-to-terminal handoff. | `CaptureServiceLifecycleTest` 11 and `CaptureRecordingControllerTest` 14 passed inside the full 336-test JVM run. |
| A-FILES | `CaptureSessionAccessRegistry`, `CaptureCsvRowValidator`, `CaptureSignalLoadBudget`, `CaptureArtifactSummary`; Swift fixture input is `-PppgReviewSwiftFixtures=<zip>`. | `ReviewCrossPlatformContractTest` 3, `ReviewAndroidFixtureProducerTest` 1, `ReviewLowHeapContractTest` 2, metadata 6 and recovery 7 all passed. |
| A-UI | `CaptureServiceViewModel`, `PendingSessionExport`, `SessionsViewModel`, `SessionRenderModel`, adaptive Compose screens and compact caption policy. | `PendingSessionExportTest` 5, `SessionsViewModelReviewTest` 12, `CaptureUiPolicyTest` 9, `ReviewAdaptiveLayoutTest` 3 and `CaptureFormLifecycleTest` 6 passed. |

No additional unfinished item was reported for the accepted packages in the controller summary. The new lint finding below is the only failure observed in this package's required Android gate.

## Source and contract findings

| Area | Current status and evidence |
|---|---|
| Service startup and terminal cleanup | `CaptureServiceLifecycle` publishes `STARTING`, retains the transaction through asynchronous initialization/finalization, and only releases the owner after `FINALIZED` or `FAILED`. `CaptureServiceLifecycleTest`: 11 tests passed. This is JVM evidence; Android process/notification behavior remains a device gate. |
| Background legal-frame health | Recording raw ingress remains separate from UI preview. `CaptureStreamHealthMonitor` checks generation/phase and freshness once per second; disconnect/generation change maps to `DEVICE_DISCONNECT`, and stale freshness beyond 5 seconds maps to `DATA_TIMEOUT`. `CaptureRecordingControllerTest`: 14 tests passed. No continuous real BLE background run was performed. |
| Session token, lease and final force | Controller increments `sessionToken`; queued metric/BP/participant work checks session identity/token. `CaptureSessionAccessRegistry` serializes writer/export/delete/recovery access. Stop drains analysis and sidecar queues before writer finalization/force. `CaptureSessionAccessRegistryTest`: 3 tests passed; `CaptureRecordingControllerTest`: 14 passed. This does not claim device power-loss durability. |
| BP writer cursor | A BP event uses the accepted PPG cursor frozen when the user commits; the writer stores a monotonic cursor/time pair. `CaptureRecordingControllerTest`: 14 tests passed, including accepted-cursor freeze; `CaptureSidecarTest`: 4 passed, including cursor/time and safe-tail validation. |
| CSV, sidecar and metric provenance | Session inspection cross-checks raw and CSV samples/identity/counts; metrics and BP validate cursor/time/value constraints; optional ECG validates identity and derived row count. CSV `sqi` remains template-match SQI. Combo SQI remains provisional display feedback and makes no calibrated medical claim. |
| Recovery source and compatibility | `source_session_id` identifies the original source across a recovery chain; `parent_session_id` identifies the direct parent. New recovery provenance fields decode as optional/defaulted. Recovery copies safe prefixes and preserves hashes without modifying source raw. Legacy session v1, 408 raw/CSV, and older analysis artifacts remain readable. `CaptureSessionMetadataTest`: 6 tests; `CaptureSessionRecoveryServiceTest`: 7 tests, all passed. |
| Bounded archive, offline summary and plotting | Archive/artifact summaries use bounded reads; offline rendering uses a memory budget and bucket summary and can publish raw/filter partial signal before later stages/metrics. Budget or analysis failure remains local to that stage. `ReviewLowHeapContractTest`: 2 tests passed. |
| Pending export, notification permission and compact caption | Pending export persists kind, paths and token in `SavedStateHandle`; only a matching result consumes it (`PendingSessionExportTest`: 5 passed). Notification permission gate passed 6 policy tests and a Robolectric Activity permission-return test; physical Settings interaction remains untested. Compact caption removes the score already displayed as the tile value; `CaptureUiPolicyTest`: 9 and `ReviewAdaptiveLayoutTest`: 3 passed, including scaled text layout. |
| Real Swift-writer archive read by Kotlin | The actual current archive was read and checked through the `ReviewCrossPlatformContractTest` suite: 3 tests passed, including explicit-file enforcement and raw/CSV/ECG/session inspection. `ReviewAndroidFixtureProducerTest`: 1 passed and confirms current Kotlin writer/archive output against its frozen expected fixture. |

No new numbered finding was introduced. Any controller-frozen GAP remains controller-owned; this docs-only package does not claim to close or renumber it. The Android run below re-executes the Swift-writer-to-Kotlin-reader leg and the Kotlin producer fixture gate. It does not run an iOS test process against newly generated Android output in this Android-only WP; any claim that the reverse Swift reader leg was re-run must come from the accepted A-FILES/I-EVID report, not this Android command.

## Acceptance runs

Java runtime for each command: `/Applications/Android Studio.app/Contents/jbr/Contents/Home`.

1. `./gradlew --no-daemon :app:testDebugUnitTest -PppgReviewSwiftFixtures=/Users/enene/Data/1919/PPGCollector/PPGCollectorTests/Resources/ReviewRound/swift_sessions.zip` — **passed**. JUnit XML: 336 tests across 64 suites, 0 failures, 0 errors, 0 skipped. Swift archive and Kotlin contract suites listed above executed with non-zero test counts.
2. `./gradlew --no-daemon :app:assembleDebug` — **passed** (`BUILD SUCCESSFUL`; tasks up-to-date).
3. `./gradlew --no-daemon :app:lintDebug :app:assembleDebugAndroidTest :app:verifyReleasePrivacy` — command **failed overall** because `lintDebug` found 1 error, 30 warnings and 2 hints. `assembleDebugAndroidTest` completed successfully/up-to-date. `verifyReleasePrivacy` passed with `REL-005 privacy/profile audit passed`; release assembly tasks were up-to-date. The sole lint error is `SessionsViewModel.kt:451`: `Path::of` is API 34 while the project minimum is API 26. This file belongs to accepted A-UI scope; it is not modified here. Controller fixed it (`Paths.get`) and reran the full gate `lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy`: BUILD SUCCESSFUL, lint 0 errors (30 warnings), 336 JVM tests 0 failure/error/skip.

The non-fatal Gradle configuration-cache report during command 3 was discarded by Gradle; it did not change the lint failure or the successful AndroidTest/privacy task results.

## Automated evidence vs. device gates

Automated evidence is limited to the exact JVM/fake/file and build results above. No real device was used. Keep these checks pending:

- Background capture with a sustained legal BLE stream, UI/activity recreation, actual disconnect, and stale-stream finalization.
- Android notification permission grant/denial and the real Settings return path.
- R9: one pre-record BP plus two in-record BP events, post-stop form clearing, duplicate-name invalidation, detail timeline, and requested/actual MTU or fallback.
- R10: inspect one high-gap session from before board repair and one after; compare raw evidence, repaired ZERO/FIXED, metric provenance, BP cursor and marker toggle.
- R11: normal stop/restart and recovery-copy detail/ECG/BP identity.
- R12: RAW polarity, invalid/missing metric sidecar partial display, and bounded/offline-stage failure on real session data.
- Compact caption/layout and notification text under real device font/display sizes. No UI pixel-parity or platform-API translation gate is added.

## Stop point

All automated Android gates pass after the controller lint fix. Reverse leg (Kotlin writer → Swift reader) is covered by iOS `R4AndroidImportContractTests` on the byte-identical `android_sessions.zip` (controller full iOS run). Real-device gates above remain pending. Committed by the controller as V3.R13.
