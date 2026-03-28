# Microsoft Auth Error 400 - Debug Steps

## Problem

Error 400 when signing into Microsoft account via CurseForge or Minecraft launcher. This blocks launching SkyFactory One with the optimized Metal terrain renderer.

Error message: "Please retry with a different device or other authentication method to sign in."

## Root Cause

Error 400 typically means the sign-in request couldn't be processed. Common causes:
- Too many sign-in attempts in a row
- Stale auth cache in the launcher
- Network or DNS issue
- App version mismatch

## Debug Steps

### 1. Wait 15-30 minutes

If you've attempted to sign in multiple times, Microsoft rate-limits the account. Wait and retry.

### 2. Clear CurseForge auth cache

```bash
rm -rf "$HOME/Library/Application Support/curseforge/Cache"
```

Then relaunch CurseForge and try signing in again.

### 3. Try the vanilla Minecraft launcher

```bash
open -a "Minecraft Launcher"
```

Sign in there instead. If it works, the token can be extracted for the optimized launch script.

### 4. Sign in via browser first

Go to https://microsoft.com and sign in successfully in a browser. This refreshes the Microsoft session, which may unblock the launcher.

### 5. Switch network

Try a different Wi-Fi network or use phone hotspot temporarily. DNS or network-level issues can cause Error 400.

### 6. Delete stale launcher credentials

```bash
rm "$HOME/Library/Application Support/minecraft/launcher_msa_credentials.bin"
rm "$HOME/Library/Application Support/minecraft/launcher_accounts.json"
```

Then relaunch the Minecraft launcher and sign in fresh.

### 7. Use a different sign-in method

At the Microsoft sign-in prompt, select "Other ways to sign in" and try:
- Face, fingerprint, PIN, or security key
- Approve sign-in with mobile app
- Send a code to email

### 8. Reinstall the launcher

Delete and reinstall CurseForge or the Minecraft launcher from scratch.

## After Successful Sign-In

Once signed in through any launcher, extract the live auth token for the optimized launch script:

```bash
# While the game is running:
python3 refresh_token.py
```

This grabs `--accessToken`, `--uuid`, and `--username` from the running Java process. The optimized `launch-skyfactory.sh` script uses these credentials automatically.

## Reference

- Microsoft support article: https://support.microsoft.com/en-us/account-billing/error-400-when-signing-in
- Microsoft sign-in helper tool: https://account.live.com/SignInRecovery
