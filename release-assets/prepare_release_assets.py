#!/usr/bin/env python3
"""
prepare_release_assets.py

Pre-release script — run before building an AAB.

1. Finds the NEWEST dated version of each doc in release-assets/
2. Refreshes geofabrik_states.json from the live Geofabrik index
3. Copies and RENAMES each to its app asset name in app/src/main/assets/
4. Verifies every asset landed correctly

Usage:
    cd ~/Meshtastic-Android
    python release-assets/prepare_release_assets.py

Release-assets folder holds DATED files as produced each session:
    grouptrack_manual_2026-08-18.html
    grouptrack_manual_2026-08-20.html        <- newest wins
    grouptrack_release_notes_2026-08-18.html
    GroupTrack_QuickStart_..._2026-08-17_v4.html
    geofabrik_states.json
    update_geofabrik_states.py
    prepare_release_assets.py                <- this script
"""

import os, sys, glob, re, shutil, subprocess, json
from datetime import date

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RELEASE_DIR = os.path.join(REPO_ROOT, "release-assets")
APP_ASSETS = os.path.join(REPO_ROOT, "app", "src", "main", "assets")

# Each entry: (glob pattern in release-assets, target name in app assets)
DOC_ASSETS = [
    ("grouptrack_manual_*.html",                    "grouptrack_manual.html"),
    ("grouptrack_release_notes_*.html",             "grouptrack_release_notes.html"),
    ("GroupTrack_QuickStart_*_*.html",               "GroupTrack_QuickStart.html"),
]

# Non-dated assets (copied directly, no date matching)
STATIC_ASSETS = [
    ("geofabrik_states.json",                       "geofabrik_states.json"),
]


def find_newest(pattern):
    """Find the newest file matching a glob pattern, sorted by filename (dates sort naturally)."""
    matches = sorted(glob.glob(os.path.join(RELEASE_DIR, pattern)))
    if not matches:
        return None
    # Newest = last when sorted (dates in YYYY-MM-DD sort correctly)
    return matches[-1]


def refresh_geofabrik():
    """Run the Geofabrik updater to refresh geofabrik_states.json."""
    updater = os.path.join(RELEASE_DIR, "update_geofabrik_states.py")
    target = os.path.join(RELEASE_DIR, "geofabrik_states.json")

    if not os.path.exists(updater):
        print("  WARNING: update_geofabrik_states.py not found — skipping refresh")
        return

    print("--- Refreshing geofabrik_states.json ---")
    result = subprocess.run(
        [sys.executable, updater, "--out", target],
        capture_output=True, text=True
    )
    if result.returncode == 0:
        try:
            with open(target) as f:
                data = json.load(f)
            print("  OK: %d states, generated %s" % (
                len(data.get("states", [])), data.get("generated", "?")))
        except Exception as e:
            print("  WARNING: verify failed: %s" % e)
    else:
        print("  WARNING: refresh failed (using existing file)")
        if result.stderr:
            print("  %s" % result.stderr[:200])


def copy_assets():
    """Find newest dated docs and copy/rename to app assets."""
    print("\n--- Copying assets to %s ---" % APP_ASSETS)

    if not os.path.isdir(APP_ASSETS):
        print("ERROR: %s not found — are you in the repo root?" % APP_ASSETS)
        return False

    all_ok = True

    # Dated docs — find newest
    for pattern, target_name in DOC_ASSETS:
        src = find_newest(pattern)
        if src is None:
            print("  MISSING: no file matching '%s'" % pattern)
            all_ok = False
            continue

        dst = os.path.join(APP_ASSETS, target_name)
        src_size = os.path.getsize(src)
        src_basename = os.path.basename(src)

        shutil.copy2(src, dst)
        dst_size = os.path.getsize(dst)

        if src_size == dst_size:
            print("  OK: %s → %s (%d bytes)" % (src_basename, target_name, dst_size))
        else:
            print("  ERROR: %s size mismatch (%d → %d)" % (target_name, src_size, dst_size))
            all_ok = False

    # Static assets — copy directly
    for src_name, target_name in STATIC_ASSETS:
        src = os.path.join(RELEASE_DIR, src_name)
        dst = os.path.join(APP_ASSETS, target_name)

        if not os.path.exists(src):
            print("  MISSING: %s" % src_name)
            all_ok = False
            continue

        shutil.copy2(src, dst)
        print("  OK: %s → %s (%d bytes)" % (src_name, target_name, os.path.getsize(dst)))

    return all_ok


def verify_assets():
    """Content checks on deployed assets."""
    print("\n--- Verifying deployed assets ---")

    checks = [
        ("grouptrack_manual.html",        "Ride Map",             40),
        ("grouptrack_release_notes.html",  "RELNOTES-",            1),
        ("geofabrik_states.json",          '"slug"',              50),
    ]

    all_ok = True
    for filename, marker, min_count in checks:
        path = os.path.join(APP_ASSETS, filename)
        if not os.path.exists(path):
            print("  MISSING: %s" % filename)
            all_ok = False
            continue

        with open(path, encoding="utf-8", errors="replace") as f:
            content = f.read()

        count = content.count(marker)
        status = "OK" if count >= min_count else "FAIL"
        print("  %s: %s — '%s' × %d (need %d+)" % (status, filename, marker, count, min_count))
        if count < min_count:
            all_ok = False

    return all_ok


def show_inventory():
    """Show what's in the release-assets folder."""
    print("\n--- Release-assets inventory ---")
    for pattern, target in DOC_ASSETS:
        matches = sorted(glob.glob(os.path.join(RELEASE_DIR, pattern)))
        if matches:
            newest = os.path.basename(matches[-1])
            print("  %s (%d versions, newest: %s)" % (target, len(matches), newest))
        else:
            print("  %s — NO FILES FOUND matching '%s'" % (target, pattern))

    for src, target in STATIC_ASSETS:
        path = os.path.join(RELEASE_DIR, src)
        if os.path.exists(path):
            print("  %s (%d bytes)" % (src, os.path.getsize(path)))
        else:
            print("  %s — MISSING" % src)


def main():
    print("=" * 60)
    print("GroupTrack — Prepare Release Assets — %s" % date.today())
    print("=" * 60)
    print("Repo: %s" % REPO_ROOT)
    print()

    show_inventory()

    # Step 1: Refresh Geofabrik
    print()
    refresh_geofabrik()

    # Step 2: Copy and rename
    copy_ok = copy_assets()

    # Step 3: Verify
    verify_ok = verify_assets()

    print("\n" + "=" * 60)
    if copy_ok and verify_ok:
        print("ALL ASSETS READY — proceed with build")
        print("  ./gradlew bundleGoogleRelease ...")
    else:
        print("WARNINGS ABOVE — review before building")
    print("=" * 60)


if __name__ == "__main__":
    main()
