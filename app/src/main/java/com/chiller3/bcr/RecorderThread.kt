package com.chiller3.bcr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Int64Ref
import android.system.Os
import android.system.OsConstants
import android.telecom.Call
import android.telecom.PhoneAccount
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.net.toFile
import androidx.documentfile.provider.DocumentFile
import com.chiller3.bcr.format.Encoder
import com.chiller3.bcr.format.Format
import com.chiller3.bcr.format.SampleRate
import java.io.IOException
import java.lang.Process
import java.nio.ByteBuffer
import java.text.ParsePosition
import java.time.*
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.SignStyle
import java.time.temporal.ChronoField
import java.time.temporal.Temporal
import android.os.Process as AndroidProcess

/**
 * Captures call audio and encodes it into an output file in the user's selected directory or the
 * fallback/default directory.
 *
 * @constructor Create a thread for recording a call. Note that the system only has a single
 * [MediaRecorder.AudioSource.VOICE_CALL] stream. If multiple calls are being recorded, the recorded
 * audio for each call may not be as expected.
 * @param context Used for querying shared preferences and accessing files via SAF. A reference is
 * kept in the object.
 * @param listener Used for sending completion notifications. The listener is called from this
 * thread, not the main thread.
 * @param call Used only for determining the output filename and is not saved.
 */
