package tech.illusion.spaceflightchess.content

import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelNormalizationTest {

    // RED 和 BLUE 在 task 3 round 4 设备日志里实测的 getVisualBounds(relativeTo = source) 读数——
    // 见 .superpowers/sdd/2026-09-21-hangar-window/task-3-report.md。RED 是两个「原始尺寸正常」
    // 的资产之一（最长边 ~0.29m）；BLUE 是两个「原始尺寸大 10-20 倍」的资产之一（最长边 ~3.2m）
    // ——正是这个量级差异，让 SpatialModelView 的 Resizability.FitInside 在没有归一化的情况下
    // 产出四种不同视觉大小。BoundingBox 的公开构造函数收 (center, halfExtent)，size = halfExtent*2。
    private val redBounds = BoundingBox(
        center = Vector3(4.7683713E-8f, 0.14842509f, 0.012640496f),
        halfExtent = Vector3(0.29242814f / 2f, 0.10691099f / 2f, 0.22169164f / 2f),
    )
    private val blueBounds = BoundingBox(
        center = Vector3(0.15478003f, -0.095912606f, -0.29493392f),
        halfExtent = Vector3(2.5631597f / 2f, 1.798265f / 2f, 3.2048972f / 2f),
    )
    private val targetLongestEdgeM = 0.08448f

    @Test
    fun `原始尺寸相差一个数量级的两个资产归一化到同一个最长边`() {
        val (redScale, _) = longestEdgeNormalization(redBounds, targetLongestEdgeM)
        val (blueScale, _) = longestEdgeNormalization(blueBounds, targetLongestEdgeM)

        val redLongestEdgeAfterScale = maxOf(redBounds.size.x, redBounds.size.y, redBounds.size.z) * redScale
        val blueLongestEdgeAfterScale = maxOf(blueBounds.size.x, blueBounds.size.y, blueBounds.size.z) * blueScale

        // JUnit 4 的 assertEquals 对浮点必须带 delta——不带 delta 的重载已废弃，Kotlin 下还会
        // 解析到装箱的 assertEquals(Object, Object)。
        assertEquals(targetLongestEdgeM, redLongestEdgeAfterScale, 0.0001f)
        assertEquals(targetLongestEdgeM, blueLongestEdgeAfterScale, 0.0001f)
        // BLUE 原始最长边(3.2m)是 RED(0.29m)的 ~11 倍，缩放后两者必须落在同一个目标值——这正是
        // Resizability.FitInside 做不到、这个函数存在的理由。
        assertEquals(redLongestEdgeAfterScale, blueLongestEdgeAfterScale, 0.0001f)
    }

    @Test
    fun `回中偏移把缩放后的包围盒中心归零`() {
        val (redScale, redRecenter) = longestEdgeNormalization(redBounds, targetLongestEdgeM)
        val (blueScale, blueRecenter) = longestEdgeNormalization(blueBounds, targetLongestEdgeM)

        assertEquals(0f, redBounds.center.x * redScale + redRecenter.x, 0.0001f)
        assertEquals(0f, redBounds.center.y * redScale + redRecenter.y, 0.0001f)
        assertEquals(0f, redBounds.center.z * redScale + redRecenter.z, 0.0001f)

        assertEquals(0f, blueBounds.center.x * blueScale + blueRecenter.x, 0.0001f)
        assertEquals(0f, blueBounds.center.y * blueScale + blueRecenter.y, 0.0001f)
        assertEquals(0f, blueBounds.center.z * blueScale + blueRecenter.z, 0.0001f)
    }

    @Test
    fun `退化包围盒不会产生除零或无穷大`() {
        val degenerate = BoundingBox(center = Vector3.ZERO, halfExtent = Vector3.ZERO)

        val (scale, recenter) = longestEdgeNormalization(degenerate, targetLongestEdgeM)

        assertTrue("退化包围盒不该产出非有限 scale，实际是 $scale", scale.isFinite())
        assertEquals(0f, recenter.x, 0f)
        assertEquals(0f, recenter.y, 0f)
        assertEquals(0f, recenter.z, 0f)
    }
}
