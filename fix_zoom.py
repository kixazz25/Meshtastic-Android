content = open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt', 'r', encoding='utf-8').read()

old = '    LaunchedEffect(hudMode, convoyState, selectedNode) {'
new = '    LaunchedEffect(hudMode, selectedNode) {'

print('Found:', old in content)
result = content.replace(old, new)
open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt', 'w', encoding='utf-8').write(result)
print('Done')
