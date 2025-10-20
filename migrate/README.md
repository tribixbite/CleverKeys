# Migration of Review and Status Files

The contents of `CURRENT_SESSION_STATUS.md` and `REVIEW_COMPLETED.md` have been migrated to this directory to improve maintainability.

- `project_status.md`: High-level project status, bug counts, session summaries, and achievements.
- `completed_reviews.md`: A consolidated archive of all detailed file-by-file comparison reviews.
- `todo/`: A directory containing categorized tasks.
  - `critical.md`: Showstopper bugs and immediate fixes required to get the keyboard functional.
  - `core.md`: Bugs and missing features in the core keyboard logic (parsing, event handling, etc.).
  - `features.md`: Missing user-facing features (autocorrection, spell check, etc.).
  - `neural.md`: Issues related to the swipe prediction and ONNX machine learning pipeline.
  - `settings.md`: Bugs and TODOs for the settings and preferences UI.
  - `ui.md`: UI-related bugs and missing features (theming, suggestion bar, layouts).
