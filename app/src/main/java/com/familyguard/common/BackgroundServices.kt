package com.familyguard.common

import android.app.Service
import android.content.Intent
import android.os.IBinder

// These non-UI service stubs reserve background entry points for later consent-based features.
abstract class BackgroundFeatureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
}

class UsageTrackingService : BackgroundFeatureService()
class LocationService : BackgroundFeatureService()
class SosService : BackgroundFeatureService()
class NotificationService : BackgroundFeatureService()
