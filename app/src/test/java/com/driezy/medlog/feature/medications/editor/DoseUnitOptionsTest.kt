package com.driezy.medlog.feature.medications.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoseUnitOptionsTest {

    private val options = doseUnitOptions(
        tablet = "片",
        capsule = "粒",
        drop = "滴",
        bag = "袋",
        tube = "支",
        patch = "贴",
    )

    @Test
    fun `gram is offered as a dose unit`() {
        assertTrue("单位档位缺少 g（克）", options.contains("g"))
    }

    @Test
    fun `milligram millilitre gram and microgram are all offered together`() {
        assertTrue(options.contains("mg"))
        assertTrue(options.contains("ml"))
        assertTrue(options.contains("g"))
        assertTrue(options.contains("μg"))
    }

    @Test
    fun `options keep declaration order and have no duplicates`() {
        assertEquals(options.distinct(), options)
        assertEquals(
            listOf("片", "粒", "ml", "mg", "g", "μg", "滴", "袋", "支", "贴"),
            options,
        )
    }
}
