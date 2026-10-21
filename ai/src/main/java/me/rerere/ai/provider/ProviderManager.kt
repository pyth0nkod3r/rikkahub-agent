package me.rerere.ai.provider

import android.content.Context
import me.rerere.ai.provider.providers.AICoreProvider
import me.rerere.ai.provider.providers.chatgpt.ChatGPTProvider
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.grok.GrokProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import okhttp3.OkHttpClient

/**
 * Provider管理器，负责注册和获取Provider实例
 */
class ProviderManager(
    client: OkHttpClient,
    context: Context,
    accountCredentials: AccountCredentialProvider,
) {
    // 存储已注册的Provider实例
    private val providers = mutableMapOf<String, Provider<*>>()

    init {
        // 注册内置Provider
        registerProvider("openai", OpenAIProvider(client, context))
        registerProvider("google", GoogleProvider(client, context))
        registerProvider("claude", ClaudeProvider(client, context))
        registerProvider("chatgpt", ChatGPTProvider(client, accountCredentials))
        registerProvider("grok", GrokProvider(client, accountCredentials))
        registerProvider("aicore", AICoreProvider(context))
    }

    /**
     * 注册Provider
     */
    fun registerProvider(type: String, provider: Provider<*>) {
        providers[type] = provider
    }

    /**
     * 获取指定类型的Provider
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : ProviderSetting> getProvider(type: String): Provider<T> {
        return providers[type] as? Provider<T>
            ?: throw IllegalArgumentException("Provider type '$type' not found")
    }

    /**
     * 获取所有已注册的Provider类型
     */
    fun getRegisteredTypes(): Set<String> {
        return providers.keys.toSet()
    }

    /**
     * 根据ProviderSetting获取对应的Provider实例
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : ProviderSetting> getProviderByType(setting: T): Provider<T> {
        return when (setting) {
            is ProviderSetting.OpenAI -> getProvider("openai")
            is ProviderSetting.Google -> getProvider("google")
            is ProviderSetting.Claude -> getProvider("claude")
            is ProviderSetting.ChatGPT -> getProvider("chatgpt")
            is ProviderSetting.Grok -> getProvider("grok")
            is ProviderSetting.AICore -> getProvider("aicore")
            is ProviderSetting.LiteRtLocal -> getProvider("local_litert")
            is ProviderSetting.LlamaCppLocal -> getProvider("local_llamacpp")
            is ProviderSetting.Codex -> getProvider("chatgpt")
            is ProviderSetting.GeminiOAuth -> getProvider("gemini_oauth")
        } as Provider<T>
    }
}
