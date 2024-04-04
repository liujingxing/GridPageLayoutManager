package androidx.recyclerview.widget

/**
 * User: ljx
 * Date: 2024/4/4
 * Time: 23:10
 */
fun IntArray.rangeRemoved(positionStart: Int, count: Int): Boolean {
    //remove [positionStart, positionEnd) range
    val positionEnd: Int = positionStart + count
    var changed = false
    forEachIndexed { index, value ->
        if (value >= positionStart) {
            val offset = if (value >= positionEnd) {
                count
            } else {
                value - positionStart + 1
            }
            set(index, value - offset)
            changed = true
        }
    }
    return changed
}

fun IntArray.rangeInsert(positionStart: Int, itemCount: Int): Boolean {
    var changed = false
    forEachIndexed { index, value ->
        if (positionStart <= value || value == -1 || (index == lastIndex && value + 1 == positionStart)) {
            set(index, value + itemCount)
            changed = true
        }
    }
    return changed
}