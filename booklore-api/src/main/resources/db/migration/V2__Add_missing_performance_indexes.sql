-- Performance indexes for hot query paths found while reviewing the OPDS/reading-progress
-- repositories (book listing, series browsing, Continue Reading, reading-session stats).
-- All additive - no existing indexes are touched or dropped.

-- Virtually every book-listing query (all books, recent, by-library, search, series results)
-- orders by added_on DESC, but the column was never indexed - every page load forced a full
-- sort. The composite also serves plain library_id lookups as a prefix, so it helps the
-- library-scoped listing queries without needing a second lookup against book__idx_library_id.
CREATE INDEX "book__idx_added_on" ON "book" ("added_on" DESC);
CREATE INDEX "book__idx_library_id_added_on" ON "book" ("library_id", "added_on" DESC);

-- OPDS series browsing filters by series_name and orders by series_number; previously
-- unindexed entirely, forcing a full scan of book_metadata on every series click.
CREATE INDEX "book_metadata__idx_series_name" ON "book_metadata" ("series_name", "series_number");

-- Continue Reading (OPDS "on deck" feed) filters by user_id + read_status and orders by
-- last_read_time.
CREATE INDEX "user_book_progress__idx_user_status_last_read" ON "user_book_progress" ("user_id", "read_status", "last_read_time" DESC);

-- Reading-session stats (peak hours, favorite days, weekly listening trend, etc.) almost
-- always filter by user_id + book_type alongside the existing time-range indexes, which
-- only cover (user_id, start_time) / (book_id, start_time) / (user_id, book_id, start_time).
CREATE INDEX "reading_sessions__idx_user_type_time" ON "reading_sessions" ("user_id", "book_type", "start_time");
