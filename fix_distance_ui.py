content = open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt', 'r', encoding='utf-8').read()

old = '''          // NODE mode RETURN is inside NodeDetailHud panel

          // ── Task 5.3: Show Lead Track toggle + Task 5.4: Route Recorder ──
          Column('''

new = '''          // NODE mode RETURN is inside NodeDetailHud panel

          // ── Distance odometer — bottom right, only when recording ─────────
          val distanceMiles by viewModel.distanceMiles.collectAsStateWithLifecycle()
          if (recordingState != RecordingState.IDLE) {
              Column(
                  modifier = Modifier
                      .align(Alignment.BottomEnd)
                      .padding(end = 16.dp, bottom = 64.dp),
                  horizontalAlignment = androidx.compose.ui.Alignment.End
              ) {
                  Text("Distance", color = Color(0xFFFF0000).copy(alpha = 0.75f), fontSize = 11.sp,
                      fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                      letterSpacing = 1.sp)
                  Row(verticalAlignment = Alignment.Bottom) {
                      Text("%.2f".format(distanceMiles), color = Color(0xFFFF0000).copy(alpha = 0.75f),
                          fontSize = 48.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                          lineHeight = 48.sp)
                      Text(" mi", color = Color(0xFFFF0000).copy(alpha = 0.75f), fontSize = 16.sp,
                          fontFamily = FontFamily.Monospace,
                          modifier = Modifier.padding(bottom = 6.dp))
                  }
              }
          }

          // ── Task 5.3: Show Lead Track toggle + Task 5.4: Route Recorder ──
          Column('''

print('Found:', old in content)
result = content.replace(old, new)
open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt', 'w', encoding='utf-8').write(result)
print('Done')
