# FamilyMan Phase 1

This private, consent-based Android starter uses one parent app named **FamilyMan**, a child-facing screen named **LearnBuddy**, and documents a future separate SMS app named **Message**. The Android package remains `com.familyguard` so existing Firebase setup is not renamed. This is an early starter, not a finished monitoring app. Background tracking services are empty stubs and do not collect data.

The Android project is at the repository root. GitHub Actions builds a debug APK on each push to `main` and stores it as the `FamilyMan-debug-apk` artifact for 14 days. The Google Services Gradle plugin is applied only when `app/google-services.json` exists, so the starter can build before Firebase is configured. To enable Firebase features, follow `FIREBASE_SETUP.md` and add your private configuration file locally; never upload that file to this public repository.

## Open and run

1. Install Android Studio and open the `FamilyGuard` project folder.
2. Set up Firebase by following `FIREBASE_SETUP.md` and add `app/google-services.json`.
3. Enable Google, Email/Password, and Anonymous sign-in in Firebase Authentication. Google sign-in also needs a web client ID from Firebase configuration.
4. Connect an Android phone or start an emulator, wait for Gradle sync, then press **Run**.
5. On FamilyMan, sign in with Google or use **Guest login** with a username and password. Guest login uses Firebase email/password internally with a generated, hidden identifier; the app stores the username at `users/{uid}/username` and reserves its SHA-256 username key in `usernameIndex` to keep names unique. Guest accounts receive the same parent dashboard and referral-code generation as Google accounts.
6. On a child's LearnBuddy screen, enter the parent's six-digit code to connect by code alone. LearnBuddy also offers a referral-code entry option. Parent connect codes expire after 15 minutes and link one child.

Never upload the private Firebase configuration file to a public repository. The parent dashboard and child screens are intentionally basic. Configure database rules to cover `usernameIndex` and `referrals` before testing; see Firebase setup notes.

## Phase 1 screens and services

- Welcome screen: **Sign in with Google**, **Guest login**, and referral-code entry. Guest form displays only username and password, never email wording.
- Guest account identity: Firebase Auth email/password is used with a generated internal email identifier. The username itself is stored at `users/{uid}/username` and claimed through the username index. Do not store a plaintext password in Realtime Database.
- Parent dashboard: displays the six-digit parent connect code, can generate a friend referral code, and lists children with a visible **Child Referral** (parent code) or **Friend Referral** label. A child entry has `referralType` set to `parent` or `friend`.
- Referral-code entry is available from LearnBuddy as well as the welcome screen. A parent's six-digit connect code links their own child; a friend referral code associates a referred child with the referring friend's family/dashboard. Share codes privately.
- Child pairing: requires only the six-digit code. No email or phone number is requested.
- Child home: LearnBuddy has exactly three sections: **Games** (placeholder), **Chat** (placeholder), and **Study** (placeholder list for future materials).
- Background-only service stubs exist for usage tracking, location, SOS and notifications. They are not visible child tabs and currently do not perform collection or monitoring.
- `future-sms-app/README.md` describes a future separate SMS app stub with display name **Message**. It is not an Android app module yet.

## Project layout

- `app/src/main/java/com/familyguard/common`: Firebase setup, shared models, repositories and background service stubs.
- `app/src/main/java/com/familyguard/parent`: parent feature package stub.
- `app/src/main/java/com/familyguard/child`: child feature package stub.
- `future-sms-app/README.md`: future Message app notes.

The starter does not yet implement monitoring, chat, games, study downloads or SMS synchronization. Add future features transparently and with the child's consent; review Firebase rules and Android permissions before collecting sensitive data.

## सरल हिन्दी में

यह निजी इस्तेमाल के लिए शुरुआती Android project है। Parent app का नाम **FamilyMan**, बच्चे की app screen का नाम **LearnBuddy**, और आगे बनने वाले अलग SMS app का नाम **Message** है। Parent welcome screen पर Google sign-in के साथ **Guest login** भी है। Guest में सिर्फ username और password भरें, email नहीं दिखता। Firebase Auth अंदरूनी रूप से email/password इस्तेमाल करता है, username `users/{uid}/username` में रखा जाता है और username unique रखा जाता है।

LearnBuddy में भी referral code डालने का विकल्प है। बच्चे को parent से मिला छह अंकों का connect code अकेले डालना है, email या phone number की जरूरत नहीं है। Parent का छह अंकों का code उसके अपने बच्चे को जोड़ता है। Friend referral code से आने वाला बच्चा referring friend के family/dashboard में दिखता है। Dashboard में हर बच्चे के साथ **Child Referral** (parent code) या **Friend Referral** label दिखता है और database में `referralType` की value `parent` या `friend` होती है। Parent dashboard में parent code और friend referral code बनाया जा सकता है। Codes निजी तौर पर share करें।

LearnBuddy में सिर्फ तीन sections हैं: **Games**, **Chat**, और **Study**। अभी ये placeholders हैं। Usage, location, SOS और notifications के लिए खाली background service stubs हैं, वे अभी कुछ track नहीं करते। Firebase setup के लिए `FIREBASE_SETUP.md` देखें, Authentication में Google, Email/Password और Anonymous sign-in enable करें, और private `google-services.json` file public न करें। Username/referral database paths के rules भी setup के समय जोड़ें।
