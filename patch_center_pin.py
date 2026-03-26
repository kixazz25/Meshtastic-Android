import sys

path = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

errors = []

# Part 1: Add center pin to single result fly
old1 = """                                            if (n != 0.0 && s != 0.0) {
                                                val clat = (n + s) / 2.0
                                                val clng = (e + w) / 2.0
                                                webViewRef.value?.evaluateJavascript("setView($clat,$clng,10)", null)
                                                webViewRef.value?.evaluateJavascript("showAreaBoundary($n,$s,$e,$w)", null)
                                            } else {
                                                webViewRef.value?.evaluateJavascript("setView(${addr.latitude},${addr.longitude},10)", null)
                                            }"""

new1 = """                                            if (n != 0.0 && s != 0.0) {
                                                val clat = (n + s) / 2.0
                                                val clng = (e + w) / 2.0
                                                webViewRef.value?.evaluateJavascript("setView($clat,$clng,10)", null)
                                                webViewRef.value?.evaluateJavascript("showAreaBoundary($n,$s,$e,$w)", null)
                                                webViewRef.value?.evaluateJavascript("showSearchCenter($clat,$clng)", null)
                                            } else {
                                                webViewRef.value?.evaluateJavascript("setView(${addr.latitude},${addr.longitude},10)", null)
                                                webViewRef.value?.evaluateJavascript("showSearchCenter(${addr.latitude},${addr.longitude})", null)
                                            }"""

if old1 in content:
    content = content.replace(old1, new1)
    print("Part 1 OK — center pin on single result")
else:
    errors.append("Part 1: single result anchor not found")

# Part 2: Add center pin to list tap
old2 = """                                        if (n != 0.0 && s != 0.0) {
                                            val clat = (n + s) / 2.0
                                            val clng = (e + w) / 2.0
                                            webViewRef.value?.evaluateJavascript("setView($clat,$clng,10)", null)
                                            webViewRef.value?.evaluateJavascript("showAreaBoundary($n,$s,$e,$w)", null)
                                        } else {
                                            webViewRef.value?.evaluateJavascript("setView(${addr.latitude},${addr.longitude},10)", null)
                                        }"""

new2 = """                                        if (n != 0.0 && s != 0.0) {
                                            val clat = (n + s) / 2.0
                                            val clng = (e + w) / 2.0
                                            webViewRef.value?.evaluateJavascript("setView($clat,$clng,10)", null)
                                            webViewRef.value?.evaluateJavascript("showAreaBoundary($n,$s,$e,$w)", null)
                                            webViewRef.value?.evaluateJavascript("showSearchCenter($clat,$clng)", null)
                                        } else {
                                            webViewRef.value?.evaluateJavascript("setView(${addr.latitude},${addr.longitude},10)", null)
                                            webViewRef.value?.evaluateJavascript("showSearchCenter(${addr.latitude},${addr.longitude})", null)
                                        }"""

if old2 in content:
    content = content.replace(old2, new2)
    print("Part 2 OK — center pin on list tap")
else:
    errors.append("Part 2: list tap anchor not found")

# Part 3: Clear center pin on draw mode
old3 = """                                webViewRef.value?.evaluateJavascript("clearAreaBoundary()", null)
                                webViewRef.value?.evaluateJavascript("activateDrawMode()", null)"""

new3 = """                                webViewRef.value?.evaluateJavascript("clearAreaBoundary()", null)
                                webViewRef.value?.evaluateJavascript("clearSearchCenter()", null)
                                webViewRef.value?.evaluateJavascript("activateDrawMode()", null)"""

if old3 in content:
    content = content.replace(old3, new3)
    print("Part 3 OK — center pin cleared on draw mode")
else:
    errors.append("Part 3: draw mode anchor not found")

if errors:
    for e in errors: print("ERROR:", e)
    sys.exit(1)

with open(path, "w", encoding="utf-8") as f:
    f.write(content)

print("ALL PATCHED OK")
