# Phase 2 — Smart Download Manager

Status: architecture and discovery; no download behavior implemented.

## Non-negotiable constraints
- Keep the existing production build and Phase 1 notification PR unchanged.
- Reuse the current download/playback mechanism if present; do not add a competing pipeline.
- Downloads and metadata stay on the user's device. No cross-customer data transfer.
- Never delete existing files during upgrades or on failed transfers.
- Keep playback functional if a download fails or is cancelled.

## Discovery checklist (before implementation)
1. Locate download entry points, platform implementations, and any media player cache/offline support.
2. Identify supported downloadable content and whether source URLs permit durable downloads; streaming-only/DRM content may not support this.
3. Identify current persistence, permissions, file locations, background execution, and notification behavior.
4. Check Android and shared Kotlin Multiplatform module boundaries.

## Proposed state model
`Queued`, `Running(bytesDownloaded, totalBytes?)`, `Paused`, `Completed(localUri)`, `Failed(retryable, reason)`, `Cancelled`.

- Unknown total size must display indeterminate progress, not a fabricated percentage.
- Resume only when the source supports byte ranges and validators match; otherwise restart safely.
- Persist state atomically and reconcile incomplete transfers on startup.
- Download into a temporary file; publish as complete only after validation and atomic finalization.
- Cancel must not delete a previously completed download.

## Planned implementation sequence
1. Inventory and tests for current download behavior.
2. Repository and state persistence layer, with migration-safe defaults.
3. Queue and progress UI, with a single source of truth.
4. Pause/resume/cancel and recovery with platform capability checks.
5. Storage accounting, safe deletion, and low-space handling.
6. CI debug APK and on-device verification; release only after approval.

## Acceptance tests
- Normal download; unknown content length; network loss; app restart; insufficient storage.
- Pause/resume with and without HTTP range support; cancellation; retry after failure.
- Completed file remains accessible after app restart and unrelated settings changes.
- Existing playback, notification settings, and watch data remain unaffected.
- No customer release or signing changes.

Related tracking: #12. Multiple profiles remain a future, separate phase.


## Existing Android downloader identified (integration decision)

The Android app already has a production-capable transfer subsystem:

- `AndroidDownloadScheduler` schedules user-initiated JobScheduler jobs on Android 14+ and WorkManager work on older supported devices. Its `restore` method handles missing jobs by pausing interrupted downloads.
- `AndroidDownloadStore` persists `AndroidDownloadTransfer` records individually using `android.util.AtomicFile`, with a generation token to reject stale worker updates.
- `AndroidDownloadLifecycleTest` verifies persisted jobs, process recreation, missing-job recovery, stale-worker isolation, and background transfer behavior.

**Do not replace this store, reschedule its jobs from the new queue, or independently delete media files.** The existing scheduler/store remains authoritative for real transfer execution, credentials, retry state, progress, and downloaded file locations. The new `DownloadQueueRepository` is a prospective presentation/ordering layer only, and its separate `download_manager/queue-v1.json` must not become a second authoritative transfer state.

Before enabling the new lifecycle bridge or UI, implement a read-only mapping from existing `DownloadItem`/`DownloadStatus` to the queue presentation model, including stable identity and file URI semantics; then test restart, cancellation, retry, and generation races against the existing Android lifecycle tests. Decide whether a second persisted queue is needed at all: prefer deriving visible state from `AndroidDownloadStore.transfers` and storing only metadata that the existing store does not own (such as manual ordering). Keep the bridge opt-in until that decision is implemented and tested.
