CONVOY MASTER RADIO CONFIG — ONE TIME CAPTURE
===============================================

This is a developer-only operation performed ONCE before the app ships.
No user in the shipped app can create or modify the master config.

WHAT IT IS:
  master_config.json — your radio's full config snapshot
  Stored in: app/src/main/assets/master_config.json
  On install: copied to C:/ConvoyProto/master_config.json
  If missing on device: restored from bundled asset automatically

HOW TO CAPTURE:
  1. Connect your Meshtastic radio via Bluetooth
  2. Run the app in DEBUG mode
  3. Open Convoy > Event/Ride > Dev: Capture Master Config
     (this menu item only appears in debug builds)
  4. App reads full radio snapshot and saves to assets/master_config.json
  5. Commit the file to the repo
  6. Remove the dev menu item before release build

FIELDS CAPTURED:
  - Hardware model and firmware version
  - Full LoRa config (region, modem preset, bandwidth, spread factor)
  - All channel settings
  - Full DeviceProfile protobuf (base64) for complete restore fidelity

WHAT RIDE CREATORS GET:
  - master_config.json bundled in their app install
  - Every event derives LoRa settings from master
  - Only channel name and PSK change per event
  - Radio written from master + event overrides — no deviation possible

WHAT RIDERS GET:
  - Ride invite package from organizer (F2 transfer)
  - App writes radio from invite — derived from master
  - Rider never sees a config screen

NOBODY ELSE CREATES THIS FILE. EVER.
