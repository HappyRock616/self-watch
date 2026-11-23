/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.barcode

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.content.ContextCompat
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The barcode camera, on the built-in Camera2 API (no CameraX: it ships native code).
 *
 * - [CameraManager] picks the first back-facing camera.
 * - One [CameraCaptureSession] feeds the preview surface (from the screen's `TextureView`) and an
 *   [ImageReader] (`YUV_420_888`, about 1280×720, `maxImages` 2). Only the latest image is taken and
 *   frames are dropped while one is being decoded.
 * - Continuous autofocus; the torch through `FLASH_MODE_TORCH` ([setTorch]).
 * - Camera callbacks run on a background [HandlerThread]; decoding runs on its own thread; results
 *   arrive on the main thread through [onResult], once per accepted code (the screen then stops).
 *
 * The screen calls [start] in `onResume` (after the CAMERA permission is granted; see
 * [hasPermission]) and [stop] in `onPause`. Devices without a camera ([hasCamera] false) use manual
 * entry only.
 */
class BarcodeCamera(
    ctx: Context,
    private val analyzer: BarcodeAnalyzer = BarcodeAnalyzer(),
    private val onResult: (ScanResult) -> Unit,
) {
    private val app: Context = ctx.applicationContext ?: ctx
    private val main = Handler(Looper.getMainLooper())
    private val decoding = AtomicBoolean(false)

    // Written on the caller's thread (start/stop) and the camera thread; read on both.
    @Volatile private var thread: HandlerThread? = null
    @Volatile private var handler: Handler? = null
    @Volatile private var decoder: ExecutorService? = null
    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var reader: ImageReader? = null
    @Volatile private var previewSurface: Surface? = null
    @Volatile private var request: CaptureRequest.Builder? = null
    private var frameBuffer = ByteArray(0)

    /** True when the back camera has a flash unit (known after [start] succeeded). */
    @Volatile
    var torchAvailable: Boolean = false
        private set

    @Volatile
    private var torchOn = false

    /**
     * Opens the back camera and starts preview and scanning into [preview] (a `TextureView`'s surface
     * texture). Fails with `PermissionMissing` without the CAMERA permission, `Unsupported`-style
     * `Io("no camera")` without a back camera, or `Io` when the camera cannot be opened.
     */
    fun start(preview: SurfaceTexture): Outcome<Unit> {
        if (!hasPermission(app)) return Outcome.Fail(SwError.PermissionMissing(Manifest.permission.CAMERA))
        val manager = app.getSystemService(CameraManager::class.java) ?: return Outcome.Fail(SwError.Io("no camera"))
        stop()
        return try {
            val id = backCameraId(manager) ?: return Outcome.Fail(SwError.Io("no camera"))
            val chars = manager.getCameraCharacteristics(id)
            torchAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
            val previewSizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
            val analysisSize = chooseSize(yuvSizes.toList()) ?: Size(TARGET_WIDTH, TARGET_HEIGHT)
            val previewSize = chooseSize(previewSizes.toList()) ?: analysisSize
            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList() ?: emptyList()

            val t = HandlerThread("BarcodeCamera").also { it.start() }
            thread = t
            val h = Handler(t.looper)
            handler = h
            decoder = Executors.newSingleThreadExecutor { r -> Thread(r, "BarcodeDecode") }

            preview.setDefaultBufferSize(previewSize.width, previewSize.height)
            val surface = Surface(preview)
            previewSurface = surface
            val ir = ImageReader.newInstance(analysisSize.width, analysisSize.height, ImageFormat.YUV_420_888, MAX_IMAGES)
            ir.setOnImageAvailableListener({ onImage(it) }, h)
            reader = ir

            openCamera(manager, id, h, surface, ir.surface, chooseAfMode(afModes))
            Outcome.Ok(Unit)
        } catch (e: CameraAccessException) {
            SwLog.w(TAG, "camera unavailable", e)
            stop()
            Outcome.Fail(SwError.Io("camera unavailable"))
        } catch (e: SecurityException) {
            SwLog.w(TAG, "camera permission refused", e)
            stop()
            Outcome.Fail(SwError.PermissionMissing(Manifest.permission.CAMERA))
        } catch (e: IllegalArgumentException) {
            SwLog.w(TAG, "camera setup failed", e)
            stop()
            Outcome.Fail(SwError.Io("camera setup failed"))
        }
    }

    /** Stops scanning and closes the camera and its threads. Safe to call at any time. */
    fun stop() {
        try {
            session?.close()
        } catch (e: IllegalStateException) {
            SwLog.w(TAG, "closing the session failed", e)
        }
        session = null
        device?.close()
        device = null
        reader?.close()
        reader = null
        previewSurface?.release()
        previewSurface = null
        request = null
        decoder?.shutdownNow()
        decoder = null
        thread?.quitSafely()
        thread = null
        handler = null
        decoding.set(false)
        torchOn = false
        analyzer.reset()
    }

    /** Turns the torch on or off (ignored without a flash unit or before the camera is running). */
    fun setTorch(on: Boolean) {
        torchOn = on
        val h = handler ?: return
        h.post { applyRequest() }
    }

    // The permission is checked just above the openCamera calls (hasPermission), which lint cannot see through.
    @SuppressLint("MissingPermission")
    private fun openCamera(manager: CameraManager, id: String, h: Handler, preview: Surface, analysis: Surface, afMode: Int) {
        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                try {
                    val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    b.addTarget(preview)
                    b.addTarget(analysis)
                    b.set(CaptureRequest.CONTROL_AF_MODE, afMode)
                    b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    request = b
                    createSession(camera, listOf(preview, analysis), h)
                } catch (e: CameraAccessException) {
                    SwLog.w(TAG, "creating the capture request failed", e)
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                SwLog.i(TAG, "camera disconnected")
                camera.close()
                if (device === camera) device = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                SwLog.w(TAG, "camera error $error")
                camera.close()
                if (device === camera) device = null
            }
        }
        if (!hasPermission(app)) throw SecurityException("CAMERA not granted")
        if (executorApis) {
            manager.openCamera(id, HandlerExecutor(h), callback)
        } else {
            manager.openCamera(id, callback, h)
        }
    }

    private fun createSession(camera: CameraDevice, surfaces: List<Surface>, h: Handler) {
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (device !== camera) {
                    s.close()
                    return
                }
                session = s
                applyRequest()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                SwLog.w(TAG, "camera session configuration failed")
            }
        }
        if (executorApis) {
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                surfaces.map { OutputConfiguration(it) },
                HandlerExecutor(h),
                callback,
            )
            camera.createCaptureSession(config)
        } else {
            createSessionLegacy(camera, surfaces, callback, h)
        }
    }

    @Suppress("DEPRECATION") // The only session API before Android 9.
    private fun createSessionLegacy(camera: CameraDevice, surfaces: List<Surface>, cb: CameraCaptureSession.StateCallback, h: Handler) {
        camera.createCaptureSession(surfaces, cb, h)
    }

    private fun applyRequest() {
        val s = session ?: return
        val b = request ?: return
        b.set(
            CaptureRequest.FLASH_MODE,
            if (torchOn && torchAvailable) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF,
        )
        try {
            s.setRepeatingRequest(b.build(), null, handler)
        } catch (e: CameraAccessException) {
            SwLog.w(TAG, "starting the preview failed", e)
        } catch (e: IllegalStateException) {
            SwLog.w(TAG, "session closed", e)
        }
    }

    private fun onImage(r: ImageReader) {
        val image = try {
            r.acquireLatestImage()
        } catch (e: IllegalStateException) {
            SwLog.w(TAG, "acquiring a frame failed", e)
            null
        } ?: return
        image.use { img ->
            // Drop the frame while the previous one is still being decoded.
            if (!decoding.compareAndSet(false, true)) return
            val plane = img.planes[0]
            val w = img.width
            val h = img.height
            val stride = plane.rowStride
            val buf = plane.buffer
            val needed = stride * (h - 1) + w
            if (buf.remaining() < needed || plane.pixelStride != 1) {
                decoding.set(false)
                return
            }
            if (frameBuffer.size < needed) frameBuffer = ByteArray(needed)
            val frame = frameBuffer
            buf.get(frame, 0, needed)
            val exec = decoder
            if (exec == null) {
                decoding.set(false)
                return
            }
            try {
                exec.execute {
                    try {
                        val found = analyzer.analyze(frame, w, h, stride)
                        if (found != null) main.post { onResult(found) }
                    } catch (e: RuntimeException) {
                        SwLog.w(TAG, "decoding a frame failed", e)
                    } finally {
                        decoding.set(false)
                    }
                }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                SwLog.d(TAG) { "decoder stopped: ${e.message}" }
                decoding.set(false)
            }
        }
    }

    /** Runs commands on a [Handler]'s thread. */
    private class HandlerExecutor(private val h: Handler) : Executor {
        override fun execute(command: Runnable) {
            if (!h.post(command)) throw java.util.concurrent.RejectedExecutionException("handler thread stopped")
        }
    }

    /** Constants and the testable choices. */
    companion object {
        /** Wanted analysis width. */
        const val TARGET_WIDTH: Int = 1280

        /** Wanted analysis height. */
        const val TARGET_HEIGHT: Int = 720

        /** ImageReader `maxImages`. */
        const val MAX_IMAGES: Int = 2

        private const val TAG = "BarcodeCamera"

        /** True when the device has any camera (tablets may not: use manual entry). */
        fun hasCamera(ctx: Context): Boolean = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

        /** True when the CAMERA permission is granted. */
        fun hasPermission(ctx: Context): Boolean =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

        /** Android 9 (API 28) and later open cameras and sessions with executors and `SessionConfiguration`. */
        fun usesExecutorApis(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.P

        /** [usesExecutorApis] for this device. */
        @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.P)
        val executorApis: Boolean get() = usesExecutorApis(Build.VERSION.SDK_INT)

        /**
         * The size closest to 1280×720: among sizes with at least 640 px width, the one with the
         * smallest pixel-count difference to 1280×720, preferring 16:9; null when [sizes] is empty.
         */
        fun chooseSize(sizes: List<Size>): Size? {
            if (sizes.isEmpty()) return null
            val target = TARGET_WIDTH.toLong() * TARGET_HEIGHT
            val usable = sizes.filter { maxOf(it.width, it.height) >= MIN_WIDTH }.ifEmpty { sizes }
            return usable.minWithOrNull(
                compareBy<Size> { if (it.width.toLong() * 9 == it.height.toLong() * 16) 0 else 1 }
                    .thenBy { kotlin.math.abs(it.width.toLong() * it.height - target) },
            )
        }

        /** Continuous autofocus when offered (picture, then video), else auto, else off. */
        fun chooseAfMode(available: List<Int>): Int = when {
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in available -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in available -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            CameraMetadata.CONTROL_AF_MODE_AUTO in available -> CameraMetadata.CONTROL_AF_MODE_AUTO
            else -> CameraMetadata.CONTROL_AF_MODE_OFF
        }

        private const val MIN_WIDTH = 640

        private fun backCameraId(manager: CameraManager): String? =
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
            }
    }
}
