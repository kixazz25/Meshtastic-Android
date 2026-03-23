content = open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt', 'r', encoding='utf-8').read()

old = '    LaunchedEffect(hudMode, selectedNode) {'
new = '    LaunchedEffect(hudMode, selectedNode, mapReady) {'

print('Found:', old in content)
result = content.replace(old, new)
open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt', 'w', encoding='utf-8').write(result)
print('Done')
