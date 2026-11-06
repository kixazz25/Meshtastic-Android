package com.grouptrack.shared.log

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import co.touchlab.kermit.platformLogWriter

/**
 * GTLOG-2026-11-06: THE logger for GroupTrack's shared code -- ALWAYS use gtLogger("<Tag>"), never Kermit's global Logger.
 * Meshtastic sets the global Kermit Logger so a release build sends log lines to Datadog/Crashlytics (with consent) and not to logcat.
 * This one has its own config: the platform log only (Android logcat with the given tag; the iPad's system log), every severity, never
 * analytics. So `adb logcat -s <Tag>` and deploy_v2's recordings see the shared code exactly as they saw the 2.7e code.
 */
fun gtLogger(tag: String): Logger = Logger(loggerConfigInit(platformLogWriter(), minSeverity = Severity.Verbose), tag)
