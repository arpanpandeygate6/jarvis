package com.gate6.jarvis.wake

import android.content.Context
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import org.tensorflow.lite.Interpreter

/**
 * On-device "Hey Jarvis" detector: [MicroFrontend] features into the microWakeWord streaming model
 * (esphome/micro-wake-word-models, Apache-2.0) on LiteRT. Audio never leaves the phone.
 *
 * Feed 16 kHz mono PCM16 with [process]. Not thread-safe: use from one thread.
 */
class WakeWordDetector(context: Context, private val cutoff: Float) : Closeable {
    private val model: MappedByteBuffer = context.assets.openFd(MODEL_ASSET).use { fd ->
        FileInputStream(fd.fileDescriptor).channel.use { ch ->
            ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }
    private var interpreter = newInterpreter()
    private val stride: Int
    private val inScale: Float
    private val inZeroPoint: Int
    private val outScale: Float
    private val outZeroPoint: Int

    private val frontend = MicroFrontend()
    private val frame = IntArray(MicroFrontend.NUM_CHANNELS)
    private val input: ByteBuffer
    private val output = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())
    private var framesInInput = 0
    private val scores = WakeScores(SLIDING_WINDOW, cutoff)

    init {
        val inT = interpreter.getInputTensor(0)
        val outT = interpreter.getOutputTensor(0)
        stride = inT.shape()[1]
        inScale = inT.quantizationParams().scale
        inZeroPoint = inT.quantizationParams().zeroPoint
        outScale = outT.quantizationParams().scale
        outZeroPoint = outT.quantizationParams().zeroPoint
        input = ByteBuffer.allocateDirect(stride * MicroFrontend.NUM_CHANNELS).order(ByteOrder.nativeOrder())
    }

    /** Returns true when the wake word was heard in these samples. The detector then resets itself. */
    fun process(samples: ShortArray, count: Int): Boolean {
        var offset = 0
        while (offset < count) {
            offset += frontend.process(samples, offset, count - offset, frame)
            if (!frontend.lastHadOutput) continue
            for (v in frame) input.put(quantize(v, inScale, inZeroPoint))
            if (++framesInInput < stride) continue
            input.rewind()
            output.rewind()
            interpreter.run(input, output)
            input.rewind()
            framesInInput = 0
            val p = ((output.get(0).toInt() and 0xFF) - outZeroPoint) * outScale
            if (scores.add(p)) {
                reset()
                return true
            }
        }
        return false
    }

    /** Clears audio history and the model's streaming state. */
    fun reset() {
        frontend.reset()
        scores.clear()
        framesInInput = 0
        input.rewind()
        interpreter.close()
        interpreter = newInterpreter() // the model keeps its streaming state in variables
    }

    override fun close() = interpreter.close()

    private fun newInterpreter() = Interpreter(model, Interpreter.Options().setNumThreads(1))

    companion object {
        const val MODEL_ASSET = "wake/hey_jarvis.tflite"
        private const val SLIDING_WINDOW = 5 // from hey_jarvis.json

        /** Frontend output (uint16) to the model's int8 input, as microWakeWord does it. */
        fun quantize(feature: Int, scale: Float, zeroPoint: Int): Byte {
            val f = feature * 0.0390625 // frontend's float scale (1/25.6)
            return Math.rint(f / scale + zeroPoint).coerceIn(-128.0, 127.0).toInt().toByte()
        }
    }
}

/** Sliding-window mean of model probabilities; fires when the mean exceeds [cutoff]. */
class WakeScores(private val size: Int, private val cutoff: Float) {
    private val window = FloatArray(size)
    private var count = 0
    private var next = 0

    fun add(p: Float): Boolean {
        window[next] = p
        next = (next + 1) % size
        if (count < size) count++
        return count == size && window.average() > cutoff
    }

    fun clear() {
        count = 0
        next = 0
        window.fill(0f)
    }
}
