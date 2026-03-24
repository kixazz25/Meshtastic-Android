import os

path = os.path.join('app', 'src', 'main', 'java', 'com', 'geeksville', 'mesh', 'convoy', 'ConvoyReconnectWaitScreen.kt')
lines = open(path, 'r', encoding='utf-8').readlines()

print(f'Line 183: {lines[182].rstrip()}')
print(f'Line 284: {lines[283].rstrip()}')
print(f'Line 110: {lines[109].rstrip()}')

# Extract writeChannel block (lines 183-284, 0-indexed 182-283)
write_channel_block = lines[182:284]

# Remove it from current position
lines = lines[:182] + lines[284:]

# Find new position of "Stage 1 sequence" comment (was line 110, now shifted back by 102 lines)
for i, l in enumerate(lines):
    if '// Stage 1 sequence' in l:
        insert_pos = i
        print(f'Stage 1 sequence now at line {i+1}')
        break

# Insert writeChannel block before Stage 1 sequence
lines = lines[:insert_pos] + write_channel_block + ['\n'] + lines[insert_pos:]

open(path, 'w', encoding='utf-8').writelines(lines)
print(f'Done. File has {len(lines)} lines.')
