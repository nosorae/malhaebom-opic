package io.github.nosorae.aiopic.apps.opic.shared

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.time.Instant

/** 위젯 첫 줄 문구(F19.6). 문구는 리소스에서 읽어서 Android 리소스가 있는 테스트로 본다. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ReviewWidgetTitleTest {
    private val at = Instant.parse("2026-11-20T11:00:00Z")

    @Test
    fun `F19_6 할 카드가 없으면 오늘 복습 끝 문구이고 있으면 남은 장 수를 보인다`() = runBlocking {
        assertEquals("오늘 복습 끝", ReviewWidget(at, left = 0, weekDays = 0).title())
        assertEquals("오늘 복습 2장", ReviewWidget(at, left = 2, weekDays = 0).title())
    }
}
