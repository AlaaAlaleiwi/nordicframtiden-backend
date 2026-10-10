-- Server-side session revocation: every JWT carries the user's id and this
-- version; bumping it (password change, disable, "log out everywhere")
-- invalidates all tokens issued before.
ALTER TABLE app_user ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;
