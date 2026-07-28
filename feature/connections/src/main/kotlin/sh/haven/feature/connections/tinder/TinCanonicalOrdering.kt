package sh.haven.feature.connections.tinder

import sh.haven.core.data.db.entities.ConnectionGroup
import sh.haven.core.data.db.entities.ConnectionProfile

class CanonicalOrderResult(
    val canonicalProfiles: List<ConnectionProfile>,
    val canonicalFlatIds: List<String>,
    val dependentsByParent: Map<String, List<ConnectionProfile>>,
    val allTopLevel: List<ConnectionProfile>
)

fun calculateCanonicalOrder(
    connections: List<ConnectionProfile>,
    groups: List<ConnectionGroup>
): CanonicalOrderResult {
    val profileMap = connections.associateBy { it.id }
    val dependentsByParent = connections
        .mapNotNull { profile ->
            val parentId = profile.jumpProfileId
                ?: profile.vncSshProfileId?.takeIf { profile.vncSshForward }
                ?: profile.rdpSshProfileId
                ?: profile.smbSshProfileId?.takeIf { profile.smbSshForward }
            if (parentId != null && parentId in profileMap) parentId to profile else null
        }
        .groupBy({ it.first }, { it.second })
    val renderedAsChild = dependentsByParent.values.flatten().map { it.id }.toSet()
    val allTopLevel = connections.filter { it.id !in renderedAsChild }
    val byGroup = allTopLevel.filter { it.groupId != null }.groupBy { it.groupId!! }

    val profilesList = mutableListOf<ConnectionProfile>()
    val flatIdsList = mutableListOf<String>()

    allTopLevel.filter { it.groupId == null }
        .sortedBy { it.sortOrder }
        .forEach {
            profilesList.add(it)
            flatIdsList.add(it.id)
        }

    groups.sortedBy { it.sortOrder }.forEach { group ->
        flatIdsList.add("group-${group.id}")
        byGroup[group.id].orEmpty()
            .sortedBy { it.sortOrder }
            .forEach {
                profilesList.add(it)
                flatIdsList.add(it.id)
            }
    }

    return CanonicalOrderResult(profilesList, flatIdsList, dependentsByParent, allTopLevel)
}

fun matchesConnectionFilter(p: ConnectionProfile, query: String, previewText: String? = null): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return p.label.lowercase().contains(q) ||
           p.host.lowercase().contains(q) ||
           p.username.lowercase().contains(q) ||
           (previewText != null && previewText.lowercase().contains(q))
}
