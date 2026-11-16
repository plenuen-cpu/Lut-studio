package com.plenuen.lumen.nima

import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The NIMA model on the phone's own CPU.
 *
 * One interpreter, reused: the automatic pass asks for dozens of scores in a row and loading the
 * weights each time would dominate the run. Input is a direct float buffer in `[-1, 1]`, output is
 * the ten-bucket probability distribution of the AVA-trained aesthetic model.
 *
 * The published weights declare a dynamic batch axis (`shape_signature` −1). Some runtimes accept a
 * `[1, 224, 224, 3]` buffer against that signature and some refuse it, so the batch is pinned to 1
 * explicitly before the first inference; when the model is already static that call is a no-op.
 */
class NimaInterpreter private constructor(
    private val interpreter: Interpreter,
    inputBytes: Int,
    outputBytes: Int,
) : Closeable {

    private val input: ByteBuffer = ByteBuffer.allocateDirect(inputBytes).order(ByteOrder.nativeOrder())
    private val output: ByteBuffer = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.nativeOrder())
    private val probabilities = FloatArray(NimaScores.BUCKETS)
    private var lastDeviation = 0f

    fun deviation(): Float = lastDeviation

    /** Scores an already prepared 224×224 input buffer; 1…10 or null when inference fails. */
    fun score(prepared: FloatArray): Float? {
        if (prepared.size < NimaScores.INPUT_FLOATS) return null
        return runCatching {
            input.rewind()
            input.asFloatBuffer().put(prepared, 0, NimaScores.INPUT_FLOATS)
            output.rewind()
            interpreter.run(input, output)
            output.rewind()
            for (i in 0 until NimaScores.BUCKETS) probabilities[i] = output.float
            val clean = NimaScores.normalise(probabilities)
            val mean = NimaScores.expectedScore(clean) ?: return null
            lastDeviation = NimaScores.deviation(clean, mean)
            mean
        }.getOrElse { error ->
            Log.w("LumenNima", "inference failed: ${error.message}")
            null
        }
    }

    override fun close() {
        runCatching { interpreter.close() }
    }

    companion object {
        /**
         * Opens the weights from [file]. Two CPU threads: the pass runs while the preview keeps
         * drawing, and more threads than that cost more than they save on a phone.
         */
        fun open(file: File): NimaInterpreter? = runCatching {
            val options = Interpreter.Options().apply { setNumThreads(2) }
            val interpreter = Interpreter(file, options)

            // 동적 배치 축을 1로 고정한다(이미 정적이면 아무 일도 하지 않는다).
            runCatching {
                interpreter.resizeInput(0, intArrayOf(1, NimaScores.INPUT_SIZE, NimaScores.INPUT_SIZE, 3))
            }
            runCatching { interpreter.allocateTensors() }

            val inputShape = interpreter.getInputTensor(0).shape()
            val outputShape = interpreter.getOutputTensor(0).shape()
            val inputOk = inputShape.size == 4 &&
                (inputShape[0] == 1 || inputShape[0] == -1) &&
                inputShape[1] == NimaScores.INPUT_SIZE &&
                inputShape[2] == NimaScores.INPUT_SIZE &&
                inputShape[3] == NimaScores.CHANNELS
            if (!inputOk) {
                Log.w("LumenNima", "unexpected input shape ${inputShape.contentToString()}")
                interpreter.close()
                return null
            }
            val inputBytes = interpreter.getInputTensor(0).numBytes()
            val outputBytes = interpreter.getOutputTensor(0).numBytes()
            if (inputBytes < NimaScores.INPUT_FLOATS * 4 || outputBytes < NimaScores.BUCKETS * 4) {
                Log.w(
                    "LumenNima",
                    "unexpected tensor sizes in=$inputBytes out=$outputBytes " +
                        "shape=${outputShape.contentToString()}",
                )
                interpreter.close()
                return null
            }
            NimaInterpreter(interpreter, inputBytes, outputBytes)
        }.getOrElse { error ->
            Log.w("LumenNima", "model could not be opened: ${error.message}")
            null
        }
    }
}
