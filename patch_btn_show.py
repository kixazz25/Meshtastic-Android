kt_path = 'app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt'
lines = open(kt_path, encoding='utf-8').readlines()

# Lines 844-868 (0-indexed: 843-867) is the clickable block to replace
# Find exact start by looking for the clickable before SHOW DOWNLOADED
start = None
for i, line in enumerate(lines):
    if 'SHOW DOWNLOADED' in line:
        # Walk back to find the modifier = Modifier.fillMaxWidth().clickable line
        for j in range(i, max(i-30, 0), -1):
            if 'fillMaxWidth().clickable' in lines[j]:
                start = j
                break
        break

if start is None:
    print('ERROR: could not find start')
else:
    # Find the closing },
    end = None
    depth = 0
    for i in range(start, len(lines)):
        depth += lines[i].count('{') - lines[i].count('}')
        if depth <= 0 and '},' in lines[i]:
            end = i
            break
    
    print(f'Replacing lines {start+1} to {end+1}')
    indent = '                            '
    new_lines = [
        f'{indent}modifier = Modifier.fillMaxWidth().clickable {{\n',
        f'{indent}    webViewRef.value?.evaluateJavascript("getMapBounds()", null)\n',
        f'{indent}}},\n'
    ]
    lines[start:end+1] = new_lines
    open(kt_path, 'w', encoding='utf-8').writelines(lines)
    print('Fixed OK')
