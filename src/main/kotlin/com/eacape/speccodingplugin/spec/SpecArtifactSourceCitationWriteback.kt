package com.eacape.speccodingplugin.spec

internal data class ArtifactSourceCitationWritebackResult(
    val content: String,
    val sectionTitle: String,
    val insertedCount: Int,
)

internal object SpecArtifactSourceCitationWriteback {
    fun apply(
        phase: SpecPhase,
        existingContent: String,
        citations: List<ArtifactSourceCitation>,
    ): ArtifactSourceCitationWritebackResult? {
        val normalizedCitations = citations
            .mapNotNull(::normalizeCitation)
            .distinctBy(ArtifactSourceCitation::sourceId)
        if (normalizedCitations.isEmpty()) {
            return null
        }

        val normalizedContent = normalizeContent(existingContent).trimEnd()
        val preferredTitle = preferredSectionTitle(phase)
        val merged = mergeSection(
            content = normalizedContent,
            preferredTitle = preferredTitle,
            alternativeTitles = alternativeSectionTitles(preferredTitle),
            citations = normalizedCitations,
        )
        return ArtifactSourceCitationWritebackResult(
            content = merged.updatedContent,
            sectionTitle = merged.sectionTitle,
            insertedCount = merged.insertedCount,
        )
    }

    private data class MergedSection(
        val updatedContent: String,
        val sectionTitle: String,
        val insertedCount: Int,
    )

    private fun normalizeCitation(citation: ArtifactSourceCitation): ArtifactSourceCitation? {
        val sourceId = citation.sourceId.trim()
        val storedRelativePath = citation.storedRelativePath
            .replace("\\", "/")
            .trim()
        if (sourceId.isBlank() || storedRelativePath.isBlank()) {
            return null
        }
        return citation.copy(
            sourceId = sourceId,
            storedRelativePath = storedRelativePath,
            locator = citation.locator?.trim()?.ifBlank { null },
            note = citation.note?.trim()?.ifBlank { null },
        )
    }

    private fun mergeSection(
        content: String,
        preferredTitle: String,
        alternativeTitles: List<String>,
        citations: List<ArtifactSourceCitation>,
    ): MergedSection {
        val sectionTitles = listOf(preferredTitle) + alternativeTitles
        val headingMatches = HEADING_REGEX.findAll(content).toList()
        val targetHeading = headingMatches.firstOrNull { match ->
            val headingTitle = match.groupValues[1].trim()
            sectionTitles.any { title -> headingTitle.equals(title, ignoreCase = true) }
        }

        if (targetHeading == null) {
            val sectionBody = citations.joinToString(separator = "\n", transform = ::renderCitationLine)
            val updatedContent = if (content.isBlank()) {
                "## $preferredTitle\n\n$sectionBody"
            } else {
                "$content\n\n## $preferredTitle\n\n$sectionBody"
            }
            return MergedSection(
                updatedContent = updatedContent,
                sectionTitle = preferredTitle,
                insertedCount = citations.size,
            )
        }

        val sectionTitle = targetHeading.groupValues[1].trim()
        val nextHeading = headingMatches.firstOrNull { match -> match.range.first > targetHeading.range.first }
        val sectionStart = targetHeading.range.first
        val sectionEnd = nextHeading?.range?.first ?: content.length
        val prefix = content.substring(0, sectionStart).trimEnd()
        val sectionBlock = content.substring(sectionStart, sectionEnd)
        val suffix = content.substring(sectionEnd).trimStart('\n')
        val missingCitations = citations.filterNot { citation ->
            containsCitation(sectionBlock, citation)
        }
        if (missingCitations.isEmpty()) {
            return MergedSection(
                updatedContent = content,
                sectionTitle = sectionTitle,
                insertedCount = 0,
            )
        }

        val updatedSection = appendCitations(sectionBlock, missingCitations)
        val updatedContent = buildString {
            if (prefix.isNotBlank()) {
                append(prefix)
                append("\n\n")
            }
            append(updatedSection.trimEnd())
            if (suffix.isNotBlank()) {
                append("\n\n")
                append(suffix)
            }
        }.trimEnd()
        return MergedSection(
            updatedContent = updatedContent,
            sectionTitle = sectionTitle,
            insertedCount = missingCitations.size,
        )
    }

    private fun appendCitations(
        sectionBlock: String,
        citations: List<ArtifactSourceCitation>,
    ): String {
        val normalizedSection = sectionBlock.trimEnd()
        val hasBody = normalizedSection
            .lineSequence()
            .drop(1)
            .any { line -> line.isNotBlank() }
        return buildString {
            append(normalizedSection)
            append(if (hasBody) "\n" else "\n\n")
            citations.forEachIndexed { index, citation ->
                if (index > 0) {
                    append('\n')
                }
                append(renderCitationLine(citation))
            }
        }.trimEnd()
    }

    private fun containsCitation(
        sectionBlock: String,
        citation: ArtifactSourceCitation,
    ): Boolean {
        val sourceIdRegex = Regex("""(?i)\b${Regex.escape(citation.sourceId)}\b""")
        return sourceIdRegex.containsMatchIn(sectionBlock)
    }

    private fun renderCitationLine(citation: ArtifactSourceCitation): String {
        val detailParts = buildList {
            citation.locator?.let(::add)
            citation.note?.let(::add)
        }
        val detailSuffix = if (detailParts.isEmpty()) {
            ""
        } else {
            " - ${detailParts.joinToString(separator = " | ")}"
        }
        return "- `${citation.sourceId}` `${citation.storedRelativePath}`$detailSuffix"
    }

    private fun preferredSectionTitle(phase: SpecPhase): String {
        return when (phase) {
            SpecPhase.DESIGN -> "References"
            SpecPhase.SPECIFY,
            SpecPhase.IMPLEMENT,
            -> "Sources"
        }
    }

    private fun alternativeSectionTitles(preferredTitle: String): List<String> {
        return listOf("Sources", "References")
            .filterNot { title -> title.equals(preferredTitle, ignoreCase = true) }
    }

    private fun normalizeContent(content: String): String {
        return content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
    }

    private val HEADING_REGEX = Regex("""(?m)^##\s+(.+?)\s*$""")
}
