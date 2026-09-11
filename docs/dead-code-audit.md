# Dead code audit

Findings from the codebase review of 2026-09-03, verified against the tree at `5f1bddb9`.
The findings were unreferenced classes, orphaned schema, and one latent data-integrity gap.
Tracing items 1-4 before deleting them also turned up two live bugs, now fixed (see "Real bugs
found while tracing these"). Recorded so they can be closed deliberately rather than
rediscovered.

Each item lists what it is, why it is dead, and what was done. **All seven were resolved on
2026-09-11.**

---

## 1-4. Unreferenced classes — deleted 2026-09-11

Before deleting, each was traced through git history to rule out "unbuilt feature" as opposed
to "dead". None was scaffolding for something unfinished — each was either superseded or lost
its only caller deliberately.

| Class(es) | History | Outcome |
|---|---|---|
| `LoginRateLimitService` | Added in `f7650d9f`; the same day `03272f7c` added `AuthRateLimitService`, moved every call site over, and left the old file behind. The replacement is a strict superset (username-keyed limits, refresh-token limits, bounded cache). | Deleted |
| `AdminEventBroadcaster` | Added in `63dc2bcb` to toast admins when a file failed to import during a folder-as-book scan. The multi-format rewrite `3f334202` deleted that processor and both call sites — and the behaviour went with them. | Deleted; behaviour restored (below) |
| `BookMetadataAuthorMapping`, `BookMetadataCategoryMapping`, `BookShelfMapping` + their repositories and `*Key` id classes | A batch-fetch optimisation (`7a4a401e`, June 2025) replaced six days later by `@EntityGraph` (`32b35d4a`). When author ordering added `sort_order` to `book_metadata_author_mapping` (`9c249fff`), the stale entity was not updated — its `(book_id, author_id)` id no longer matched the table's `(book_id, sort_order)` primary key, so reviving it would have been actively wrong. | Deleted; tables untouched (live via the `@ManyToMany` `@JoinTable` mappings) |
| `ComicCreatorMappingRepository` | Created with the comic-metadata feature (`c1c72ea7`); its sibling repositories are all used, this one never was. | Deleted — but its unused `deleteByComicMetadataBookId` pointed at a real bug (below) |
| `UserSettingRepository` | Its only caller ever was `TelemetryService` (counting Hardcover-sync users for the install ping); upstream removed telemetry deliberately in `23559d8b` and missed this file. | Deleted |

### Real bugs found while tracing these

**Comic creators were duplicated on every metadata save.** `ComicMetadataEntity.creatorMappings`
is the inverse (`mappedBy`) side with no `orphanRemoval`, so `BookMetadataUpdater.updateCreatorRole`
clearing a role before re-adding its creators only emptied the in-memory set — the old rows
survived the flush. Every save that carried creators therefore re-inserted all of them: an
unchanged inker became two rows, a replaced penciller kept the old name alongside the new, and
clearing a role did nothing. The UI hid it (the DTO mapper collects names into a `Set`), but magic
shelves matched stale creators and `CbxMetadataWriter` joins names without de-duplicating, so
write-back put `"A, A"` into `ComicInfo.xml`.

A second defect masked part of it: `MetadataChangeDetector.hasCreatorChanges` compared only the
total creator *count*, so swapping one creator for another (or moving a name between roles) was
judged "no change" and the save was skipped entirely.

Fixed with `orphanRemoval = true` and a per-role name comparison, guarded by
`ComicCreatorUpdateIntegrationTest` (real DB) and new `MetadataChangeDetectorTest` cases. Rows
accumulated before the fix are **not** cleaned up by a migration. Exact duplicates are never
legitimate and can be counted with:

```sql
SELECT count(*) FROM (
  SELECT 1 FROM comic_metadata_creator_mapping
  GROUP BY book_id, creator_id, role HAVING count(*) > 1) d;
```

Prod had 0 duplicate groups when checked on 2026-09-11, so no cleanup was needed there.

Any later metadata edit to an affected book rewrites its creator rows cleanly, but a save with
no changes is skipped by the detector and leaves the duplicates in place. Stale rows with a
*different* name cannot be told apart from legitimate ones without history.

**Failed imports were invisible to admins.** Since `3f334202`, a file that threw or produced no
book during a scan only reached the server log; the UI still said "Finished processing library".
`FileAsBookProcessor` now returns the failed file names and `LibraryProcessingService` reports
them as a WARN to users with `ADMIN` or `MANAGE_LIBRARY` (so scheduled scans with no requesting
user are covered too). Because the UI's live-notification box shows only the latest `LOG`
message, the failure summary *replaces* the "Finished…" message rather than following it — a
separate message would be overwritten a few milliseconds later. Deliberate skips (unsupported
type, no book file in a group) are not reported, matching the original behaviour.

---

## 5-7. Dead schema and missing FKs — resolved 2026-09-11 in `V8`

Checked against prod (bookshelfserver) first: `epub_viewer_preference` and `book_award` had 0
rows, and there were 0 orphaned rows in the viewer-preference tables. `V8__Drop_dead_tables_and_add_viewer_preference_fks.sql`:

- **5. `epub_viewer_preference` — dropped**, along with `EpubViewerPreferencesEntity`,
  `EpubViewerPreferencesRepository`, `EpubViewerPreferencesMapper`, the `EpubViewerPreferences`
  DTO, and its entry in `BookMergeService`'s table list (a native `UPDATE` there would otherwise
  have failed every merge once the table was gone). It stopped being written when upstream's new
  eBook reader (`8c35f241`, Jan 2026) switched to `ebook_viewer_preference`; that commit created
  the new table but never carried the old rows over. Prod had none to lose.
- **6. Missing FKs — added.** `cbx_`, `pdf_` and `new_pdf_viewer_preference` now have
  `ON DELETE CASCADE` FKs to `book` and `users` plus a `book_id` index, matching
  `ebook_viewer_preference`. The migration deletes orphans first, which was a no-op on prod but
  keeps it safe on other installs.
- **7. `book_award` — dropped.** The original write-up here ("scaffolded, never built") was
  wrong. It was a working feature from `775f341c` (Jan 2025) to `7a4a401e` (Jun 2025): the
  GoodReads parser read `awardsWon`, `BookMetadataUpdater` stored them, and the UI had an
  award-winner filter. Upstream removed all of it in a load-time optimisation and left the table.
  If awards are wanted again, design a fresh table (this one's `awarded_at NOT NULL` fits GoodReads
  data poorly) and load it on the book detail page only.

---

## Not findings

Recorded so they are not re-investigated:

- **`AuditAction` enum** — all 51 values are emitted somewhere. `DUPLICATE_BOOKS_MERGED` was the
  last unused one and is now wired in `BookMergeService`.
- **`ComicCreatorMappingEntity`, `UserSettingEntity`** — live, reached via cascaded collections.
  Only their repositories were dead (item 4, now deleted).
