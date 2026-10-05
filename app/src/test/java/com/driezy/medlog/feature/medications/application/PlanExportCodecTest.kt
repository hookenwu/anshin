package com.driezy.medlog.feature.medications.application

import com.driezy.medlog.data.model.Medication
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.zip.GZIPOutputStream

/**
 * [PlanExportCodec] 单元测试。
 *
 * 覆盖：encode/decode 往返转换、无效输入处理、归档药品过滤、
 * QR 可用性判断。不依赖 Android 运行时，纯 JVM 。
 */
class PlanExportCodecTest {
    private val utc = ZoneId.of("UTC")

    // ── 测试辅助 ──────────────────────────────────────────────────────────────

    private fun med(
        name: String = "药品A",
        doseUnit: String = "片",
        timePeriod: String = "exact",
        reminderTimes: String = "08:00",
        reminderHour: Int = 8,
        reminderMinute: Int = 0,
        isArchived: Boolean = false,
        doseQuantity: Double = 1.0,
        doseStrength: Double? = null,
        doseStrengthUnit: String? = null,
    ) = Medication(
        id = 0,
        name = name,
        doseUnit = doseUnit,
        timePeriod = timePeriod,
        reminderTimes = reminderTimes,
        reminderHour = reminderHour,
        reminderMinute = reminderMinute,
        isArchived = isArchived,
        doseQuantity = doseQuantity,
        doseStrength = doseStrength,
        doseStrengthUnit = doseStrengthUnit,
    )

    // ── encode / decode 基本往返 ───────────────────────────────────────────────

    @Test
    fun `encode produces anshin v1 prefix`() {
        val encoded = PlanExportCodec.encode(listOf(med()), utc)
        assertNotNull("encode 不应返回 null", encoded)
        assertTrue(
            "编码结果必须以 '${PlanExportCodec.SCHEME}' 开头",
            encoded!!.startsWith(PlanExportCodec.SCHEME),
        )
    }

    @Test
    fun `decode returns null for empty string`() {
        assertNull(PlanExportCodec.decode(""))
    }

    @Test
    fun `decode returns null for invalid prefix`() {
        assertNull(PlanExportCodec.decode("notanshin:v1:abc"))
    }

    @Test
    fun `decode returns null for garbage after prefix`() {
        assertNull(PlanExportCodec.decode("${PlanExportCodec.SCHEME}!!!invalid-base64!!!"))
    }

    @Test
    fun `decodeWithDiagnostics reports invalid payload reason`() {
        val result = PlanExportCodec.decodeWithDiagnostics("${PlanExportCodec.SCHEME}!!!invalid-base64!!!")

        assertTrue(result is PlanExportDecodeResult.Failure)
        assertTrue((result as PlanExportDecodeResult.Failure).reason.contains("IllegalArgumentException"))
    }

    @Test
    fun `encode then decode round-trips medication name and doseUnit`() {
        val original = med(name = "布洛芬", doseUnit = "粒")
        val encoded = PlanExportCodec.encode(listOf(original), utc)!!
        val plan = PlanExportCodec.decode(encoded)

        assertNotNull("decode 不应返回 null", plan)
        assertEquals(1, plan!!.meds.size)
        val entry = plan.meds.first()
        assertEquals("布洛芬", entry.name)
        assertEquals("粒", entry.doseUnit)
    }

    @Test
    fun `encode then decode round-trips reminderTimes and hours`() {
        val original = med(reminderTimes = "08:00,20:00", reminderHour = 8, reminderMinute = 0)
        val encoded = PlanExportCodec.encode(listOf(original), utc)!!
        val plan = PlanExportCodec.decode(encoded)!!

        val entry = plan.meds.first()
        assertEquals("08:00,20:00", entry.reminderTimes)
        assertEquals(8, entry.reminderHour)
        assertEquals(0, entry.reminderMinute)
    }

    @Test
    fun `encode then decode preserves multiple medications`() {
        val meds = listOf(med("药A"), med("药B"), med("药C"))
        val encoded = PlanExportCodec.encode(meds, utc)!!
        val plan = PlanExportCodec.decode(encoded)!!

        assertEquals(3, plan.meds.size)
        assertEquals(listOf("药A", "药B", "药C"), plan.meds.map { it.name })
    }

    @Test
    fun `encode then toMedication restores name, doseUnit, reminderTimes`() {
        val original = med(name = "阿司匹林", doseUnit = "mg", reminderTimes = "12:00")
        val encoded = PlanExportCodec.encode(listOf(original), utc)!!
        val plan = PlanExportCodec.decode(encoded)!!
        val restored = with(PlanExportCodec) {
            plan.meds.first().toMedication(Instant.parse("2026-08-02T04:00:00Z"), ZoneId.of("Asia/Shanghai"))
        }

        assertEquals("阿司匹林", restored.name)
        assertEquals("mg", restored.doseUnit)
        assertEquals("12:00", restored.reminderTimes)
    }

    @Test
    fun `invalid legacy start date uses injected instant instead of wall clock`() {
        val fallback = Instant.parse("2026-08-02T04:00:00Z")
        val entry = MedExportEntry(
            name = "药品A",
            doseUnit = "片",
            timePeriod = "exact",
            reminderTimes = "08:00",
            reminderHour = 8,
            reminderMinute = 0,
            startDate = "invalid",
        )

        val restored = with(PlanExportCodec) { entry.toMedication(fallback, ZoneId.of("Asia/Shanghai")) }

        assertEquals(fallback.toEpochMilli(), restored.startDate)
    }

