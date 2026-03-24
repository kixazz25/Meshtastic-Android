import os

path = os.path.join('app', 'src', 'main', 'java', 'com', 'geeksville', 'mesh', 'convoy', 'ConvoyReconnectWaitScreen.kt')
lines = open(path, 'r', encoding='utf-8').readlines()

changes = {
    # Stage 1: replace "tap WRITE CHANNEL" statusMsg + addLog + return with auto-proceed
    # Lines 128-131 (0-indexed 127-130)
    129: '            statusMsg = "\\u25cc Auto-proceeding to write channel..."\n',
    130: '            addLog("Stage 1: Connected \\u2713 -- auto-proceeding")\n',
    # Line 131 return@LaunchedEffect stays

    # Stage 1 catch reconnect (line 161-162)
    161: '            statusMsg = "\\u25cc Auto-proceeding to write channel..."\n',
    162: '            addLog("Stage 1: Reconnected \\u2713 -- auto-proceeding")\n',

    # Stage 2: replace "tap PROCEED TO VERIFY" statusMsg + addLog + return with auto-proceed
    # Lines 254-256 (0-indexed 253-255)
    254: '                statusMsg = "\\u25cc Auto-proceeding to verify..."\n',
    255: '                addLog("Stage 2: Connected \\u2713 -- auto-proceeding")\n',

    # Stage 2 catch reconnect (lines 171-172)
    171: '            statusMsg = "\\u25cc Auto-proceeding to verify..."\n',
    172: '            addLog("Stage 2: Reconnected \\u2713 -- auto-proceeding")\n',

    # Update "60s wait complete" log messages
    125: '        addLog("Stage 1: 40s wait complete")\n',
    250: '            addLog("Stage 2: 40s wait complete")\n',
}

for line_num, new_content in changes.items():
    idx = line_num - 1
    print(f'Line {line_num} WAS: {lines[idx].rstrip()}')
    lines[idx] = new_content
    print(f'Line {line_num} NOW: {lines[idx].rstrip()}')

# Now add auto-proceed calls after the statusMsg/addLog pairs
# Stage 1 connected block: after line 130 insert writeChannel()
# Stage 1 catch: after line 162 insert writeChannel() 
# Stage 2 connected block: after line 255 insert onProceed()
# Stage 2 catch: after line 172 insert onProceed()

# Work bottom to top
insert_points = [
    (255, '                onProceed()\n'),  # Stage 2 connected auto-proceed
    (172, '            onProceed()\n'),       # Stage 2 catch auto-proceed
    (162, '            writeChannel()\n'),    # Stage 1 catch auto-proceed
    (130, '            writeChannel()\n'),    # Stage 1 connected auto-proceed
]

for line_num, new_line in insert_points:
    lines.insert(line_num, new_line)
    print(f'Inserted after line {line_num}: {new_line.rstrip()}')

open(path, 'w', encoding='utf-8').writelines(lines)
print(f'Done. File has {len(lines)} lines.')
