package com.driezy.medlog.data.model

enum class BloodPressureClassification { LOW, NORMAL, ELEVATED, STAGE_1, STAGE_2, CRISIS }

enum class BmiClassification { UNDERWEIGHT, NORMAL, OVERWEIGHT, OBESE }

/** Pure health metric language; Android labels are presentation mappings. */
enum class HealthType(
    val unit: String,
    val normalMin: Double,
    val normalMax: Double,
    val normalSecMin: Double? = null,
    val normalSecMax: Double? = null,
    val trendThreshold: Double = 0.5,
) {
    BLOOD_PRESSURE("mmHg", 90.0, 120.0, 60.0, 80.0, 3.0),
    BLOOD_GLUCOSE("mmol/L", 3.9, 6.1, trendThreshold = 0.3),
    WEIGHT("kg", 0.0, Double.MAX_VALUE, trendThreshold = 0.5),
    BODY_FAT("%", 5.0, 45.0, trendThreshold = 1.0),
    HEART_RATE("bpm", 60.0, 100.0, trendThreshold = 3.0),
    TEMPERATURE("°C", 36.1, 37.3, trendThreshold = 0.2),
    SPO2("%", 95.0, 100.0, trendThreshold = 1.0),

    /**
     * 氧流量（L/min）：家用氧疗没有统一的"临床正常值"，处方量因人而异，常见 1–5 L/min。
     * 因此刻意取一个远宽于真实处方量的区间（0–15 L/min），只用于挡住明显离谱的输入；
     * 趋势阈值取 1.0 L/min（家用流量计的最小调节档位）。
     */
    OXYGEN_FLOW("L/min", 0.0, 15.0, trendThreshold = 1.0),

    /**
     * 读数次数（次）：言语/认知训练里的"读了多少遍"，是计数量而非体征，同样没有正常范围。
     * 区间刻意放宽到 0–1000 次；趋势阈值取 1 次（再多读一遍即视为变化）。
     */
    READING_COUNT("次", 0.0, 1000.0, trendThreshold = 1.0),
    ;

    fun isNormal(value: Double): Boolean = value in normalMin..normalMax

    fun formatValue(value: Double, secondaryValue: Double?): String = when (this) {
        BLOOD_PRESSURE -> if (secondaryValue != null) {
            "${value.toInt()}/${secondaryValue.toInt()} $unit"
        } else {
            "${value.toInt()} $unit"
        }
        TEMPERATURE, BLOOD_GLUCOSE, WEIGHT, BODY_FAT, OXYGEN_FLOW -> "%.1f %s".format(value, unit)
        READING_COUNT -> "${value.toInt()} $unit"
        else -> "${value.toInt()} $unit"
    }

    companion object {
        fun fromName(name: String): HealthType = entries.firstOrNull { it.name == name } ?: BLOOD_PRESSURE

        fun classifyBloodPressure(systolic: Double, diastolic: Double): BloodPressureClassification = when {
            systolic < 90 || diastolic < 60 -> BloodPressureClassification.LOW
            systolic < 120 && diastolic < 80 -> BloodPressureClassification.NORMAL
            systolic < 130 && diastolic < 80 -> BloodPressureClassification.ELEVATED
            systolic < 140 || diastolic < 90 -> BloodPressureClassification.STAGE_1
            systolic < 180 || diastolic < 120 -> BloodPressureClassification.STAGE_2
            else -> BloodPressureClassification.CRISIS
        }

        fun calculateBmi(weightKg: Double, heightCm: Double): Double? {
            if (heightCm <= 0) return null
            val heightM = heightCm / 100.0
            return weightKg / (heightM * heightM)
        }

        fun classifyBmi(bmi: Double): BmiClassification = when {
            bmi < 18.5 -> BmiClassification.UNDERWEIGHT
            bmi < 24.0 -> BmiClassification.NORMAL
            bmi < 28.0 -> BmiClassification.OVERWEIGHT
            else -> BmiClassification.OBESE
        }
    }
}
