# GroupTrack V2.5 — Data Protection: Backup, Journal, Rollback
## Schema Hardening Prerequisite
## May 9, 2026

---

## 1. THE PROBLEM

The user spatial database (grouptrack_user.spatialite) contains irreplaceable personal data: waypoints, routes, tracks, ride areas. A bad import, an accidental bulk delete, a corrupt consolidation, or a failed source ingestion could destroy rider data that took months to build. 

Three layers of protection required:
- **Backup** — full database snapshots, recoverable
- **Journal** — every change logged with before-state, reversible
- **Rollback** — undo to a specific point in time

---

## 2. BACKUP

### Automatic backup:
- On app launch: copy grouptrack_user.spatialite to grouptrack_user.backup
- Before any destructive operation (source removal, area removal, bulk delete): create timestamped backup
- Location: /sdcard/Documents/GroupTrack/data/backups/
- Naming: grouptrack_user_YYYYMMDD_HHMMSS.backup
- Retain last 5 backups. Delete oldest when creating 6th.
- Never auto-delete backups during storage cleanup — only the backup rotation.

### Manual backup:
- Settings → Data Management → Backup Now
- Creates timestamped backup immediately
- User can also export to Downloads for off-device storage

### Recovery:
- Settings → Data Management → Restore from Backup
- Shows list of available backups with timestamp and size
- User selects backup → confirmation: "This will replace all current waypoints, routes, tracks, and ride areas with the backup from [date]. This cannot be undone."
- Restore = close current DB, copy backup over current, reopen
- After restore, create a backup of what was just replaced (so user can undo the restore)

### Backup schema:
```sql
CREATE TABLE backup_log (
    backup_id       INTEGER PRIMARY KEY AUTOINCREMENT,
    filename        TEXT NOT NULL,           -- backup file name
    created_at      TEXT NOT NULL,           -- ISO 8601
    trigger_reason  TEXT NOT NULL,           -- 'app_launch' | 'pre_destructive' | 'manual' | 'pre_restore'
    description     TEXT,                    -- what operation triggered this backup
    size_bytes      INTEGER NOT NULL,        -- backup file size
    waypoint_count  INTEGER DEFAULT 0,       -- snapshot counts for display
    route_count     INTEGER DEFAULT 0,
    track_count     INTEGER DEFAULT 0,
    area_count      INTEGER DEFAULT 0
);
```

---

## 3. CHANGE JOURNAL

Every INSERT, UPDATE, and DELETE on user data tables is journaled with enough detail to reverse the operation.

### Journal table:
```sql
CREATE TABLE change_journal (
    journal_id      INTEGER PRIMARY KEY AUTOINCREMENT,
    transaction_id  TEXT NOT NULL,           -- UUID grouping related changes
    sequence        INTEGER NOT NULL,        -- order within transaction (1, 2, 3...)
    timestamp       TEXT NOT NULL,           -- ISO 8601
    table_name      TEXT NOT NULL,           -- 'waypoints' | 'routes' | 'tracks' | 'ride_areas'
    operation       TEXT NOT NULL,           -- 'INSERT' | 'UPDATE' | 'DELETE'
    record_id       TEXT NOT NULL,           -- primary key of affected record
    before_state    TEXT,                    -- JSON snapshot of record BEFORE change (NULL for INSERT)
    after_state     TEXT,                    -- JSON snapshot of record AFTER change (NULL for DELETE)
    user_action     TEXT NOT NULL,           -- human-readable: 'Created waypoint "Gas Station"'
    rolled_back     INTEGER DEFAULT 0       -- 1 if this change was rolled back
);

CREATE INDEX idx_journal_transaction ON change_journal(transaction_id);
CREATE INDEX idx_journal_timestamp ON change_journal(timestamp);
CREATE INDEX idx_journal_table ON change_journal(table_name, record_id);
```

### What gets journaled:

| Table | INSERT | UPDATE | DELETE |
|-------|--------|--------|--------|
| waypoints | Full record as after_state | Before + after state | Full record as before_state |
| routes | Full record as after_state | Before + after state | Full record as before_state |
| tracks | Full record as after_state | Before + after state | Full record as before_state |
| ride_areas | Full record as after_state | Before + after state | Full record as before_state |
| tile_sources | Full record as after_state | Before + after state | Full record as before_state |
| map_slots | N/A | Before + after state | N/A |

### What does NOT get journaled:
- Trail data (grouptrack_trails.db) — public data, re-downloadable, no user data at risk
- Transfer queue — transient operational data
- Transfer history — audit data, not user content
- data_log — already a log, don't log the log
- backup_log — metadata, not user content

