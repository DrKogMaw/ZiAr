#!/usr/bin/env bash
set -euo pipefail
mkdir -p ui-preview
adb install android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.ziar.transfer/.MainActivity
sleep 5
adb exec-out screencap -p > ui-preview/find-pc.png
adb shell uiautomator dump /sdcard/window.xml
adb pull /sdcard/window.xml ui-preview/window.xml
python3 - <<'PY'
import re
import subprocess
import xml.etree.ElementTree as ET

for node in ET.parse('ui-preview/window.xml').iter('node'):
    if node.attrib.get('text') == 'Nearby phones':
        left, top, right, bottom = map(int, re.findall(r'\d+', node.attrib['bounds']))
        subprocess.run(['adb', 'shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2)], check=True)
        break
else:
    raise SystemExit('Nearby tab not found in the UI')
PY
sleep 4
adb exec-out screencap -p > ui-preview/nearby.png
adb shell settings put system font_scale 1.3
adb shell am force-stop com.ziar.transfer
adb shell am start -n com.ziar.transfer/.MainActivity
sleep 3
adb exec-out screencap -p > ui-preview/find-pc-large-text.png
adb shell settings put system font_scale 1.0
