package app.adamhurwitz.cameraxfeed.utils

import android.database.Cursor
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.paging.Config
import androidx.paging.DataSource
import androidx.paging.LivePagedListBuilder
import androidx.room.RoomDatabase
import androidx.room.RoomSQLiteQuery
import androidx.room.paging.LimitOffsetDataSource
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.extension.ExtensionContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

val TEST_COROUTINE_DISPATCHER_NAMESPACE =
        ExtensionContext.Namespace.create(TEST_COROUTINE_DISPATCHER_NAMESPACE_STRING)
val TEST_COROUTINE_SCOPE_NAMESPACE =
        ExtensionContext.Namespace.create(TEST_COROUTINE_SCOPE_NAMESPACE_STRING)

/**
 * Gets the value of a [LiveData] or waits for it to have one, with a timeout.
 *
 * Use this extension from host-side (JVM) tests. It's recommended to use it alongside
 * `InstantTaskExecutorRule` or a similar mechanism to execute tasks synchronously.
 */
fun <T> LiveData<T>.getOrAwaitValue(
        time: Long = 5,
        timeUnit: TimeUnit = TimeUnit.SECONDS,
        afterObserve: () -> Unit = {}
): T {
    var data: T? = null
    val latch = CountDownLatch(1)
    val observer = object : Observer<T> {
        override fun onChanged(o: T?) {
            data = o
            latch.countDown()
            this@getOrAwaitValue.removeObserver(this)
        }
    }
    this.observeForever(observer)
    afterObserve.invoke()
    // Don't wait indefinitely if the LiveData is not set.
    if (!latch.await(time, timeUnit)) {
        this.removeObserver(observer)
        throw TimeoutException("LiveData value was never set.")
    }
    @Suppress("UNCHECKED_CAST")
    return data as T
}

fun <T> List<T>.asPagedList() = LivePagedListBuilder<Int, T>(
    createMockDataSourceFactory(this),
        Config(
                enablePlaceholders = false,
                prefetchDistance = 24,
                pageSize = if (size == 0) 1 else size
        )
).build().getOrAwaitValue()

private fun <T> createMockDataSourceFactory(itemList: List<T>): DataSource.Factory<Int, T> =
        object : DataSource.Factory<Int, T>() {
            override fun create(): DataSource<Int, T> =
                MockLimitDataSource(itemList)
        }

private val mockQuery = mockk<RoomSQLiteQuery> { every { sql } returns "" }

private val mockDb =
        mockk<RoomDatabase> { every { invalidationTracker } returns mockk(relaxUnitFun = true) }

class MockLimitDataSource<T>(private val itemList: List<T>) :
        LimitOffsetDataSource<T>(
            mockDb,
            mockQuery, false, null) {
    override fun convertRows(cursor: Cursor?): MutableList<T> = itemList.toMutableList()
    override fun countItems(): Int = itemList.count()
    override fun isInvalid(): Boolean = false
    override fun loadRange(
            params: LoadRangeParams,
            callback: LoadRangeCallback<T>
    ) {
        /* Not implemented */
    }

    override fun loadRange(startPosition: Int, loadCount: Int) =
            itemList.subList(startPosition, startPosition + loadCount).toMutableList()

    override fun loadInitial(params: LoadInitialParams, callback: LoadInitialCallback<T>) {
        callback.onResult(itemList, 0)
    }
}