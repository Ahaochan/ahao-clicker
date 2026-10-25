package com.ahao.clicker.core

object StepText {
    fun parse(text: String): List<ClickStep> {
        require(text.length <= 64_000)
        val lines = text.trim().lines()
        require(lines.size in 1..500)
        return lines.map { line ->
            val fields = line.split(',')
            require(fields.size == 3) { "Each line needs x,y,waitMillis" }
            ClickStep(TapPoint(fields[0].trim().toFloat(), fields[1].trim().toFloat()), fields[2].trim().toLong())
        }
    }
    fun format(steps: List<ClickStep>): String = steps.joinToString("\n") {
        "${it.point.x},${it.point.y},${it.waitBeforeMillis}"
    }
}
