/*
 * Copyright (c) 2020 Proton Technologies AG
 * 
 * This file is part of ProtonMail.
 * 
 * ProtonMail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * ProtonMail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with ProtonMail. If not, see https://www.gnu.org/licenses/.
 */
package ch.protonmail.android.activities

import androidx.lifecycle.*
import ch.protonmail.android.activities.messageDetails.repository.MessageDetailsRepository
import ch.protonmail.android.api.models.room.messages.Label
import ch.protonmail.android.api.models.room.messages.Message
import ch.protonmail.android.api.utils.ApplyRemoveLabels
import ch.protonmail.android.core.Constants
import ch.protonmail.android.core.UserManager
import ch.protonmail.android.jobs.ApplyLabelJob
import ch.protonmail.android.jobs.RemoveLabelJob
import ch.protonmail.android.utils.Event
import ch.protonmail.android.utils.UserUtils
import com.birbit.android.jobqueue.JobManager
import kotlinx.coroutines.*
import java.util.*

// region constants
const val FLOW_START_ACTIVITY = 1
const val FLOW_USED_SPACE_CHANGED = 2
const val FLOW_TRY_COMPOSE = 3
// endregion

class MailboxViewModel(private val messageDetailsRepository: MessageDetailsRepository,
                       val userManager: UserManager, private val jobManager: JobManager) : ViewModel() {

    var pendingSendsLiveData = messageDetailsRepository.findAllPendingSendsAsync()
    var pendingUploadsLiveData = messageDetailsRepository.findAllPendingUploadsAsync()
    //used data actions
    private val _manageLimitReachedWarning: MutableLiveData<Event<Boolean>> =
        MutableLiveData()
    private val _manageLimitApproachingWarning: MutableLiveData<Event<Boolean>> =
        MutableLiveData()
    private val _manageLimitBelowCritical: MutableLiveData<Event<Boolean>> =
        MutableLiveData()
    private val _manageLimitReachedWarningOnTryCompose: MutableLiveData<Event<Boolean>> =
        MutableLiveData()

    val manageLimitReachedWarning: LiveData<Event<Boolean>>
        get() = _manageLimitReachedWarning
    val manageLimitApproachingWarning: LiveData<Event<Boolean>>
        get() = _manageLimitApproachingWarning
    val manageLimitBelowCritical: LiveData<Event<Boolean>>
        get() = _manageLimitBelowCritical
    val manageLimitReachedWarningOnTryCompose: LiveData<Event<Boolean>>
        get() = _manageLimitReachedWarningOnTryCompose

    private val _toastMessageMaxLabelsReached = MutableLiveData<Event<MaxLabelsReached>>()
    val toastMessageMaxLabelsReached: LiveData<Event<MaxLabelsReached>>
        get() = _toastMessageMaxLabelsReached

    fun reloadDependenciesForUser() {
        pendingSendsLiveData = messageDetailsRepository.findAllPendingSendsAsync()
        pendingUploadsLiveData = messageDetailsRepository.findAllPendingUploadsAsync()
    }

    fun usedSpaceActionEvent(limitReachedFlow: Int) {
        userManager.setShowStorageLimitReached(true)
        val user = userManager.user
        val userUsedSpace = user.usedSpace
        val userMaxSpace = if (user.maxSpace == 0L) Long.MAX_VALUE else user.maxSpace
        val percentageUsed = userUsedSpace * 100 / userMaxSpace
        val limitReached = percentageUsed >= 100
        val limitApproaching = percentageUsed >= Constants.STORAGE_LIMIT_WARNING_PERCENTAGE

        when (limitReachedFlow) {
            FLOW_START_ACTIVITY -> {
                if (limitReached) {
                    _manageLimitReachedWarning.postValue(Event(limitReached))
                } else if (limitApproaching) {
                    _manageLimitApproachingWarning.postValue(Event(limitApproaching))
                }
            }
            FLOW_USED_SPACE_CHANGED -> {
                if (limitReached) {
                    _manageLimitReachedWarning.postValue(Event(limitReached))
                } else if (limitApproaching) {
                    _manageLimitApproachingWarning.postValue(Event(limitApproaching))
                } else {
                    _manageLimitBelowCritical.postValue(Event(true))
                }
            }
            FLOW_TRY_COMPOSE -> {
                    _manageLimitReachedWarningOnTryCompose.postValue(Event(limitReached))
            }
        }
    }

    private fun getAllLabelsByIds(labelIds: List<String>) =
        messageDetailsRepository.findAllLabelsWithIds(labelIds)

    fun processLabels(messageIds: List<String>, checkedLabelIds: List<String>, unchangedLabels: List<String>) {
        //check for case of too many labels being added to a message- will need to edit this if removing labels is introduced to mailbox view
        val iterator = messageIds.iterator()

        val labelsToApplyMap = HashMap<String, MutableList<String>>()
        val labelsToRemoveMap = HashMap<String, MutableList<String>>()
        var result: Pair<Map<String, List<String>>, Map<String, List<String>>>? = null

        GlobalScope.launch(Dispatchers.Default, CoroutineStart.DEFAULT) {
            withContext(Dispatchers.Default) {
                while (iterator.hasNext()) {
                    val messageId = iterator.next()
                    val message = messageDetailsRepository.findMessageById(messageId, Dispatchers.Default)

                    if (message != null) {
                        val currentLabelsIds = message.labelIDsNotIncludingLocations
                        val labels = getAllLabelsByIds(currentLabelsIds)
                        val applyRemoveLabels = resolveMessageLabels(message, ArrayList(checkedLabelIds), ArrayList<String>(unchangedLabels), labels)
                        val apply = applyRemoveLabels?.labelsToApply
                        val remove = applyRemoveLabels?.labelsToRemove
                        apply?.forEach {
                            var labelsToApply: MutableList<String>? = labelsToApplyMap[it]
                            if (labelsToApply == null) {
                                labelsToApply = ArrayList()
                            }
                            labelsToApply.add(messageId)
                            labelsToApplyMap[it] = labelsToApply
                        }
                        remove?.forEach {
                            var labelsToRemove: MutableList<String>? = labelsToRemoveMap[it]
                            if (labelsToRemove == null) {
                                labelsToRemove = ArrayList()
                            }
                            labelsToRemove.add(messageId)
                            labelsToRemoveMap[it] = labelsToRemove
                        }
                    }
                }

                result = Pair(labelsToApplyMap, labelsToRemoveMap)
            }
            val applyKeySet = result?.first?.keys
            val removeKeySet = result?.second?.keys
            applyKeySet?.forEach {
                jobManager.addJobInBackground(ApplyLabelJob(labelsToApplyMap[it], it))
            }

            removeKeySet?.forEach {
                jobManager.addJobInBackground(RemoveLabelJob(labelsToRemoveMap[it], it))
            }
        }
    }

    private fun resolveMessageLabels(message: Message, checkedLabelIds: MutableList<String>, unchangedLabels: List<String>, currentContactLabels: List<Label>?): ApplyRemoveLabels? {
        val labelsToRemove = ArrayList<String>()

        //handle the case where too many labels exist for this message
        currentContactLabels?.forEach {
            val labelId = it.id
            if (!checkedLabelIds.contains(labelId) && !unchangedLabels.contains(labelId) && !it.exclusive) {
                // this label should be removed
                labelsToRemove.add(labelId)
            } else if (checkedLabelIds.contains(labelId)) {
                // the label remains
                checkedLabelIds.remove(labelId)
            }
        }

        val labelList = ArrayList(message.labelIDsNotIncludingLocations)
        labelList.addAll(checkedLabelIds)
        labelList.removeAll(labelsToRemove)
        val labelSet = labelList.toSet()
        val maxLabelsAllowed = UserUtils.getMaxAllowedLabels(userManager)

        if (labelSet.size > maxLabelsAllowed) {
            _toastMessageMaxLabelsReached.value = Event(MaxLabelsReached(message.subject, maxLabelsAllowed))
            return null
        }

        // update the message with the new contactLabels
        message.addLabels(checkedLabelIds)
        message.removeLabels(labelsToRemove)
        messageDetailsRepository.saveMessageInDB(message)

        return ApplyRemoveLabels(checkedLabelIds, labelsToRemove)
    }

    companion object {

        fun create(activity: BaseActivity,
                   messageDetailsRepository: MessageDetailsRepository,
                   userManager: UserManager,
                   jobManager: JobManager): MailboxViewModel =
            ViewModelProviders.of(activity, MailboxViewModelFactory(messageDetailsRepository, userManager, jobManager)).get(MailboxViewModel::class.java)
    }

    private class MailboxViewModelFactory(private val messageDetailsRepository: MessageDetailsRepository,
                                          private val userManager: UserManager, private val jobManager: JobManager) : ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel?> create(modelClass: Class<T>): T {
            return MailboxViewModel(
                    messageDetailsRepository, userManager,
                jobManager ) as T
        }
    }

    data class MaxLabelsReached(val subject: String?, val maxAllowedLabels: Int)
}
