kt_path = 'app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt'
lines = open(kt_path, encoding='utf-8').readlines()

# Find the clickable line for SHOW DOWNLOADED button
# It's the second occurrence of fillMaxWidth().clickable
start = None
count = 0
for i, line in enumerate(lines):
    if 'fillMaxWidth().clickable {' in line:
        count += 1
        if count == 2:  # second clickable is the SHOW DOWNLOADED button
            start = i
            break

if start is None:
    print('ERROR: could not find second clickable block')
else:
    # Find the closing }, of this clickable lambda
    end = None
    for i in range(start + 1, len(lines)):
        stripped = lines[i].strip()
        if stripped == '},':
            end = i
            break
    
    if end is None:
        print('ERROR: could not find end of clickable block')
    else:
        print(f'Replacing lines {start+1} to {end+1}')
        # Replace entire block with clean version
        indent = '                            '
        new_lines = [
            f'{indent}modifier = Modifier.fillMaxWidth().clickable {{\n',
            f'{indent}    webViewRef.value?.evaluateJavascript("getMapBounds()", null)\n',
            f'{indent}}},\n'
        ]
        lines[start:end+1] = new_lines
        open(kt_path, 'w', encoding='utf-8').writelines(lines)
        print('Fixed OK')
