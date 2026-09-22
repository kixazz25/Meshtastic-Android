-- =====================================================================
-- GroupTrack — AWS model alignment, PHASE A                 2026-09-22 v1
-- MARKER: AWSALIGN-2026-09-22
-- =====================================================================
-- Converges convoy_tracker (V3) with the device model (convergence doc v24).
-- PHASE A = ADDITIVE AND LOOSENING ONLY: new columns (nullable or defaulted),
--   one new table, NOT NULL -> NULL on two columns. Existing V3 code keeps
--   working: nothing it writes is removed, renamed or tightened.
-- PHASE B (3.0, with the code regeneration — NOT here): repoint rides.route_id
--   and the survey track link to grouptrack_spatial; drop rides.channel_name /
--   channel_psk (-> config) and ride_status; retire enrollments.callsign.
-- Run ONCE. MySQL has no ADD COLUMN IF NOT EXISTS: a second run stops at the
--   first "Duplicate column" error, having changed nothing further.
-- =====================================================================
USE convoy_tracker;

-- Rider profile (premium members only reach the server). Callsign lives HERE,
-- the same everywhere; defaults seed each ride's enrollment.
ALTER TABLE users
  ADD COLUMN callsign      VARCHAR(50)  NULL AFTER last_name,
  ADD COLUMN default_role  ENUM('leader','rider','middle','tail_gunner') NOT NULL DEFAULT 'rider' AFTER callsign,
  ADD COLUMN vehicle_type  VARCHAR(100) NULL AFTER default_role,
  ADD COLUMN team          VARCHAR(50)  NULL AFTER vehicle_type,
  ADD COLUMN config_mode   ENUM('own','inherit_org') NOT NULL DEFAULT 'own' AFTER primary_org_id,
  ADD COLUMN config_id     VARCHAR(11)  NULL AFTER config_mode;
CREATE INDEX idx_users_callsign ON users(callsign);

-- Org standing config.
ALTER TABLE organizations
  ADD COLUMN config_id     VARCHAR(11)  NULL AFTER zip_code;

-- Config inheritance: inherit through the leader, or unique if requested.
-- channel_name / channel_psk stay until Phase B (V3 code still writes them).
ALTER TABLE rides
  ADD COLUMN config_mode   ENUM('inherit_leader','unique') NOT NULL DEFAULT 'inherit_leader' AFTER channel_psk,
  ADD COLUMN config_id     VARCHAR(11)  NULL AFTER config_mode;

-- The enrollment carries the per-ride data: radio, TAK identity, role, team.
ALTER TABLE enrollments
  ADD COLUMN role            ENUM('leader','rider','middle','tail_gunner') NOT NULL DEFAULT 'rider' AFTER user_id,
  ADD COLUMN team            VARCHAR(50)  NULL AFTER role,
  ADD COLUMN node_num        BIGINT       NULL AFTER vehicle_type,
  ADD COLUMN tak_uid         VARCHAR(64)  NULL AFTER node_num,
  ADD COLUMN device_callsign VARCHAR(50)  NULL AFTER tak_uid,
  ADD COLUMN created_by      ENUM('login','packet','invite') NULL AFTER device_callsign,
  ADD COLUMN first_seen      DATETIME     NULL AFTER enrolled_at,
  ADD COLUMN last_seen       DATETIME     NULL AFTER first_seen;

-- Surveys: keyed by the TRACK, ride optional; a free rider's survey carries only
-- the submitter's EMAIL (no user row until premium enrollment).
ALTER TABLE ride_surveys
  MODIFY COLUMN ride_id  CHAR(36) COLLATE utf8mb4_unicode_ci NULL,
  MODIFY COLUMN user_id  CHAR(36) COLLATE utf8mb4_unicode_ci NULL,
  ADD COLUMN track_id        VARCHAR(64)  NULL AFTER ride_id,
  ADD COLUMN submitter_email VARCHAR(255) NULL AFTER user_id,
  ADD CONSTRAINT chk_survey_who CHECK (user_id IS NOT NULL OR submitter_email IS NOT NULL),
  ADD UNIQUE KEY uq_survey_track_user  (track_id, user_id),
  ADD UNIQUE KEY uq_survey_track_email (track_id, submitter_email);

-- Configs: immutable; config_id = the GT id (channel name = network name),
-- identical on every device and the server (no key mapping).
CREATE TABLE IF NOT EXISTS network_configs (
  config_id        VARCHAR(11)  NOT NULL,
  owner_type       ENUM('org','leader','ride') NOT NULL,
  owner_id         CHAR(36)     NOT NULL,
  display_name     VARCHAR(255) NOT NULL,
  base_version     INT          NOT NULL,
  region           VARCHAR(16)  NOT NULL,
  modem_preset     VARCHAR(32)  NOT NULL,
  hop_limit        TINYINT      NOT NULL,
  tx_power         TINYINT      NOT NULL,
  frequency_slot   INT          NOT NULL DEFAULT 0,
  psk              VARCHAR(512) NOT NULL,
  role             VARCHAR(32)  NOT NULL,
  network_ssid     VARCHAR(64)  NULL,
  network_password VARCHAR(128) NULL,
  tak_sdk_version  VARCHAR(32)  NULL,
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (config_id),
  KEY idx_configs_owner (owner_type, owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='GT rules: immutable (rotation = new config) | config_id = GT id, same everywhere';

-- One-time codes that confirm a free submitter's email before their first
-- contribution is accepted.
CREATE TABLE IF NOT EXISTS email_verifications (
  verification_id  CHAR(36)     NOT NULL DEFAULT (uuid()),
  email            VARCHAR(255) NOT NULL,
  code_hash        CHAR(64)     NOT NULL,
  expires_at       DATETIME     NOT NULL,
  verified_at      DATETIME     NULL,
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (verification_id),
  KEY idx_verif_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='GT rules: code stored hashed | confirms an email before its first contribution';

-- Contributions: a free rider is identified ONLY by the submitter's email.
USE grouptrack_data;
ALTER TABLE contributions
  MODIFY COLUMN user_id CHAR(36) NULL,
  ADD COLUMN submitter_email VARCHAR(255) NULL AFTER user_id,
  ADD CONSTRAINT chk_contrib_who CHECK (user_id IS NOT NULL OR submitter_email IS NOT NULL),
  ADD UNIQUE KEY uq_contribution_email (artifact_type, artifact_id, submitter_email),
  COMMENT='GT rules: SERVER ONLY | one row per artifact per contributor (S3) | premium: user_id = convoy_tracker.users | free: submitter_email only';
