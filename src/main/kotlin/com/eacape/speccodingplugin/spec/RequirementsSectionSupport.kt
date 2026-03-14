package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.SpecCodingBundle

enum class RequirementsSectionId(
    val stableId: String,
    private val displayNameKey: String,
    internal val markers: List<String>,
) {
    FUNCTIONAL(
        stableId = "functional-requirements",
        displayNameKey = "spec.requirements.section.functional",
        markers = listOf("## Functional Requirements", "## 功能需求"),
    ),
    NON_FUNCTIONAL(
        stableId = "non-functional-requirements",
        displayNameKey = "spec.requirements.section.nonFunctional",
        markers = listOf("## Non-Functional Requirements", "## 非功能需求"),
    ),
    USER_STORIES(
        stableId = "user-stories",
        displayNameKey = "spec.requirements.section.userStories",
        markers = listOf("## User Stories", "## 用户故事"),
    ),
    ACCEPTANCE_CRITERIA(
        stableId = "acceptance-criteria",
        displayNameKey = "spec.requirements.section.acceptanceCriteria",
        markers = listOf("## Acceptance Criteria", "## 验收标准"),
    ),
    ;

    fun displayName(): String = SpecCodingBundle.message(displayNameKey)

    companion object {
        fun fromStableId(stableId: String): RequirementsSectionId? =
            entries.firstOrNull { section -> section.stableId == stableId.trim() }
    }
}

object RequirementsSectionSupport {

    fun missingSections(content: String): List<RequirementsSectionId> {
        return RequirementsSectionId.entries.filterNot { section ->
            section.markers.any { marker -> content.contains(marker, ignoreCase = true) }
        }
    }

    fun hasRequiredSections(content: String): Boolean = missingSections(content).isEmpty()

    fun describeSections(sections: List<RequirementsSectionId>): String {
        return sections.joinToString(", ") { section -> section.displayName() }
    }
}
