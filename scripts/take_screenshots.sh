#!/bin/bash
# Script to capture app screenshots for promo images

mkdir -p screenshots

# 1. Launch Launcher Activity (Splash/Typing Area)
echo "Launching LauncherActivity..."
adb shell am start -n tribixbite.cleverkeys/tribixbite.cleverkeys.LauncherActivity
sleep 3
adb exec-out screencap -p > screenshots/1_launcher.png
echo "Captured LauncherActivity"

# 2. Launch Settings Activity
echo "Launching SettingsActivity..."
adb shell am start -n tribixbite.cleverkeys/tribixbite.cleverkeys.SettingsActivity
sleep 2
adb exec-out screencap -p > screenshots/2_settings.png
echo "Captured SettingsActivity"

# 3. Launch Theme Settings Activity
echo "Launching ThemeSettingsActivity..."
adb shell am start -n tribixbite.cleverkeys/tribixbite.cleverkeys.ThemeSettingsActivity
sleep 2
adb exec-out screencap -p > screenshots/3_themes.png
echo "Captured ThemeSettingsActivity"

echo "Screenshots captured in screenshots/ directory."
