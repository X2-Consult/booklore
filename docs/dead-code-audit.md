# Dead code audit

Findings from the codebase review of 2026-09-03, verified against the tree at `5f1bddb9`.
The findings were unreferenced classes, orphaned schema, and one latent data-integrity gap.
Tracing items 1-4 before deleting them also turned up two live bugs, now fixed (see "Real bugs
found while tracing these"). Recorded so they can be closed deliberately rather than
rediscovered.

Each item lists what it is, why it is dead, and the recommended action. Items 1-4 were
resolved on 2026-09-11; **items 5-7 are still open.**

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

## 5. `epub_viewer_preference` — orphaned feature slice, needs a decision

Three Java files and one table, all unreferenced:

- `model/entity/EpubViewerPreferencesEntity.java`
- `repository/EpubViewerPreferencesRepository.java`
- `mapper/EpubViewerPreferencesMapper.java`
- table `epub_viewer_preference` (`V1__baseline_schema.sql:494`)

EPUB reader preferences **are** saved today — via `EbookViewerPreferenceEntity` /
`ebook_viewer_preference`, written by `BookUpdateService:139-157`. Of the five viewer-preference
entities, this is the only one `BookUpdateService` does not touch:

| Entity | Wired into `BookUpdateService` |
|---|---|
| `EbookViewerPreferenceEntity` | yes |
| `PdfViewerPreferencesEntity` | yes |
| `NewPdfViewerPreferencesEntity` | yes |
| `CbxViewerPreferencesEntity` | yes |
| `EpubViewerPreferencesEntity` | **no** |

So this is the pre-`ebook_viewer_preference` generation, left behind when the newer generic
table superseded it. Note the old table's columns are not a subset of the new one's — it has
`letter_spacing`, `spread` and `custom_font_id`, which `ebook_viewer_preference` does not.

**The open question is whether user data was stranded in the old table at that transition.**
On the dev box (`ai-webserver`) all five tables are empty, which proves nothing — that box has
almost no reading history. Run this on **prod (`bookshelfserver`)** before deleting anything:

```sql
SELECT count(*) FROM epub_viewer_preference;
```

**Action:**
- If prod returns 0 — delete the three Java files and drop the table in a Flyway migration.
- If prod returns > 0 — those are real reader settings that silently stopped being honoured.
  Decide whether to migrate them into `ebook_viewer_preference` (dropping the three columns
  that have no home) or accept the loss, then delete.

---

## 6. Missing FKs on four viewer-preference tables — real latent bug, worth fixing

Of the five viewer-preference tables, only `ebook_viewer_preference` has referential integrity
(`V1__baseline_schema.sql:1097-1098`):

```sql
ALTER TABLE "ebook_viewer_preference" ADD CONSTRAINT "fk_ebook_viewer_preference_book"
  FOREIGN KEY ("book_id") REFERENCES "book" ("id") ON DELETE CASCADE;
ALTER TABLE "ebook_viewer_preference" ADD CONSTRAINT "fk_ebook_viewer_preference_user"
  FOREIGN KEY ("user_id") REFERENCES "users" ("id") ON DELETE CASCADE;
```

`cbx_viewer_preference`, `pdf_viewer_preference`, `new_pdf_viewer_preference` and
`epub_viewer_preference` have **no FK to `book` or `users` at all**. (`epub_viewer_preference`
has only `epub_viewer_preference_ibfk_1` → `custom_font`.)

Consequence: deleting a book or a user leaves those rows behind permanently. Every other
`book_id` child table is `ON DELETE CASCADE`; these four are the exception, so the cleanup that
happens everywhere else silently skips them. It is slow junk accumulation rather than
corruption — ids are serial and never reused, so stale rows cannot resurface against a
different book — but it grows without bound and nothing will ever collect it.

Note this is **not** the same as the merge path: `BookMergeService` re-points all four tables
explicitly, so duplicate-collapse already handles them correctly. It is ordinary deletion that
leaks.

**Action:** a `V8` migration that deletes existing orphans, then adds the eight missing FKs.
Count them first on prod:

```sql
SELECT 'cbx',    count(*) FROM cbx_viewer_preference p       LEFT JOIN book b ON b.id = p.book_id WHERE b.id IS NULL
UNION ALL SELECT 'pdf',    count(*) FROM pdf_viewer_preference p       LEFT JOIN book b ON b.id = p.book_id WHERE b.id IS NULL
UNION ALL SELECT 'newpdf', count(*) FROM new_pdf_viewer_preference p   LEFT JOIN book b ON b.id = p.book_id WHERE b.id IS NULL
UNION ALL SELECT 'epub',   count(*) FROM epub_viewer_preference p      LEFT JOIN book b ON b.id = p.book_id WHERE b.id IS NULL;
```

(If item 5 resolves to "drop the table", `epub_viewer_preference` falls out of this one.)

---

## 7. `book_award` — scaffolded, never built

`V1__baseline_schema.sql:84` creates the table, `:954` adds a unique index on
`(book_id, name, category, awarded_at)`, `:1068` adds the FK to `book`. There is no entity, no
repository, no service, no endpoint, and no frontend reference — in either the Postgres schema
or the archived MariaDB one, so it has been empty since the beginning.

This is a designed-but-unimplemented feature rather than an accident: the table has a
deliberate composite key and cascade. Awards are available from both Goodreads and Hardcover,
which are already wired as metadata providers, so it is buildable.

**Action:** decide. Either build it (a metadata-provider field → table → book detail panel), or
drop the table in a Flyway migration and reclaim the idea later. Leaving an empty table with
constraints in the baseline schema is the worst of the three.

---

## Not findings

Recorded so they are not re-investigated:

- **`AuditAction` enum** — all 51 values are emitted somewhere. `DUPLICATE_BOOKS_MERGED` was the
  last unused one and is now wired in `BookMergeService`.
- **`ComicCreatorMappingEntity`, `UserSettingEntity`** — live, reached via cascaded collections.
  Only their repositories were dead (item 4, now deleted).
