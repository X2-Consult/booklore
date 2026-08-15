-- koreader_user.password stored the user's raw KOReader sync password in plaintext and
-- was returned as-is by GET /api/v1/koreader-users/me. Only the MD5 digest (password_md5)
-- is actually needed to authenticate KOSync's x-auth-key header, so the reversible
-- plaintext copy is dropped. Existing users must set a new KOReader password once after
-- this migration to see it again - it is no longer retrievable from the server, matching
-- how api_token handles secrets (hash-only storage, shown once at creation/change time).
ALTER TABLE "koreader_user" DROP COLUMN "password";
