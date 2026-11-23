/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package app.selfwatch.sample.link

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import app.selfwatch.link.client.SelfWatchLink
import java.util.UUID

/**
 * One screen: enter your watch app's UUID, send a test dictionary, see results and replies. Uses Self
 * Watch when it is installed, otherwise the (stub) Garmin toolkit transport.
 */
class MainActivity : Activity() {
    private lateinit var transport: CompanionTransport
    private lateinit var log: TextView
    private val listening = mutableSetOf<UUID>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        log = findViewById(R.id.log)
        val uuidField: EditText = findViewById(R.id.uuid)
        if (savedInstanceState == null) uuidField.setText(EXAMPLE_UUID)

        val useSelfWatch = SelfWatchLink.isInstalled(this)
        transport = if (useSelfWatch) SelfWatchTransport(this) else GarminToolkitTransport(this)
        findViewById<TextView>(R.id.transport).text = getString(
            R.string.using_transport,
            getString(if (useSelfWatch) R.string.transport_self_watch else R.string.transport_garmin),
        )
        transport.start(::append)

        findViewById<Button>(R.id.send).setOnClickListener {
            val uuid = try {
                UUID.fromString(uuidField.text.toString().trim())
            } catch (_: IllegalArgumentException) {
                append(getString(R.string.bad_uuid))
                return@setOnClickListener
            }
            if (listening.add(uuid)) {
                transport.listen(uuid) { message -> append(getString(R.string.received, message.toString())) }
            }
            transport.send(uuid, testMessage(), ::append)
        }
    }

    override fun onDestroy() {
        transport.stop()
        super.onDestroy()
    }

    private fun testMessage(): Map<String, Any?> = linkedMapOf(
        "hello" to "from the phone",
        "tSec" to System.currentTimeMillis() / 1000,
        "values" to listOf(1, 2.5, true, null),
    )

    private fun append(line: String) {
        log.append(line)
        log.append("\n")
    }

    private companion object {
        /** Placeholder: replace with the id from your watch app's manifest.xml. */
        const val EXAMPLE_UUID = "6a1e2c3d-0000-4000-8000-00000000abcd"
    }
}
