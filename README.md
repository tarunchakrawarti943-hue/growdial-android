# Growdial Android app

The Growdial website inside an Android app:
- screenshots and screen recording are blocked (the screen shows black),
- camera + microphone for live calls and "Go live from this phone",
- photo / PDF uploads (pick a file or take a photo),
- UPI apps, WhatsApp, YouTube open in their own apps; payments come back to the app,
- no-internet screen, pull-to-refresh, full-screen video, downloads.

## Get the app without Android Studio (free, with GitHub)
1. Make a free account on github.com → **New repository** → name `growdial-android` → **Private** → Create.
2. **Add file → Upload files** → drag everything from this folder (including the `.github` folder) → **Commit**.
3. Open the **Actions** tab. "Build Android app" runs by itself (about 5–8 minutes).
4. Open the finished run → **Artifacts** → download **growdial-apk** → unzip → copy the `.apk` to the phone and install it
   (allow "Install unknown apps" once).

## For the Play Store
1. Actions → **Make Play Store signing key** → Run workflow → type a strong password.
2. Download the **signing-key** artifact (it is kept only 1 day). Keep `release.jks` and the password safe — for ever.
3. Settings → Secrets and variables → Actions → add the 4 secrets written in `HOW-TO.txt`.
4. Actions → Build Android app → Run workflow → download **growdial-playstore** → upload the `.aab` in Play Console.
5. Next versions: in `app/build.gradle.kts` change `appVersion = 1` to 2, 3, … and run the build again.

## Settings you may change (app/build.gradle.kts and MainActivity.kt)
- `siteUrl` — the website the app opens.
- `applicationId` — the app's id on Play Store (can never change after the first upload).
- `SECURE` in MainActivity.kt — `false` allows screenshots again.
