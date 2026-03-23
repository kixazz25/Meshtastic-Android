content = open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt', 'r', encoding='utf-8').read()

old = '        Text("My Cart  \u2605 HOTEL-10", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 16.sp,'
new = '        Text("My Cart  \u2605 ${myCart?.callsign ?: myCartId.takeLast(8)}", color = Color(0xFFFF0000).copy(alpha = 1f), fontSize = 16.sp,'

print('Found:', old in content)
result = content.replace(old, new)
open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt', 'w', encoding='utf-8').write(result)
print('Done')
