package com.ljx.layoutmanager

import androidx.recyclerview.widget.rangeInsert
import androidx.recyclerview.widget.rangeRemoved
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun testRangeRemoved() {
        val array = intArrayOf(19, 22, 42, 50)
        array.rangeRemoved(40, 10)
        assertArrayEquals(intArrayOf(19, 22, 39, 40), array)

        val array1 = intArrayOf(19, 22, 42, 50)
        array1.rangeRemoved(43, 8)
        assertArrayEquals(intArrayOf(19, 22, 42, 42), array1)

        val array2 = intArrayOf(19, 22, 42, 50)
        array2.rangeRemoved(50, 1)
        assertArrayEquals(intArrayOf(19, 22, 42, 49), array2)
    }

    @Test
    fun testRangeInsert() {
        val array = intArrayOf(19, 22, 42, 50)
        array.rangeInsert(51, 10)
        assertArrayEquals(intArrayOf(19, 22, 42, 60), array)

        val array1 = intArrayOf(19, 22, 42, 50)
        array1.rangeInsert(40, 8)
        assertArrayEquals(intArrayOf(19, 22, 50, 58), array1)

        val array2 = intArrayOf(19, 22, 42, 50)
        array2.rangeInsert(0, 25)
        assertArrayEquals(intArrayOf(44, 47, 67, 75), array2)
    }
}