import os

path = os.path.join('app', 'src', 'main', 'java', 'com', 'geeksville', 'mesh', 'convoy', 'ConvoyScreen.kt')
lines = open(path, 'r', encoding='utf-8').readlines()

# ── Work bottom to top to preserve line numbers ───────────────────────────────

# STEP 1: Wrap HudCard content in Row with slider at left
# GroupHud HudCard is at line 744 (0-indexed 743)
# GroupHud closes at line 789 (0-indexed 788)
# Replace lines 744-789 with Row { slider + HudCard { original content } }

hudcard_start = 743  # 0-indexed line 744
grouphud_close = 788  # 0-indexed line 789

print(f'HudCard start: {lines[hudcard_start].rstrip()}')
print(f'GroupHud close: {lines[grouphud_close].rstrip()}')

# Extract original HudCard content (lines 744-788 inclusive, 0-indexed 743-787)
original_hudcard_lines = lines[hudcard_start:grouphud_close]  # includes HudCard { line

# Build CH% display to insert after "Lost" HudStat (line 753, 0-indexed 752 in original)
# Find "Lost" in original_hudcard_lines
lost_idx = None
for i, l in enumerate(original_hudcard_lines):
    if 'HudStat("Lost"' in l:
        lost_idx = i
        break
print(f'Lost HudStat at original_hudcard index {lost_idx}')

# Insert spacer + CH% row after Lost HudStat
# First find the closing } of the Row containing Lost
row_close_idx = None
for i in range(lost_idx, lost_idx + 5):
    if original_hudcard_lines[i].strip() == '}':
        row_close_idx = i
        break
print(f'Row close after Lost at original_hudcard index {row_close_idx}')

ch_display = [
    '          Spacer(Modifier.height(4.dp))\n',
    '          Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {\n',
    '              val chColor = when {\n',
    '                  avgChannelUtil > 40f -> Color(0xFFFF4444)\n',
    '                  avgChannelUtil > 25f -> Color(0xFFFFAA00)\n',
    '                  else -> Color(0xFF00CC44)\n',
    '              }\n',
    '              HudStat("CH%", "%.0f%%".format(avgChannelUtil), chColor)\n',
    '          }\n',
]

original_hudcard_lines = (
    original_hudcard_lines[:row_close_idx+1] +
    ch_display +
    original_hudcard_lines[row_close_idx+1:]
)

# Build new Row-wrapped block
new_block = (
    ['    Row(verticalAlignment = Alignment.CenterVertically) {\n',
     '        Column(\n',
     '            modifier = Modifier.padding(end = 4.dp),\n',
     '            horizontalAlignment = Alignment.CenterHorizontally\n',
     '        ) {\n',
     '            Text("${currentIntervalSecs}s", color = Color(0xFFFF0000).copy(alpha = 0.8f),\n',
     '                fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)\n',
     '            androidx.compose.material3.Slider(\n',
     '                value = currentIntervalSecs.toFloat(),\n',
     '                onValueChange = { onIntervalChange(it.toInt()) },\n',
     '                valueRange = 2f..8f,\n',
     '                steps = 5,\n',
     '                modifier = Modifier\n',
     '                    .height(100.dp)\n',
     '                    .graphicsLayer { rotationZ = -90f }\n',
     '                    .width(100.dp)\n',
     '            )\n',
     '            Text("INT", color = Color(0xFFFF0000).copy(alpha = 0.6f),\n',
     '                fontSize = 9.sp, fontFamily = FontFamily.Monospace)\n',
     '        }\n'] +
    original_hudcard_lines +
    ['    } // end Row\n']
)

lines = lines[:hudcard_start] + new_block + lines[grouphud_close+1:]
print(f'Replaced GroupHud body. File now has {len(lines)} lines')

# STEP 2: Add avgChannelUtil, currentIntervalSecs, onIntervalChange params to GroupHud signature
# onToggleLeadOnly was at line 742 (0-indexed 741) - now shifted
for i, l in enumerate(lines):
    if 'onToggleLeadOnly: () -> Unit = {}' in l and i > 700:
        print(f'onToggleLeadOnly param now at line {i+1}')
        lines.insert(i+1, '    avgChannelUtil: Float = 0f,\n')
        lines.insert(i+2, '    currentIntervalSecs: Int = 5,\n')
        lines.insert(i+3, '    onIntervalChange: (Int) -> Unit = {},\n')
        print(f'Inserted 3 params after line {i+1}')
        break

# STEP 3: Add import for ChannelViewModel and graphicsLayer
for i, l in enumerate(lines):
    if 'import androidx.hilt.navigation.compose.hiltViewModel' in l:
        lines.insert(i+1, 'import com.geeksville.mesh.ui.sharing.ChannelViewModel\n')
        lines.insert(i+2, 'import androidx.compose.ui.graphics.graphicsLayer\n')
        print(f'Added imports after line {i+1}')
        break

# STEP 4: Add channelViewModel param to ConvoyScreen
for i, l in enumerate(lines):
    if 'viewModel: ConvoyViewModel = hiltViewModel()' in l and i < 100:
        lines.insert(i+1, '    channelViewModel: ChannelViewModel = hiltViewModel(),\n')
        print(f'Added channelViewModel param after line {i+1}')
        break

# STEP 5: Wire args in GroupHud call site
for i, l in enumerate(lines):
    if 'onToggleLeadOnly = { viewModel.toggleLeadOnly() }' in l:
        print(f'GroupHud call onToggleLeadOnly at line {i+1}')
        lines.insert(i+1, '                        avgChannelUtil = viewModel.avgChannelUtil.collectAsStateWithLifecycle().value,\n')
        lines.insert(i+2, '                        currentIntervalSecs = viewModel.currentIntervalSecs.collectAsStateWithLifecycle().value,\n')
        lines.insert(i+3, '                        onIntervalChange = { secs -> viewModel.setGpsInterval(secs, channelViewModel) },\n')
        print(f'Inserted 3 args after line {i+1}')
        break

open(path, 'w', encoding='utf-8').writelines(lines)
print('Done')
