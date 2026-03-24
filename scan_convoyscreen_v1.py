import os

path = os.path.join('app', 'src', 'main', 'java', 'com', 'geeksville', 'mesh', 'convoy', 'ConvoyScreen.kt')
lines = open(path, 'r', encoding='utf-8').readlines()
print(f'Total lines: {len(lines)}')

for i, l in enumerate(lines):
    if 'fun ConvoyScreen(' in l:
        print(f'ConvoyScreen fun: line {i+1}')
    if 'viewModel: ConvoyViewModel = hiltViewModel()' in l:
        print(f'viewModel param: line {i+1}')
    if 'import androidx.hilt.navigation.compose.hiltViewModel' in l:
        print(f'hiltViewModel import: line {i+1}')
    if 'fun GroupHud(' in l:
        print(f'GroupHud fun: line {i+1}')
    if 'onToggleLeadOnly: () -> Unit = {}' in l:
        print(f'onToggleLeadOnly param: line {i+1}')
    if 'HudCard {' in l and i > 730:
        print(f'GroupHud HudCard: line {i+1}')
    if 'HudStat("Lost"' in l:
        print(f'Lost HudStat: line {i+1}')
    if 'HudMode.GROUP ->' in l:
        print(f'HudMode.GROUP: line {i+1}')
    if 'onToggleLeadOnly = { viewModel.toggleLeadOnly() }' in l:
        print(f'onToggleLeadOnly call: line {i+1}')
    if lines[i].strip() == '}' and i > 730 and i < 810:
        if 'GroupHud' in ''.join(lines[max(0,i-60):i]):
            print(f'Possible GroupHud close: line {i+1}: prev={lines[i-1].rstrip()}')
