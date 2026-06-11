package com.family.hippomuncher

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var drawer: DrawerLayout
    private lateinit var gameView: GameSurfaceView
    private lateinit var pipPreview: PreviewView
    private lateinit var permissionOverlay: LinearLayout

    private lateinit var btnEasy: Button
    private lateinit var btnMedium: Button
    private lateinit var btnHard: Button

    private lateinit var cameraExecutor: ExecutorService
    private var analyzer: FaceTrackerAnalyzer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraStarted = false

    private val prefs by lazy { getSharedPreferences("hippo", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawer = findViewById(R.id.drawerLayout)
        gameView = findViewById(R.id.gameSurface)
        pipPreview = findViewById(R.id.pipPreview)
        permissionOverlay = findViewById(R.id.permissionOverlay)

        btnEasy = findViewById(R.id.btnEasy)
        btnMedium = findViewById(R.id.btnMedium)
        btnHard = findViewById(R.id.btnHard)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // ---- High score persistence per difficulty ----
        val savedDiffStr = prefs.getString("difficulty", GameSurfaceView.Difficulty.MEDIUM.name) ?: GameSurfaceView.Difficulty.MEDIUM.name
        val initialDiff = try {
            GameSurfaceView.Difficulty.valueOf(savedDiffStr)
        } catch (e: Exception) {
            GameSurfaceView.Difficulty.MEDIUM
        }

        btnEasy.setOnClickListener { updateDifficulty(GameSurfaceView.Difficulty.EASY) }
        btnMedium.setOnClickListener { updateDifficulty(GameSurfaceView.Difficulty.MEDIUM) }
        btnHard.setOnClickListener { updateDifficulty(GameSurfaceView.Difficulty.HARD) }

        updateDifficulty(initialDiff)

        gameView.onNewHighScore = { hs ->
            val key = getHighScoreKey(gameView.difficulty)
            prefs.edit().putInt(key, hs).apply()
        }

        wireDrawer()

        findViewById<Button>(R.id.btnGrantCamera).setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    // ======================== Drawer & menu ========================
    private fun wireDrawer() {
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener {
            drawer.openDrawer(GravityCompat.START)
        }

        // Opening the drawer must instantly pause the game loop's action;
        // closing it resumes.
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) = gameView.pauseGame()
            override fun onDrawerClosed(drawerView: View) = gameView.resumeGame()
        })

        findViewById<Button>(R.id.btnPlay).setOnClickListener {
            gameView.startCalibration()
            drawer.closeDrawer(GravityCompat.START)
        }
        findViewById<Button>(R.id.btnRecalibrate).setOnClickListener {
            gameView.startCalibration()
            drawer.closeDrawer(GravityCompat.START)
        }
        findViewById<Button>(R.id.btnResetScore).setOnClickListener {
            gameView.resetHighScore()
            val key = getHighScoreKey(gameView.difficulty)
            prefs.edit().putInt(key, 0).apply()
            drawer.closeDrawer(GravityCompat.START)
        }
        findViewById<Button>(R.id.btnQuit).setOnClickListener {
            finish()
        }
    }

    private fun getHighScoreKey(diff: GameSurfaceView.Difficulty): String = when (diff) {
        GameSurfaceView.Difficulty.EASY -> "high_score_easy"
        GameSurfaceView.Difficulty.MEDIUM -> "high_score_medium"
        GameSurfaceView.Difficulty.HARD -> "high_score_hard"
    }

    private fun updateDifficulty(diff: GameSurfaceView.Difficulty) {
        gameView.difficulty = diff
        prefs.edit().putString("difficulty", diff.name).apply()

        val lime = ContextCompat.getColor(this, R.color.neon_lime)
        val cyan = ContextCompat.getColor(this, R.color.neon_cyan)
        val red = ContextCompat.getColor(this, R.color.neon_red)
        val dim = Color.parseColor("#44FFFFFF")

        btnEasy.backgroundTintList = ColorStateList.valueOf(if (diff == GameSurfaceView.Difficulty.EASY) lime else dim)
        btnEasy.setTextColor(if (diff == GameSurfaceView.Difficulty.EASY) Color.parseColor("#14122B") else Color.WHITE)

        btnMedium.backgroundTintList = ColorStateList.valueOf(if (diff == GameSurfaceView.Difficulty.MEDIUM) cyan else dim)
        btnMedium.setTextColor(if (diff == GameSurfaceView.Difficulty.MEDIUM) Color.parseColor("#14122B") else Color.WHITE)

        btnHard.backgroundTintList = ColorStateList.valueOf(if (diff == GameSurfaceView.Difficulty.HARD) red else dim)
        btnHard.setTextColor(if (diff == GameSurfaceView.Difficulty.HARD) Color.parseColor("#14122B") else Color.WHITE)

        // Load the high score for this difficulty
        val key = getHighScoreKey(diff)
        gameView.highScore = prefs.getInt(key, 0)
    }

    // ======================== Permissions ========================
    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && hasCameraPermission()) {
            permissionOverlay.visibility = View.GONE
            startCamera()
        } else if (requestCode == REQ_CAMERA) {
            permissionOverlay.visibility = View.VISIBLE
        }
    }

    // ======================== CameraX pipeline ========================
    private fun startCamera() {
        if (cameraStarted) return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            // Low-res analysis stream: plenty for face boxes, light enough
            // for the Portal's SoC to sustain ≥30 fps into ML Kit.
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val faceAnalyzer = FaceTrackerAnalyzer { frame ->
                gameView.onFaceFrame(frame)
            }
            analyzer = faceAnalyzer
            analysis.setAnalyzer(cameraExecutor, faceAnalyzer)

            // Tiny PiP preview for positioning feedback.
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(pipPreview.surfaceProvider)
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )
            cameraStarted = true
        }, ContextCompat.getMainExecutor(this))
    }

    // ======================== Lifecycle ========================
    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) {
            permissionOverlay.visibility = View.GONE
            // CameraX is lifecycle-aware and resumes the stream itself;
            // we just (re)build the pipeline on first grant / first resume.
            startCamera()
            gameView.resumeGame()
        } else {
            permissionOverlay.visibility = View.VISIBLE
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onPause() {
        // CameraX automatically stops the stream when the lifecycle pauses.
        // We additionally freeze gameplay so nothing moves while invisible.
        gameView.pauseGame()
        super.onPause()
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        analyzer?.shutdown()           // release the ML Kit detector
        cameraExecutor.shutdown()
        gameView.sound.release()
        super.onDestroy()
    }

    private companion object {
        const val REQ_CAMERA = 1001
        const val KEY_HIGH_SCORE = "high_score"
    }
}
