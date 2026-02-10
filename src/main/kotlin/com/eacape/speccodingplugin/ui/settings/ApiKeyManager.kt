package com.eacape.speccodingplugin.ui.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * API Key Manager
 * 使用 PasswordSafe 安全存储 API Keys
 */
object ApiKeyManager {

    private const val OPENAI_KEY_ID = "SpecCoding.OpenAI.ApiKey"
    private const val ANTHROPIC_KEY_ID = "SpecCoding.Anthropic.ApiKey"

    /**
     * 保存 OpenAI API Key
     */
    fun saveOpenAiKey(apiKey: String) {
        val attributes = CredentialAttributes(OPENAI_KEY_ID)
        val credentials = Credentials("", apiKey)
        PasswordSafe.instance.set(attributes, credentials)
    }

    /**
     * 获取 OpenAI API Key
     */
    fun getOpenAiKey(): String? {
        val attributes = CredentialAttributes(OPENAI_KEY_ID)
        return PasswordSafe.instance.getPassword(attributes)
    }

    /**
     * 保存 Anthropic API Key
     */
    fun saveAnthropicKey(apiKey: String) {
        val attributes = CredentialAttributes(ANTHROPIC_KEY_ID)
        val credentials = Credentials("", apiKey)
        PasswordSafe.instance.set(attributes, credentials)
    }

    /**
     * 获取 Anthropic API Key
     */
    fun getAnthropicKey(): String? {
        val attributes = CredentialAttributes(ANTHROPIC_KEY_ID)
        return PasswordSafe.instance.getPassword(attributes)
    }

    /**
     * 清除所有 API Keys
     */
    fun clearAllKeys() {
        PasswordSafe.instance.set(CredentialAttributes(OPENAI_KEY_ID), null)
        PasswordSafe.instance.set(CredentialAttributes(ANTHROPIC_KEY_ID), null)
    }
}
