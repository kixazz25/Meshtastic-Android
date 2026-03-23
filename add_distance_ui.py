lines = open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt', 'r', encoding='utf-8').readlines()

print(f'Line 465: {lines[464].rstrip()}')
print(f'Line 466: {lines[465].rstrip()}')
print(f'Line 467: {lines[466].rstrip()}')

new_lines = [
    '\n',
    '          // ── Distance odometer -- bottom right, only when recording ─────\n',
    '          val distanceMiles by viewModel.distanceMiles.collectAsStateWithLifecycle()\n',
    '          if (recordingState != RecordingState.IDLE) {\n',
    '              Column(\n',
    '                  modifier = Modifier\n',
    '                      .align(Alignment.BottomEnd)\n',
    '                      .padding(end = 16.dp, bottom = 64.dp),\n',
    '                  horizontalAlignment = androidx.compose.ui.Alignment.End\n',
    '              ) {\n',
    '                  Text("Distance", color = Color(0xFFFF0000).copy(alpha = 0.75f), fontSize = 11.sp,\n',
    '                      fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,\n',
    '                      letterSpacing = 1.sp)\n',
    '                  Row(verticalAlignment = Alignment.Bottom) {\n',
    '                      Text("%.2f".format(distanceMiles), color = Color(0xFFFF0000).copy(alpha = 0.75f),\n',
    '                          fontSize = 48.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,\n',
    '                          lineHeight = 48.sp)\n',
    '                      Text(" mi", color = Color(0xFFFF0000).copy(alpha = 0.75f), fontSize = 16.sp,\n',
    '                          fontFamily = FontFamily.Monospace,\n',
    '                          modifier = Modifier.padding(bottom = 6.dp))\n',
    '                  }\n',
    '              }\n',
    '          }\n',
    '\n',
]

# Insert after line 466 (0-indexed 465) - the blank line after NODE mode comment
lines = lines[:466] + new_lines + lines[466:]
open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt', 'w', encoding='utf-8').writelines(lines)
print('Done')
