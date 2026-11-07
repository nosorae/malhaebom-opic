package io.github.nosorae.aiopic.ui.featurelive

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.nosorae.aiopic.engine.answer.store.OpicDatabase
import io.github.nosorae.aiopic.engine.answer.store.OpicDatabaseConstructor
import kotlin.coroutines.CoroutineContext

actual fun inMemoryDatabase(context: CoroutineContext?): OpicDatabase = Room.inMemoryDatabaseBuilder<OpicDatabase>(factory = OpicDatabaseConstructor::initialize)
    .setDriver(BundledSQLiteDriver())
    .apply { context?.let(::setQueryCoroutineContext) }
    .build()
