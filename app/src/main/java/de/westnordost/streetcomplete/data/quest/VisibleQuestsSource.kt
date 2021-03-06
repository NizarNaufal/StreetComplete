package de.westnordost.streetcomplete.data.quest

import de.westnordost.osmapi.map.data.BoundingBox
import de.westnordost.streetcomplete.data.osm.osmquest.OsmQuest
import de.westnordost.streetcomplete.data.osm.osmquest.OsmQuestController
import de.westnordost.streetcomplete.data.osmnotes.notequests.OsmNoteQuest
import de.westnordost.streetcomplete.data.osmnotes.notequests.OsmNoteQuestController
import de.westnordost.streetcomplete.data.visiblequests.TeamModeQuestFilter
import de.westnordost.streetcomplete.data.visiblequests.VisibleQuestTypeDao
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/** Access and listen to quests visible on the map */
@Singleton class VisibleQuestsSource @Inject constructor(
    private val osmQuestController: OsmQuestController,
    private val osmNoteQuestController: OsmNoteQuestController,
    private val visibleQuestTypeDao: VisibleQuestTypeDao,
    private val teamModeQuestFilter: TeamModeQuestFilter
) {
    private val listeners: MutableList<VisibleQuestListener> = CopyOnWriteArrayList()

    private val osmQuestStatusListener = object : OsmQuestController.QuestStatusListener {
        override fun onChanged(quest: OsmQuest, previousStatus: QuestStatus) {
            if (quest.status.isVisible && !previousStatus.isVisible) {
                onQuestBecomesVisible(quest, QuestGroup.OSM)
            } else if(!quest.status.isVisible && previousStatus.isVisible) {
                onQuestBecomesInvisible(quest.id!!, QuestGroup.OSM)
            }
        }

        override fun onRemoved(questId: Long, previousStatus: QuestStatus) {
            if (previousStatus.isVisible) {
                onQuestBecomesInvisible(questId, QuestGroup.OSM)
            }
        }

        override fun onUpdated(added: Collection<OsmQuest>, updated: Collection<OsmQuest>, deleted: Collection<Long>) {
            onUpdatedVisibleQuests(
                added.filter { visibleQuestTypeDao.isVisible(it.type) },
                updated.filter { visibleQuestTypeDao.isVisible(it.type) },
                deleted,
                QuestGroup.OSM
            )
        }
    }

    private val osmNoteQuestStatusListener = object : OsmNoteQuestController.QuestStatusListener {
        override fun onAdded(quest: OsmNoteQuest) {
            if (quest.status.isVisible) {
                onQuestBecomesVisible(quest, QuestGroup.OSM_NOTE)
            }
        }

        override fun onChanged(quest: OsmNoteQuest, previousStatus: QuestStatus) {
            if (quest.status.isVisible && !previousStatus.isVisible) {
                onQuestBecomesVisible(quest, QuestGroup.OSM_NOTE)
            } else if(!quest.status.isVisible && previousStatus.isVisible) {
                onQuestBecomesInvisible(quest.id!!, QuestGroup.OSM_NOTE)
            }
        }

        override fun onRemoved(questId: Long, previousStatus: QuestStatus) {
            if (previousStatus.isVisible) {
                onQuestBecomesInvisible(questId, QuestGroup.OSM_NOTE)
            }
        }

        override fun onUpdated(added: Collection<OsmNoteQuest>, updated: Collection<OsmNoteQuest>, deleted: Collection<Long>) {
            onUpdatedVisibleQuests(added, updated, deleted, QuestGroup.OSM_NOTE)
        }
    }

    private val teamModeChangeListener = object : TeamModeQuestFilter.TeamModeChangeListener {
        override fun onTeamModeChanged(enabled: Boolean) {
            onVisibleQuestsInvalidated()
        }
    }

    init {
        osmQuestController.addQuestStatusListener(osmQuestStatusListener)
        osmNoteQuestController.addQuestStatusListener(osmNoteQuestStatusListener)
        teamModeQuestFilter.addListener(teamModeChangeListener)
    }


    /** Get count of all unanswered quests in given bounding box */
    fun getAllVisibleCount(bbox: BoundingBox): Int {
        return osmQuestController.getAllVisibleInBBoxCount(bbox) +
                osmNoteQuestController.getAllVisibleInBBoxCount(bbox)
    }

    /** Retrieve all visible (=new) quests in the given bounding box from local database */
    fun getAllVisible(bbox: BoundingBox, questTypes: Collection<String>): List<QuestAndGroup> {
        if (questTypes.isEmpty()) return listOf()
        val osmQuests = osmQuestController.getAllVisibleInBBox(bbox, questTypes).filter { teamModeQuestFilter.isVisible(it) }
        val osmNoteQuests = osmNoteQuestController.getAllVisibleInBBox(bbox).filter { teamModeQuestFilter.isVisible(it) }

        return osmQuests.map { QuestAndGroup(it, QuestGroup.OSM) } +
                osmNoteQuests.map { QuestAndGroup(it, QuestGroup.OSM_NOTE) }
    }

    fun addListener(listener: VisibleQuestListener) {
        listeners.add(listener)
    }
    fun removeListener(listener: VisibleQuestListener) {
        listeners.remove(listener)
    }

    private fun onQuestBecomesVisible(quest: Quest, group: QuestGroup) {
        listeners.forEach { it.onUpdatedVisibleQuests(listOf(quest), emptyList(), group) }
    }
    private fun onQuestBecomesInvisible(questId: Long, group: QuestGroup) {
        listeners.forEach { it.onUpdatedVisibleQuests(emptyList(), listOf(questId), group) }
    }
    private fun onUpdatedVisibleQuests(added: Collection<Quest>, updated: Collection<Quest>, deleted: Collection<Long>, group: QuestGroup) {
        val addedQuests = (added + updated).filter { it.status.isVisible && teamModeQuestFilter.isVisible(it) }
        val deletedQuestIds = updated.filter { !it.status.isVisible || !teamModeQuestFilter.isVisible(it) }.map { it.id!! } + deleted
        listeners.forEach { it.onUpdatedVisibleQuests(addedQuests, deletedQuestIds, group) }
    }
    private fun onVisibleQuestsInvalidated() {
        listeners.forEach { it.onVisibleQuestsInvalidated() }
    }
}

interface VisibleQuestListener {
    /** Called when given quests in the given group have been added/removed */
    fun onUpdatedVisibleQuests(added: Collection<Quest>, removed: Collection<Long>, group: QuestGroup)

    /** Called when something has changed which should trigger any listeners to update all */
    fun onVisibleQuestsInvalidated()
}
