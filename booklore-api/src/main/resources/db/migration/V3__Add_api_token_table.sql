-- Self-service API tokens: a user generates their own token (distinct from their login
-- password) for third-party apps to authenticate with. Enforcement of what a token can
-- do (read-only + reading-progress updates only, regardless of the account's own
-- permissions) lives in ApiTokenAuthFilter, not in this table.

CREATE TABLE "api_token" (
    "id" BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL,
    "user_id" BIGINT NOT NULL,
    "name" VARCHAR(100) NOT NULL,
    "token_hash" VARCHAR(64) NOT NULL,
    "created_at" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "last_used_at" TIMESTAMP,
    "revoked_at" TIMESTAMP,
    PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "api_token__uq_token_hash" ON "api_token" ("token_hash");
CREATE INDEX "api_token__idx_user_id" ON "api_token" ("user_id");

ALTER TABLE "api_token" ADD CONSTRAINT "fk_api_token_user_id" FOREIGN KEY ("user_id") REFERENCES "users" ("id") ON DELETE CASCADE;
