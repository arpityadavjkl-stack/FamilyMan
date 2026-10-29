package com.familyguard.common

import android.app.Application
import com.google.firebase.FirebaseApp

class FamilyGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Firebase reads its settings from app/google-services.json.
        FirebaseApp.initializeApp(this)
    }
}
