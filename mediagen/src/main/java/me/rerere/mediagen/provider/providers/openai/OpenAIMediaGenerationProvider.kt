package me.rerere.mediagen.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.mediagen.model.ImageRole
import me.rerere.mediagen.model.MediaGenerationInput
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.model.MediaGenerationOutput
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.model.MediaGenerationTask
import me.rerere.mediagen.model.MediaGenerationUsage
import me.rerere.mediagen.provider.MediaGenerationProvider
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.executeJson
import me.rerere.mediagen.provider.providers.imageMimeType
import me.rerere.mediagen.provider.providers.long
import me.rerere.mediagen.provider.providers.obj
import me.rerere.mediagen.provider.providers.postJson
import me.rerere.mediagen.provider.providers.string
import me.rerere.mediagen.provider.providers.toUrlOrDataUri
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * 目前只实现了图像：接口同步返回结果，[create] 直接得到终态任务。请求里带图片输入时走编辑接口，否则走生成接口。
 */
class OpenAIMediaGenerationProvider(
    private val client: OkHttpClient,
) : MediaGenerationProvider<MediaGenerationProviderSetting.OpenAI> {
    override val id: String = PROVIDER_ID

    override suspend fun create(
        setting: MediaGenerationProviderSetting.OpenAI,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): Result<MediaGenerationTask> = runCatching {
        val baseUrl = setting.baseUrl.trimEnd('/')
        fun normalizePath(path: String): String {
            val trimmed = if (path.startsWith("/")) path else "/$path"
            return if (baseUrl.endsWith("/v1") && trimmed.startsWith("/v1/")) {
                trimmed.removePrefix("/v1")
            } else {
                trimmed
            }
        }
        val genPath = normalizePath(setting.generationPath)
        val edPath = normalizePath(setting.editPath)
        val builder = Request.Builder().addHeader("Authorization", "Bearer ${setting.apiKey}")
        val httpRequest = if (model.kind == MediaKind.VIDEO || request.inputs.isEmpty()) {
            builder.url("$baseUrl$genPath").postJson(buildGenerationBody(setting, model, request))
        } else {
            builder.url("$baseUrl$edPath").post(buildEditBody(setting, model, request))
        }.build()
        parseTask(client.executeJson(httpRequest, id), model.modelId)
    }

    internal fun buildGenerationBody(
        setting: MediaGenerationProviderSetting.OpenAI,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): JsonObject = buildJsonObject {
        if (model.kind == MediaKind.VIDEO) {
            validateVideo(request)
            model.customParameters.forEach { (key, value) -> put(key, value) }
            request.extraParameters.forEach { (key, value) -> put(key, value) }
            put("model", model.modelId)
            put("prompt", request.prompt)
            request.aspectRatio?.let { put("aspect_ratio", it) }
            request.durationSeconds?.let { put("duration", it) }
            request.generateAudio?.let { put("generate_audio", it) }
            request.seed?.let { put("seed", it) }
            request.count?.let { put("n", it) }
            resolveSize(setting, model, request.resolution)?.let { put("size", it) }

            val firstFrame = request.inputs.filterIsInstance<MediaGenerationInput.Image>()
                .firstOrNull { it.role == ImageRole.FIRST_FRAME }
            val lastFrame = request.inputs.filterIsInstance<MediaGenerationInput.Image>()
                .firstOrNull { it.role == ImageRole.LAST_FRAME }
            val references = request.inputs.filterIsInstance<MediaGenerationInput.Image>()
                .filter { it.role == ImageRole.REFERENCE }
            val videoRef = request.inputs.filterIsInstance<MediaGenerationInput.Video>().firstOrNull()

            firstFrame?.let {
                val uri = it.toUrlOrDataUri()
                put("first_frame", uri)
                put("first_image", uri)
                put("image_url", uri)
                put("image", uri)
            }
            lastFrame?.let {
                val uri = it.toUrlOrDataUri()
                put("last_frame", uri)
                put("end_frame", uri)
            }
            if (firstFrame == null && references.isNotEmpty()) {
                val uri = references.first().toUrlOrDataUri()
                put("image_url", uri)
                put("image", uri)
                put("input_reference", uri)
                putJsonArray("images") {
                    references.forEach { ref -> add(JsonPrimitive(ref.toUrlOrDataUri())) }
                }
            }
            videoRef?.let {
                val uri = it.toUrlOrDataUri()
                put("video_url", uri)
                put("video", uri)
            }

            // Automatic mode inference for video generation if not explicitly provided
            val hasExplicitMode = request.extraParameters.containsKey("mode") || model.customParameters.containsKey("mode")
            if (!hasExplicitMode) {
                val modelIdLower = model.modelId.lowercase()
                if (modelIdLower.contains("agnes")) {
                    val mode = when {
                        firstFrame != null || lastFrame != null -> "keyframe"
                        references.isNotEmpty() || videoRef != null -> "reference"
                        else -> "text"
                    }
                    put("mode", mode)
                }
            }
        } else {
            validate(request)
            model.customParameters.forEach { (key, value) -> put(key, value) }
            request.extraParameters.forEach { (key, value) -> put(key, value) }
            put("model", model.modelId)
            put("prompt", request.prompt)
            request.count?.let { put("n", it) }
            resolveSize(setting, model, request.resolution)?.let { put("size", it) }
        }
    }

    private fun validateVideo(request: MediaGenerationRequest) {
        require(!request.prompt.isNullOrBlank()) { "OpenAI video generation requires a non-empty prompt" }
    }

    internal fun buildEditBody(
        setting: MediaGenerationProviderSetting.OpenAI,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): MultipartBody {
        validate(request)
        val fields = linkedMapOf<String, String>()
        model.customParameters.forEach { (key, value) -> fields[key] = value }
        request.extraParameters.forEach { (key, value) ->
            fields[key] = (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
        }
        fields["model"] = model.modelId
        fields["prompt"] = request.prompt.orEmpty()
        request.count?.let { fields["n"] = it.toString() }
        resolveSize(setting, model, request.resolution)?.let { fields["size"] = it }

        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (key, value) -> builder.addFormDataPart(key, value) }

        val imageFieldName = if (request.inputs.size == 1) "image" else "image[]"
        request.inputs.forEach { input ->
            require(input is MediaGenerationInput.Image) {
                "OpenAI image generation does not support ${input::class.simpleName} input"
            }
            val file = File(input.url.removePrefix("file://"))
            require(file.exists()) { "Image file does not exist: ${input.url}" }
            val mediaType = EDIT_IMAGE_MEDIA_TYPES[file.extension.lowercase()]
                ?: throw IllegalArgumentException("Unsupported image file type for OpenAI edit: ${file.extension}")
            builder.addFormDataPart(imageFieldName, file.name, file.asRequestBody(mediaType.toMediaType()))
        }
        return builder.build()
    }

    internal fun parseTask(root: JsonObject, fallbackModel: String? = null): MediaGenerationTask {
        val defaultFormat = root.string("output_format")
        val data = root["data"] as? JsonArray ?: error("OpenAI response does not contain data")
        return MediaGenerationTask(
            // 同步接口没有服务端任务 ID
            id = Uuid.random().toString(),
            provider = id,
            model = fallbackModel,
            status = MediaGenerationStatus.SUCCEEDED,
            outputs = data.map { element ->
                val item = element.jsonObject
                // 部分中转同时给 url 和空字符串的 b64_json，空串当作没有
                val base64 = item.string("b64_json")?.takeIf { it.isNotBlank() }
                // dall-e 系列和部分中转不返回 b64_json，而是有时效的 url
                val url = (item.string("url") ?: item.string("video_url"))?.takeIf { it.isNotBlank() }
                if (base64 == null && url == null) error("OpenAI media contains neither b64_json nor url")
                val isVideo = (fallbackModel != null && fallbackModel.contains("video", ignoreCase = true)) ||
                    url?.endsWith(".mp4", ignoreCase = true) == true
                val mimeType = if (isVideo) "video/mp4" else imageMimeType(item.string("output_format") ?: defaultFormat, "image/png")
                MediaGenerationOutput(
                    url = url,
                    data = base64?.let { Base64.decode(it.substringAfter("base64,")) },
                    mimeType = mimeType,
                    resolution = root.string("size") ?: item.string("resolution"),
                )
            },
            createdAtEpochSeconds = root.long("created"),
            usage = root.obj("usage")?.let {
                MediaGenerationUsage(totalTokens = it.long("total_tokens"))
            },
            // data 里是整张图片的 base64，不放进 metadata
            metadata = JsonObject(root - "data"),
        )
    }

    private fun validate(request: MediaGenerationRequest) {
        require(!request.prompt.isNullOrBlank()) { "OpenAI image generation requires a non-empty prompt" }
        require(request.aspectRatio == null) { "OpenAI image generation uses resolution instead of aspectRatio" }
        require(request.durationSeconds == null) { "OpenAI image generation does not expose duration" }
        require(request.generateAudio == null) { "OpenAI image generation does not expose generateAudio" }
        require(request.watermark == null) { "OpenAI image generation does not expose watermark" }
        require(request.seed == null) { "OpenAI image generation does not expose seed" }
        require(request.promptEnhancement == null) { "OpenAI image generation does not expose prompt enhancement" }
        require(request.callbackUrl == null) { "OpenAI image generation returns results synchronously" }
    }

    // "auto" 交给服务端默认值；Grok 的兼容接口不接受 size
    private fun resolveSize(
        setting: MediaGenerationProviderSetting.OpenAI,
        model: MediaGenerationModel,
        size: String?,
    ): String? {
        if (size.isNullOrBlank() || size.equals("auto", ignoreCase = true)) return null
        return size.takeUnless { isGrok(setting, model) }
    }

    // 只匹配 x.ai 本身及其子域名，避免 "xxx-max.ai" 之类的中转域名被误判
    private fun isGrok(setting: MediaGenerationProviderSetting.OpenAI, model: MediaGenerationModel): Boolean {
        val host = setting.baseUrl.toHttpUrlOrNull()?.host?.lowercase()
        return host == "x.ai" || host?.endsWith(".x.ai") == true || model.modelId.contains("grok", ignoreCase = true)
    }

    private companion object {
        const val PROVIDER_ID = "openai"
        val EDIT_IMAGE_MEDIA_TYPES = mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "webp" to "image/webp",
        )
    }
}
