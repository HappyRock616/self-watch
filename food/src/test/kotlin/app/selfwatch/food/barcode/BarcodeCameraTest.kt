/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.barcode

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Size
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Camera2 wrapper: only the API-level branches and pure choices (no real camera in unit tests). */
@RunWith(RobolectricTestRunner::class)
class BarcodeCameraTest {

    @Test
    fun executorApisFromAndroid9() {
        assertFalse(BarcodeCamera.usesExecutorApis(Build.VERSION_CODES.O))
        assertFalse(BarcodeCamera.usesExecutorApis(Build.VERSION_CODES.O_MR1))
        assertTrue(BarcodeCamera.usesExecutorApis(Build.VERSION_CODES.P))
        assertTrue(BarcodeCamera.usesExecutorApis(35))
    }

    @Test
    fun choosesTheSizeNearest720p() {
        assertNull(BarcodeCamera.chooseSize(emptyList()))
        val sizes = listOf(Size(4032, 3024), Size(1920, 1080), Size(1280, 960), Size(1280, 720), Size(640, 480), Size(320, 240))
        assertEquals(Size(1280, 720), BarcodeCamera.chooseSize(sizes))
        assertEquals(Size(1920, 1080), BarcodeCamera.chooseSize(listOf(Size(1920, 1080), Size(1280, 960), Size(3840, 2160))))
        assertEquals(Size(1280, 960), BarcodeCamera.chooseSize(listOf(Size(1280, 960), Size(2592, 1944))))
        assertEquals(Size(320, 240), BarcodeCamera.chooseSize(listOf(Size(320, 240), Size(176, 144))))
    }

    @Test
    fun prefersContinuousAutofocus() {
        val all = listOf(
            CameraMetadata.CONTROL_AF_MODE_OFF, CameraMetadata.CONTROL_AF_MODE_AUTO,
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
        )
        assertEquals(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE, BarcodeCamera.chooseAfMode(all))
        assertEquals(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO, BarcodeCamera.chooseAfMode(all.dropLast(1)))
        assertEquals(CameraMetadata.CONTROL_AF_MODE_AUTO, BarcodeCamera.chooseAfMode(all.take(2)))
        assertEquals(CameraMetadata.CONTROL_AF_MODE_OFF, BarcodeCamera.chooseAfMode(emptyList())) // fixed focus
    }

    @Test
    fun noCameraAndNoPermission() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, false)
        assertFalse(BarcodeCamera.hasCamera(app))
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
        assertTrue(BarcodeCamera.hasCamera(app))

        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        assertFalse(BarcodeCamera.hasPermission(app))
        val cam = BarcodeCamera(app) { }
        assertEquals(Outcome.Fail(SwError.PermissionMissing(Manifest.permission.CAMERA)), cam.start(SurfaceTexture(0)))
        cam.setTorch(true) // ignored when not running
        cam.stop()
        cam.stop() // twice is fine

        // Permission but no back camera registered: a clean failure, not a crash.
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        assertTrue(BarcodeCamera.hasPermission(app))
        assertEquals(Outcome.Fail(SwError.Io("no camera")), cam.start(SurfaceTexture(0)))
        assertFalse(cam.torchAvailable)
    }
}
