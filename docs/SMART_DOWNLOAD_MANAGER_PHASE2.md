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
