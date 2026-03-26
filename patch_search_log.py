import sys

path = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

old = """                                        } else if (results.size == 1) {
                                            val addr = results[0]
                                            val extras = addr.extras"""

new = """                                        } else if (results.size == 1) {
                                            val addr = results[0]
                                            android.util.Log.i("ConvoySearch", "lat=${addr.latitude} lng=${addr.longitude} extras=${addr.extras} feature=${addr.featureName} locality=${addr.locality} admin=${addr.adminArea}")
                                            val extras = addr.extras"""

if old in content:
    content = content.replace(old, new)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print("PATCHED OK")
else:
    print("ERROR: anchor not found")
    sys.exit(1)
