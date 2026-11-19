package io.github.nosorae.aiopic.engine.answer.store

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.coroutines.CoroutineContext

actual fun inMemoryDatabase(context: CoroutineContext?): OpicDatabase = Room.inMemoryDatabaseBuilder<OpicDatabase>(factory = OpicDatabaseConstructor::initialize)
    .setDriver(BundledSQLiteDriver())
    .apply { context?.let(::setQueryCoroutineContext) }
    .build()

@OptIn(ExperimentalForeignApi::class)
actual fun freshDbPath(name: String): String = (NSTemporaryDirectory() + name).also { path ->
    listOf("", "-wal", "-shm").forEach { NSFileManager.defaultManager.removeItemAtPath(path + it, null) }
}
