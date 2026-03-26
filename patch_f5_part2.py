import sys

path = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"
path_config = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyConfig.kt"

with open(path, "r", encoding="utf-8") as f:
    screen = f.read()

old = '            android.widget.Toast.makeText(context, "Map download complete — ${summary.downloaded} tiles", android.widget.Toast.LENGTH_LONG).show()\n        }\n    }'

new = '            android.widget.Toast.makeText(context, "Map download complete — ${summary.downloaded} tiles", android.widget.Toast.LENGTH_LONG).show()\n            val localUrl = ConvoyConfig.LOCAL_TILE_BASE + ConvoyConfig.ACTIVE_TILE_SOURCE + "/{z}/{x}/{y}.png"\n            webViewRef.value?.evaluateJavascript("setTileUrl(\'$localUrl\')", null)\n        }\n    }'

if old in screen:
    screen = screen.replace(old, new)
    print("Part 2 OK — local tile switch added")
else:
    print("ERROR: anchor not found")
    sys.exit(1)

with open(path, "w", encoding="utf-8") as f:
    f.write(screen)

# Part 3 — LOCAL_TILE_BASE in ConvoyConfig
with open(path_config, "r", encoding="utf-8") as f:
    config = f.read()

old3 = '    var ACTIVE_TILE_SOURCE = "SAT"'
new3 = '    var ACTIVE_TILE_SOURCE = "SAT"\n    const val LOCAL_TILE_BASE = "file:///data/user/0/com.geeksville.mesh.google.debug/files/tiles/"'

if old3 in config:
    config = config.replace(old3, new3)
    print("Part 3 OK — LOCAL_TILE_BASE added")
else:
    print("ERROR: Part 3 anchor not found")
    sys.exit(1)

with open(path_config, "w", encoding="utf-8") as f:
    f.write(config)

print("ALL PATCHED OK")
