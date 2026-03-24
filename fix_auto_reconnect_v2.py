import os

path = os.path.join('app', 'src', 'main', 'java', 'com', 'geeksville', 'mesh', 'convoy', 'ConvoyReconnectWaitScreen.kt')
lines = open(path, 'r', encoding='utf-8').readlines()

print(f'File has {len(lines)} lines')
print(f'Line 96:  {lines[95].rstrip()}')
print(f'Line 220: {lines[219].rstrip()}')

assert 'delay(1000)' in lines[95], f'Line 96 is not delay(1000): {lines[95]}'
assert 'delay(1000)' in lines[219], f'Line 220 is not delay(1000): {lines[219]}'

# Find @Composable fun ConvoyReconnectWaitScreen
composable_line = None
for i, l in enumerate(lines):
    if '@Composable' in l and i+1 < len(lines) and 'fun ConvoyReconnect' in lines[i+1]:
        composable_line = i
        break

print(f'@Composable at line {composable_line+1}')

reusable_fn = [
    '\n',
    '/**\n',
    ' * Automated radio disconnect/reconnect during reboot wait countdown.\n',
    ' * At i==15 disconnects. At i==12 reconnects. BT_MANUAL fallback remains as safety net.\n',
    ' */\n',
    'private suspend fun autoReconnectTick(\n',
    '    i: Int,\n',
    '    savedAddress: String,\n',
    '    uiViewModel: com.geeksville.mesh.model.UIViewModel,\n',
    '    addLog: (String) -> Unit\n',
    ') {\n',
    '    if (savedAddress.isEmpty()) return\n',
    '    if (i == 15) {\n',
    '        addLog("Auto-disconnect: cycling radio connection...")\n',
    '        uiViewModel.setDeviceAddress("n")\n',
    '    }\n',
    '    if (i == 12) {\n',
    '        addLog("Auto-reconnect: restoring radio connection...")\n',
    '        uiViewModel.setDeviceAddress(savedAddress)\n',
    '    }\n',
    '}\n',
    '\n',
]

lines = lines[:composable_line] + reusable_fn + lines[composable_line:]
offset = len(reusable_fn)

s1_delay = 95 + offset
s2_delay = 219 + offset
s1_addlog = 90 + offset

print(f'Stage 1 delay now at line {s1_delay+1}: {lines[s1_delay].rstrip()}')
print(f'Stage 2 delay now at line {s2_delay+1}: {lines[s2_delay].rstrip()}')
assert 'delay(1000)' in lines[s1_delay]
assert 'delay(1000)' in lines[s2_delay]

# Insert address capture after s1_addlog
lines = lines[:s1_addlog+1] + [
    '        val savedDeviceAddress = uiViewModel.getDeviceAddress() ?: ""\n',
] + lines[s1_addlog+1:]
s1_delay += 1
s2_delay += 1

# Insert calls - Stage 2 first (higher line, won't affect Stage 1)
lines = lines[:s2_delay+1] + [
    '                autoReconnectTick(i, savedDeviceAddress, uiViewModel, ::addLog)\n',
] + lines[s2_delay+1:]

# Stage 1
lines = lines[:s1_delay+1] + [
    '            autoReconnectTick(i, savedDeviceAddress, uiViewModel, ::addLog)\n',
] + lines[s1_delay+1:]

open(path, 'w', encoding='utf-8').writelines(lines)
print(f'Done. File has {len(lines)} lines.')
