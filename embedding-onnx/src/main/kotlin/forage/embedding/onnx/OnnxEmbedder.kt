package forage.embedding.onnx

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import forage.core.embedding.Embedder
import forage.core.embedding.Vectors
import java.nio.LongBuffer

/** Sentence embeddings with ONNX Runtime: tokenize, run the transformer, mean-pool, normalise. */
class OnnxEmbedder(private val spec: ModelSpec, files: ModelFiles.Local) : Embedder {
    override val modelId = spec.id
    override val dimensions = spec.dimensions

    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(files.model.toString(), OrtSession.SessionOptions())
    private val tokenizer = HuggingFaceTokenizer.builder()
        .optTokenizerPath(files.tokenizer)
        .optTruncation(true)
        .optMaxLength(spec.maxTokens)
        .optPadding(true)
        .build()

    override fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val encodings = tokenizer.batchEncode(texts)
        val length = encodings[0].ids.size
        val shape = longArrayOf(texts.size.toLong(), length.toLong())
        val ids = LongArray(texts.size * length)
        val mask = LongArray(texts.size * length)
        encodings.forEachIndexed { row, e ->
            e.ids.copyInto(ids, row * length)
            e.attentionMask.copyInto(mask, row * length)
        }

        val inputs = HashMap<String, OnnxTensor>()
        try {
            inputs["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
            inputs["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape)
            // BERT-style exports also ask for segment ids, which are all zero for single texts.
            if ("token_type_ids" in session.inputNames) {
                inputs["token_type_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size)), shape)
            }
            session.run(inputs).use { output ->
                @Suppress("UNCHECKED_CAST")
                val hidden = output[0].value as Array<Array<FloatArray>>   // [batch][token][dimension]
                return hidden.mapIndexed { row, tokens -> meanPool(tokens, mask, row * length) }
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    /** Average the token vectors, ignoring padding. */
    private fun meanPool(tokens: Array<FloatArray>, mask: LongArray, offset: Int): FloatArray {
        val sum = FloatArray(dimensions)
        var count = 0
        for (t in tokens.indices) {
            if (mask[offset + t] == 0L) continue
            count++
            val vector = tokens[t]
            for (d in 0 until dimensions) sum[d] += vector[d]
        }
        if (count > 0) for (d in 0 until dimensions) sum[d] /= count
        return Vectors.normalize(sum)
    }

    override fun close() {
        tokenizer.close()
        session.close()
    }
}
