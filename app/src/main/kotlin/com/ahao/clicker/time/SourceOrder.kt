package com.ahao.clicker.time

import com.ahao.clicker.core.BuiltinTimeSource

internal object SourceOrder {
    private val defaults = listOf(BuiltinTimeSource.JD, BuiltinTimeSource.TAOBAO, BuiltinTimeSource.ALIYUN,
        BuiltinTimeSource.TENCENT, BuiltinTimeSource.CLOUDFLARE, BuiltinTimeSource.LOCAL)
    fun decode(saved: String): List<BuiltinTimeSource> = (saved.take(4_096).split(',').mapNotNull { name ->
        BuiltinTimeSource.entries.firstOrNull { it.name == name.trim() }
    } + defaults).distinct()
    fun encode(order: List<BuiltinTimeSource>): String = order.joinToString(",") { it.name }
    fun move(order: List<BuiltinTimeSource>, source: BuiltinTimeSource, target: BuiltinTimeSource): List<BuiltinTimeSource> {
        val from = order.indexOf(source); val to = order.indexOf(target)
        if (from < 0 || to < 0 || from == to) return order
        return order.toMutableList().apply { removeAt(from); add(to, source) }
    }
}
