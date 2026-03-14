package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.llm.LlmRouter
import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState

internal object RequirementsSectionAiSupport {

    fun resolvePreferredRealProviderId(
        providerHint: String? = null,
        llmRouter: LlmRouter? = null,
        settingsProvider: (() -> SpecCodingSettingsState)? = null,
    ): String? {
        val router = llmRouter ?: runCatching { LlmRouter.getInstance() }.getOrNull() ?: return null
        val availableProviders = router.availableUiProviders()
        if (availableProviders.isEmpty()) {
            return null
        }

        val hintedProvider = providerHint
            ?.trim()
            ?.takeIf(String::isNotBlank)
        if (hintedProvider != null) {
            availableProviders.firstOrNull { provider -> provider == hintedProvider }?.let { return it }
        }

        val defaultProvider = runCatching {
            (settingsProvider ?: { SpecCodingSettingsState.getInstance() })()
                .defaultProvider
                .trim()
                .takeIf(String::isNotBlank)
        }.getOrNull()
        if (defaultProvider != null) {
            availableProviders.firstOrNull { provider -> provider == defaultProvider }?.let { return it }
        }

        return availableProviders.firstOrNull()
    }

    fun unavailableReason(
        providerHint: String? = null,
        llmRouter: LlmRouter? = null,
        settingsProvider: (() -> SpecCodingSettingsState)? = null,
    ): String? {
        return if (
            resolvePreferredRealProviderId(
                providerHint = providerHint,
                llmRouter = llmRouter,
                settingsProvider = settingsProvider,
            ) == null
        ) {
            SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill.error.aiUnavailable")
        } else {
            null
        }
    }
}
