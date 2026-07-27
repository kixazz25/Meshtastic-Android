-- ============================================================
-- GroupTrack V3.0 Complete Schema
-- Target: convoy-tracker-db.cudtjxrtdbql.us-east-1.rds.amazonaws.com
-- Database: convoy_tracker
-- Run: mysql -h [endpoint] -u convoy_admin -p convoy_tracker < create_schema_v3.sql
-- ============================================================

-- ── Stage 1 — Core V3.0 Tables ───────────────────────────────────────────

-- Organizations (defined now, populated V4.0)
CREATE TABLE IF NOT EXISTS organizations (
    org_id       CHAR(36)      PRIMARY KEY DEFAULT (UUID()),
    org_name     VARCHAR(255)  NOT NULL,
    org_type     ENUM('club','outfitter','private') DEFAULT 'club',
    contact_email VARCHAR(255) NULL,
    website      VARCHAR(255)  NULL,
    zip_code     VARCHAR(10)   NULL,
    is_active    BOOLEAN       DEFAULT TRUE,
    created_at   DATETIME      DEFAULT NOW()
);

-- Users
CREATE TABLE IF NOT EXISTS users (
    user_id         CHAR(36)     PRIMARY KEY DEFAULT (UUID()),
    google_id       VARCHAR(255) UNIQUE NOT NULL,
    email           VARCHAR(255) UNIQUE NOT NULL,
    first_name      VARCHAR(100) NOT NULL,
    last_name       VARCHAR(100) NOT NULL,
    cell            VARCHAR(20)  NULL,
    zip_code        VARCHAR(10)  NULL,
    is_active       BOOLEAN      DEFAULT TRUE,
    email_opt_in    BOOLEAN      DEFAULT TRUE,
    primary_org_id  CHAR(36)     NULL,          -- V4.0 — nullable FK to organizations
    expires_at      DATETIME     NULL,           -- NULL = free tier
    created_at      DATETIME     DEFAULT NOW(),
    FOREIGN KEY (primary_org_id) REFERENCES organizations(org_id)
);

-- Rides
CREATE TABLE IF NOT EXISTS rides (
    ride_id          CHAR(36)      PRIMARY KEY,
    organizer_id     CHAR(36)      NOT NULL,
    org_id           CHAR(36)      NULL,          -- V4.0 — NULL = personal ride
    ride_name        VARCHAR(255)  NOT NULL,
    channel_name     VARCHAR(11)   NOT NULL,
    channel_psk      VARCHAR(512)  NOT NULL,
    ride_date        DATE          NOT NULL,
    description      TEXT          NULL,
    zip_code         VARCHAR(10)   NULL,
    is_public        BOOLEAN       DEFAULT FALSE, -- TRUE = discoverable + broadcast eligible
    broadcast_sent   BOOLEAN       DEFAULT FALSE, -- Set TRUE when email shot fires
    map_bounds_north DECIMAL(10,7) NULL,
    map_bounds_south DECIMAL(10,7) NULL,
    map_bounds_east  DECIMAL(10,7) NULL,
    map_bounds_west  DECIMAL(10,7) NULL,
    map_tile_source  VARCHAR(10)   DEFAULT 'SAT',
    map_zoom_max     INT           DEFAULT 18,
    created_at       DATETIME      DEFAULT NOW(),
    expires_at       DATETIME      NOT NULL,
    FOREIGN KEY (organizer_id) REFERENCES users(user_id),
    FOREIGN KEY (org_id)       REFERENCES organizations(org_id)
);

-- Invites
CREATE TABLE IF NOT EXISTS invites (
    invite_id  CHAR(36)     PRIMARY KEY DEFAULT (UUID()),
    ride_id    CHAR(36)     NOT NULL,
    token      VARCHAR(64)  UNIQUE NOT NULL,
    created_by CHAR(36)     NOT NULL,
    claimed_by CHAR(36)     NULL,
    claimed_at DATETIME     NULL,
    expires_at DATETIME     NOT NULL,
    created_at DATETIME     DEFAULT NOW(),
    FOREIGN KEY (ride_id)    REFERENCES rides(ride_id),
    FOREIGN KEY (created_by) REFERENCES users(user_id),
    FOREIGN KEY (claimed_by) REFERENCES users(user_id)
);

-- Enrollments
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

-- Org Members (defined now, populated V4.0)
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

-- ── Stage 2 — Broadcast + Follow Tables (V3.0 populated) ─────────────────

-- Follows (user → user only in V3.0, org follows added V4.0)
CREATE TABLE IF NOT EXISTS follows (
    follow_id    CHAR(36)  PRIMARY KEY DEFAULT (UUID()),
    follower_id  CHAR(36)  NOT NULL,              -- the rider who follows
    following_id CHAR(36)  NOT NULL,              -- the organizer being followed
    created_at   DATETIME  DEFAULT NOW(),
    status       ENUM('active','muted') DEFAULT 'active',
    UNIQUE KEY uq_follow (follower_id, following_id),
    FOREIGN KEY (follower_id)  REFERENCES users(user_id),
    FOREIGN KEY (following_id) REFERENCES users(user_id)
);

-- Notifications (broadcast log — prevents duplicates, tracks read state)
CREATE TABLE IF NOT EXISTS notifications (
    notification_id CHAR(36)     PRIMARY KEY DEFAULT (UUID()),
    user_id         CHAR(36)     NOT NULL,
    ride_id         CHAR(36)     NULL,
    type            ENUM('invite','broadcast','org_broadcast','system') NOT NULL,
    message         VARCHAR(500) NULL,
    sent_at         DATETIME     DEFAULT NOW(),
    read_at         DATETIME     NULL,
    FOREIGN KEY (user_id) REFERENCES users(user_id),
    FOREIGN KEY (ride_id) REFERENCES rides(ride_id)
);

