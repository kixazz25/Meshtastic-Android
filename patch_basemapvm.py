path = "feature/map/src/main/kotlin/org/meshtastic/feature/map/BaseMapViewModel.kt"
with open(path, "r", encoding="utf-8") as f:
    content = f.read()

old = """    val nodes: StateFlow<List<Node>> =
        nodeRepository
            .getNodes()
            .map { nodes -> nodes.filterNot { node -> node.isIgnored } }
            .stateInWhileSubscribed(initialValue = emptyList())"""

new = """    val nodes: StateFlow<List<Node>> =
        try {
            nodeRepository
                .getNodes()
                .map { nodes -> nodes.filterNot { node -> node.isIgnored } }
                .stateInWhileSubscribed(initialValue = emptyList())
        } catch (e: Exception) {
            kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        }"""

if old in content:
    content = content.replace(old, new)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print("Fixed nodes guard")
else:
    print("ERROR: not found")
