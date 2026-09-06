-- Denormalise the owning library path onto book_file so a file's identity
-- (library_path_id, file_sub_path, file_name) lives in one row - needed to
-- detect/prevent duplicate book rows for the same file on disk.
ALTER TABLE "book_file" ADD COLUMN "library_path_id" BIGINT;

UPDATE "book_file" bf
SET "library_path_id" = (SELECT b."library_path_id" FROM "book" b WHERE b."id" = bf."book_id");

ALTER TABLE "book_file" ADD CONSTRAINT "fk_book_file_library_path"
    FOREIGN KEY ("library_path_id") REFERENCES "library_path" ("id") ON DELETE CASCADE;

-- Non-unique for now: existing installs may already have duplicate rows, which a
-- unique index would reject here. CollapseExactFileDuplicatesMigration collapses
-- them and then adds the UNIQUE index (dropping this one).
CREATE INDEX "book_file__idx_identity"
    ON "book_file" ("library_path_id", "file_sub_path", "file_name");
