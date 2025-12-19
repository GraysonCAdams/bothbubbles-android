package com.bothbubbles.services.notifications

import com.bothbubbles.data.repository.UnifiedChatGroupRepository
import com.bothbubbles.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class BadgeManager @Inject constructor(
    private val unifiedChatGroupRepository: UnifiedChatGroupRepository,
    private val notificationServiceProvider: Provider<NotificationService>,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val _totalUnread = MutableStateFlow(0)
    val totalUnread: StateFlow<Int> = _totalUnread

    init {
        scope.launch {
            unifiedChatGroupRepository.observeTotalUnreadCount()
                .collect { count ->
                    val unreadCount = count ?: 0
                    _totalUnread.value = unreadCount
                    notificationServiceProvider.get().updateAppBadge(unreadCount)
                }
        }
    }
}
