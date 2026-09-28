package com.appgate.tv.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import com.appgate.tv.service.BrainService

/** Base for screens that talk to the brain service; binds on start, unbinds on stop. */
abstract class ServiceBoundActivity : AppCompatActivity(), BrainService.Listener {
    protected var service: BrainService? = null
        private set
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as? BrainService.LocalBinder)?.service ?: return
            service = s
            s.addListener(this@ServiceBoundActivity)
            onServiceReady(s)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service?.removeListener(this@ServiceBoundActivity)
            service = null
            onServiceLost()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BrainService.start(this)
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, BrainService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        service?.removeListener(this)
        onServiceLost()
        if (bound) runCatching { unbindService(connection) }
        bound = false
        service = null
        super.onStop()
    }

    protected open fun onServiceReady(service: BrainService) {}
    protected open fun onServiceLost() {}
}
