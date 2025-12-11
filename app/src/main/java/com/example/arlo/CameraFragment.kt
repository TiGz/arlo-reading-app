package com.example.arlo

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import com.example.arlo.databinding.FragmentCameraBinding
import com.example.arlo.ocr.OCRQueueManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFragment : Fragment() {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var viewModel: CameraViewModel

    // Capture state for new book flow
    private var captureStep = CaptureStep.COVER
    private var currentBookId: Long = -1L
    private var coverImagePath: String? = null

    // Preview state
    private var previewImagePath: String? = null
    private var previewPageNumber: Int = 0

    // OCR Queue
    private val ocrQueueManager: OCRQueueManager by lazy {
        (requireActivity().application as ArloApplication).ocrQueueManager
    }

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && !it.value)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(context, "Permission request denied", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            } else {
                startCamera()
            }
        }

    private var mode: String = MODE_NEW_BOOK
    private var bookId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            mode = it.getString(ARG_MODE, MODE_NEW_BOOK)
            bookId = it.getLong(ARG_BOOK_ID, -1L)
        }

        // Set initial capture step based on mode
        captureStep = if (mode == MODE_NEW_BOOK) CaptureStep.COVER else CaptureStep.PAGE
        currentBookId = bookId
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this)[CameraViewModel::class.java]

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        setupUI()
        updateUIForStep()

        // Speak the initial instruction
        view.postDelayed({
            speakInstruction()
        }, 500)

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun setupUI() {
        binding.imageCaptureButton.setOnClickListener { takePhoto() }

        binding.btnBack.setOnClickListener {
            stopTTS()
            parentFragmentManager.popBackStack()
        }

        binding.btnSkip.setOnClickListener {
            stopTTS()
            // Done adding pages, go to reader
            if (currentBookId != -1L) {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, UnifiedReaderFragment.newInstance(currentBookId))
                    .commit()
                // Clear back stack to library
                parentFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, UnifiedReaderFragment.newInstance(currentBookId))
                    .addToBackStack(null)
                    .commit()
            } else {
                parentFragmentManager.popBackStack()
            }
        }

        // Preview buttons
        binding.btnRetake.setOnClickListener {
            onRetakeClicked()
        }

        binding.btnProcessWithAI.setOnClickListener {
            onProcessWithAIClicked()
        }

        // Observe queue status
        observeQueueStatus()
    }

    private fun observeQueueStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ocrQueueManager.pendingPages.collect { pendingPages ->
                    val count = pendingPages.size
                    if (count > 0) {
                        binding.chipQueueStatus.visibility = View.VISIBLE
                        binding.chipQueueStatus.text = if (count == 1) {
                            "1 page processing..."
                        } else {
                            "$count pages processing..."
                        }
                    } else {
                        binding.chipQueueStatus.visibility = View.GONE
                    }
                }
            }
        }

        // Observe queue state for missing page warnings
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ocrQueueManager.queueState.collect { state ->
                    if (state is OCRQueueManager.QueueState.MissingPages) {
                        val message = "Missing page(s) detected!\n" +
                            "Expected page ${state.expectedPageNum}, but found page ${state.detectedPageNum}.\n" +
                            "You may have skipped a page."
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun updateUIForStep() {
        when (captureStep) {
            CaptureStep.COVER -> {
                binding.previewContainer.visibility = View.GONE
                binding.viewFinder.visibility = View.VISIBLE
                binding.instructionCard.visibility = View.VISIBLE
                binding.imageCaptureButton.visibility = View.VISIBLE
                binding.tvInstructionTitle.text = "Step 1"
                binding.tvInstruction.text = "Take a photo of the cover"
                binding.tvInstructionHint.text = "This will be used as your book's thumbnail"
                binding.btnSkip.visibility = View.GONE
            }
            CaptureStep.PAGE -> {
                binding.previewContainer.visibility = View.GONE
                binding.viewFinder.visibility = View.VISIBLE
                binding.instructionCard.visibility = View.VISIBLE
                binding.imageCaptureButton.visibility = View.VISIBLE
                val pageNum = if (mode == MODE_ADD_PAGES) "next" else "first"
                binding.tvInstructionTitle.text = if (mode == MODE_NEW_BOOK) "Step 2" else "Add Page"
                binding.tvInstruction.text = "Take a photo of the $pageNum page"
                binding.tvInstructionHint.text = "Position the page clearly in the frame"
                binding.btnSkip.visibility = View.VISIBLE
                binding.btnSkip.text = "Done"
            }
            CaptureStep.PREVIEW -> {
                binding.previewContainer.visibility = View.VISIBLE
                binding.viewFinder.visibility = View.GONE
                binding.instructionCard.visibility = View.GONE
                binding.imageCaptureButton.visibility = View.GONE
                binding.btnSkip.visibility = View.GONE
                // Load the preview image
                previewImagePath?.let { path ->
                    binding.ivPreview.load(File(path)) {
                        crossfade(true)
                    }
                }
            }
        }
    }

    private fun speakInstruction() {
        val tts = (requireActivity().application as ArloApplication).ttsService
        val text = binding.tvInstruction.text.toString()

        if (tts.isReady()) {
            binding.ivSpeaker.visibility = View.VISIBLE
            tts.speak(text)

            // Hide speaker after a delay (approximate speech duration)
            binding.ivSpeaker.postDelayed({
                if (_binding != null) {
                    binding.ivSpeaker.visibility = View.GONE
                }
            }, 2500)
        } else {
            // TTS not available - just show the instruction visually
            binding.ivSpeaker.visibility = View.GONE
        }
    }

    private fun stopTTS() {
        val tts = (requireActivity().application as ArloApplication).ttsService
        tts.stop()
        binding.ivSpeaker.visibility = View.GONE
    }

    private fun showLoading(message: String) {
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.tvLoadingText.text = message
        binding.imageCaptureButton.isEnabled = false
    }

    private fun hideLoading() {
        binding.loadingOverlay.visibility = View.GONE
        binding.imageCaptureButton.isEnabled = true
    }

    private fun handleOCRError(result: CameraViewModel.OCRResult.Error) {
        hideLoading()

        if (result.isApiKeyError) {
            // Show API key dialog
            Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
            (requireActivity() as? MainActivity)?.showApiKeyDialogForReentry()
        } else {
            // Show error with retry option
            Toast.makeText(requireContext(), "Error: ${result.message}\nTap capture to retry.", Toast.LENGTH_LONG).show()
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())

        val photoFile = File(requireContext().filesDir, "$name.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        showLoading(if (captureStep == CaptureStep.COVER) "Processing cover..." else "Extracting text with AI...")

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    hideLoading()
                    Toast.makeText(requireContext(), "Capture failed", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Photo capture succeeded: ${photoFile.absolutePath}")
                    processCapture(photoFile)
                }
            }
        )
    }

    private fun processCapture(photoFile: File) {
        val uri = Uri.fromFile(photoFile)

        when (captureStep) {
            CaptureStep.COVER -> processCoverCapture(photoFile, uri)
            CaptureStep.PAGE -> processPageCapture(photoFile)
            CaptureStep.PREVIEW -> { /* Do nothing - already in preview */ }
        }
    }

    private fun processCoverCapture(photoFile: File, uri: Uri) {
        // Create a smaller thumbnail for the cover
        val thumbnailFile = createThumbnail(photoFile)
        coverImagePath = thumbnailFile?.absolutePath ?: photoFile.absolutePath

        // Extract title from cover using Claude OCR
        viewModel.extractTitleFromCover(uri) { extractedTitle, result ->
            when (result) {
                is CameraViewModel.OCRResult.Success -> {
                    hideLoading()

                    // Create the book with cover
                    val title = if (extractedTitle.isNullOrBlank()) {
                        "Book ${SimpleDateFormat("MMM d", Locale.US).format(System.currentTimeMillis())}"
                    } else {
                        // Clean up extracted title - take first line, trim
                        extractedTitle.lines().firstOrNull()?.take(50)?.trim() ?: "New Book"
                    }

                    viewModel.createBookWithCover(title, coverImagePath!!) { newBookId ->
                        currentBookId = newBookId

                        // Move to page capture
                        captureStep = CaptureStep.PAGE
                        updateUIForStep()
                        speakInstruction()
                    }
                }
                is CameraViewModel.OCRResult.Error -> {
                    handleOCRError(result)
                }
            }
        }
    }

    private fun processPageCapture(photoFile: File) {
        if (currentBookId == -1L) {
            hideLoading()
            Toast.makeText(requireContext(), "Error: No book selected", Toast.LENGTH_SHORT).show()
            return
        }

        hideLoading()

        // Store preview image path and show preview
        previewImagePath = photoFile.absolutePath

        // Get next page number
        viewLifecycleOwner.lifecycleScope.launch {
            previewPageNumber = (requireActivity().application as ArloApplication)
                .repository.getNextPageNumber(currentBookId)

            captureStep = CaptureStep.PREVIEW
            updateUIForStep()
        }
    }

    private fun onRetakeClicked() {
        // Delete the preview image
        previewImagePath?.let { path ->
            File(path).delete()
        }
        previewImagePath = null
        previewPageNumber = 0

        // Return to camera
        captureStep = CaptureStep.PAGE
        updateUIForStep()
    }

    private fun onProcessWithAIClicked() {
        val imagePath = previewImagePath ?: return
        val pageNumber = previewPageNumber

        // Queue page for background processing
        viewLifecycleOwner.lifecycleScope.launch {
            ocrQueueManager.queuePage(currentBookId, imagePath, pageNumber)
            Toast.makeText(requireContext(), "Page queued for processing", Toast.LENGTH_SHORT).show()

            // Clear preview state
            previewImagePath = null
            previewPageNumber = 0

            // Return to camera for next page
            captureStep = CaptureStep.PAGE
            updateUIForStep()

            // Update instruction for next page
            binding.tvInstructionTitle.text = "Continue"
            binding.tvInstruction.text = "Take a photo of the next page"
        }
    }

    private fun createThumbnail(original: File): File? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(original.absolutePath, options)

            // Calculate sample size for ~400px width
            val targetWidth = 400
            val scale = options.outWidth / targetWidth
            options.inSampleSize = if (scale > 1) scale else 1
            options.inJustDecodeBounds = false

            val bitmap = BitmapFactory.decodeFile(original.absolutePath, options)

            val thumbnailFile = File(requireContext().filesDir, "thumb_${original.name}")
            FileOutputStream(thumbnailFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            bitmap.recycle()

            thumbnailFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create thumbnail", e)
            null
        }
    }

    companion object {
        private const val TAG = "CameraFragment"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        const val ARG_MODE = "mode"
        const val ARG_BOOK_ID = "book_id"
        const val MODE_NEW_BOOK = "new_book"
        const val MODE_ADD_PAGES = "add_pages"

        fun newInstance(mode: String, bookId: Long = -1L) =
            CameraFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, mode)
                    putLong(ARG_BOOK_ID, bookId)
                }
            }
    }

    enum class CaptureStep {
        COVER,
        PAGE,
        PREVIEW  // Show captured image before processing
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopTTS()
        _binding = null
        cameraExecutor.shutdown()
    }
}
