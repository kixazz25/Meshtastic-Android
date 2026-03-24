import sys, os

path = os.path.join('app', 'src', 'main', 'java', 'com', 'geeksville', 'mesh', 'convoy', 'ConvoyReconnectWaitScreen.kt')
lines = open(path, 'r', encoding='utf-8').readlines()

for i, l in enumerate(lines):
    if 'Stage 1 sequence' in l:
        print(f'Stage 1 start: line {i+1}')
    if 'stage = 1' in l:
        print(f'stage = 1: line {i+1}')
    if 'Waiting 60s for binary install reboot' in l:
        print(f'Stage 1 log: line {i+1}')
    if 'for (i in 60 downTo 1)' in l:
        print(f'for loop 60: line {i+1}')
    if 'Binary install reboot' in l:
        print(f'Stage 1 statusMsg: line {i+1}')
    if 'Stage 2: Waiting 60s for channel write reboot' in l:
        print(f'Stage 2 log: line {i+1}')
    if 'Channel write reboot' in l:
        print(f'Stage 2 statusMsg: line {i+1}')
    if 'Not connected' in l:
        print(f'BT_MANUAL: line {i+1}: {l.rstrip()}')
