import os

path = os.path.join('app', 'src', 'main', 'java', 'com', 'geeksville', 'mesh', 'convoy', 'ConvoyScreen.kt')
lines = open(path, 'r', encoding='utf-8').readlines()

grouphud_start = None
grouphud_end = None
for i, l in enumerate(lines):
    if '@Composable' in l and i+1 < len(lines) and 'fun GroupHud(' in lines[i+1]:
        grouphud_start = i
    if grouphud_start and i > grouphud_start + 20:
        if l.strip() == '}' and i+1 < len(lines) and lines[i+1].strip() == '':
            grouphud_end = i
            break

print(f'GroupHud: lines {grouphud_start+1} to {grouphud_end+1}')

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
    val chColor = when {
        avgChannelUtil > 40f -> Color(0xFFFF4444)
        avgChannelUtil > 25f -> Color(0xFFFFAA00)
        else                 -> Color(0xFF00CC44)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Vertical interval slider — flush against HudCard
        Column(
            modifier = Modifier.padding(0.dp),
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
                    .height(80.dp)
                    .graphicsLayer { rotationZ = -90f }
                    .width(80.dp)
            )
            Text("INT", color = Color(0xFFFF0000).copy(alpha = 0.6f),
                fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
        HudCard {
            Text("GROUP", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 16.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp, modifier = Modifier.padding(bottom = 4.dp))
            // Row 1: SPAN big + CH% color block
            Row(verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("SPAN", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp, modifier = Modifier.padding(end = 4.dp, bottom = 6.dp))
                    Text("%.1f".format(state.span_miles),
                        color = Color(0xFFFF0000).copy(alpha = 1f),
                        fontSize = 48.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, lineHeight = 48.sp)
                    Text(" mi", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 6.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 6.dp)) {
                    Text("CH%", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp)
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(modifier = Modifier.size(8.dp).background(chColor,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                        Text("%.0f%%".format(avgChannelUtil), color = chColor,
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            // Row 2: Carts · Active · Lost · Lead · Tail
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Bottom) {
                HudStat("Carts",       "${state.nodes.size}")
                HudStat("Active",      "${state.activeCount}")
                HudStat("Lost",        "${state.lostCount}")
                HudStat("\u25b2 Lead", state.lead?.callsign ?: "--", Color(0xFF1CF0A0))
                HudStat("\u25bd Tail", state.tail?.callsign ?: "--", Color(0xFFFF8C42))
            }
        }
    }
}
'''

new_lines = [l + '\n' for l in new_grouphud.split('\n')]
while new_lines and new_lines[-1].strip() == '':
    new_lines.pop()
new_lines.append('\n')

lines = lines[:grouphud_start] + new_lines + lines[grouphud_end+1:]
print(f'Done. File now has {len(lines)} lines.')
open(path, 'w', encoding='utf-8').writelines(lines)
