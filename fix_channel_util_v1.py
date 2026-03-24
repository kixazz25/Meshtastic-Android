import os

# ── Step 1: Add _avgChannelUtil StateFlow to ConvoyViewModel ─────────────────
vm_path = os.path.join('app', 'src', 'main', 'java', 'com', 'geeksville', 'mesh', 'convoy', 'ConvoyViewModel.kt')
lines = open(vm_path, 'r', encoding='utf-8').readlines()

# Find _distanceMiles declaration and insert after it
for i, l in enumerate(lines):
    if '_distanceMiles = MutableStateFlow(0.0)' in l:
        print(f'Found _distanceMiles at line {i+1}')
        insert_pos = i + 2  # after the val distanceMiles line
        break

lines.insert(insert_pos, '    private val _avgChannelUtil = MutableStateFlow(0f)\n')
lines.insert(insert_pos + 1, '    val avgChannelUtil: StateFlow<Float> = _avgChannelUtil.asStateFlow()\n')
lines.insert(insert_pos + 2, '\n')
print(f'Inserted _avgChannelUtil StateFlow after line {insert_pos}')

# Find where we read battery_level in readLiveNodes and add channel_util after it
for i, l in enumerate(lines):
    if 'battery_pct = node.deviceMetrics.battery_level' in l:
        print(f'Found battery_pct at line {i+1}')
        battery_line = i
        break

# Find the closing of the mapNotNull block - look for the allNodes filter line
for i in range(battery_line, battery_line + 30):
    if 'val filterInput' in lines[i]:
        filter_line = i
        print(f'Found filterInput at line {i+1}')
        break

# Insert channel util calculation before filterInput
util_calc = [
    '        // Calculate average channel utilization across all nodes\n',
    '        val avgUtil = if (allNodes.isEmpty()) 0f else\n',
    '            allNodes.mapNotNull { node ->\n',
    '                try {\n',
    '                    val nodeRaw = nodeRepository.nodeDBbyNum.value[node.nodeId.removePrefix("!").toLong(16).toInt()]\n',
    '                    nodeRaw?.deviceMetrics?.channel_utilization\n',
    '                } catch (e: Exception) { null }\n',
    '            }.average().toFloat().takeIf { !it.isNaN() } ?: 0f\n',
    '        _avgChannelUtil.value = avgUtil\n',
    '\n',
]
lines = lines[:filter_line] + util_calc + lines[filter_line:]
print(f'Inserted channel util calculation before filterInput')

open(vm_path, 'w', encoding='utf-8').writelines(lines)
print('ViewModel done')
