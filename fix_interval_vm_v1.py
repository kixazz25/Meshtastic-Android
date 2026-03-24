import os

# ── Step 1: Add setGpsInterval and currentIntervalSecs to ConvoyViewModel ────
vm_path = os.path.join('app', 'src', 'main', 'java', 'com', 'geeksville', 'mesh', 'convoy', 'ConvoyViewModel.kt')
lines = open(vm_path, 'r', encoding='utf-8').readlines()

# Find dismissNodeHud function to insert after it
for i, l in enumerate(lines):
    if 'fun dismissNodeHud()' in l:
        # Find closing brace
        for j in range(i, i+6):
            if lines[j].strip() == '}' and j > i:
                print(f'dismissNodeHud closes at line {j+1}')
                insert_pos = j + 1
                break
        break

new_fns = [
    '\n',
    '    // ── GPS interval ────────────────────────────────────────────────────\n',
    '    private val _currentIntervalSecs = MutableStateFlow(5)\n',
    '    val currentIntervalSecs: StateFlow<Int> = _currentIntervalSecs.asStateFlow()\n',
    '\n',
    '    fun setGpsInterval(secs: Int, channelViewModel: com.geeksville.mesh.ui.sharing.ChannelViewModel) {\n',
    '        _currentIntervalSecs.value = secs\n',
    '        viewModelScope.launch {\n',
    '            try {\n',
    '                channelViewModel.setConfig(\n',
    '                    org.meshtastic.proto.Config(\n',
    '                        position = org.meshtastic.proto.Config.PositionConfig(\n',
    '                            gps_update_interval = secs,\n',
    '                            gps_mode = org.meshtastic.proto.Config.PositionConfig.GpsMode.ENABLED\n',
    '                        )\n',
    '                    )\n',
    '                )\n',
    '                android.util.Log.i("ConvoyGPS", "GPS interval set to ${secs}s")\n',
    '            } catch (e: Exception) {\n',
    '                android.util.Log.e("ConvoyGPS", "Failed to set GPS interval: ${e.message}")\n',
    '            }\n',
    '        }\n',
    '    }\n',
]

lines = lines[:insert_pos] + new_fns + lines[insert_pos:]
open(vm_path, 'w', encoding='utf-8').writelines(lines)
print(f'Inserted setGpsInterval after line {insert_pos}')

# ── Step 2: Wire slider in ConvoyScreen GroupHud call ────────────────────────
screen_path = os.path.join('app', 'src', 'main', 'java', 'com', 'geeksville', 'mesh', 'convoy', 'ConvoyScreen.kt')
lines2 = open(screen_path, 'r', encoding='utf-8').readlines()

for i, l in enumerate(lines2):
    if 'avgChannelUtil = viewModel.avgChannelUtil.collectAsStateWithLifecycle().value,' in l:
        print(f'avgChannelUtil arg at line {i+1}')
        lines2.insert(i+1, '                        currentIntervalSecs = viewModel.currentIntervalSecs.collectAsStateWithLifecycle().value,\n')
        lines2.insert(i+2, '                        onIntervalChange = { secs -> viewModel.setGpsInterval(secs, channelViewModel) },\n')
        print(f'Inserted interval wiring after line {i+1}')
        break

# Add channelViewModel to ConvoyScreen composable if not already there
for i, l in enumerate(lines2):
    if 'fun ConvoyScreen(' in l:
        print(f'ConvoyScreen at line {i+1}')
        # Check if channelViewModel already declared
        for j in range(i, i+20):
            if 'channelViewModel' in lines2[j]:
                print(f'channelViewModel already in ConvoyScreen at line {j+1}')
                break
        break

open(screen_path, 'w', encoding='utf-8').writelines(lines2)
print('ConvoyScreen wiring done')
