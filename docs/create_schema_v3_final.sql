-- ============================================================
-- GroupTrack V3.0 Complete Schema — Final
-- Target: convoy-tracker-db.cudtjxrtdbql.us-east-1.rds.amazonaws.com
-- Database: convoy_tracker
-- Run: mysql -h [endpoint] -u convoy_admin -p convoy_tracker < create_schema_v3_final.sql
-- Schema Version: 3.0 Final
-- Tables: 12 + indexes + seed data
-- Generated: March 29, 2026
-- ============================================================
-- IMPORTANT NOTES FOR DEVELOPMENT:
-- 1. organizations and org_members are defined but NEVER populated in V3.0
-- 2. All org FKs on rides and users are nullable — never set in V3.0
-- 3. kml_routes.file_hash UNIQUE constraint is the duplicate prevention mechanism
--    MD5 hash computed ON DEVICE before upload — API checks hash first, discards silently if exists
-- 4. rides and kml_routes have a circular nullable FK — rides.route_id → kml_routes
--    and kml_routes.source_ride_id → rides. Both nullable. No circular insert issue.
--    rides is created first (route_id NULL). KML donated post-ride (source_ride_id set).
-- 5. email_templates seed row uses {{placeholders}} — replaced at queue-time by PHP
-- 6. Google HYB tile source replaced with Esri overlay before V3.0 wiring begins
-- ============================================================

-- ── Stage 1 — Organizations (V4.0 — defined now, never populated in V3.0) ─

CREATE TABLE IF NOT EXISTS organizations (
    org_id        CHAR(36)     PRIMARY KEY DEFAULT (UUID()),
    org_name      VARCHAR(255) NOT NULL,
    org_type      ENUM('club','outfitter','private') DEFAULT 'club',
    contact_email VARCHAR(255) NULL,
    website       VARCHAR(255) NULL,
    zip_code      VARCHAR(10)  NULL,
    is_active     BOOLEAN      DEFAULT TRUE,
    created_at    DATETIME     DEFAULT NOW()
);

-- ── Stage 1 — Users ───────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS users (
    user_id        CHAR(36)     PRIMARY KEY DEFAULT (UUID()),
    google_id      VARCHAR(255) UNIQUE NOT NULL,
    email          VARCHAR(255) UNIQUE NOT NULL,
    first_name     VARCHAR(100) NOT NULL,
    last_name      VARCHAR(100) NOT NULL,
    cell           VARCHAR(20)  NULL,
    zip_code       VARCHAR(10)  NULL,
    is_active      BOOLEAN      DEFAULT TRUE,
    email_opt_in   BOOLEAN      DEFAULT TRUE,       -- rider can opt out of broadcast emails
    primary_org_id CHAR(36)     NULL,               -- V4.0 — nullable, never set in V3.0
    expires_at     DATETIME     NULL,               -- NULL = free tier
    created_at     DATETIME     DEFAULT NOW(),
    FOREIGN KEY (primary_org_id) REFERENCES organizations(org_id)
);

-- ── Stage 1 — KML Routes ─────────────────────────────────────────────────
-- Created before rides so rides.route_id FK can reference it
-- DUPLICATE PREVENTION: file_hash UNIQUE — MD5 computed on device before upload
-- If hash exists: API returns { accepted: false } silently — no error shown to rider
-- source_ride_id: which ride generated this donation — permanent provenance
-- route_name: named by the rider at end of ride in post-ride survey

CREATE TABLE IF NOT EXISTS kml_routes (
    route_id       CHAR(36)     PRIMARY KEY DEFAULT (UUID()),
    uploaded_by    CHAR(36)     NOT NULL,           -- donor — permanent attribution
    source_ride_id CHAR(36)     NULL,               -- which ride this was donated from
    route_name     VARCHAR(255) NOT NULL,           -- named by rider at donation time
    description    TEXT         NULL,               -- trail notes, difficulty, length
    zip_code       VARCHAR(10)  NULL,               -- for regional browse
    state          VARCHAR(2)   NULL,               -- for regional browse
    file_path      VARCHAR(500) NOT NULL,           -- EC2 path: /var/www/html/uploads/kml/{route_id}.kml
    file_name      VARCHAR(255) NOT NULL,           -- original filename from device
    file_size_kb   INT          NULL,               -- display only
    file_hash      VARCHAR(32)  NOT NULL UNIQUE,    -- MD5 hash — duplicate prevention key
    is_public      BOOLEAN      DEFAULT TRUE,       -- community library
    download_count INT          DEFAULT 0,          -- popularity metric
    uploaded_at    DATETIME     DEFAULT NOW(),
    FOREIGN KEY (uploaded_by)    REFERENCES users(user_id)
    -- source_ride_id FK added after rides table created — see ALTER TABLE below
);

