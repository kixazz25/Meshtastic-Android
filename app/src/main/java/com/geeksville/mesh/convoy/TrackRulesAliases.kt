package com.geeksville.mesh.convoy

/** PANELMOVE3-2026-11-19: TrackRules moved to com.grouptrack.shared.track (the iPad needs the rules -- the rules decide what every
 *  recorded track looks like, so they cannot stay Android-only).
 *  These are TYPEALIASES, not a facade object. Unlike FACADE-2026-11-19 (two TrailFilterState objects, one delegating to the other --
 *  4 builds lost to it), a typealias is the SAME symbol under a second name: there is exactly one of each of these. Every one of the
 *  22 app files that name these keeps compiling unchanged, and no symbol check can be fooled by a simple name. */
typealias TrackPoint = com.grouptrack.shared.track.TrackPoint
typealias WPoint = com.grouptrack.shared.track.WPoint
typealias Line = com.grouptrack.shared.track.Line
typealias LineSource = com.grouptrack.shared.track.LineSource
typealias TrackGeo = com.grouptrack.shared.track.TrackGeo
typealias TrackGrounds = com.grouptrack.shared.track.TrackGrounds
typealias TrackPath = com.grouptrack.shared.track.TrackPath
typealias TrackWriter = com.grouptrack.shared.track.TrackWriter
typealias ScreenWriter = com.grouptrack.shared.track.ScreenWriter
typealias LeadCartProcess = com.grouptrack.shared.track.LeadCartProcess
typealias MyCartProcess = com.grouptrack.shared.track.MyCartProcess