### Transaction grouping:
Related changes share a transaction_id. Examples:
- Waypoint consolidation: DELETE waypoint A + UPDATE waypoint B = one transaction, two journal entries
- Source ingestion: all trail INSERTs share one transaction (but trails aren't journaled — only user data tables)
- Bulk waypoint import from ride enrollment (V3.0): all INSERTs share one transaction

### Journal triggers (one per table per operation):

```sql
-- WAYPOINT INSERT
CREATE TRIGGER jrnl_waypoint_insert AFTER INSERT ON waypoints
BEGIN
    INSERT INTO change_journal (
        transaction_id, sequence, timestamp, table_name, operation,
        record_id, before_state, after_state, user_action
    ) VALUES (
        COALESCE(@current_transaction_id, hex(randomblob(16))),
        COALESCE(@current_sequence, 1),
        datetime('now'),
        'waypoints', 'INSERT',
        NEW.waypoint_id,
        NULL,
        json_object(
            'waypoint_id', NEW.waypoint_id,
            'name', NEW.name,
            'type', NEW.type,
            'description', NEW.description,
            'area_id', NEW.area_id,
            'ride_id', NEW.ride_id,
            'shared', NEW.shared,
            'created_at', NEW.created_at,
            'updated_at', NEW.updated_at
        ),
        'Created waypoint "' || NEW.name || '"'
    );
END;

-- WAYPOINT UPDATE
CREATE TRIGGER jrnl_waypoint_update AFTER UPDATE ON waypoints
BEGIN
    INSERT INTO change_journal (
        transaction_id, sequence, timestamp, table_name, operation,
        record_id, before_state, after_state, user_action
    ) VALUES (
        COALESCE(@current_transaction_id, hex(randomblob(16))),
        COALESCE(@current_sequence, 1),
        datetime('now'),
        'waypoints', 'UPDATE',
        NEW.waypoint_id,
        json_object(
            'waypoint_id', OLD.waypoint_id,
            'name', OLD.name,
            'type', OLD.type,
            'description', OLD.description,
            'area_id', OLD.area_id,
            'ride_id', OLD.ride_id,
            'shared', OLD.shared,
            'created_at', OLD.created_at,
            'updated_at', OLD.updated_at
        ),
        json_object(
            'waypoint_id', NEW.waypoint_id,
            'name', NEW.name,
            'type', NEW.type,
            'description', NEW.description,
            'area_id', NEW.area_id,
            'ride_id', NEW.ride_id,
            'shared', NEW.shared,
            'created_at', NEW.created_at,
            'updated_at', NEW.updated_at
        ),
        CASE
            WHEN OLD.name != NEW.name THEN 'Renamed waypoint "' || OLD.name || '" to "' || NEW.name || '"'
            WHEN OLD.type != NEW.type THEN 'Changed type of "' || NEW.name || '"'
            WHEN OLD.shared != NEW.shared THEN CASE NEW.shared WHEN 1 THEN 'Shared waypoint "' || NEW.name || '"' ELSE 'Unshared waypoint "' || NEW.name || '"' END
            ELSE 'Updated waypoint "' || NEW.name || '"'
        END
    );
END;

-- WAYPOINT DELETE
CREATE TRIGGER jrnl_waypoint_delete BEFORE DELETE ON waypoints
BEGIN
    INSERT INTO change_journal (
        transaction_id, sequence, timestamp, table_name, operation,
        record_id, before_state, after_state, user_action
    ) VALUES (
        COALESCE(@current_transaction_id, hex(randomblob(16))),
        COALESCE(@current_sequence, 1),
        datetime('now'),
        'waypoints', 'DELETE',
        OLD.waypoint_id,
        json_object(
            'waypoint_id', OLD.waypoint_id,
            'name', OLD.name,
            'type', OLD.type,
            'description', OLD.description,
            'area_id', OLD.area_id,
            'ride_id', OLD.ride_id,
            'shared', OLD.shared,
            'created_at', OLD.created_at,
            'updated_at', OLD.updated_at
        ),
        NULL,
        'Deleted waypoint "' || OLD.name || '"'
    );
END;
```

Same pattern for routes, tracks, ride_areas — three triggers each (INSERT, UPDATE, DELETE). 12 journal triggers total across 4 tables.

---

## 4. ROLLBACK

### Single operation undo:
- "Undo last action" available immediately after any change
- Reads the most recent journal entry (or transaction group)
- Reverses the operation:
  - INSERT → DELETE the record
  - DELETE → INSERT from before_state JSON
  - UPDATE → UPDATE with before_state values
- Mark journal entry as rolled_back=1
- Log the rollback as a new journal entry

### Transaction rollback:
- All entries sharing a transaction_id are rolled back together
- Rolled back in reverse sequence order (last change first)
- Example: waypoint consolidation rollback = re-INSERT deleted waypoint A, then revert UPDATE on waypoint B

### Point-in-time rollback:
- Settings → Data Management → Rollback to Date/Time
- Show list of transactions with timestamps and descriptions
- User selects a point → ALL transactions after that point are rolled back in reverse order
- Confirmation: "Roll back N changes made since [date/time]? This will undo: [list of user_action descriptions]"
- After rollback, create a backup of the pre-rollback state (so rollback itself can be undone)

### Rollback limitations:
- Cannot rollback geometry changes on waypoints (SpatiaLite geometry not stored in JSON before_state). SOLUTION: store geometry as WKT text in the before_state JSON.
- Cannot rollback file operations (track file deleted from filesystem). SOLUTION: journal stores the fact, but actual file recovery requires the backup. Rollback of track DELETE recreates the DB record but warns: "Track file may need to be restored from backup."
- Cannot rollback trail source ingestion (trails are in separate DB, not journaled). SOLUTION: trail re-ingestion is the recovery path — re-download and re-ingest the source.

### Rollback UI:
```
┌──────────────────────────────────────────┐
│ CHANGE HISTORY                           │
│                                          │
│ Today                                    │
│ 3:42 PM  Deleted waypoint "Old Gate"     │
│          [UNDO]                          │
│                                          │
│ 3:38 PM  Consolidated "Gas Station"      │
│          into "Chevron Fuel Stop"        │
│          [UNDO]                          │
│                                          │
│ 3:15 PM  Created waypoint "Trailhead"   │
│          [UNDO]                          │
│                                          │
│ 2:50 PM  Renamed route "Bar 10 Day 1"   │
│          [UNDO]                          │
│                                          │
│ Yesterday                                │
│ ...                                      │
│                                          │
│ [ROLLBACK TO DATE...]                    │
│ [RESTORE FROM BACKUP...]                 │
└──────────────────────────────────────────┘
```

---

## 5. JOURNAL MAINTENANCE

### Size management:
- Journal grows with every change. Needs periodic cleanup.
- Retain last 30 days of journal entries by default.
- Entries older than 30 days: purge (DELETE WHERE timestamp < date('now', '-30 days') AND rolled_back = 0)
- Rolled-back entries purge after 7 days.
- Before purge: create a backup (so data is recoverable even after journal cleanup).

### Journal + backup interaction:
- Backup captures the full database state at a point in time
- Journal captures individual changes between backups
- Recovery strategy: restore backup + replay journal forward to desired point
- Simpler recovery: just restore the backup closest to desired state

---

## 6. GEOMETRY IN JOURNAL

Waypoint geometry (POINT) and route geometry (LINESTRING) must be captured in the journal for complete rollback.

### Solution — store as WKT in JSON:
```sql
-- In trigger, capture geometry as WKT text
json_object(
    'waypoint_id', OLD.waypoint_id,
    'name', OLD.name,
    'geometry_wkt', AsText(OLD.geometry),  -- 'POINT(-113.5 37.1)'
    ...
)

-- On rollback, recreate geometry from WKT
UPDATE waypoints SET geometry = GeomFromText(
    json_extract(before_state, '$.geometry_wkt'), 4326
) WHERE waypoint_id = ?;
```

This captures the exact position in the journal so moved or deleted waypoints can be perfectly restored.

---

## 7. IMPACT ON SCHEMA

### New tables to add to grouptrack_user.spatialite:
- change_journal (12 triggers: 3 per table × 4 tables)
- backup_log (metadata for backup management)

### Schema version:
- Add schema_version to db_metadata: version 2 includes journal and backup tables
- On app launch: if schema_version < 2, run migration to add new tables and triggers
- Migration is non-destructive — adds tables, doesn't modify existing data

---

## 8. STANDING RULES

1. Every user data change is journaled. No exceptions.
2. Backup before every destructive operation. Automatic, not optional.
3. Journal stores before AND after state. Both needed for rollback.
4. Geometry stored as WKT in journal for spatial rollback.
5. Journal retained 30 days. Backups retained last 5.
6. Rollback of a rollback is possible (backup created before rollback executes).
7. Trail data (public, re-downloadable) is NOT journaled — recovery is re-ingestion.
8. Track file recovery requires backup — journal only tracks DB record, not filesystem.

---

*GroupTrack V2.5 | Data Protection Specification v1 | May 9, 2026*
*Schema must include journal and backup tables before any application code.*
