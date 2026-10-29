with open('app/src/main/res/values/strings.xml', 'rb') as f:
    content = f.read()

# Replace the literal & (0x26) in the menu_backup line with &
# Find the position of 'menu_backup">Profiles ' then replace the next & with &
idx = content.find(b'menu_backup">Profiles ')
if idx >= 0:
    amp_idx = content.find(b'&', idx)
    if amp_idx >= 0:
        content = content[:amp_idx] + b'&' + content[amp_idx+1:]
        print(f'Replaced at byte {amp_idx}')
    else:
        print('Ampersand not found after prefix')
else:
    print('Prefix not found')

with open('app/src/main/res/values/strings.xml', 'wb') as f:
    f.write(content)
print('Done')