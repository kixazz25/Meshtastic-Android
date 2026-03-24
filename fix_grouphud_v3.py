import os

path = os.path.join('app', 'src', 'main', 'java', 'com', 'geeksville', 'mesh', 'convoy', 'ConvoyScreen.kt')
lines = open(path, 'r', encoding='utf-8').readlines()

assert 'fun GroupHud(' in lines[733], f'Expected GroupHud at 734, got: {lines[733]}'
assert lines[788].strip() == '}', f'Expected }} at 789, got: {lines[788]}'

new_grouphud = '''\
@Composable
fun GroupHud(
    state: ConvoyEngine.ConvoyState,
    onModeChange: (HudMode) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    trackActive: Boolean = false,
    trackLeadOnly: Boolean = true,
    onStartTrack: () -> Unit = {},
    onStopTrack: () -> Unit = {},
    onToggleLeadOnly: () -> Unit = {},
    avgChannelUtil: Float = 0f,
    currentIntervalSecs: Int = 5,
    onIntervalChange: (Int) -> Unit = {}
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier.padding(end = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("${currentIntervalSecs}s", color = Color(0xFFFF0000).copy(alpha = 0.8f),
                fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            androidx.compose.material3.Slider(
                value = currentIntervalSecs.toFloat(),
                onValueChange = { onIntervalChange(it.toInt()) },
                valueRange = 2f..8f,
                steps = 5,
                modifier = Modifier
                    .height(100.dp)
                    .graphicsLayer { rotationZ = -90f }
                    .width(100.dp)
            )
            Text("INT", color = Color(0xFFFF0000).copy(alpha = 0.6f),
                fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
        HudCard {
            Text("GROUP", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 16.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp, modifier = Modifier.padding(bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                HudStat("Carts", "${state.nodes.size}")
                HudStat("Active", "${state.activeCount}")
                HudStat("Lost", "${state.lostCount}")
            }
            Spacer(Modifier.height(4.dp))
            val chColor = when {
                avgChannelUtil > 40f -> Color(0xFFFF4444)
                avgChannelUtil > 25f -> Color(0xFFFFAA00)
                else -> Color(0xFF00CC44)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                HudStat("CH%", "%.0f%%".format(avgChannelUtil), chColor)
            }
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Column {
                    Text("SPAN", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("%.1f".format(state.span_miles), color = Color(0xFFFF0000).copy(alpha = 1f),
                            fontSize = 48.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            lineHeight = 48.sp)
                        Text(" mi", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(bottom = 6.dp))
                    }
                }
                Column(modifier = Modifier.padding(bottom = 4.dp)) {
                    Text("\\u25b2 Lead", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    Text(state.lead?.callsign ?: "--", color = Color(0xFF1CF0A0),
                        fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                Column(modifier = Modifier.padding(bottom = 4.dp)) {
                    Text("\\u25bd Tail", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    Text(state.tail?.callsign ?: "--", color = Color(0xFFFF8C42),
                        fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

'''

new_lines = [l + '\n' for l in new_grouphud.split('\n')]
while new_lines and new_lines[-1].strip() == '':
    new_lines.pop()
new_lines.append('\n')

# Replace lines 733-788 (0-indexed), keeping @Composable on 732
lines = lines[:732] + new_lines + lines[789:]
print(f'GroupHud replaced. File now has {len(lines)} lines.')

# Add graphicsLayer import
for i, l in enumerate(lines):
    if 'import androidx.hilt.navigation.compose.hiltViewModel' in l:
        lines.insert(i+1, 'import androidx.compose.ui.graphics.graphicsLayer\n')
        lines.insert(i+2, 'import com.geeksville.mesh.ui.sharing.ChannelViewModel\n')
        print(f'Added imports after line {i+1}')
        break

# Inject channelViewModel inside ConvoyScreen body (after val convoyState line)
for i, l in enumerate(lines):
    if 'val convoyState by viewModel.convoyState.collectAsStateWithLifecycle()' in l:
        lines.insert(i, '    val channelViewModel: ChannelViewModel = hiltViewModel()\n')
        print(f'Injected channelViewModel at line {i+1}')
        break

# Wire args in GroupHud call
for i, l in enumerate(lines):
    if 'onToggleLeadOnly = { viewModel.toggleLeadOnly() }' in l:
        print(f'GroupHud call at line {i+1}')
        lines.insert(i+1, '                        avgChannelUtil = viewModel.avgChannelUtil.collectAsStateWithLifecycle().value,\n')
        lines.insert(i+2, '                        currentIntervalSecs = viewModel.currentIntervalSecs.collectAsStateWithLifecycle().value,\n')
        lines.insert(i+3, '                        onIntervalChange = { secs -> viewModel.setGpsInterval(secs, channelViewModel) },\n')
        print(f'Wired args after line {i+1}')
        break

open(path, 'w', encoding='utf-8').writelines(lines)
print('Done.')
