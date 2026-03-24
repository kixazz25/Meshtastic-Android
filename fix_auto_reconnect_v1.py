import os, sys

path = os.path.join('app', 'src', 'main', 'java', 'com', 'geeksville', 'mesh', 'convoy', 'ConvoyReconnectWaitScreen.kt')
lines = open(path, 'r', encoding='utf-8').readlines()

# Find exact insertion points by content
stage1_for_line = None   # line 93 - 'for (i in 60 downTo 1)' first occurrence
stage1_statusmsg = None  # line 95 - 'Binary install reboot'
stage1_bt_start = None   # line 109 - BT_MANUAL comment
stage1_bt_end = None     # end of BT_MANUAL block for stage 1

stage2_for_line = None   # line 217 - second 'for (i in 60 downTo 1)'
stage2_bt_start = None   # line 233
stage2_bt_end = None     # end of BT_MANUAL block for stage 2

first_for = True
first_bt = True

for i, l in enumerate(lines):
    if 'for (i in 60 downTo 1)' in l:
        if first_for:
            stage1_for_line = i
            first_for = False
        else:
            stage2_for_line = i
    if '// Not connected' in l:
        if first_bt:
            stage1_bt_start = i
            first_bt = False
        else:
            stage2_bt_start = i

print(f'Stage1 for loop: {stage1_for_line+1}')
print(f'Stage1 BT block start: {stage1_bt_start+1}')
print(f'Stage2 for loop: {stage2_for_line+1}')
print(f'Stage2 BT block start: {stage2_bt_start+1}')

# Find end of stage1 BT block - look for the closing of the outer if (!rawConnected) block
# Stage 1 BT block ends at the closing brace before Stage 2 begins
# It spans from stage1_bt_start to just before stage = 2 assignment
stage1_bt_end = None
stage2_bt_end = None

for i in range(stage1_bt_start, stage2_for_line):
    if 'stage1GatePassed' in lines[i] or 'addLog("Stage 1: 60s wait complete")' in lines[i]:
        pass
    if i > stage1_bt_start and 'addLog("Stage 1: FAILED")' in lines[i]:
        # Find the closing brace after this
        for j in range(i, i+5):
            if lines[j].strip() == '}':
                stage1_bt_end = j
                break
        break

for i in range(stage2_bt_start, len(lines)):
    if 'addLog("Stage 2: FAILED")' in lines[i]:
        for j in range(i, i+5):
            if lines[j].strip() == '}':
                stage2_bt_end = j
                break
        break

print(f'Stage1 BT block end: {stage1_bt_end+1 if stage1_bt_end else "NOT FOUND"}')
print(f'Stage2 BT block end: {stage2_bt_end+1 if stage2_bt_end else "NOT FOUND"}')

# Now add address capture before Stage 1 for loop
# Insert: val savedDeviceAddress = uiViewModel.getDeviceAddress() ?: ""
# before stage1_for_line

# Add auto-disconnect at i==15 and auto-reconnect at i==12 inside Stage 1 for loop
# The for loop body is:
#   countdown = i
#   statusMsg = "..."
#   delay(1000)
# We insert after the delay:

stage1_statusmsg_line = stage1_for_line + 2  # the statusMsg line inside for loop
stage1_delay_line = stage1_for_line + 3      # delay(1000) line

stage2_statusmsg_line = stage2_for_line + 2
stage2_delay_line = stage2_for_line + 3

print(f'Stage1 delay line: {stage1_delay_line+1}: {lines[stage1_delay_line].rstrip()}')
print(f'Stage2 delay line: {stage2_delay_line+1}: {lines[stage2_delay_line].rstrip()}')

# Build the modifications
# 1. Insert address capture before stage1_for_line (after the addLog line for "Stage 1: Waiting 60s")
# Find the addLog line for stage 1
stage1_addlog = stage1_for_line - 2  # "addLog Stage 1: Waiting 60s"
for i in range(stage1_for_line-5, stage1_for_line):
    if 'Waiting 60s for binary install reboot' in lines[i]:
        stage1_addlog = i
        break

print(f'Stage1 addlog: {stage1_addlog+1}: {lines[stage1_addlog].rstrip()}')

indent = '        '  # 8 spaces

# Insert address capture after stage1_addlog
address_capture = [
    f'\n',
    f'{indent}// Capture device address for auto-reconnect\n',
    f'{indent}val savedDeviceAddress = uiViewModel.getDeviceAddress() ?: ""\n',
    f'\n',
]

# Auto disconnect/reconnect lines for stage 1 for loop body (after delay)
auto_reconnect_s1 = [
    f'{indent}    if (i == 15 && savedDeviceAddress.isNotEmpty()) {{\n',
    f'{indent}        addLog("Stage 1: Auto-disconnecting radio...")\n',
    f'{indent}        uiViewModel.setDeviceAddress("n")\n',
    f'{indent}    }}\n',
    f'{indent}    if (i == 12 && savedDeviceAddress.isNotEmpty()) {{\n',
    f'{indent}        addLog("Stage 1: Auto-reconnecting radio...")\n',
    f'{indent}        uiViewModel.setDeviceAddress(savedDeviceAddress)\n',
    f'{indent}    }}\n',
]

# Auto disconnect/reconnect for stage 2 (same pattern)
auto_reconnect_s2 = [
    f'{indent}    if (i == 15 && savedDeviceAddress.isNotEmpty()) {{\n',
    f'{indent}        addLog("Stage 2: Auto-disconnecting radio...")\n',
    f'{indent}        uiViewModel.setDeviceAddress("n")\n',
    f'{indent}    }}\n',
    f'{indent}    if (i == 12 && savedDeviceAddress.isNotEmpty()) {{\n',
    f'{indent}        addLog("Stage 2: Auto-reconnecting radio...")\n',
    f'{indent}        uiViewModel.setDeviceAddress(savedDeviceAddress)\n',
    f'{indent}    }}\n',
]

# Apply changes - work from bottom to top to preserve line numbers
# Step 1: Insert auto_reconnect_s2 after stage2 delay line
insert_pos_s2 = stage2_delay_line + 1
lines = lines[:insert_pos_s2] + auto_reconnect_s2 + lines[insert_pos_s2:]
print(f'Inserted Stage 2 auto-reconnect after line {insert_pos_s2}')

# Recalculate stage1 positions (not affected - stage2 is after)
# Step 2: Insert auto_reconnect_s1 after stage1 delay line
insert_pos_s1 = stage1_delay_line + 1
lines = lines[:insert_pos_s1] + auto_reconnect_s1 + lines[insert_pos_s1:]
print(f'Inserted Stage 1 auto-reconnect after line {insert_pos_s1}')

# Step 3: Insert address capture after stage1_addlog
insert_pos_addr = stage1_addlog + 1
lines = lines[:insert_pos_addr] + address_capture + lines[insert_pos_addr:]
print(f'Inserted address capture after line {insert_pos_addr}')

open(path, 'w', encoding='utf-8').writelines(lines)
print(f'Done. File has {len(lines)} lines.')
