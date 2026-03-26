import sys

path = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyTileDownloader.kt"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# Add Dispatchers.IO import
old_import = "import kotlinx.coroutines.isActive"
new_import = "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.isActive\nimport kotlinx.coroutines.withContext"

if old_import in content:
    content = content.replace(old_import, new_import)
    print("Import OK")
else:
    print("ERROR: import anchor not found")
    sys.exit(1)

# Wrap OkHttp execute() in withContext(Dispatchers.IO)
old_dl = """    suspend fun downloadTile(url: String, dest: File): Boolean {
        repeat(2) { attempt ->
            if (!coroutineContext.isActive) return false
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    response.body?.bytes()?.let { bytes ->
                        dest.writeBytes(bytes)
                        return true
                    }
                }
                response.close()
            } catch (e: Exception) {
                if (attempt == 1) return false
                // Brief pause before retry
                kotlinx.coroutines.delay(500)
            }
        }
        return false
    }"""

new_dl = """    suspend fun downloadTile(url: String, dest: File): Boolean {
        repeat(2) { attempt ->
            if (!coroutineContext.isActive) return false
            try {
                val success = withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(url).build()
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        response.body?.bytes()?.let { bytes ->
                            dest.writeBytes(bytes)
                            true
                        } ?: false
                    } else {
                        response.close()
                        false
                    }
                }
                if (success) return true
            } catch (e: Exception) {
                if (attempt == 1) return false
                kotlinx.coroutines.delay(500)
            }
        }
        return false
    }"""

if old_dl in content:
    content = content.replace(old_dl, new_dl)
    print("downloadTile OK")
else:
    print("ERROR: downloadTile anchor not found")
    sys.exit(1)

with open(path, "w", encoding="utf-8") as f:
    f.write(content)

print("PATCHED OK")
