path = r"C:\Users\kixaz\Meshtastic-Android\app\src\main\java\com\geeksville\mesh\convoy\ConvoyScreen.kt"
with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# Remove duplicate state vars
content = content.replace(
    "    var showLayerMenu by remember { mutableStateOf(false) }\n    var mapTypeLabel by remember { mutableStateOf(\"SAT\") }\n    var showLayerMenu by remember { mutableStateOf(false) }\n    var mapTypeLabel by remember { mutableStateOf(\"SAT\") }\n",
    "    var showLayerMenu by remember { mutableStateOf(false) }\n    var mapTypeLabel by remember { mutableStateOf(\"SAT\") }\n"
)
print("Removed duplicate state vars")

# Add missing imports
imports_to_add = {
    "import androidx.compose.material3.DropdownMenu": "import androidx.compose.material3.Card",
    "import androidx.compose.material3.DropdownMenuItem": "import androidx.compose.material3.Card",
    "import androidx.compose.ui.draw.background": "import androidx.compose.ui.draw",
    "import androidx.compose.foundation.background": "import androidx.compose.material3.Card",
    "import androidx.compose.ui.unit.dp": None,
}

if "import androidx.compose.material3.DropdownMenu" not in content:
    content = content.replace(
        "import androidx.compose.material3.Card",
        "import androidx.compose.material3.Card\nimport androidx.compose.material3.DropdownMenu\nimport androidx.compose.material3.DropdownMenuItem"
    )
    print("Added DropdownMenu imports")

if "import androidx.compose.foundation.background" not in content:
    content = content.replace(
        "import androidx.compose.foundation.",
        "import androidx.compose.foundation.background\nimport androidx.compose.foundation."
    )
    print("Added background import")

if "import androidx.compose.ui.Modifier" not in content and "Modifier.size" in content:
    pass  # size comes from Modifier which should already be imported

with open(path, "w", encoding="utf-8") as f:
    f.write(content)
print("Done")