-- Email Templates (GroupTrack branded HTML in V3.0, swapped for org branding in V4.0)
CREATE TABLE IF NOT EXISTS email_templates (
    template_id   CHAR(36)     PRIMARY KEY DEFAULT (UUID()),
    template_name VARCHAR(100) UNIQUE NOT NULL,
    org_id        CHAR(36)     NULL,              -- NULL = GroupTrack default, SET = org override in V4.0
    subject       VARCHAR(255) NOT NULL,
    html_body     MEDIUMTEXT   NOT NULL,          -- Full HTML with {{placeholders}}
    is_active     BOOLEAN      DEFAULT TRUE,
    created_at    DATETIME     DEFAULT NOW(),
    updated_at    DATETIME     DEFAULT NOW() ON UPDATE NOW(),
    FOREIGN KEY (org_id) REFERENCES organizations(org_id)
);

-- Email Queue (broadcast send queue — processed by EC2 cron)
CREATE TABLE IF NOT EXISTS email_queue (
    queue_id       CHAR(36)     PRIMARY KEY DEFAULT (UUID()),
    user_id        CHAR(36)     NOT NULL,
    ride_id        CHAR(36)     NULL,
    template_id    CHAR(36)     NULL,
    email_address  VARCHAR(255) NOT NULL,
    subject        VARCHAR(255) NOT NULL,
    html_body      MEDIUMTEXT   NOT NULL,          -- Pre-rendered HTML at queue time
    status         ENUM('pending','sent','failed') DEFAULT 'pending',
    scheduled_at   DATETIME     DEFAULT NOW(),
    sent_at        DATETIME     NULL,
    fail_reason    VARCHAR(500) NULL,
    retry_count    INT          DEFAULT 0,
    created_at     DATETIME     DEFAULT NOW(),
    FOREIGN KEY (user_id)     REFERENCES users(user_id),
    FOREIGN KEY (ride_id)     REFERENCES rides(ride_id),
    FOREIGN KEY (template_id) REFERENCES email_templates(template_id)
);

-- ── Indexes for query performance ─────────────────────────────────────────

CREATE INDEX idx_rides_organizer    ON rides(organizer_id);
CREATE INDEX idx_rides_org          ON rides(org_id);
CREATE INDEX idx_rides_public       ON rides(is_public, ride_date);
CREATE INDEX idx_rides_zip          ON rides(zip_code);
CREATE INDEX idx_invites_token      ON invites(token);
CREATE INDEX idx_invites_ride       ON invites(ride_id);
CREATE INDEX idx_enrollments_ride   ON enrollments(ride_id);
CREATE INDEX idx_enrollments_user   ON enrollments(user_id);
CREATE INDEX idx_follows_follower   ON follows(follower_id);
CREATE INDEX idx_follows_following  ON follows(following_id);
CREATE INDEX idx_notifications_user ON notifications(user_id, read_at);
CREATE INDEX idx_email_queue_status ON email_queue(status, scheduled_at);
CREATE INDEX idx_org_members_org    ON org_members(org_id);
CREATE INDEX idx_org_members_user   ON org_members(user_id);

-- ── Seed Data — GroupTrack default email template ─────────────────────────

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
  body { margin:0; padding:0; background:#1A2E4A; font-family: Arial, sans-serif; }
  .wrapper { max-width:600px; margin:0 auto; background:#1A2E4A; }
  .header { background:#1A2E4A; padding:32px 24px 20px; text-align:center; }
  .logo-group { font-size:36px; font-weight:900; color:#F5A623; letter-spacing:-1px; }
  .logo-track { font-size:36px; font-weight:900; color:#4AB8E8; letter-spacing:-1px; }
  .tagline { color:#7A9EC0; font-size:13px; margin-top:6px; letter-spacing:2px; text-transform:uppercase; }
  .divider { height:3px; background:linear-gradient(90deg,#F5A623,#4AB8E8); margin:0; }
  .body { background:#FFFFFF; padding:32px 24px; }
  .greeting { font-size:16px; color:#333; margin-bottom:16px; }
  .ride-card { background:#EEF4FB; border-left:4px solid #2E75B6; border-radius:6px; padding:20px 20px; margin:20px 0; }
  .ride-name { font-size:22px; font-weight:bold; color:#1F4E79; margin-bottom:8px; }
  .ride-detail { font-size:14px; color:#555; margin:4px 0; }
  .ride-detail b { color:#333; }
  .cta { text-align:center; margin:28px 0 12px; }
  .cta-btn { display:inline-block; background:#2E75B6; color:#FFFFFF; font-size:15px; font-weight:bold; padding:14px 36px; border-radius:8px; text-decoration:none; letter-spacing:0.5px; }
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
    <p class="cta-sub">Tap the button above to download the ride to GroupTrack and get your radio configured automatically.</p>
    <p style="font-size:13px;color:#888;margin-top:24px;">No cell signal required during the ride. GroupTrack operates entirely over LoRa mesh radio — pre-download your maps and you are ready to roll.</p>
  </div>
  <div class="footer">
    <div class="footer-text">
      GroupTrack &nbsp;|&nbsp; Off-Grid Convoy Coordination<br>
      <a href="http://www.grouptrack.org" class="footer-link">www.grouptrack.org</a>
      &nbsp;&nbsp;|&nbsp;&nbsp;
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
