import os

path = os.path.join('app', 'src', 'main', 'java', 'com', 'geeksville', 'mesh', 'convoy', 'ConvoyScreen.kt')
lines = open(path, 'r', encoding='utf-8').readlines()

# Step 1: Add onIntervalChange callback to GroupHud signature
for i, l in enumerate(lines):
    if 'avgChannelUtil: Float = 0f,' in l:
        print(f'avgChannelUtil param at line {i+1}')
        lines.insert(i+1, '    currentIntervalSecs: Int = 5,\n')
        lines.insert(i+2, '    onIntervalChange: (Int) -> Unit = {},\n')
        print(f'Inserted interval params after line {i+1}')
        break

# Step 2: Wrap HudCard content in a Row with vertical slider on left
# Find HudCard { in GroupHud
for i, l in enumerate(lines):
    if 'fun GroupHud(' in l:
        grouphud_start = i
        break

for i in range(grouphud_start, grouphud_start + 30):
    if 'HudCard {' in lines[i]:
        print(f'HudCard at line {i+1}')
        hudcard_line = i
        break

# Replace HudCard { with Row containing slider + HudCard
old_hudcard = '    HudCard {\n'
# Find closing } of HudCard (the one that closes GroupHud's HudCard)
# We'll wrap by inserting Row before HudCard and adding slider column

# Insert Row wrapper and slider BEFORE HudCard
slider_prefix = [
    '    Row(verticalAlignment = Alignment.CenterVertically) {\n',
    '        // Vertical interval slider — left side\n',
    '        Column(\n',
    '            modifier = Modifier.padding(end = 8.dp),\n',
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
    '                    .height(120.dp)\n',
    '                    .graphicsLayer { rotationZ = -90f }\n',
    '                    .width(120.dp)\n',
    '            )\n',
    '            Text("INT", color = Color(0xFFFF0000).copy(alpha = 0.6f),\n',
    '                fontSize = 9.sp, fontFamily = FontFamily.Monospace)\n',
    '        }\n',
    '        // HUD content\n',
    '        HudCard {\n',
]

lines = lines[:hudcard_line] + slider_prefix + lines[hudcard_line+1:]
print(f'Inserted slider prefix at line {hudcard_line+1}')

# Now find the closing brace of HudCard in GroupHud and add Row closing brace
# Re-read to find the } that closes HudCard just before fun GroupHud ends
for i, l in enumerate(lines):
    if 'fun GroupHud(' in l:
        grouphud_start2 = i
        break

# Find } } pattern at end of GroupHud (HudCard close then fun close)
for i in range(grouphud_start2 + 20, grouphud_start2 + 120):
    if lines[i].strip() == '}' and lines[i+1].strip() == '}' and lines[i+2].strip() == '':
        print(f'HudCard close at line {i+1}, fun close at line {i+2}')
        # Insert } to close the Row after HudCard close
        lines.insert(i+1, '    } // end Row\n')
        print(f'Inserted Row close at line {i+2}')
        break

open(path, 'w', encoding='utf-8').writelines(lines)
print('Slider done')
