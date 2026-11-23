/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.TextureView
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.R
import app.selfwatch.food.barcode.BarcodeCamera
import app.selfwatch.food.barcode.Gtin
import app.selfwatch.graph
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.time.Day
import app.selfwatch.ui.SwActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import app.selfwatch.ui.R as UiR

/**
 * Barcode scan (WP-10 `ScanActivity`): Camera2 preview with an aiming frame and torch, haptic on a
 * read, then the food detail; not found → Open Food Facts (if allowed) → custom food form. The CAMERA
 * permission is asked here, the first time the screen opens; without it (or without a camera) the
 * screen offers typed entry.
 */
class ScanActivity : SwActivity(), FoodLoggedListener {

    private val day: Day? by lazy { intent.getIntExtra(EXTRA_DAY, 0).takeIf { it != 0 }?.let { Day(it) } }
    private val slot: Long by lazy { intent.getLongExtra(EXTRA_SLOT, 0L) }

    private val vm: ScanViewModel by viewModels {
        val g = graph
        viewModelFactory { initializer { ScanViewModel { GraphFoodBackend(g.food) } } }
    }

    private var camera: BarcodeCamera? = null
    private var cameraAllowed = false
    private var cameraRunning = false
    private lateinit var preview: TextureView
    private lateinit var previewFrame: View
    private lateinit var status: TextView
    private lateinit var actions: LinearLayout
    private lateinit var manualEdit: TextInputEditText
    private lateinit var toolbar: MaterialToolbar
    private var torchOn = false
    private var openedFor: Long = 0L

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraAllowed = granted
        if (granted) {
            showCamera(true)
            startCameraIfReady()
        } else {
            showCamera(false)
            status.text = getString(R.string.scan_permission_denied)
        }
    }

    private val editor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val id = r.data?.getLongExtra(FoodEditorActivity.RESULT_FOOD_ID, 0L) ?: 0L
        if (id != 0L) openDetail(FoodRef(FoodOrigin.MY_FOOD, id)) else vm.scanAgain()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx: Context = this
        val pad = resources.getDimensionPixelSize(UiR.dimen.sw_space_2)
        val root = FoodViews.column(ctx)
        toolbar = MaterialToolbar(ctx).apply {
            title = getString(R.string.add_scan)
            setNavigationIcon(UiR.drawable.sw_ic_close)
            navigationContentDescription = getString(UiR.string.sw_close)
            setNavigationOnClickListener { finish() }
            menu.add(0, MENU_TORCH, 0, R.string.scan_torch).apply {
                setIcon(R.drawable.ic_torch)
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
                isVisible = false
            }
            setOnMenuItemClickListener {
                toggleTorch()
                true
            }
        }
        root.addView(toolbar, FoodViews.matchWrap())

        previewFrame = FrameLayout(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        preview = TextureView(ctx).apply {
            contentDescription = getString(R.string.scan_preview)
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) = startCameraIfReady()
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    stopCamera()
                    return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
        }
        (previewFrame as FrameLayout).addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val aim = View(ctx).apply {
            setBackgroundResource(R.drawable.scan_frame)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        (previewFrame as FrameLayout).addView(
            aim,
            FrameLayout.LayoutParams(FoodViews.dp(ctx, AIM_WIDTH_DP), FoodViews.dp(ctx, AIM_HEIGHT_DP), Gravity.CENTER),
        )
        root.addView(previewFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val panel = FoodViews.column(ctx).apply { setPaddingRelative(pad, pad / 2, pad, pad / 2) }
        status = FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, getString(R.string.scan_hint)).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            tag = "status"
        }
        panel.addView(status, FoodViews.matchWrap())
        actions = FoodViews.column(ctx)
        panel.addView(actions, FoodViews.matchWrap())
        val manual = FoodViews.row(ctx)
        val (manualLayout, edit) = FoodViews.field(ctx, R.string.scan_type_code, InputType.TYPE_CLASS_NUMBER)
        manualEdit = edit
        edit.imeOptions = EditorInfo.IME_ACTION_SEARCH
        edit.tag = "manual"
        edit.setOnEditorActionListener { _, _, _ ->
            vm.onTyped(edit.text?.toString().orEmpty())
            true
        }
        manual.addView(manualLayout, FoodViews.weight())
        manual.addView(
            FoodViews.textButton(ctx, getString(R.string.scan_look_up)) { vm.onTyped(edit.text?.toString().orEmpty()) }.apply { tag = "look_up" },
        )
        panel.addView(manual, FoodViews.matchWrap())
        root.addView(panel, FoodViews.matchWrap())
        setRootContent(root)

        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
                    if (f is FoodDetailSheet && !isFinishing && !isChangingConfigurations) vm.scanAgain()
                }
            },
            false,
        )

        when {
            !BarcodeCamera.hasCamera(ctx) -> {
                showCamera(false)
                status.text = getString(R.string.scan_no_camera)
            }
            BarcodeCamera.hasPermission(ctx) -> {
                cameraAllowed = true
                showCamera(true)
            }
            savedInstanceState == null -> {
                showCamera(false)
                status.text = getString(R.string.scan_permission_reason)
                permission.launch(Manifest.permission.CAMERA)
            }
            else -> {
                showCamera(false)
                status.text = getString(R.string.scan_permission_denied)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { vm.state.collect { render(it) } }
        }
    }

    override fun onResume() {
        super.onResume()
        startCameraIfReady()
    }

    override fun onPause() {
        stopCamera()
        super.onPause()
    }

    private fun showCamera(on: Boolean) {
        FoodViews.show(previewFrame, on)
    }

    private fun startCameraIfReady() {
        if (!cameraAllowed || cameraRunning || vm.state.value != ScanState.Scanning) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val st = preview.surfaceTexture ?: return
        val cam = camera ?: BarcodeCamera(this) { result -> onRead(result.gtin) }.also { camera = it }
        when (val r = cam.start(st)) {
            is Outcome.Ok -> {
                cameraRunning = true
                toolbar.menu.findItem(MENU_TORCH)?.isVisible = cam.torchAvailable
            }
            is Outcome.Fail -> {
                SwLog.w(TAG, "camera start failed: ${r.error}")
                showCamera(false)
                status.text = getString(R.string.scan_camera_failed)
            }
        }
    }

    private fun stopCamera() {
        if (!cameraRunning) return
        cameraRunning = false
        torchOn = false
        camera?.stop()
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        torchOn = !torchOn
        cam.setTorch(torchOn)
        toolbar.menu.findItem(MENU_TORCH)?.title = getString(if (torchOn) R.string.scan_torch_off else R.string.scan_torch)
    }

    private fun onRead(gtin: Long) {
        val haptic = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        preview.performHapticFeedback(haptic)
        stopCamera()
        vm.onBarcode(gtin)
    }

    private fun render(s: ScanState) {
        actions.removeAllViews()
        val ctx: Context = this
        when (s) {
            ScanState.Scanning -> {
                openedFor = 0L
                if (cameraAllowed && previewFrame.isVisible) status.text = getString(R.string.scan_hint)
                startCameraIfReady()
            }
            ScanState.BadCode -> status.text = getString(R.string.scan_bad_code)
            is ScanState.Looking -> status.text = getString(
                if (s.online) R.string.scan_looking_online else R.string.scan_looking,
                Gtin.shortForm(s.gtin),
            )
            is ScanState.Found -> {
                status.text = getString(R.string.scan_found, s.name)
                if (openedFor != s.gtin) {
                    openedFor = s.gtin
                    openDetail(s.ref)
                }
            }
            is ScanState.NotFound -> {
                status.text = getString(if (s.triedOnline) R.string.scan_not_found_online else R.string.scan_not_found, Gtin.shortForm(s.gtin))
                if (s.canLookOnline) actions.addView(button(FoodViews.filledButton(ctx, getString(R.string.scan_look_online)) { vm.lookOnline() }, "look_online"))
                actions.addView(button(FoodViews.outlinedButton(ctx, getString(R.string.scan_create_food)) { createFood(s.gtin) }, "create_food"))
                actions.addView(button(FoodViews.textButton(ctx, getString(R.string.scan_again)) { scanAgain() }, "scan_again"))
            }
            is ScanState.Unavailable -> {
                status.text = getString(
                    when (s.error) {
                        is SwError.Blocked -> R.string.scan_off_blocked
                        SwError.Offline -> R.string.scan_offline
                        else -> R.string.scan_off_failed
                    },
                )
                actions.addView(button(FoodViews.outlinedButton(ctx, getString(R.string.scan_create_food)) { createFood(s.gtin) }, "create_food"))
                actions.addView(button(FoodViews.textButton(ctx, getString(R.string.scan_again)) { scanAgain() }, "scan_again"))
            }
        }
    }

    private fun button(b: MaterialButton, tag: String): MaterialButton = b.also { it.tag = tag }

    private fun scanAgain() {
        openedFor = 0L
        vm.scanAgain()
    }

    private fun createFood(gtin: Long) {
        editor.launch(FoodEditorActivity.newIntent(this, gtin))
    }

    private fun openDetail(ref: FoodRef) {
        if (supportFragmentManager.findFragmentByTag(FoodDetailSheet.TAG) != null) return
        FoodDetailSheet.forFood(ref, day, slot).show(supportFragmentManager, FoodDetailSheet.TAG)
    }

    override fun onFoodLogged(name: String, entryId: Long) {
        android.widget.Toast.makeText(this, getString(R.string.food_added, name), android.widget.Toast.LENGTH_SHORT).show()
        finish()
    }

    /** Intents. */
    companion object {
        private const val TAG = "ScanActivity"
        private const val EXTRA_DAY = "day"
        private const val EXTRA_SLOT = "slot"
        private const val MENU_TORCH = 1
        private const val AIM_WIDTH_DP = 260f
        private const val AIM_HEIGHT_DP = 150f

        /** Opens the scanner, logging into [slot] (0: guess) on [day] (null: today). */
        fun intent(ctx: Context, day: Day?, slot: Long): Intent =
            Intent(ctx, ScanActivity::class.java).putExtra(EXTRA_DAY, day?.yyyymmdd ?: 0).putExtra(EXTRA_SLOT, slot)
    }
}
