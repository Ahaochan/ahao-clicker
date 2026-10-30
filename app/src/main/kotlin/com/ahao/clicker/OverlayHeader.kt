package com.ahao.clicker

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/** Keeps existing controls on one row, shrinking their cells and icons in narrow windows. */
internal class OverlayHeader(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    private var visibleControls = emptyList<View>()
    private var cells = emptyList<OverlayRect>()
    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(attrs: AttributeSet) = LayoutParams(context, attrs)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        visibleControls = (0 until childCount).map(::getChildAt).filter { it.visibility != GONE }
        val target = resources.getDimensionPixelSize(R.dimen.overlay_button_target)
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED)
            target * visibleControls.size else MeasureSpec.getSize(widthMeasureSpec)
        cells = overlayHeaderCells(width, target, visibleControls.size)
        visibleControls.forEachIndexed { index, view ->
            val cell = cells[index]
            val icon = minOf(resources.getDimensionPixelSize(R.dimen.overlay_icon_size), cell.height * 3 / 5)
            val inset = (cell.height - icon) / 2
            if (view.paddingLeft != inset) view.setPadding(inset, inset, inset, inset)
            view.measure(MeasureSpec.makeMeasureSpec(cell.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cell.height, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(width, resolveSize(cells.maxOf { it.top + it.height }, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        visibleControls.forEachIndexed { index, view ->
            val cell = cells[index]
            view.layout(cell.left, cell.top, cell.left + cell.width, cell.top + cell.height)
        }
    }
}
