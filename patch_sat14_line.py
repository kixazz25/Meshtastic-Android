kt_path = 'app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt'
lines = open(kt_path, encoding='utf-8').readlines()

# Line 904 (0-indexed: 903)
target = 903
if 'SAT/18' in lines[target]:
    lines[target] = lines[target].replace('SAT/18', 'SAT/14')
    open(kt_path, 'w', encoding='utf-8').writelines(lines)
    print('Fixed OK')
else:
    print(f'ERROR: SAT/18 not found on line 904, content: {lines[target].strip()}')
