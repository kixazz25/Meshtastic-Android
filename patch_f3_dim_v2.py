import sys

path = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

old = '                        Text("${pending.tileCount} tiles \u2014 ${"%.1f".format(pending.sizeMB)} MB estimated")\n                        Text("Source: ${pending.sourceName.uppercase()}")'

new = ('                        val widthMi = run {\n'
       '                            val dLon = Math.toRadians(pending.east - pending.west)\n'
       '                            val lat = Math.toRadians((pending.north + pending.south) / 2.0)\n'
       '                            3958.8 * Math.acos(Math.sin(lat).let { s -> s * s + Math.cos(lat).let { c -> c * c * Math.cos(dLon) } })\n'
       '                        }\n'
       '                        val heightMi = 3958.8 * Math.toRadians(pending.north - pending.south)\n'
       '                        Text("${"%.1f".format(widthMi)} mi \u00d7 ${"%.1f".format(heightMi)} mi")\n'
       '                        Text("${pending.tileCount} tiles \u2014 ${"%.1f".format(pending.sizeMB)} MB estimated")\n'
       '                        Text("Source: ${pending.sourceName.uppercase()}")')

if old in content:
    content = content.replace(old, new)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print("PATCHED OK")
else:
    # Try original text without em dash
    old2 = ('                        Text("${pending.tileCount} tiles — ${"%.1f".format(pending.sizeMB)} MB estimated")\n'
            '                        Text("Source: ${pending.sourceName.uppercase()}")')
    if old2 in content:
        new2 = ('                        val widthMi = run {\n'
                '                            val dLon = Math.toRadians(pending.east - pending.west)\n'
                '                            val lat = Math.toRadians((pending.north + pending.south) / 2.0)\n'
                '                            3958.8 * Math.acos(Math.sin(lat) * Math.sin(lat) + Math.cos(lat) * Math.cos(lat) * Math.cos(dLon))\n'
                '                        }\n'
                '                        val heightMi = 3958.8 * Math.toRadians(pending.north - pending.south)\n'
                '                        Text("${"%.1f".format(widthMi)} mi × ${"%.1f".format(heightMi)} mi")\n'
                '                        Text("${pending.tileCount} tiles — ${"%.1f".format(pending.sizeMB)} MB estimated")\n'
                '                        Text("Source: ${pending.sourceName.uppercase()}")')
        content = content.replace(old2, new2)
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)
        print("PATCHED OK (alt anchor)")
    else:
        print("ERROR: anchor not found")
        sys.exit(1)
