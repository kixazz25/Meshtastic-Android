import os

path = os.path.join('app', 'src', 'main', 'java', 'com', 'geeksville', 'mesh', 'convoy', 'ConvoyReconnectWaitScreen.kt')
lines = open(path, 'r', encoding='utf-8').readlines()

# Find and print key lines to verify before changing
for i, l in enumerate(lines):
    if 'tap WRITE CHANNEL to continue' in l:
        print(f'Line {i+1}: {l.rstrip()}')
    if 'tap PROCEED TO VERIFY' in l:
        print(f'Line {i+1}: {l.rstrip()}')
    if '60s wait complete' in l:
        print(f'Line {i+1}: {l.rstrip()}')
    if 'Stage 2: 60s wait complete' in l:
        print(f'Line {i+1}: {l.rstrip()}')
    if 'Stage 1: Connected' in l and 'after' not in l:
        print(f'Line {i+1}: {l.rstrip()}')
    if 'Stage 2: Connected' in l and 'after' not in l:
        print(f'Line {i+1}: {l.rstrip()}')
    if 'Stage 1: Reconnected' in l:
        print(f'Line {i+1}: {l.rstrip()}')
    if 'Stage 2: Reconnected' in l:
        print(f'Line {i+1}: {l.rstrip()}')
