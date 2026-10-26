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
            "Концерты и отсутствия",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = "Новый концерт и отметка «не могу» на дату концерта"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
