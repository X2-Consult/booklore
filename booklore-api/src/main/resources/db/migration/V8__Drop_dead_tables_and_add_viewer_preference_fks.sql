-- Dead-schema cleanup and missing referential integrity (docs/dead-code-audit.md items 5-7).
-- Checked on prod first: epub_viewer_preference and book_award were empty, and there were no
-- orphaned viewer-preference rows. The orphan DELETEs below stay for other installs.

-- Superseded by ebook_viewer_preference when upstream replaced the EPUB reader (8c35f241);
-- nothing has written to it since, and its rows were never carried over.
DROP TABLE IF EXISTS "epub_viewer_preference";

-- The awards feature was removed upstream in 7a4a401e and the table left behind.
DROP TABLE IF EXISTS "book_award";

-- These were the only book/user child tables without foreign keys, so deleting a book or a user
-- left their rows behind forever. Clear any such orphans, then add the same constraints and
-- book_id index that ebook_viewer_preference has (the (user_id, book_id) unique index already
-- serves user deletes).
DELETE FROM "cbx_viewer_preference" p
WHERE NOT EXISTS (SELECT 1 FROM "book" b WHERE b."id" = p."book_id")
   OR NOT EXISTS (SELECT 1 FROM "users" u WHERE u."id" = p."user_id");
DELETE FROM "pdf_viewer_preference" p
WHERE NOT EXISTS (SELECT 1 FROM "book" b WHERE b."id" = p."book_id")
   OR NOT EXISTS (SELECT 1 FROM "users" u WHERE u."id" = p."user_id");
DELETE FROM "new_pdf_viewer_preference" p
WHERE NOT EXISTS (SELECT 1 FROM "book" b WHERE b."id" = p."book_id")
   OR NOT EXISTS (SELECT 1 FROM "users" u WHERE u."id" = p."user_id");

ALTER TABLE "cbx_viewer_preference" ADD CONSTRAINT "fk_cbx_viewer_preference_book"
    FOREIGN KEY ("book_id") REFERENCES "book" ("id") ON DELETE CASCADE;
ALTER TABLE "cbx_viewer_preference" ADD CONSTRAINT "fk_cbx_viewer_preference_user"
    FOREIGN KEY ("user_id") REFERENCES "users" ("id") ON DELETE CASCADE;
CREATE INDEX "cbx_viewer_preference__fk_cbx_viewer_preference_book" ON "cbx_viewer_preference" ("book_id");

ALTER TABLE "pdf_viewer_preference" ADD CONSTRAINT "fk_pdf_viewer_preference_book"
    FOREIGN KEY ("book_id") REFERENCES "book" ("id") ON DELETE CASCADE;
ALTER TABLE "pdf_viewer_preference" ADD CONSTRAINT "fk_pdf_viewer_preference_user"
    FOREIGN KEY ("user_id") REFERENCES "users" ("id") ON DELETE CASCADE;
CREATE INDEX "pdf_viewer_preference__fk_pdf_viewer_preference_book" ON "pdf_viewer_preference" ("book_id");

ALTER TABLE "new_pdf_viewer_preference" ADD CONSTRAINT "fk_new_pdf_viewer_preference_book"
    FOREIGN KEY ("book_id") REFERENCES "book" ("id") ON DELETE CASCADE;
ALTER TABLE "new_pdf_viewer_preference" ADD CONSTRAINT "fk_new_pdf_viewer_preference_user"
    FOREIGN KEY ("user_id") REFERENCES "users" ("id") ON DELETE CASCADE;
CREATE INDEX "new_pdf_viewer_preference__fk_new_pdf_viewer_preference_book" ON "new_pdf_viewer_preference" ("book_id");
