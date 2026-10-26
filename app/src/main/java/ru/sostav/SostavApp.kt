package ru.sostav

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth

class SostavApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.FIREBASE_CONFIGURED) {
            FirebaseApp.initializeApp(this)
            FirebaseAuth.getInstance().setLanguageCode("ru")
        }
        val channel = NotificationChannel(
            "sostav",
            "События и отметки",
            NotificationManager.IMPORTANCE_HIGH,
        )
        channel.description = "Новое событие и отметка «нет» на эти дни"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
