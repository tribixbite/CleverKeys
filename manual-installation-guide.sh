#!/data/data/com.termux/files/usr/bin/bash

clear
cat << 'GUIDE'
╔══════════════════════════════════════════════════════════════╗
║         CLEVERKEYS - MANUAL INSTALLATION GUIDE               ║
╔══════════════════════════════════════════════════════════════╗

📱 INSTALLATION STEPS (Do this now!)
══════════════════════════════════════════════════════════════

Step 1: Open File Manager
──────────────────────────────────────────────────────────────
   • Use any file manager app (Files, Total Commander, etc.)
   • Or copy APK to Downloads:
     cp build/outputs/apk/debug/tribixbite.keyboard2.apk \
        ~/storage/downloads/cleverkeys.apk

Step 2: Navigate to APK
──────────────────────────────────────────────────────────────
   Path: /storage/emulated/0/Download/cleverkeys.apk
   OR:   Termux home → git → swype → cleverkeys → build →
         outputs → apk → debug → tribixbite.keyboard2.apk

Step 3: Install APK
──────────────────────────────────────────────────────────────
   • Tap on the APK file
   • Tap "Install" button
   • Wait for installation to complete
   • You should see "App installed" message

Step 4: Enable CleverKeys
──────────────────────────────────────────────────────────────
   Settings → System → Languages & Input → On-screen keyboard
   
   You should see:
     • Gboard (or current keyboard)
     • CleverKeys Neural Keyboard  ← Enable this!
   
   Toggle it ON

Step 5: Switch to CleverKeys
──────────────────────────────────────────────────────────────
   Method 1: Via text input
     • Open any app (Chrome, Messages, etc.)
     • Tap on a text field
     • Tap keyboard icon in bottom-right
     • Select "CleverKeys Neural Keyboard"
   
   Method 2: Set as default
     Settings → System → Languages & Input → On-screen keyboard
     → Default keyboard → CleverKeys Neural Keyboard


📋 VERIFICATION CHECKLIST
══════════════════════════════════════════════════════════════

After switching to CleverKeys, verify:

□ Keyboard appears when you tap text field
□ Keys respond to taps (characters appear)
□ Backspace key works
□ Shift key works (capitals)
□ Can switch to numbers/symbols
□ Keyboard works in multiple apps

If ALL checks pass: Say "keyboard works!"
If ANY fail: Describe the issue


🔍 WHAT TO LOOK FOR
══════════════════════════════════════════════════════════════

✅ GOOD SIGNS:
   • Keyboard displays when you tap text fields
   • Keys produce characters when tapped
   • No crash/error messages
   • Can type in Chrome, Messages, etc.

❌ BAD SIGNS:
   • Keyboard doesn't appear
   • App crashes when switching
   • Keys don't respond to taps
   • Error messages appear


🐛 TROUBLESHOOTING
══════════════════════════════════════════════════════════════

Problem: APK won't install
───────────────────────────────────────────────────────────────
Solution: 
  • Check "Install unknown apps" permission
  • Settings → Apps → Special app access → 
    Install unknown apps → Enable for your file manager

Problem: CleverKeys not in keyboard list
───────────────────────────────────────────────────────────────
Solution:
  • Installation may have failed
  • Check: Settings → Apps → See all apps
  • Look for "CleverKeys" or "tribixbite.keyboard2"
  • If not there, reinstall

Problem: Keyboard crashes when switching
───────────────────────────────────────────────────────────────
Solution:
  • This is the bug we fixed on Nov 21!
  • Should NOT happen with this APK
  • If it does, we need to investigate further
  • Run: adb logcat to see error logs

Problem: Can't find APK file
───────────────────────────────────────────────────────────────
Solution:
  • Run this to copy to Downloads:
    cp build/outputs/apk/debug/tribixbite.keyboard2.apk \
       ~/storage/downloads/cleverkeys.apk
  • Then open Downloads folder and tap cleverkeys.apk


📊 APK INFORMATION
══════════════════════════════════════════════════════════════
GUIDE

# Show APK info
if [ -f "build/outputs/apk/debug/tribixbite.keyboard2.apk" ]; then
    SIZE=$(du -h "build/outputs/apk/debug/tribixbite.keyboard2.apk" | cut -f1)
    DATE=$(stat -c '%y' "build/outputs/apk/debug/tribixbite.keyboard2.apk" | cut -d' ' -f1,2 | cut -d'.' -f1)
    echo "File:     build/outputs/apk/debug/tribixbite.keyboard2.apk"
    echo "Size:     $SIZE"
    echo "Built:    $DATE"
    echo "Package:  tribixbite.keyboard2"
    echo "Service:  tribixbite.keyboard2.CleverKeysService"
    echo "Fix:      Nov 21 - applicationIdSuffix removed ✅"
else
    echo "❌ APK not found! Run: ./gradlew assembleDebug"
fi

cat << 'GUIDE'


💾 COPY TO DOWNLOADS (Optional)
══════════════════════════════════════════════════════════════

To make installation easier, copy APK to Downloads folder:

GUIDE

echo "Press ENTER to copy APK to Downloads folder..."
echo "(Or Ctrl+C to skip)"
read -r

if [ -f "build/outputs/apk/debug/tribixbite.keyboard2.apk" ]; then
    echo "Copying APK to Downloads..."
    cp build/outputs/apk/debug/tribixbite.keyboard2.apk ~/storage/downloads/cleverkeys.apk
    if [ $? -eq 0 ]; then
        echo "✅ Copied to: ~/storage/downloads/cleverkeys.apk"
        echo "   (Also accessible at: /storage/emulated/0/Download/cleverkeys.apk)"
        echo ""
        echo "Now:"
        echo "1. Open your file manager"
        echo "2. Go to Downloads folder"
        echo "3. Tap 'cleverkeys.apk'"
        echo "4. Tap 'Install'"
    else
        echo "❌ Copy failed. You may need to grant storage permissions."
        echo "   Run: termux-setup-storage"
    fi
else
    echo "❌ APK not found!"
fi

cat << 'GUIDE'


╔══════════════════════════════════════════════════════════════╗
║  After installation, come back and report results!           ║
╚══════════════════════════════════════════════════════════════╝
GUIDE