-- ── Stage 1 — Rides ───────────────────────────────────────────────────────
-- org_id: NULL = personal ride, SET = org ride (V4.0 only)
-- is_public: FALSE = invite only (default), TRUE = discoverable + broadcast eligible
-- Org rides default TRUE at application layer when org_id set — not enforced by DB
-- route_id: NULL = no KML overlay, SET = one KML route displayed on map for all riders
-- broadcast_sent: prevents duplicate email shots on same ride

CREATE TABLE IF NOT EXISTS rides (
    ride_id          CHAR(36)      PRIMARY KEY,
    organizer_id     CHAR(36)      NOT NULL,
    org_id           CHAR(36)      NULL,            -- V4.0 — nullable, never set in V3.0
    route_id         CHAR(36)      NULL,            -- one KML per ride maximum
    ride_name        VARCHAR(255)  NOT NULL,
    channel_name     VARCHAR(11)   NOT NULL,
    channel_psk      VARCHAR(512)  NOT NULL,
    ride_date        DATE          NOT NULL,
    description      TEXT          NULL,
    zip_code         VARCHAR(10)   NULL,
    is_public        BOOLEAN       DEFAULT FALSE,   -- FALSE = invite only
    broadcast_sent   BOOLEAN       DEFAULT FALSE,   -- TRUE after email shot fired
    map_bounds_north DECIMAL(10,7) NULL,            -- automated map download on invite accept
    map_bounds_south DECIMAL(10,7) NULL,
    map_bounds_east  DECIMAL(10,7) NULL,
    map_bounds_west  DECIMAL(10,7) NULL,
    map_tile_source  VARCHAR(10)   DEFAULT 'SAT',
    map_zoom_max     INT           DEFAULT 18,
    created_at       DATETIME      DEFAULT NOW(),
    expires_at       DATETIME      NOT NULL,        -- 30 days after ride_date
    FOREIGN KEY (organizer_id) REFERENCES users(user_id),
    FOREIGN KEY (org_id)       REFERENCES organizations(org_id),
    FOREIGN KEY (route_id)     REFERENCES kml_routes(route_id)
);

-- Now add the circular FK from kml_routes back to rides
-- Both nullable — no circular insert issue
-- rides created first (route_id NULL), KML donated post-ride (source_ride_id set)
ALTER TABLE kml_routes
    ADD CONSTRAINT fk_kml_source_ride
    FOREIGN KEY (source_ride_id) REFERENCES rides(ride_id);

-- ── Stage 1 — Invites ────────────────────────────────────────────────────
-- Private rides (is_public = FALSE) are accessible by invite token only
-- Public rides also use invite tokens for enrollment tracking
-- token: URL-safe random 64-char string generated by PHP
-- claimed_by: NULL until rider taps link and downloads ride

CREATE TABLE IF NOT EXISTS invites (
    invite_id  CHAR(36)    PRIMARY KEY DEFAULT (UUID()),
    ride_id    CHAR(36)    NOT NULL,
    token      VARCHAR(64) UNIQUE NOT NULL,
    created_by CHAR(36)    NOT NULL,
    claimed_by CHAR(36)    NULL,
    claimed_at DATETIME    NULL,
    expires_at DATETIME    NOT NULL,               -- 48 hours after creation
    created_at DATETIME    DEFAULT NOW(),
    FOREIGN KEY (ride_id)    REFERENCES rides(ride_id),
    FOREIGN KEY (created_by) REFERENCES users(user_id),
    FOREIGN KEY (claimed_by) REFERENCES users(user_id)
);

