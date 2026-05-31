# GroupTrack — Document Catalog (the index that replaces memory)

_Built 2026-05-31 from the docs/ folder inventory. The answer to "does it exist / which version / where." Consult this, not recollection._

**How to use:** every document the project has is listed once, grouped by topic, current file marked ✅ and superseded forks ⤵. If a doc isn't in this catalog, it doesn't exist — that's the point. When you create or supersede a doc, update this catalog (or ask Claude to).

**Rule going forward:** one living file per document. Edit that file; git holds the history. No new `_vN` filenames. Old forks → `archive/`.

> ⚠️ VERSION PICKS NEED DATE-CONFIRMATION. The ✅ "current" marks below are inferred from filenames. Per the rule "latest modified date = current," confirm against `ls -lt` before fully trusting. Items needing confirmation are tagged (confirm).

---

## 1. ROADMAP & STRATEGY
- ✅ roadmap — `GroupTrack_Product_Roadmap_V9.docx` (confirm). ⤵ Complete_Roadmap, Complete_Roadmap_v4, ProductStrategy_Roadmap, product_roadmap_v8 (5)/(6).
- ✅ `GroupTrack_Strategic_Context.docx`
- ✅ `GroupTrack_OrgServices_Revenue_v1.docx`

## 2. DESIGN & ARCHITECTURE
- ✅ consolidated design — `GroupTrack_V25_ConsolidatedDesign_May17_v2.docx` (confirm). ⤵ ConsolidatedDesign_v2.
- ✅ `GroupTrack_MapIndependence_Design.docx` — per-map state (shipped May 31).
- ⚠️ `GroupTrack_RoutePlanning_DesignNotes.docx` — SUPERSEDED BY DECISION: freehand; testers chose snap-2. History only; do NOT implement.
- ✅ `GroupTrack_TrailArchitecture_v2.docx` — note: spec'd source_id never implemented.
- ✅ `GroupTrack_V25_SpatialArchitecture_v1.docx` + `GroupTrack_V25_SpatialImplementationGuide_v1.md`
- ✅ `GroupTrack_MapManager_Spec.docx`
- ✅ `GroupTrack_StandaloneMode_Spec_v2.docx`
- ✅ `GroupTrack_V3_Architecture_Plan.docx`

## 3. CHECKLISTS & PLANS (active tracking)
- ✅ V2.5 master checklist — `v25_master_checklist.md` (in Drive, LIVING). Superseded: LivingChecklist.docx, Placeholder_Checklist_v1.txt, V25_Backlog, V25_ActionPlan_v3.
- ✅ `GroupTrack_V25_MasterPlan_v1.md`
- ✅ `GroupTrack_V25_DecisionLog_May18_v3.docx` (latest v3)
- ✅ `GroupTrack_V25_LifecycleOwnership_v3.docx` + `GroupTrack_V25_EntityLifecycle_v1.md`

## 4. HANDOFFS (dated, append-only — newest = current state)
- ✅ latest: `GroupTrack_Handoff_May31.docx` (today). Prior: May30, V25_Handoff_May29, SessionHandoff_May16, Handoff_May2/3_EOD, + April series. KEEP all as history.

## 5. RELEASE NOTES
- ✅ current V2.5: `GroupTrack_Release_Notes_V2.5.pdf` + `GroupTrack_V25_ReleaseNotes.docx`. ⤵ V2.5_ReleaseNotes_May29.
- ⤵ V2.4 release notes (multiple) — archive.

## 6. USER MANUAL & GUIDES
- ✅ V2.5 manual: `GroupTrack_V25_Manual_CookbookDraft.docx` + `grouptrack_manual.html` (3-section spine, LIVING). Also V25_UserManual_v3, V25_ManualAddendum.
- ⤵ V2.4/V2.3 manuals & install guides — archive.
- ✅ `GroupTrack_FAQ_Final.pdf`, `GroupTrack_BetaTester_Flyer.pdf`

## 7. V3.0 PLANNING (future)
- ✅ implementation: `GroupTrack_V3_CompleteImplementation_FINAL_v2.docx`. ⤵ CompleteImplementation_v1, Implementation_Plan.
- ✅ `GroupTrack_V3_StubbedProcesses_FINAL_v2.docx`
- ✅ V3_Registration_Paywall_Spec_v2, V3_ProcessSpec_v1, V3_MapEnhancements_v1, V3_Online_Offline_Manual_v2, V3_PhaseB_Tasks_v2, V3_Complete_Task_List, V3_PickupGuide_v1.

## 8. ENVIRONMENT & INFRA REFERENCE
- ✅ `DEV_ENVIRONMENT_v3.md`
- ✅ AWS: `GroupTrack_AWS_Environment_Reference_v2.docx` (latest). ⤵ v1, (1).
- ✅ EC2_launch_reference, MasterConfig_Reference_v1, EnrollmentAPI_v1, NightlyProcesses_v1.
- ✅ PlayStore_Keys, PlayStore_Checklist_v1, PlayStore_DeploymentNotes_v1, GooglePlay_Setup.pdf, Firebase_SHA256_Fix.

## 9. CODE CROSS-REFERENCE (grep-killers, regenerated)
- ✅ field_crossref_raw.txt (authoritative), where_used_raw.txt, function_universe_raw.txt, navigation_xref.txt.
- ✅ Function_CrossRef_V1.docx, WhereUsed_Reference_v1.docx (doc form).

## 10. REFERENCE DATA
- ✅ UtahTrailsData_Reference, V25_TrailSourceCatalog_v1.md, V25_MapSourceCatalog_v2.md, TickEngine_Reference.md, V25_DataProtection_v1.md.

## 11. BUG / ISSUE NOTES (point-in-time history)
- LeadLock_Problem_Resolution, BL02_channel_num_bug, OfflineMaps_Debug, OpenIssues_AWS_Plan, Recommit_Issue_Notes, Dashboard_OpenIssues_Apr11.

## 12. SCRIPTS
- ✅ recommit_docs_v11.sh (current nightly). sync_assets_v1.sh. ⤵ recommit v3/v6/v9/v10 — archive.

## 13. SESSION SUMMARIES (Apr–May, historical)
- Day23–27 summaries, SessionCloseout Apr7–11, WorkPlan Apr5–7, weekday task lists. Pure history.

## 14. PROTOTYPES & HTML
- v3_prototype.html, v3_proto_v005, v3_map_functions, grouptrack_architecture.html, grouptrack_website.html, V25_InteractivePanels.html, V25_ScreenReference_v5.html, TrailImport_Flow_Mockup.html.

## 15. BIG CONSOLIDATED SOURCE
- ✅ `GroupTrack_AllDocs.txt` — ~80 docs concatenated; full-text search of older content.

---
_⚠️ = status note. ⤵ = superseded fork (→ archive/). ✅ = current. (confirm) = verify by modified date._