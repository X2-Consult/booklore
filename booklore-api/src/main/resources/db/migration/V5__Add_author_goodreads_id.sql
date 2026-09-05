ALTER TABLE "author" ADD COLUMN "goodreads_id" VARCHAR(20) DEFAULT NULL;
CREATE INDEX "author__idx_goodreads_id" ON "author" ("goodreads_id");
