import Foundation
import GroupTrackShared

// DBPROBE-2026-11-18 (XCODESTEPS, after STEP 4) -- the iPad builds its own database.
//
// Fred, 11:14: "why doesn't application install and launch create of database and folders". It
// does: GtIosHkEnv.kt:159 is `GtSchema.ensure(s, d) { name -> iosBundleAsset(name) }`, and STEP 4
// put the schema files in the bundle. Nothing had ever called it. This calls it.
//
// \u26a0 initDb MUST NOT THROW, and does not -- GtStartupHousekeeping:102 calls it UNGUARDED, so it
// catches internally and leaves the connections null. nil is therefore the failure signal, and the
// reason appears in its own log line, not as a crash.
//
// \u26d4 THE TWO VERSION NUMBERS ARE DIFFERENT THINGS. PRAGMA user_version == 1 is GtSchema.CURRENT,
// GtSchema's own step counter. schema_spatial_v3.sql's "v3" is the FILE's generation, from Android's
// v1 -> v2 -> v3 history. Version 1 is BUILT FROM the v3 files. Nothing in the code says so, which
// is why it is said here.

struct DbCheck: Identifiable {
    let id = UUID()
    let what: String
    let got: String
    let ok: Bool
}

enum DbProbe {

    static func run() -> [DbCheck] {
        var out: [DbCheck] = []

        let storage = GtIosStorageKt.iosStorage()
        let paths   = IosImportPaths(st: storage)
        let env     = IosHkEnv(paths: paths, clock: IosClock(), tag: "DBPROBE")

        out.append(DbCheck(what: "GroupTrack root (Documents, rider-visible in Files)",
                           got: env.dbDir(), ok: !env.dbDir().isEmpty))

        env.doInitDb()

        let spatial = env.spatialDb()
        let data    = env.extDb()

        out.append(DbCheck(what: "spatialDb() after initDb",
                           got: spatial == nil ? "nil -- DATABASE UNAVAILABLE, see the DBPROBE log line"
                                               : (spatial?.path ?? "(no path)"),
                           ok: spatial != nil))
        out.append(DbCheck(what: "extDb() after initDb",
                           got: data == nil ? "nil -- DATABASE UNAVAILABLE" : (data?.path ?? "(no path)"),
                           ok: data != nil))

        if let s = spatial {
            let v = s.rawQueryInt(sql: "PRAGMA user_version")
            out.append(DbCheck(what: "spatial PRAGMA user_version (GtSchema.CURRENT)",
                               got: "\(v), expected 1", ok: v == 1))
            let t = s.rawQueryInt(sql: "SELECT COUNT(*) FROM sqlite_master WHERE type='table'")
            out.append(DbCheck(what: "spatial tables created", got: "\(t)", ok: t > 0))
            let trails = s.rawQueryInt(sql: "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='trails'")
            out.append(DbCheck(what: "the trails table exists", got: trails == 1 ? "yes" : "NO", ok: trails == 1))
        }
        if let d = data {
            let v = d.rawQueryInt(sql: "PRAGMA user_version")
            out.append(DbCheck(what: "data PRAGMA user_version", got: "\(v), expected 1", ok: v == 1))
            let t = d.rawQueryInt(sql: "SELECT COUNT(*) FROM sqlite_master WHERE type='table'")
            out.append(DbCheck(what: "data tables created", got: "\(t)", ok: t > 0))
        }

        let bad = out.filter { !$0.ok }
        print("DBPROBE-2026-11-18 ----------------------------------------------")
        for c in out { print("DBPROBE \(c.ok ? "PASS" : "FAIL") \(c.what): \(c.got)") }
        print("DBPROBE \(out.count - bad.count) of \(out.count) passed")
        if bad.isEmpty {
            print("DBPROBE \u{2b50} GroupTrack's data model now exists on iPadOS, built by the SAME GtSchema")
            print("DBPROBE    Android runs, from the SAME schema files, on a database the app created itself.")
        }
        return out
    }
}
