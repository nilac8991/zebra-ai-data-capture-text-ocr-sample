package com.example.zebra.zaidcsdktextocrsample.core.camera

import android.content.Context
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import com.example.zebra.zaidcsdktextocrsample.data.await
import com.zebra.ai.vision.analyzer.tracking.EntityTrackerAnalyzer
import com.zebra.ai.vision.detector.TextOCR
import com.zebra.ai.vision.entity.ParagraphEntity
import com.zebra.ai.vision.viewfinder.EntityViewController
import com.zebra.ai.vision.viewfinder.listners.EntityViewResizeListener
import com.zebra.ai.vision.viewfinder.listners.EntityViewResizeSpecs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.concurrent.Executor

/**
 * CameraX + Zebra AI Suite EntityTrackerAnalyzer session for TextOCR operations.
 *
 * Responsibilities:
 * - Bind a CameraX [Preview] to Zebra's [EntityViewController] (EntityViewfinder surface).
 * - Bind a CameraX [ImageAnalysis] pipeline that forwards frames to [EntityTrackerAnalyzer].
 * - Emit OCR results (as [ParagraphEntity] list) via [onParagraphs].
 *
 * Coordinate mapping (VIEW_REFERENCED):
 * - The analyzer runs in [ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED], so it must be kept in sync
 *   with the current sensor->view transform.
 * - We register an [EntityViewResizeListener] on the [EntityViewController] and, whenever the viewfinder
 *   resizes or rotates, we:
 *   1) call [EntityTrackerAnalyzer.updateTransform] with [EntityViewResizeSpecs.sensorToViewMatrix]
 *   2) apply the default viewfinder FOV crop via [EntityTrackerAnalyzer.setCropRect] (when available)
 *
 * Pause/resume:
 * - [pauseAnalysis] detaches the ImageAnalysis analyzer (preview remains running).
 * - [resumeAnalysis] re-attaches the analyzer using the cached [EntityTrackerAnalyzer].
 *
 * Lifecycle:
 * - Call [start] once to bind the camera and begin emitting OCR results.
 * - Call [pauseAnalysis] / [resumeAnalysis] to control OCR processing without unbinding the camera.
 * - Call [close] to unbind CameraX. Executors are owned by the caller.
 */
class CameraXTrackerSession(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val entityViewController: EntityViewController,
    private val cameraExecutor: Executor,
    private val entityExecutor: Executor,
) : Closeable {

    /** Prevents binding multiple times. */
    private var started = false

    /** Cached provider to allow unbinding on [close]. */
    private var provider: ProcessCameraProvider? = null

    /** Cached analysis use-case so we can pause/resume by attaching/detaching the analyzer. */
    private var analysis: ImageAnalysis? = null

    /** Cached tracker so we can re-attach the analyzer on resume. */
    private var tracker: EntityTrackerAnalyzer? = null

    /** True when OCR processing is paused (preview remains active). */
    private var paused = false

    /**
     * Starts the CameraX + EntityTrackerAnalyzer pipeline.
     *
     * What happens:
     * 1) Creates an [EntityTrackerAnalyzer] configured for view-referenced coordinates.
     * 2) Registers a viewfinder resize listener to keep the analyzer transform/crop updated.
     *    (Without this mapping, analysis may be skipped and no OCR will be produced.)
     * 3) Obtains a [ProcessCameraProvider] (async via coroutine), then binds:
     *    - CameraX [Preview] using [entityViewController.surfaceProvider]
     *    - CameraX [ImageAnalysis] delegating frames to [EntityTrackerAnalyzer]
     * 4) For every analyzer callback, filters the results to [ParagraphEntity] and emits them.
     *
     * @param scope Coroutine scope used to await CameraProvider without blocking the UI.
     * @param ocr Zebra [TextOCR] model instance already created/initialized elsewhere.
     * @param onParagraphs Callback invoked with OCR paragraphs for each analysis result.
     * @param onError Callback invoked if binding or initialization fails.
     */
    fun start(
        scope: CoroutineScope,
        ocr: TextOCR,
        onParagraphs: (List<ParagraphEntity>) -> Unit,
        onError: (Throwable) -> Unit = {}
    ) {
        if (started) return
        started = true

        // Analyzer that runs detection/recognition/tracking and returns Entities.
        tracker = EntityTrackerAnalyzer(
            listOf(ocr),
            ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
            entityExecutor
        ) { result ->
            // TextOCR results are returned as a generic Entity list -> filter to ParagraphEntity.
            // Note: when paused, analyzer is detached; this callback should effectively stop firing.
            val paragraphs = result.getValue(ocr).orEmpty().filterIsInstance<ParagraphEntity>()
            onParagraphs(paragraphs)
        }

        val t = tracker ?: run {
            started = false
            onError(IllegalStateException("Tracker creation failed"))
            return
        }

        entityViewController.registerViewfinderResizeListener(object : EntityViewResizeListener {
            override fun onViewfinderResized(specs: EntityViewResizeSpecs) {
                runCatching {
                    // 1) Update the analyzer transform (sensor -> view)
                    val s2v = specs.sensorToViewMatrix
                    t.updateTransform(s2v)
                }.onFailure {
                    Log.e(TAG, "Mapping update failed: ${it.message}", it)
                }
            }
        })

        scope.launch {
            try {
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = entityViewController.surfaceProvider
                }

                provider = ProcessCameraProvider.getInstance(context).await()
                analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { ia ->
                        if (!paused) {
                            ia.setAnalyzer(cameraExecutor) { image ->
                                t.analyze(image)
                            }
                        }
                    }

                val camera = provider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )

                // Allows viewfinder features (e.g., coordinate transforms / interactions) to work.
                entityViewController.setCameraController(camera)

                Log.d(TAG, "Camera bound successfully")
            } catch (e: Throwable) {
                started = false
                Log.e(TAG, "Camera bind failed: ${e.message}", e)
                onError(e)
            }
        }
    }

    /**
     * Pauses OCR analysis while keeping the camera preview active.
     *
     * Implementation details:
     * - Detaches the [ImageAnalysis] analyzer via [ImageAnalysis.clearAnalyzer].
     * - This stops feeding frames to [EntityTrackerAnalyzer], reducing CPU/DSP/GPU usage.
     * - Camera preview continues to run since [Preview] remains bound.
     */
    fun pauseAnalysis() {
        if (paused) return
        paused = true
        analysis?.clearAnalyzer()
        Log.d(TAG, "Analysis paused")
    }

    /**
     * Resumes OCR analysis after a previous [pauseAnalysis].
     *
     * Implementation details:
     * - Re-attaches the analyzer to the existing [ImageAnalysis] use-case.
     * - Uses the cached [EntityTrackerAnalyzer] to continue processing frames.
     */
    fun resumeAnalysis() {
        if (!paused) return
        paused = false

        val ia = analysis ?: return
        val t = tracker ?: return

        ia.setAnalyzer(cameraExecutor) { image ->
            t.analyze(image)
        }

        Log.d(TAG, "Analysis resumed")
    }

    /** Returns true if OCR processing is currently paused. */
    fun isPaused(): Boolean = paused

    /**
     * Stops the session by unbinding all CameraX use-cases.
     * Note: this class does not own executors; the caller should manage executor shutdown.
     */
    override fun close() {
        runCatching { analysis?.clearAnalyzer() }
        runCatching { provider?.unbindAll() }

        analysis = null
        tracker = null
        provider = null

        paused = false
        started = false
    }

    companion object {
        const val TAG = "CameraXTrackerSession"
    }
}