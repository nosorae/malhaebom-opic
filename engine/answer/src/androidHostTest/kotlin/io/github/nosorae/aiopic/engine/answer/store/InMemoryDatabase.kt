package io.github.nosorae.aiopic.engine.answer.store

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlin.coroutines.CoroutineContext

actual fun inMemoryDatabase(context: CoroutineContext?): OpicDatabase = Room.inMemoryDatabaseBuilder<OpicDatabase>(factory = OpicDatabaseConstructor::initialize)
    .setDriver(BundledSQLiteDriver())
    .apply { context?.let(::setQueryCoroutineContext) }
    .build()

actual fun freshDbPath(name: String): String = File(System.getProperty("java.io.tmpdir"), name).path.also { path ->
    listOf("", "-wal", "-shm").forEach { File(path + it).delete() }
}
