# Firebase setup

Follow these steps before running the app. Firebase credentials are not included in this project.

1. Open https://console.firebase.google.com/ and create a Firebase project for FamilyMan.
2. In Project settings, add an Android app with package name `com.familyguard`. Use the same package name exactly.
3. Download `google-services.json` from the Android app setup page. Put it in the project at `app/google-services.json`. Do not rename it or share it publicly. It is intentionally ignored by Git.
4. In Firebase Console, open Authentication and enable Google, Email/Password, and Anonymous sign-in. Google sign-in requires the web client ID supplied by `google-services.json` and the usual Firebase SHA certificate setup for your test build. Guest accounts authenticate with a generated internal email identifier; users enter only username and password.
5. Open Realtime Database, create a database, and choose the region closest to your family. Start in locked mode, then open Rules and paste/adapt the starter rules below. Publish them.
6. Open Android Studio and open this project folder. Let Gradle sync finish. Connect an Android phone or start an emulator, then press Run.
7. On FamilyMan, use Google sign-in or Guest login. The parent dashboard has the six-digit connect code and can create a friend referral code. In LearnBuddy, a child can enter the parent's six-digit code alone, with no email or phone field. Friend referral codes also work from its referral entry screen.

## Starter Realtime Database rules

These are a development baseline, not a complete production authorization design. They let a signed-in user claim only an unused hashed username key, let signed-in users read referral records that they have been given, and let a code owner create their own referral record. Pairing codes are bearer secrets, so share them privately and use their short expiry. For a public or larger deployment, move code creation and redemption into trusted Cloud Functions with server-side rate limiting and stronger abuse protection.

```json
{
  "rules": {
    ".read": false,
    ".write": false,
    "users": {
      "$uid": {
        ".read": "auth != null && auth.uid === $uid",
        ".write": "auth != null && auth.uid === $uid",
        "role": { ".validate": "newData.val() === 'PARENT' || newData.val() === 'CHILD'" }
      }
    },
    "usernameIndex": {
      "$hashedUsername": {
        ".read": "auth != null",
        ".write": "auth != null && (!data.exists() || data.val() === auth.uid)",
        ".validate": "newData.isString() && newData.val() === auth.uid"
      }
    },
    "families": {
      "$familyId": {
        ".read": "auth != null && (data.child('parentUid').val() === auth.uid || data.child('children').child(auth.uid).exists())",
        "parentUid": {
          ".write": "auth != null && ((!data.exists() && newData.val() === auth.uid) || data.val() === auth.uid)"
        },
        "createdAt": {
          ".write": "auth != null && data.parent().child('parentUid').val() === auth.uid"
        },
        "children": {
          "$childUid": {
            ".write": "auth != null && auth.uid === $childUid && newData.child('parentUid').val() === root.child('families').child($familyId).child('parentUid').val()"
          }
        }
      }
    },
    "pairingCodes": {
      "$code": {
        ".read": "auth != null",
        "familyId": {
          ".write": "auth != null && ((!data.parent().exists() && newData.parent().child('parentUid').val() === auth.uid) || data.parent().child('parentUid').val() === auth.uid)"
        },
        "parentUid": {
          ".write": "auth != null && ((!data.exists() && newData.val() === auth.uid) || data.val() === auth.uid)"
        },
        "expiresAt": {
          ".write": "auth != null && data.parent().child('parentUid').val() === auth.uid"
        },
        "childUid": {
          ".write": "auth != null && auth.uid === newData.val() && (!data.exists() || data.val() === auth.uid)"
        }
      }
    },
    "referrals": {
      "$code": {
        ".read": "auth != null",
        ".write": "auth != null && ((!data.exists() && newData.child('ownerUid').val() === auth.uid) || data.child('ownerUid').val() === auth.uid)"
      }
    }
  }
}
```

Guest login does not hash the password in the app; Firebase Authentication handles password storage. The username index stores SHA-256 hashes of normalized usernames, not the usernames or passwords. Realtime Database rules and Firebase project settings are not created automatically by this source code. Review and tighten these rules before adding sensitive data or deploying beyond private family testing.