    @Test
    fun `exported calendar date is interpreted in the explicit travel zone`() {
        val losAngeles = ZoneId.of("America/Los_Angeles")
        val start = LocalDate.of(2025, 3, 9).atStartOfDay(losAngeles).toInstant()
        val encoded = PlanExportCodec.encode(listOf(med().copy(startDate = start.toEpochMilli())), losAngeles)!!

        val restored = with(PlanExportCodec) {
            PlanExportCodec.decode(encoded)!!.meds.single().toMedication(Instant.EPOCH, losAngeles)
        }

        assertEquals(start, Instant.ofEpochMilli(restored.startDate))
    }

    // ── 归档药品处理 ───────────────────────────────────────────────────────────

    @Test
    fun `encode skips archived medications`() {
        val meds = listOf(
            med(name = "活跃药", isArchived = false),
            med(name = "已归档药", isArchived = true),
        )
        val encoded = PlanExportCodec.encode(meds, utc)!!
        val plan = PlanExportCodec.decode(encoded)!!

        assertEquals(1, plan.meds.size)
        assertEquals("活跃药", plan.meds.first().name)
    }

    @Test
    fun `encode returns null for fully archived list`() {
        // encode 当活跃列表为空时，返回内容中 meds 为空
        val meds = listOf(med(isArchived = true))
        val encoded = PlanExportCodec.encode(meds, utc)
        // encode 本身不为 null，但解码后 meds 列表为空
        val plan = if (encoded != null) PlanExportCodec.decode(encoded) else null
        assertTrue("纯归档列表编码后 meds 应为空", plan == null || plan.meds.isEmpty())
    }

    // ── canDisplayAsQr ────────────────────────────────────────────────────────

    @Test
    fun `canDisplayAsQr returns true for small list`() {
        val encoded = PlanExportCodec.encode(listOf(med()), utc)!!
        assertTrue("单一药品应可显示二维码", PlanExportCodec.canDisplayAsQr(encoded))
    }

    @Test
    fun `canDisplayAsQr returns false for string exceeding 2900 chars`() {
        val longString = PlanExportCodec.SCHEME + "x".repeat(3000)
        assertFalse(PlanExportCodec.canDisplayAsQr(longString))
    }

    // ── 版本和 app 字段 ────────────────────────────────────────────────────────

    @Test
    fun `decoded export has correct version and app fields`() {
        val encoded = PlanExportCodec.encode(listOf(med()), utc)!!
        val plan = PlanExportCodec.decode(encoded)!!
        assertEquals(1, plan.version)
        assertEquals("anshin", plan.app)
    }

    // ── 规格（单粒强度）与旧码兼容 ─────────────────────────────────────────────

    @Test
    fun `encode then decode round-trips dose strength and restores it`() {
        val original = med(
            name = "格华止",
            doseUnit = "粒",
            doseQuantity = 2.0,
            doseStrength = 0.25,
            doseStrengthUnit = "g",
        )
        val encoded = PlanExportCodec.encode(listOf(original), utc)!!
        val entry = PlanExportCodec.decode(encoded)!!.meds.single()

        assertEquals(0.25, entry.doseStrength!!, 0.0)
        assertEquals("g", entry.doseStrengthUnit)

        val restored = with(PlanExportCodec) { entry.toMedication(Instant.EPOCH, utc) }
        assertEquals(0.25, restored.doseStrength!!, 0.0)
        assertEquals("g", restored.doseStrengthUnit)
        assertEquals(2.0, restored.doseQuantity, 0.0)
    }

    @Test
    fun `medication without strength round-trips with null strength`() {
        val encoded = PlanExportCodec.encode(listOf(med()), utc)!!
        val entry = PlanExportCodec.decode(encoded)!!.meds.single()

        assertNull(entry.doseStrength)
        assertNull(entry.doseStrengthUnit)
    }

    @Test
    fun `legacy payload carrying the removed d field still decodes`() {
        // 旧版本同时写 d 与 dq（都等于 doseQuantity）；d 已被移除，解码端必须忽略未知键。
        val legacyJson = """
            {"v":1,"app":"anshin","meds":[
              {"n":"旧药","d":2.0,"u":"片","tp":"exact","rt":"08:00","rh":8,"rm":0,"dq":2.0}
            ]}
        """.trimIndent()

        val plan = PlanExportCodec.decode(legacyPayload(legacyJson))

        assertNotNull(plan)
        val entry = plan!!.meds.single()
        assertEquals("旧药", entry.name)
        assertEquals("片", entry.doseUnit)
        assertEquals(2.0, entry.doseQuantity, 0.0)
    }

    @Test
    fun `legacy payload without dq still decodes without crashing`() {
        // 极旧码只带 d：d 被忽略，dq 回落默认值；关键是解码不失败、名称与单位保留。
        val legacyJson = """
            {"v":1,"app":"anshin","meds":[
              {"n":"更旧药","d":3.0,"u":"粒","tp":"exact","rt":"08:00","rh":8,"rm":0}
            ]}
        """.trimIndent()

        val plan = PlanExportCodec.decode(legacyPayload(legacyJson))

        assertNotNull(plan)
        assertEquals("更旧药", plan!!.meds.single().name)
    }

    /** 复刻旧版本的压缩管道：JSON → gzip → URL-safe Base64（无 padding）。 */
    private fun legacyPayload(json: String): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return PlanExportCodec.SCHEME + Base64.getUrlEncoder().withoutPadding().encodeToString(bos.toByteArray())
    }
}