-- ── Stage 1 — Enrollments ────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS enrollments (
    enrollment_id CHAR(36)     PRIMARY KEY DEFAULT (UUID()),
    ride_id       CHAR(36)     NOT NULL,
    user_id       CHAR(36)     NOT NULL,
    callsign      VARCHAR(50)  NULL,
    vehicle_type  VARCHAR(100) NULL,
    enrolled_at   DATETIME     DEFAULT NOW(),
    status        ENUM('active','cancelled') DEFAULT 'active',
    FOREIGN KEY (ride_id) REFERENCES rides(ride_id),
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- ── Stage 1 — Org Members (V4.0 — defined now, never populated in V3.0) ──

CREATE TABLE IF NOT EXISTS org_members (
    membership_id CHAR(36)  PRIMARY KEY DEFAULT (UUID()),
    org_id        CHAR(36)  NOT NULL,
    user_id       CHAR(36)  NOT NULL,
    role          ENUM('owner','admin','member') DEFAULT 'member',
    joined_at     DATETIME  DEFAULT NOW(),
    status        ENUM('active','suspended') DEFAULT 'active',
    UNIQUE KEY uq_org_user (org_id, user_id),
    FOREIGN KEY (org_id)  REFERENCES organizations(org_id),
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- ── Stage 2 — Follows ────────────────────────────────────────────────────
-- V3.0: user → user only (rider follows organizer by user_id)
-- V4.0: org follows added without changing this table
-- Broadcast query V3.0: SELECT follower_id FROM follows WHERE following_id = organizer_id
-- AND status = 'active' AND users.email_opt_in = TRUE

CREATE TABLE IF NOT EXISTS follows (
    follow_id    CHAR(36)  PRIMARY KEY DEFAULT (UUID()),
    follower_id  CHAR(36)  NOT NULL,               -- the rider who follows
    following_id CHAR(36)  NOT NULL,               -- the organizer being followed
    created_at   DATETIME  DEFAULT NOW(),
    status       ENUM('active','muted') DEFAULT 'active',
    UNIQUE KEY uq_follow (follower_id, following_id),
    FOREIGN KEY (follower_id)  REFERENCES users(user_id),
    FOREIGN KEY (following_id) REFERENCES users(user_id)
);

-- ── Stage 2 — Notifications ──────────────────────────────────────────────
-- Broadcast log — prevents duplicate notifications
-- Check before inserting: SELECT COUNT(*) FROM notifications
--   WHERE user_id = ? AND ride_id = ? AND type = 'broadcast'
-- If count > 0: skip — already notified

CREATE TABLE IF NOT EXISTS notifications (
    notification_id CHAR(36)     PRIMARY KEY DEFAULT (UUID()),
    user_id         CHAR(36)     NOT NULL,
    ride_id         CHAR(36)     NULL,
    type            ENUM('invite','broadcast','org_broadcast','system') NOT NULL,
    message         VARCHAR(500) NULL,
    sent_at         DATETIME     DEFAULT NOW(),
    read_at         DATETIME     NULL,             -- NULL = unread
    FOREIGN KEY (user_id) REFERENCES users(user_id),
    FOREIGN KEY (ride_id) REFERENCES rides(ride_id)
);

-- ── Stage 2 — Email Templates ────────────────────────────────────────────
-- org_id NULL = GroupTrack default branding
-- org_id SET = org override template (V4.0 — swap branding without code deploy)
-- Placeholders replaced at queue-time by PHP:
--   {{first_name}} {{organizer_name}} {{ride_name}} {{ride_date}}
--   {{description}} {{zip_code}} {{invite_url}} {{unsubscribe_url}}

CREATE TABLE IF NOT EXISTS email_templates (
    template_id   CHAR(36)     PRIMARY KEY DEFAULT (UUID()),
    template_name VARCHAR(100) UNIQUE NOT NULL,
    org_id        CHAR(36)     NULL,              -- NULL = GroupTrack default
    subject       VARCHAR(255) NOT NULL,
    html_body     MEDIUMTEXT   NOT NULL,
    is_active     BOOLEAN      DEFAULT TRUE,
    created_at    DATETIME     DEFAULT NOW(),
    updated_at    DATETIME     DEFAULT NOW() ON UPDATE NOW(),
    FOREIGN KEY (org_id) REFERENCES organizations(org_id)
);

-- ── Stage 2 — Email Queue ────────────────────────────────────────────────
-- EC2 cron processes rows WHERE status = 'pending' ORDER BY scheduled_at ASC
-- retry_count: max 3 retries before status = 'failed'
-- html_body: pre-rendered at queue time — org branding captured at send time
-- If rider unsubscribes: DELETE FROM email_queue WHERE user_id = ? AND status = 'pending'

CREATE TABLE IF NOT EXISTS email_queue (
    queue_id      CHAR(36)     PRIMARY KEY DEFAULT (UUID()),
    user_id       CHAR(36)     NOT NULL,
    ride_id       CHAR(36)     NULL,
    template_id   CHAR(36)     NULL,
    email_address VARCHAR(255) NOT NULL,
    subject       VARCHAR(255) NOT NULL,
    html_body     MEDIUMTEXT   NOT NULL,           -- pre-rendered HTML at queue time
    status        ENUM('pending','sent','failed') DEFAULT 'pending',
    scheduled_at  DATETIME     DEFAULT NOW(),
    sent_at       DATETIME     NULL,
    fail_reason   VARCHAR(500) NULL,
    retry_count   INT          DEFAULT 0,          -- max 3 before failed
    created_at    DATETIME     DEFAULT NOW(),
    FOREIGN KEY (user_id)     REFERENCES users(user_id),
    FOREIGN KEY (ride_id)     REFERENCES rides(ride_id),
    FOREIGN KEY (template_id) REFERENCES email_templates(template_id)
);

-- ── Stage 2 — Post-Ride Surveys ──────────────────────────────────────────
-- Triggered when rider taps STOP RECORD
-- KML donation handled separately via POST /kml/donate in same survey submission
-- Survey JSON payload: { ride_id, user_id, rating, notes, kml_donation: { ... } | null }
-- kml_donation field is optional — absent if rider skips KML upload
-- API processes survey insert and KML donation atomically in one POST /ride/survey call

CREATE TABLE IF NOT EXISTS ride_surveys (
    survey_id    CHAR(36)  PRIMARY KEY DEFAULT (UUID()),
    ride_id      CHAR(36)  NOT NULL,
    user_id      CHAR(36)  NOT NULL,
    rating       TINYINT   NULL,                  -- 1-5 stars
    notes        TEXT      NULL,                  -- rider notes to organizer
    submitted_at DATETIME  DEFAULT NOW(),
    UNIQUE KEY uq_survey (ride_id, user_id),      -- one survey per rider per ride
    FOREIGN KEY (ride_id) REFERENCES rides(ride_id),
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- ── Indexes ───────────────────────────────────────────────────────────────

CREATE INDEX idx_rides_organizer     ON rides(organizer_id);
CREATE INDEX idx_rides_org           ON rides(org_id);
CREATE INDEX idx_rides_public        ON rides(is_public, ride_date);
CREATE INDEX idx_rides_zip           ON rides(zip_code);
CREATE INDEX idx_rides_route         ON rides(route_id);
CREATE INDEX idx_invites_token       ON invites(token);
CREATE INDEX idx_invites_ride        ON invites(ride_id);
CREATE INDEX idx_enrollments_ride    ON enrollments(ride_id);
CREATE INDEX idx_enrollments_user    ON enrollments(user_id);
CREATE INDEX idx_follows_follower    ON follows(follower_id);
CREATE INDEX idx_follows_following   ON follows(following_id);
CREATE INDEX idx_notifications_user  ON notifications(user_id, read_at);
CREATE INDEX idx_email_queue_status  ON email_queue(status, scheduled_at);
CREATE INDEX idx_org_members_org     ON org_members(org_id);
CREATE INDEX idx_org_members_user    ON org_members(user_id);
CREATE INDEX idx_kml_routes_uploader ON kml_routes(uploaded_by);
CREATE INDEX idx_kml_routes_zip      ON kml_routes(zip_code, state);
CREATE INDEX idx_kml_routes_ride     ON kml_routes(source_ride_id);
CREATE INDEX idx_surveys_ride        ON ride_surveys(ride_id);

-- ── Seed Data — GroupTrack Default Email Template ─────────────────────────
-- template_name: grouptrack_ride_broadcast
-- org_id: NULL = GroupTrack branding (default)
-- Swap for org branding in V4.0 by inserting new row with org_id set
-- PHP placeholder replacement at queue time:
--   {{first_name}} {{organizer_name}} {{ride_name}} {{ride_date}}
--   {{description}} {{zip_code}} {{invite_url}} {{unsubscribe_url}}

INSERT INTO email_templates (template_name, org_id, subject, html_body) VALUES (
'grouptrack_ride_broadcast',
NULL,
'New Ride Posted — {{ride_name}}',
'<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
  body { margin:0; padding:0; background:#1A2E4A; font-family:Arial,sans-serif; }
  .wrapper { max-width:600px; margin:0 auto; background:#1A2E4A; }
  .header { background:#1A2E4A; padding:32px 24px 20px; text-align:center; }
  .logo-group { font-size:36px; font-weight:900; color:#F5A623; letter-spacing:-1px; }
  .logo-track { font-size:36px; font-weight:900; color:#4AB8E8; letter-spacing:-1px; }
  .tagline { color:#7A9EC0; font-size:13px; margin-top:6px; letter-spacing:2px; text-transform:uppercase; }
  .divider { height:3px; background:linear-gradient(90deg,#F5A623,#4AB8E8); margin:0; }
  .body { background:#FFFFFF; padding:32px 24px; }
  .greeting { font-size:16px; color:#333; margin-bottom:16px; }
  .ride-card { background:#EEF4FB; border-left:4px solid #2E75B6; border-radius:6px; padding:20px; margin:20px 0; }
  .ride-name { font-size:22px; font-weight:bold; color:#1F4E79; margin-bottom:8px; }
  .ride-detail { font-size:14px; color:#555; margin:4px 0; }
  .ride-detail b { color:#333; }
  .cta { text-align:center; margin:28px 0 12px; }
  .cta-btn { display:inline-block; background:#2E75B6; color:#FFFFFF; font-size:15px; font-weight:bold; padding:14px 36px; border-radius:8px; text-decoration:none; }
  .cta-sub { text-align:center; font-size:12px; color:#888; margin-top:8px; }
  .footer { background:#1A2E4A; padding:20px 24px; text-align:center; }
  .footer-text { color:#7A9EC0; font-size:11px; line-height:1.8; }
  .footer-link { color:#4AB8E8; text-decoration:none; }
  .unsubscribe { color:#556B80; font-size:10px; margin-top:12px; }
</style>
</head>
<body>
<div class="wrapper">
  <div class="header">
    <div><span class="logo-group">Group</span><span class="logo-track">Track</span></div>
    <div class="tagline">Off-Grid Convoy Coordination</div>
  </div>
  <div class="divider"></div>
  <div class="body">
    <p class="greeting">Hey {{first_name}},</p>
    <p style="font-size:15px;color:#333;">{{organizer_name}} just posted a new ride and you are on their follow list.</p>
    <div class="ride-card">
      <div class="ride-name">{{ride_name}}</div>
      <div class="ride-detail"><b>Date:</b> {{ride_date}}</div>
      <div class="ride-detail"><b>Organizer:</b> {{organizer_name}}</div>
      <div class="ride-detail"><b>Description:</b> {{description}}</div>
      <div class="ride-detail"><b>Area:</b> {{zip_code}}</div>
    </div>
    <div class="cta">
      <a href="{{invite_url}}" class="cta-btn">JOIN THIS RIDE</a>
    </div>
    <p class="cta-sub">Tap the button to download the ride to GroupTrack and get your radio configured automatically.</p>
    <p style="font-size:13px;color:#888;margin-top:24px;">No cell signal required during the ride. GroupTrack operates entirely over LoRa mesh radio.</p>
  </div>
  <div class="footer">
    <div class="footer-text">
      GroupTrack &nbsp;|&nbsp; Off-Grid Convoy Coordination<br>
      <a href="http://www.grouptrack.org" class="footer-link">www.grouptrack.org</a>
      &nbsp;|&nbsp;
      <a href="mailto:info@grouptrack.org" class="footer-link">info@grouptrack.org</a>
    </div>
    <div class="unsubscribe">
      You are receiving this because you follow {{organizer_name}} on GroupTrack.<br>
      <a href="{{unsubscribe_url}}" style="color:#556B80;">Unsubscribe from ride notifications</a>
    </div>
  </div>
</div>
</body>
</html>'
);

-- ── Verification Queries ──────────────────────────────────────────────────
-- Run after schema creation to confirm:
-- SHOW TABLES;                                          -- expect 12 tables
-- SELECT COUNT(*) FROM email_templates;                -- expect 1
-- SELECT template_name FROM email_templates;           -- expect grouptrack_ride_broadcast
-- DESCRIBE users;                                      -- confirm email_opt_in, primary_org_id
-- DESCRIBE rides;                                      -- confirm route_id, org_id, broadcast_sent
-- DESCRIBE kml_routes;                                 -- confirm file_hash UNIQUE
-- SHOW INDEX FROM kml_routes WHERE Key_name = 'file_hash'; -- confirm unique index
