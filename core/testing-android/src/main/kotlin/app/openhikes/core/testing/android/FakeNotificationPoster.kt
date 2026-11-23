package app.openhikes.core.testing.android

import android.app.Notification
import app.openhikes.core.location.NotificationPoster
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory [NotificationPoster] fake capturing posted Live Update notifications for assertions.
 */
@Singleton
class FakeNotificationPoster @Inject constructor() : NotificationPoster {
    private val posted = mutableListOf<Pair<Int, Notification>>()

    val postedNotifications: List<Pair<Int, Notification>>
        get() = posted.toList()

    var cancelCount: Int = 0
        private set

    var canPostPromoted: Boolean = true

    override fun canPostPromotedNotifications(): Boolean = canPostPromoted

    override fun post(notificationId: Int, notification: Notification) {
        posted.add(notificationId to notification)
    }

    override fun cancel(notificationId: Int) {
        cancelCount += 1
    }

    fun reset() {
        posted.clear()
        cancelCount = 0
    }
}
