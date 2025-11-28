# Wireless ADB Setup for CleverKeys Development

## One-Time Setup (Required for automated APK installation)

### Step 1: Enable Developer Options
1. Go to **Settings** → **About phone**
2. Tap **Build number** 7 times
3. You'll see "You are now a developer!"

### Step 2: Enable Wireless Debugging
1. Go to **Settings** → **System** → **Developer options**
2. Turn on **Wireless debugging**
3. Make sure you're connected to Wi-Fi

### Step 3: Pair Termux with Device (First Time Only)
1. In Developer options, tap **Wireless debugging** (the text, not just the toggle)
2. Tap **Pair device with pairing code**
3. You'll see:
   ```
   IP address & port: 192.168.1.X:XXXXX
   Wi-Fi pairing code: XXXXXX
   ```
4. Keep this dialog open

5. In Termux, run:
   ```bash
   adb pair <IP:PORT> <PAIRING_CODE>
   ```
   Example:
   ```bash
   adb pair 192.168.1.5:41379 123456
   ```

6. You should see: "Successfully paired to ..."

### Step 4: Connect to Device
1. Go back to the main **Wireless debugging** screen
2. Note the **IP address & Port** (different from pairing port!)
   ```
   IP address & Port: 192.168.1.X:XXXXX
   ```

3. In Termux, run:
   ```bash
   adb connect <IP:PORT>
   ```
   Example:
   ```bash
   adb connect 192.168.1.5:37289
   ```

4. Verify connection:
   ```bash
   adb devices
   ```
   Should show your device.

### Step 5: Install CleverKeys
Now you can use automated installation:
```bash
./adb-install.sh
```

## Quick Reconnect (After Reboot or WiFi Change)

If you've already paired once, you only need to reconnect:

1. Enable **Wireless debugging** in Developer options
2. Check the IP address & Port
3. Run:
   ```bash
   adb connect <IP:PORT>
   ```

## Automated Installation Script

Once connected, use:
```bash
./adb-install.sh
```

This will:
- Check for APK existence
- Verify ADB connection
- Install APK without user interaction
- Display next steps

## Monitor Keyboard Logs

After installation:
```bash
adb logcat -s CleverKeys:* Keyboard2:* NeuralSwipe:* AndroidRuntime:E
```

## Troubleshooting

**"unauthorized" when running adb devices:**
- Check your phone screen - there may be an authorization popup
- Tap "Always allow from this computer" and OK

**Connection times out:**
- Make sure both Termux and phone are on same Wi-Fi
- Check firewall settings
- Try disabling and re-enabling Wireless debugging

**Pairing code doesn't work:**
- The pairing code expires quickly - get a new one
- Make sure you're using the IP:PORT from "Pair device" screen
- Check for typos in IP address or pairing code

**Device disconnects frequently:**
- This is normal when phone sleeps or WiFi changes
- Keep Wireless debugging enabled
- Reconnect with: `adb connect <IP:PORT>`
