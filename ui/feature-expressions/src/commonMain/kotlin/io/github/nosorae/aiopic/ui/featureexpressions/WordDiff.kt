package io.github.nosorae.aiopic.ui.featureexpressions

/** 내가 한 말과 더 나은 표현을 낱말로 맞춘 결과 하나. */
sealed interface WordOp {
    val word: String

    data class Same(override val word: String) : WordOp

    /** 내가 한 말에만 있는 낱말. */
    data class Removed(override val word: String) : WordOp

    /** 더 나은 표현에만 있는 낱말. */
    data class Added(override val word: String) : WordOp
}

/** 공백으로 낱말을 나눠 가장 긴 공통 부분열로 맞춘다. 바뀐 자리에서는 빠진 낱말을 들어간 낱말보다 앞에 둔다(F18.3, F18.5). */
// ponytail: 낱말 수의 제곱만큼 표를 만든다. 카드 문장은 한 문장이라 짧다. 문단을 비교하게 되면 Myers 차이 계산으로 바꾼다.
fun diffWords(said: String, better: String): List<WordOp> {
    val a = said.split(' ').filter { it.isNotEmpty() }
    val b = better.split(' ').filter { it.isNotEmpty() }
    val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) {
        for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
    }
    val ops = mutableListOf<WordOp>()
    var i = 0
    var j = 0
    while (i < a.size || j < b.size) {
        when {
            i < a.size && j < b.size && a[i] == b[j] -> ops += WordOp.Same(a[i++]).also { j++ }
            i < a.size && (j == b.size || lcs[i + 1][j] >= lcs[i][j + 1]) -> ops += WordOp.Removed(a[i++])
            else -> ops += WordOp.Added(b[j++])
        }
    }
    return ops
}

/** 고칠 표현의 앞면(F18.4). 내가 한 말에서 바뀐 자리마다 빈칸 하나를 둔다. 빠뜨린 낱말 자리도 빈칸이다. */
fun maskedSaid(ops: List<WordOp>): String = buildList {
    ops.forEach { op ->
        when (op) {
            is WordOp.Same -> add(op.word)
            else -> if (lastOrNull() != BLANK) add(BLANK)
        }
    }
}.joinToString(" ")

private const val BLANK = "____"
