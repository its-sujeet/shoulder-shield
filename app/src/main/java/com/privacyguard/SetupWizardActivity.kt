package com.privacyguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.privacyguard.ml.FaceDetectorManager
import com.privacyguard.utils.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SetupWizardActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var dots: LinearLayout
    private lateinit var btnNext: Button
    private lateinit var btnSkip: Button
    private val pages = 6
    private var currentPage = 0
    private var enrollmentSkipped = false

    private val prefs by lazy { PreferencesManager(this) }
    private var cameraGranted = false
    private var overlayGranted = false
    private var notificationGranted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_wizard)

        pager = findViewById(R.id.pager)
        dots = findViewById(R.id.dots_container)
        btnNext = findViewById(R.id.btn_next)
        btnSkip = findViewById(R.id.btn_skip)

        pager.adapter = WizardAdapter(this)
        pager.isUserInputEnabled = false
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPage = position
                updateDots()
                updateButtons()
            }
        })

        btnNext.setOnClickListener { advance() }
        btnSkip.setOnClickListener { finishSetup(true) }

        renderDots()
        updateDots()

        checkInitialPermissions()
    }

    private fun checkInitialPermissions() {
        cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        overlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private val dotViews = mutableListOf<View>()

    private fun renderDots() {
        dots.removeAllViews()
        dotViews.clear()
        for (i in 0 until pages) {
            val dot = View(this)
            val size = 12
            dot.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = 8
                setMargins(0, 0, 8, 0)
            }
            dot.setBackgroundColor(
                if (i == 0) 0xFF6750A4.toInt() else 0xFF888888.toInt()
            )
            dot.isClickable = false
            dot.setBackgroundResource(android.R.drawable.presence_offline)
            dots.addView(dot)
            dotViews.add(dot)
        }
    }

    private fun updateDots() {
        for (i in 0 until pages) {
            dotViews[i].setBackgroundResource(
                if (i == currentPage) android.R.drawable.presence_online
                else android.R.drawable.presence_offline
            )
        }
    }

    private fun updateButtons() {
        val isLast = currentPage == pages - 1
        btnNext.text = if (isLast) "Get Started" else "Next"
        btnSkip.visibility = if (currentPage >= 3) View.GONE else View.VISIBLE
    }

    private fun advance() {
        when (currentPage) {
            0 -> pager.currentItem = 1
            1 -> requestCamera()
            2 -> requestOverlay()
            3 -> requestNotification()
            4 -> { /* Enrollment handles its own advance via callback */ }
            5 -> finishSetup(false)
            else -> finishSetup(false)
        }
    }

    private fun requestCamera() {
        if (cameraGranted) {
            pager.currentItem = 2
            return
        }
        cameraLauncher.launch(Manifest.permission.CAMERA)
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
        if (granted) pager.currentItem = 2
        else Toast.makeText(this, "Camera permission required for face detection", Toast.LENGTH_SHORT).show()
    }

    private fun requestOverlay() {
        if (overlayGranted) {
            pager.currentItem = 3
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            overlayLauncher.launch(intent)
        }
    }

    private val overlayLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            overlayGranted = Settings.canDrawOverlays(this)
        }
        if (overlayGranted) pager.currentItem = 3
        else Toast.makeText(this, "Overlay permission required for privacy shield", Toast.LENGTH_SHORT).show()
    }

    private fun requestNotification() {
        if (notificationGranted) {
            pager.currentItem = 4
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationGranted = granted
        pager.currentItem = 4
    }

    /** Called by EnrollmentFragment when enrollment completes or is skipped. */
    fun onEnrollmentDone(skipped: Boolean) {
        enrollmentSkipped = skipped
        pager.currentItem = 5
    }

    private fun finishSetup(skipped: Boolean) {
        lifecycleScope.launch {
            prefs.setSetupCompleted(true)
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    // ─── Adapter ───

    inner class WizardAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount() = pages
        override fun createFragment(position: Int) = WizardFragment(position)
    }

    // ─── Fragments as inner classes for simplicity ───

    inner class WizardFragment(private val step: Int) : androidx.fragment.app.Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View? {
            return when (step) {
                0 -> inflater.inflate(R.layout.fragment_setup_welcome, container, false)
                1 -> inflater.inflate(R.layout.fragment_setup_camera, container, false).also { v ->
                    v.findViewById<Button>(R.id.btn_grant_camera)?.setOnClickListener {
                        requestCamera()
                    }
                }
                2 -> inflater.inflate(R.layout.fragment_setup_overlay, container, false).also { v ->
                    v.findViewById<Button>(R.id.btn_grant_overlay)?.setOnClickListener {
                        requestOverlay()
                    }
                }
                3 -> inflater.inflate(R.layout.fragment_setup_notification, container, false).also { v ->
                    v.findViewById<Button>(R.id.btn_grant_notification)?.setOnClickListener {
                        requestNotification()
                    }
                }
                4 -> inflater.inflate(R.layout.fragment_setup_enrollment, container, false).also { v ->
                    setupEnrollmentView(v)
                }
                5 -> inflater.inflate(R.layout.fragment_setup_complete, container, false).also { v ->
                    v.findViewById<Button>(R.id.btn_finish_setup)?.setOnClickListener {
                        finishSetup(enrollmentSkipped)
                    }
                    val subtitle = v.findViewById<TextView>(R.id.tv_setup_complete_subtitle)
                    val skipInfo = v.findViewById<TextView>(R.id.tv_setup_skip_info)
                    if (enrollmentSkipped) {
                        subtitle?.text = "You skipped face enrollment.\nYou can enable it later in Settings."
                        skipInfo?.visibility = View.VISIBLE
                    }
                }
                else -> null
            }
        }

        private fun setupEnrollmentView(view: View) {
            val previewView = view.findViewById<PreviewView>(R.id.preview_view)
            val tvPercent = view.findViewById<TextView>(R.id.tv_progress_percent)
            val tvLabel = view.findViewById<TextView>(R.id.tv_progress_label)
            val tvHint = view.findViewById<TextView>(R.id.tv_hint)
            val btnSkip = view.findViewById<Button>(R.id.btn_skip_enrollment)

            val faceDetector = FaceDetectorManager(requireContext())

            // Start camera + enrollment
            lifecycleScope.launch(Dispatchers.Main) {
                tvHint?.text = "Look at the camera"
                tvLabel?.text = "Enrolling..."

                // Use the camera preview via CameraX lifecycle
                // The actual enrollment happens via processFrame callback
                // For setup wizard, use a simplified approach: process frames from CameraX
            }

            btnSkip?.setOnClickListener {
                (activity as? SetupWizardActivity)?.onEnrollmentDone(true)
            }
        }
    }
}
