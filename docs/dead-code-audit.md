# Dead code audit

Findings from the codebase review of 2026-09-03, verified against the tree at `5f1bddb9`.
Nothing here is a live bug today — these are unreferenced classes, orphaned schema, and one
latent data-integrity gap. Recorded so they can be closed deliberately rather than
rediscovered.

Each item lists what it is, why it is dead, and the recommended action. **None of these have
been actioned yet.**

---

## 1. `LoginRateLimitService` — delete (superseded)

`booklore-api/src/main/java/org/booklore/config/security/service/LoginRateLimitService.java`

A `@Service` with zero injection sites. `AuthRateLimitService` in the same package replaced it
and is the one `AuthenticationService:137-138` actually calls.

`AuthRateLimitService` is a strict superset — it adds username-keyed limiting (not just IP),
refresh-token limiting, and a `maximumSize(10000)` bound on the Caffeine cache. The dead class
lacks that bound, so it is also the worse implementation of the two.

**Action:** delete the file. Nothing references it; it is instantiated as a bean at startup and
never used.

---

## 2. `AdminEventBroadcaster` — delete (superseded)

`booklore-api/src/main/java/org/booklore/service/event/AdminEventBroadcaster.java`

Zero references. `broadcastAdminEvent` fans a `LogNotification` out to every admin over
`Topic.LOG`.

This looked at first like a half-wired feature worth finishing — the receiving end is fully
built (`booklore-ui/src/app/app.component.ts:143` subscribes to `/user/queue/log` and routes
into `notificationEventService`). It is not. The codebase already has a better-shaped
equivalent that ~40 call sites use:

```java
notificationService.sendMessageToPermissions(
    Topic.LOG, LogNotification.info(msg), Set.of(ADMIN, MANAGE_LIBRARY));
```

That routes through `NotificationService` rather than a raw `SimpMessagingTemplate`, and
targets by permission instead of hardcoding admin-only.

**Action:** delete the file. If an admin-only broadcast is ever wanted, it is a one-line call to
the existing `sendMessageToPermissions` with `Set.of(ADMIN)`.

---

## 3. Duplicate join-table entities — delete (redundant second mapping)

| Entity | Repository | Table |
|---|---|---|
| `BookMetadataAuthorMapping` | `BookMetadataAuthorMappingRepository` | `book_metadata_author_mapping` |
| `BookMetadataCategoryMapping` | `BookMetadataCategoryMappingRepository` | `book_metadata_category_mapping` |
| `BookShelfMapping` | `BookShelfMappingRepository` | `book_shelf_mapping` |

All three entities are referenced **only** by their own repository, and all three repositories
are referenced nowhere.

All three tables are already mapped — as `@ManyToMany` + `@JoinTable` on the owning side:

- `BookMetadataEntity:368-375` → `book_metadata_author_mapping`
- `BookMetadataEntity:377-384` → `book_metadata_category_mapping`
- `BookEntity:72-78` and `ShelfEntity:46-50` → `book_shelf_mapping`

So each table carries **two** JPA mappings: the live `@JoinTable` and a redundant standalone
`@Entity`. That is worth removing rather than just ignoring — two entity mappings over one
table can produce first-level-cache and flush-ordering surprises the moment anyone starts
using the second one.

Supporting evidence that these are leftovers: the sibling join tables
`book_metadata_mood_mapping` and `book_metadata_tag_mapping` have no such duplicate entity.

**Action:** delete all six files (3 entities + 3 repositories), plus the now-unused
`BookMetadataAuthorKey`, `BookMetadataCategoryKey`, `BookShelfKey` id classes. Leave the tables
alone — they hold live data, managed by the `@JoinTable` mappings.

---

## 4. Unused repositories over live entities — delete

- `ComicCreatorMappingRepository` — `ComicCreatorMappingEntity` is very much alive, but reached
  through the cascaded collection on `ComicMetadataEntity` (used by `BookCreatorService`,
  `BookMetadataUpdater`, `BookRuleEvaluatorService`, `ComicMetadataMapper`). The repository's
  three finders are never called.
- `UserSettingRepository` — `UserSettingEntity` is alive via the collection on
  `BookLoreUserEntity` (used by `UserService`, `UserDefaultsService`, `DefaultSettingInitializer`,
  `HardcoverSyncSettingsService`). Its one method, `countBySettingKeyAndSettingValue`, is never
  called.

**Action:** delete both interfaces. The entities stay.

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
  Only their repositories are dead (item 4).
