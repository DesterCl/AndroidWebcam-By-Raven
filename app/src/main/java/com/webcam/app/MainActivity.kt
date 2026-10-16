                tvFps.text = "Transmitiendo ✅"
                handler.postDelayed(this, 2000)
            }
        })
    }

    private fun getLocalIp(): String {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && !it.hostAddress.contains(':') }
                ?.hostAddress ?: "?.?.?.?"
        } catch (_: Exception) {
            "?.?.?.?"
        }
    }

    override fun onResume() {
        super.onResume()
        if (::sensorMgr.isInitialized) {
            accelerometer?.let {
                sensorMgr.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
        if (::rtspCamera.isInitialized && !rtspCamera.isOnPreview && !streaming) {
            startPreviewForSelectedCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::sensorMgr.isInitialized) sensorMgr.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val newRotation = when {
            y > 5f -> 0
            y < -5f -> 180
            x > 5f -> 270
            x < -5f -> 90
            else -> deviceRotation
        }
        if (newRotation == deviceRotation) return

        deviceRotation = newRotation
        if (streaming) applyRotation()

        val label = when (newRotation) {
            0 -> "📱 Vertical"
            180 -> "📱 Invertido"
            90 -> "📱 Horizontal ←"
            270 -> "📱 Horizontal →"
            else -> "📱 --"
        }
        runOnUiThread { tvOrientation.text = label }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private fun hasPerm(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (audioEnabled && !hasPerm(Manifest.permission.RECORD_AUDIO)) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        val missing = permissions.filterNot { hasPerm(it) }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            toggleAudio()
        } else if (requestCode == 100 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            if (::rtspCamera.isInitialized && !rtspCamera.isOnPreview && !streaming) {
                startPreviewForSelectedCamera()
            }
        }
    }

    override fun onDestroy() {
        if (::rtspCamera.isInitialized) {
            if (streaming) stopStream()
            if (rtspCamera.isOnPreview) rtspCamera.stopPreview()
        }
        super.onDestroy()
    }
}
