package com.emmaforo.remote

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class EmmaforoApp : Application() {
    lateinit var session: LampSession
        private set

    override fun onCreate() {
        super.onCreate()
        session = LampSession(this)
        session.start()
    }

    companion object {
        fun session(context: Context): LampSession {
            return (context.applicationContext as EmmaforoApp).session
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { EmmaforoScreen(EmmaforoApp.session(this)) }
    }

    override fun onResume() {
        super.onResume()
        EmmaforoApp.session(this).start()
    }

    override fun onPause() {
        EmmaforoApp.session(this).onBackground()
        super.onPause()
    }
}
