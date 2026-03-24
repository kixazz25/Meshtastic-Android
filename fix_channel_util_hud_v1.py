import os

path = os.path.join('app', 'src', 'main', 'java', 'com', 'geeksville', 'mesh', 'convoy', 'ConvoyScreen.kt')
lines = open(path, 'r', encoding='utf-8').readlines()

# Step 1: Add avgChannelUtil parameter to GroupHud function signature
for i, l in enumerate(lines):
    if 'fun GroupHud(' in l:
        print(f'GroupHud signature at line {i+1}')
        # Find onToggleLeadOnly line and insert after it
        for j in range(i, i+15):
            if 'onToggleLeadOnly' in lines[j] and '()' in lines[j]:
                print(f'onToggleLeadOnly at line {j+1}: {lines[j].rstrip()}')
                lines.insert(j+1, '    avgChannelUtil: Float = 0f,\n')
                print(f'Inserted avgChannelUtil param after line {j+1}')
                break
        break

# Step 2: Add CH% row to GroupHud after Row 1 (Carts/Active/Lost)
for i, l in enumerate(lines):
    if 'HudStat("Lost", "${state.lostCount}")' in l:
        print(f'Lost HudStat at line {i+1}')
        # Insert CH% row after the closing ) of this Row
        for j in range(i, i+5):
            if lines[j].strip() == '}':
                print(f'Row closing brace at line {j+1}')
                ch_row = [
                    '          Spacer(Modifier.height(4.dp))\n',
                    '          // Row 1b: Channel utilization\n',
                    '          val chColor = when {\n',
                    '              avgChannelUtil > 40f -> Color(0xFFFF4444)\n',
                    '              avgChannelUtil > 25f -> Color(0xFFFFAA00)\n',
                    '              else -> Color(0xFF00AA00)\n',
                    '          }\n',
                    '          Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {\n',
                    '              Column {\n',
                    '                  Text("CH%", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 11.sp,\n',
                    '                      fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,\n',
                    '                      letterSpacing = 1.sp)\n',
                    '                  Text("%.0f%%".format(avgChannelUtil), color = chColor,\n',
                    '                      fontSize = 20.sp, fontFamily = FontFamily.Monospace,\n',
                    '                      fontWeight = FontWeight.Bold)\n',
                    '              }\n',
                    '          }\n',
                ]
                lines = lines[:j+1] + ch_row + lines[j+1:]
                print(f'Inserted CH% row after line {j+1}')
                break
        break

# Step 3: Wire avgChannelUtil in ConvoyScreen where GroupHud is called
for i, l in enumerate(lines):
    if 'HudMode.GROUP ->' in l:
        print(f'HudMode.GROUP at line {i+1}')
        for j in range(i, i+20):
            if 'GroupHud(' in lines[j]:
                print(f'GroupHud call at line {j+1}')
                # Find onToggleLeadOnly call and insert after it
                for k in range(j, j+15):
                    if 'onToggleLeadOnly' in lines[k] and 'viewModel' in lines[k]:
                        print(f'onToggleLeadOnly call at line {k+1}: {lines[k].rstrip()}')
                        lines.insert(k+1, '                        avgChannelUtil = viewModel.avgChannelUtil.collectAsStateWithLifecycle().value,\n')
                        print(f'Inserted avgChannelUtil arg after line {k+1}')
                        break
                break
        break

open(path, 'w', encoding='utf-8').writelines(lines)
print('ConvoyScreen done')