class RecorderThread(
    private val context: Context,
    private val listener: OnRecordingCompletedListener,
    call: Call,
) : Thread(RecorderThread::class.java.simpleName) {
    private val tag = "${RecorderThread::class.java.simpleName}/${id}"
    private val prefs = Preferences(context)
    private val isDebug = BuildConfig.DEBUG || prefs.isDebugMode

    // Thread state
    @Volatile private var isCancelled = false
    private var captureFailed = false

    // Timestamp
    private lateinit var callTimestamp: ZonedDateTime
    private var formatter = FORMATTER

    // Filename
    private val filenameLock = Object()
    private var pendingCallDetails: Call.Details? = null
    private lateinit var filenameTemplate: FilenameTemplate
    private lateinit var filename: String
    private val redactions = HashMap<String, String>()

    // Format
    private val format: Format
    private val formatParam: UInt?
    private val sampleRate = SampleRate.fromPreferences(prefs)

    // Logging
    private lateinit var logcatFile: DocumentFile
    private lateinit var logcatProcess: Process

    init {
        Log.i(tag, "Created thread for call: $call")

        onCallDetailsChanged(call.details)

        val savedFormat = Format.fromPreferences(prefs)
        format = savedFormat.first
        formatParam = savedFormat.second
    }

    private fun redact(msg: String): String {
        synchronized(filenameLock) {
            var result = msg

            for ((source, target) in redactions) {
                result = result.replace(source, target)
            }

            return result
        }
    }

    private fun redact(uri: Uri): String = redact(Uri.decode(uri.toString()))

    /**
     * Update [filename] with information from [details].
     *
     * This function holds a lock on [filenameLock] until it returns.
     */
    fun onCallDetailsChanged(details: Call.Details) {
        synchronized(filenameLock) {
            if (!this::filenameTemplate.isInitialized) {
                // Thread hasn't started yet, so we haven't loaded the filename template
                pendingCallDetails = details
                return
            }

            redactions.clear()

            filename = filenameTemplate.evaluate {
                when {
                    it == "date" || it.startsWith("date:") -> {
                        val instant = Instant.ofEpochMilli(details.creationTimeMillis)
                        callTimestamp = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault())

                        val colon = it.indexOf(":")
                        if (colon >= 0) {
                            val pattern = it.substring(colon + 1)
                            Log.d(tag, "Using custom datetime pattern: $pattern")

                            try {
                                formatter = DateTimeFormatterBuilder()
                                    .appendPattern(pattern)
                                    .toFormatter()
                            } catch (e: Exception) {
                                Log.w(tag, "Invalid custom datetime pattern: $pattern; using default", e)
                            }
                        }

                        return@evaluate formatter.format(callTimestamp)
                    }
                    it == "direction" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            when (details.callDirection) {
                                Call.Details.DIRECTION_INCOMING -> return@evaluate "in"
                                Call.Details.DIRECTION_OUTGOING -> return@evaluate "out"
                                Call.Details.DIRECTION_UNKNOWN -> {}
                            }
                        }
                    }
                    it == "sim_slot" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            && context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                            == PackageManager.PERMISSION_GRANTED
                            && context.packageManager.hasSystemFeature(
                                PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION)) {
                            val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)

                            // Only append SIM slot ID if the device has multiple active SIMs
                            if (subscriptionManager.activeSubscriptionInfoCount > 1) {
                                val telephonyManager = context.getSystemService(TelephonyManager::class.java)
                                val subscriptionId = telephonyManager.getSubscriptionId(details.accountHandle)
                                val subscriptionInfo = subscriptionManager.getActiveSubscriptionInfo(subscriptionId)

                                return@evaluate "${subscriptionInfo.simSlotIndex + 1}"
                            }
                        }
                    }
                    it == "phone_number" -> {
                        if (details.handle?.scheme == PhoneAccount.SCHEME_TEL) {
                            redactions[details.handle.schemeSpecificPart] = "<phone number>"

                            return@evaluate details.handle.schemeSpecificPart
                        }
                    }
                    it == "caller_name" -> {
                        val callerName = details.callerDisplayName?.trim()
                        if (!callerName.isNullOrBlank()) {
                            redactions[callerName] = "<caller name>"

                            return@evaluate callerName
                        }
                    }
                    it == "contact_name" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val contactName = details.contactDisplayName?.trim()
                            if (!contactName.isNullOrBlank()) {
                                redactions[contactName] = "<contact name>"

                                return@evaluate contactName
                            }
                        }
                    }
                    else -> {
                        Log.w(tag, "Unknown filename template variable: $it")
                    }
                }

                null
            }
            // AOSP's SAF automatically replaces invalid characters with underscores, but just in
            // case an OEM fork breaks that, do the replacement ourselves to prevent directory
            // traversal attacks.
                .replace('/', '_').trim()

            Log.i(tag, "Updated filename due to call details change: ${redact(filename)}")
        }
    }

    override fun run() {
        var success = false
        var errorMsg: String? = null
        var resultUri: Uri? = null

        synchronized(filenameLock) {
            filenameTemplate = FilenameTemplate.load(context)

            onCallDetailsChanged(pendingCallDetails!!)
            pendingCallDetails = null
        }

        startLogcat()

        try {
            Log.i(tag, "Recording thread started")

            if (isCancelled) {
                Log.i(tag, "Recording cancelled before it began")
            } else {
                val initialFilename = synchronized(filenameLock) { filename }
                val outputFile = createFileInDefaultDir(initialFilename, format.mimeTypeContainer)
                resultUri = outputFile.uri

                try {
                    openFile(outputFile, true).use {
                        recordUntilCancelled(it)
                        Os.fsync(it.fileDescriptor)
                    }
                } finally {
                    val finalFilename = synchronized(filenameLock) { filename }
                    if (finalFilename != initialFilename) {
                        Log.i(tag, "Renaming ${redact(initialFilename)} to ${redact(finalFilename)}")

                        if (outputFile.renameTo(finalFilename)) {
                            resultUri = outputFile.uri
                        } else {
                            Log.w(tag, "Failed to rename to final filename: ${redact(finalFilename)}")
                        }
                    }

                    tryMoveToUserDir(outputFile)?.let {
                        resultUri = it.uri
                    }

                    processRetention()
                }

                success = !captureFailed
            }
        } catch (e: Exception) {
            Log.e(tag, "Error during recording", e)
            errorMsg = e.localizedMessage
        } finally {
            Log.i(tag, "Recording thread completed")

            try {
                stopLogcat()
            } catch (e: Exception) {
                Log.w(tag, "Failed to dump logcat", e)
            }

            val outputFile = resultUri?.let { OutputFile(it, redact(it), format.mimeTypeContainer) }

            if (success) {
                listener.onRecordingCompleted(this, outputFile!!)
            } else {
                listener.onRecordingFailed(this, errorMsg, outputFile)
            }
        }
    }

    /**
     * Cancel current recording. This stops capturing audio after processing the next minimum buffer
     * size, but the thread does not exit until all data encoded so far has been written to the
     * output file.
     *
     * If called before [start], the thread will not record any audio not create an output file. In
     * this scenario, [OnRecordingCompletedListener.onRecordingFailed] will be called with a null
     * [Uri].
     */
    fun cancel() {
        Log.d(tag, "Requested cancellation")
        isCancelled = true
    }

    private fun startLogcat() {
        if (!isDebug) {
            return
        }

        Log.d(tag, "Starting log file (${BuildConfig.VERSION_NAME})")

        val logFilename = synchronized(filenameLock) { "${filename}.log" }

        logcatFile = createFileInDefaultDir(logFilename, "text/plain")
        logcatProcess = ProcessBuilder("logcat", "*:V")
            // This is better than -f because the logcat implementation calls fflush() when the
            // output stream is stdout. logcatFile is guaranteed to have file:// scheme because it's
            // created in the default output directory.
            .redirectOutput(logcatFile.uri.toFile())
            .redirectErrorStream(true)
            .start()
    }

    private fun stopLogcat() {
        if (!isDebug) {
            return
        }

        try {
            try {
                Log.d(tag, "Stopping log file")

                // Give logcat a bit of time to flush the output. It does not have any special
                // handling to flush buffers when interrupted.
                sleep(1000)

                logcatProcess.destroy()
            } finally {
                logcatProcess.waitFor()
            }
        } finally {
            tryMoveToUserDir(logcatFile)
        }
    }

    private fun timestampFromFilename(name: String): Temporal? {
        try {
            val redacted = redactTruncate(name)

            // The date is guaranteed to be at the beginning of the filename. Try to parse it,
            // ignoring unparsed text at the end.
            val pos = ParsePosition(0)
            val parsed = formatter.parse(name, pos)

            val timestamp = try {
                parsed.query(ZonedDateTime::from)
            } catch (e: DateTimeException) {
                // A custom pattern might not specify the time zone
                parsed.query(LocalDateTime::from)
            }

            Log.d(tag, "Parsed $timestamp from $redacted; length=${name.length}; parsed=${pos.index}")

            return timestamp
        } catch (e: DateTimeParseException) {
            // Ignore
        }

        return null
    }

    /**
     * Delete files older than the specified retention period.
     *
     * The "current time" is [callTimestamp], not the actual current time and the timestamp of past
     * recordings is based on the filename, not the file modification time. Incorrectly-named files
     * are ignored.
     */
    private fun processRetention() {
        val directory = prefs.outputDir?.let {
            // Only returns null on API <21
            DocumentFile.fromTreeUri(context, it)!!
        } ?: DocumentFile.fromFile(prefs.defaultOutputDir)

        val retention = when (val r = Retention.fromPreferences(prefs)) {
            NoRetention -> {
                Log.i(tag, "Keeping all existing files")
                return
            }
            is DaysRetention -> r.toDuration()
        }
        Log.i(tag, "Retention period is $retention")

        for (item in directory.listFiles()) {
            val filename = item.name ?: continue
            val redacted = redactTruncate(filename)

            val timestamp = timestampFromFilename(filename)
            if (timestamp == null) {
                Log.w(tag, "Ignoring unrecognized filename: $redacted")
                continue
            }

            val diff = Duration.between(timestamp, callTimestamp)

            if (diff > retention) {
                Log.i(tag, "Deleting $redacted ($timestamp)")
                if (!item.delete()) {
                    Log.w(tag, "Failed to delete: $redacted")
                }
            }
        }
    }

    /**
     * Try to move [sourceFile] to the user output directory.
     *
     * @return Whether the user output directory is set and the file was successfully moved
     */
    private fun tryMoveToUserDir(sourceFile: DocumentFile): DocumentFile? {
        val userDir = prefs.outputDir?.let {
            // Only returns null on API <21
            DocumentFile.fromTreeUri(context, it)!!
        } ?: return null

        val redactedSource = redact(sourceFile.uri)

        return try {
            val targetFile = moveFileToDir(sourceFile, userDir)
            val redactedTarget = redact(targetFile.uri)

            Log.i(tag, "Successfully moved $redactedSource to $redactedTarget")
            sourceFile.delete()

            targetFile
        } catch (e: Exception) {
            Log.e(tag, "Failed to move $redactedSource to $userDir", e)
            null
        }
    }

    /**
     * Move [sourceFile] to [targetDir].
     *
     * @return The [DocumentFile] for the newly moved file.
     */
    private fun moveFileToDir(sourceFile: DocumentFile, targetDir: DocumentFile): DocumentFile {
        val targetFile = createFileInDir(targetDir, sourceFile.name!!, sourceFile.type!!)

        try {
            openFile(sourceFile, false).use { sourcePfd ->
                openFile(targetFile, true).use { targetPfd ->
                    var remain = Os.lseek(sourcePfd.fileDescriptor, 0, OsConstants.SEEK_END)
                    val offset = Int64Ref(0)

                    while (remain > 0) {
                        val ret = Os.sendfile(
                            targetPfd.fileDescriptor, sourcePfd.fileDescriptor, offset, remain)
                        if (ret == 0L) {
                            throw IOException("Unexpected EOF in sendfile()")
                        }

                        remain -= ret
                    }

                    Os.fsync(targetPfd.fileDescriptor)
                }
            }

            sourceFile.delete()
            return targetFile
        } catch (e: Exception) {
            targetFile.delete()
            throw e
        }
    }

    /**
     * Create [name] in the default output directory.
     *
     * @param name Should not contain a file extension
     * @param mimeType Determines the file extension
     *
     * @throws IOException if the file could not be created in the default directory
     */
    private fun createFileInDefaultDir(name: String, mimeType: String): DocumentFile {
        val defaultDir = DocumentFile.fromFile(prefs.defaultOutputDir)
        return createFileInDir(defaultDir, name, mimeType)
    }

    /**
     * Create a new file with name [name] inside [dir].
     *
     * @param name Should not contain a file extension
     * @param mimeType Determines the file extension
     *
     * @throws IOException if file creation fails
     */
    private fun createFileInDir(dir: DocumentFile, name: String, mimeType: String): DocumentFile {
        Log.d(tag, "Creating ${redact(name)} with MIME type $mimeType in ${dir.uri}")

        return dir.createFile(mimeType, name)
            ?: throw IOException("Failed to create file in ${dir.uri}")
    }

    /**
     * Open seekable file descriptor to [file].
     *
     * @throws IOException if [file] cannot be opened
     */
    private fun openFile(file: DocumentFile, truncate: Boolean): ParcelFileDescriptor {
        val truncParam = if (truncate) { "t" } else { "" }
        return context.contentResolver.openFileDescriptor(file.uri, "rw$truncParam")
            ?: throw IOException("Failed to open file at ${file.uri}")
    }

    /**
     * Record from [MediaRecorder.AudioSource.VOICE_CALL] until [cancel] is called or an audio
     * capture or encoding error occurs.
     *
     * [pfd] does not get closed by this method.
     */
    private fun recordUntilCancelled(pfd: ParcelFileDescriptor) {
        AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)

        val (audioRecord, bufSize) = createAudioRecord()

        Log.d(tag, "AudioRecord format: ${audioRecord.format}")

        // Where's my RAII? :(
        try {
            audioRecord.startRecording()

            try {
                val container = format.getContainer(pfd.fileDescriptor)

                try {
                    // audioRecord.format has the detected native sample rate
                    val mediaFormat = format.getMediaFormat(audioRecord.format, formatParam)
                    val encoder = format.getEncoder(mediaFormat, container)

                    try {
                        encoder.start()

                        try {
                            encodeLoop(audioRecord, encoder, bufSize)
                        } finally {
                            encoder.stop()
                        }
                    } finally {
                        encoder.release()
                    }
                } finally {
                    container.release()
                }
            } finally {
                audioRecord.stop()
            }
        } finally {
            audioRecord.release()
        }
    }

    /**
     * Create an [AudioRecord] instance for capturing the raw audio.
     *
     * This attempts to create an [AudioRecord] instance with a buffer size at 6 times the minimum
     * buffer size for the given sample rate. If initialization fails, this will internally retry
     * for [AR_INIT_ATTEMPTS] attempts with a [AR_INIT_RETRY_DELAY_MS] delay in between.
     *
     * @return The [AudioRecord] instance and the size of the buffer for [AudioRecord.read] calls
     *
     * @throws Exception if the [AudioRecord] initialization fails every attempt
     */
    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): Pair<AudioRecord, Int> {
        val minBufSize = AudioRecord.getMinBufferSize(
            sampleRate.value.toInt(), CHANNEL_CONFIG, ENCODING)
        if (minBufSize < 0) {
            throw Exception("Failure to query minimum buffer size: $minBufSize")
        }
        Log.d(tag, "AudioRecord minimum buffer size: $minBufSize")

        // The last thrown exception is reported to the caller if every initialization attempt fails
        lateinit var lastException: Exception

        for (attempt in 1..AR_INIT_ATTEMPTS) {
            try {
                Log.d(tag, "[Attempt $attempt/$AR_INIT_ATTEMPTS] Initializing AudioRecord")

                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_CALL,
                    sampleRate.value.toInt(),
                    CHANNEL_CONFIG,
                    ENCODING,
                    // On some devices, MediaCodec occasionally has sudden spikes in processing
                    // time, so use a larger internal buffer if possible to reduce the chance of
                    // overrun on the recording side.
                    minBufSize * 6,
                )
                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    throw Exception("AudioRecord not initialized")
                }

                val initialBufSize = audioRecord.bufferSizeInFrames *
                        audioRecord.format.frameSizeInBytesCompat
                Log.d(tag, "AudioRecord initial buffer size: $initialBufSize")

                return Pair(audioRecord, minBufSize)
            } catch (e: Exception) {
                Log.w(tag, "Failed to create AudioRecord", e)
                lastException = e

                if (attempt != AR_INIT_ATTEMPTS) {
                    sleep(AR_INIT_RETRY_DELAY_MS)
                }

                continue
            }
        }

        // Guaranteed to be initialized by this point
        throw lastException
    }

    /**
     * Main loop for encoding captured raw audio into an output file.
     *
     * The loop runs forever until [cancel] is called. At that point, no further data will be read
     * from [audioRecord] and the remaining output data from [encoder] will be written to the output
     * file. If [audioRecord] fails to capture data, the loop will behave as if [cancel] was called
     * (ie. abort, but ensuring that the output file is valid).
     *
     * The approximate amount of time to cancel reading from the audio source is the time it takes
     * to process the minimum buffer size. Additionally, additional time is needed to write out the
     * remaining encoded data to the output file.
     *
     * @param audioRecord [AudioRecord.startRecording] must have been called
     * @param encoder [Encoder.start] must have been called
     * @param bufSize Buffer size for each [AudioRecord.read] operation
     *
     * @throws Exception if the audio recorder or encoder encounters an error
     */
    private fun encodeLoop(audioRecord: AudioRecord, encoder: Encoder, bufSize: Int) {
        var numFrames = 0L
        val frameSize = audioRecord.format.frameSizeInBytesCompat

        // Use a slightly larger buffer to reduce the chance of problems under load
        val buffer = ByteBuffer.allocateDirect(bufSize)
        val bufferFrames = buffer.capacity().toLong() / frameSize
        val bufferNs = bufferFrames * 1_000_000_000L / audioRecord.sampleRate
        Log.d(tag, "Buffer is ${buffer.capacity()} bytes, $bufferFrames frames, ${bufferNs}ns")

        while (!isCancelled) {
            val begin = System.nanoTime()
            // We do a non-blocking read because on Samsung devices, when the call ends, the audio
            // device immediately stops producing data and blocks forever until the next call is
            // active.
            val n = audioRecord.read(buffer, buffer.remaining(), AudioRecord.READ_NON_BLOCKING)
            val recordElapsed = System.nanoTime() - begin
            var encodeElapsed = 0L

            if (n < 0) {
                Log.e(tag, "Error when reading samples from $audioRecord: $n")
                isCancelled = true
                captureFailed = true
            } else if (n == 0) {
                // Wait for the wall clock equivalent of half the buffer size
                sleep(bufferNs / 1_000_000L / 2)
                continue
            } else {
                buffer.limit(n)

                val encodeBegin = System.nanoTime()
                encoder.encode(buffer, false)
                encodeElapsed = System.nanoTime() - encodeBegin

                buffer.clear()

                numFrames += n / frameSize
            }

            val totalElapsed = System.nanoTime() - begin
            if (encodeElapsed > bufferNs) {
                Log.w(tag, "${encoder.javaClass.simpleName} took too long: " +
                        "timestamp=${numFrames.toDouble() / audioRecord.sampleRate}s, " +
                        "buffer=${bufferNs / 1_000_000.0}ms, " +
                        "total=${totalElapsed / 1_000_000.0}ms, " +
                        "record=${recordElapsed / 1_000_000.0}ms, " +
                        "encode=${encodeElapsed / 1_000_000.0}ms")
            }
        }

        // Signal EOF with empty buffer
        Log.d(tag, "Sending EOF to encoder")
        buffer.limit(buffer.position())
        encoder.encode(buffer, true)

        val durationSecs = numFrames.toDouble() / audioRecord.sampleRate
        Log.d(tag, "Input complete after ${"%.1f".format(durationSecs)}s")
    }

    companion object {
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        private const val AR_INIT_ATTEMPTS = 5
        private const val AR_INIT_RETRY_DELAY_MS = 200L

        // Eg. 20220429_180249.123-0400
        private val FORMATTER = DateTimeFormatterBuilder()
            .appendValue(ChronoField.YEAR, 4, 10, SignStyle.EXCEEDS_PAD)
            .appendValue(ChronoField.MONTH_OF_YEAR, 2)
            .appendValue(ChronoField.DAY_OF_MONTH, 2)
            .appendLiteral('_')
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .appendOffset("+HHMMss", "+0000")
            .toFormatter()

        private fun redactTruncate(msg: String): String = buildString {
            val n = 2

            if (msg.length > 2 * n) {
                append(msg.substring(0, n))
            }
            append("<...>")
            if (msg.length > 2 * n) {
                append(msg.substring(msg.length - n))
            }
        }
    }

    interface OnRecordingCompletedListener {
        /**
         * Called when the recording completes successfully. [file] is the output file.
         */
        fun onRecordingCompleted(thread: RecorderThread, file: OutputFile)

        /**
         * Called when an error occurs during recording. If [file] is not null, it points to the
         * output file containing partially recorded audio. If [file] is null, then either the
         * output file could not be created or the thread was cancelled before it was started.
         */
        fun onRecordingFailed(thread: RecorderThread, errorMsg: String?, file: OutputFile?)
    }
}