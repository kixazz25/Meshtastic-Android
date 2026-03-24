import os

path = os.path.join('app', 'src', 'main', 'java', 'com', 'geeksville', 'mesh', 'convoy', 'ConvoyReconnectWaitScreen.kt')
lines = open(path, 'r', encoding='utf-8').readlines()

content = ''.join(lines)

# 1. Shorten both countdowns from 60 to 40
content = content.replace('for (i in 60 downTo 1)', 'for (i in 40 downTo 1)')
print('Countdown changed to 40:', content.count('for (i in 40 downTo 1)'), 'occurrences')

# 2. Fix autoReconnectTick timing: disconnect at i==15 (25s elapsed), reconnect at i==12 (28s elapsed)
# These are already correct from previous fix - verify
print('disconnect at i==15:', content.count('i == 15'))
print('reconnect at i==12:', content.count('i == 12'))

# 3. Stage 1: auto-proceed to writeChannel() when connected after countdown
old_s1_connected = '''        if (rawConnected) {
            phase = "CONNECTED"
            statusMsg = "\\u25cf Radio reconnected \\u2014 tap WRITE CHANNEL to continue"
            addLog("Stage 1: Connected \\u2713")
            return@LaunchedEffect
        }'''
new_s1_connected = '''        if (rawConnected) {
            phase = "CONNECTED"
            addLog("Stage 1: Connected \\u2713 \\u2014 auto-proceeding to write channel")
            writeChannel()
            return@LaunchedEffect
        }'''

# Also catch reconnect during BT_MANUAL - auto-proceed
old_s1_catch = '''            phase = "CONNECTED"
            statusMsg = "\\u25cf Radio reconnected \\u2014 tap WRITE CHANNEL to continue"
            Log.i("ConvoyReconnect", "Stage 1: Reconnected")'''
new_s1_catch = '''            phase = "CONNECTED"
            addLog("Stage 1: Reconnected \\u2713 \\u2014 auto-proceeding to write channel")
            writeChannel()'''

# 4. Stage 2: auto-proceed to onProceed() when connected after countdown
old_s2_connected = '''              if (rawConnected) {
                  phase = "CONNECTED_2"
                  statusMsg = "\\u25cf Radio reconnected \\u2014 tap PROCEED TO VERIFY"
                  addLog("Stage 2: Connected \\u2713")
                  return@launch
              }'''
new_s2_connected = '''              if (rawConnected) {
                  phase = "CONNECTED_2"
                  addLog("Stage 2: Connected \\u2713 \\u2014 auto-proceeding to verify")
                  onProceed()
                  return@launch
              }'''

# Catch reconnect during stage 2 BT_MANUAL - auto-proceed
old_s2_catch = '''            phase = "CONNECTED_2"
            statusMsg = "\\u25cf Radio reconnected \\u2014 tap PROCEED TO VERIFY"
            Log.i("ConvoyReconnect", "Stage 2: Reconnected")'''
new_s2_catch = '''            phase = "CONNECTED_2"
            addLog("Stage 2: Reconnected \\u2713 \\u2014 auto-proceeding to verify")
            onProceed()'''

for old, new, label in [
    (old_s1_connected, new_s1_connected, 'Stage 1 auto-proceed writeChannel'),
    (old_s1_catch, new_s1_catch, 'Stage 1 catch auto-proceed'),
    (old_s2_connected, new_s2_connected, 'Stage 2 auto-proceed onProceed'),
    (old_s2_catch, new_s2_catch, 'Stage 2 catch auto-proceed'),
]:
    if old in content:
        content = content.replace(old, new)
        print(f'Applied: {label}')
    else:
        print(f'NOT FOUND: {label}')

# 5. Update status messages to reflect auto mode
content = content.replace(
    '"\\u25cc Binary install reboot \\u2014 please wait... ${i}s"',
    '"\\u25cc Binary install reboot... auto-reconnect at 25s  [${i}s]"'
)
content = content.replace(
    '"\\u25cc Channel write reboot \\u2014 please wait... ${i}s"',
    '"\\u25cc Channel write reboot... auto-reconnect at 25s  [${i}s]"'
)

open(path, 'w', encoding='utf-8').write(content)
print('Done')
