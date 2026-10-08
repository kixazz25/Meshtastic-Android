# Retired 2.6 radio-apply chain (RETIRE26-2026-10-08)

Removed from the build on branch 2.7b (cycle 1). Kept here as REFERENCE ONLY (.kt.txt -- never compiled).

* ConvoyApplyList.kt.txt -- the checklist field-array processor (LoraField, ChannelField, DeviceField, PositionField,
  ModuleField, DisplayField; ConvoyApplyList). Reference for the comm-module translator / managed-field list.
* ConvoyApplyRadioScreen, ConvoyRadioWriter (WorkingConfig), ConvoyMasterApply, ConvoyProfileBuilder,
  ConvoyVerifyConfigScreen -- the full-config write + read-back verify rules.
* ConvoySettingsPanel (developer panel), ConvoyApplyList*/MasterCapture*/MasterSuccess/ReconnectWait/
  ArchiveRestore/RadioConfigScreens/RadioManager -- the screens that drove it.

Replaced in 2.7a by: RadioConfigurator (managed fields, apply + verify), GRP Awareness check-in apply, Saved configs
(ConfigReviewScreen). The V3 screens still carry buttons that used this chain; they now open GRP Awareness and are
marked V3-REFRESH -- see the V3 pick-up planning note on ConvoyConfig.V3_FEATURES_ENABLED.
Full source history: git, before commit of RETIRE26-2026-10-08 on 2.7b.
