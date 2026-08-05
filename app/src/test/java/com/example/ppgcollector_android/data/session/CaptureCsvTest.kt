package com.example.ppgcollector_android.data.session

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureCsvTest {
    @Test
    fun schemaHeaderHasFrozenTwentyFiveColumns() {
        assertEquals(25, CaptureCsvSchema.columns.size)
        assertEquals(
            "schema_version,session_id,sample_index,device_time_s,host_frame_time_ns," +
                "frame_sequence,sample_in_frame,red,ir,heart_rate_bpm,heart_rate_valid," +
                "heart_rate_time_s,spo2_percent,spo2_valid,spo2_time_s,sqi,sqi_valid," +
                "sqi_time_s,soft_version,alg_version,preprocess_profile,protocol_profile," +
                "ratio_of_ratios,ratio_of_ratios_valid,ratio_of_ratios_time_s\n",
            CaptureCsvSchema.header,
        )
    }

    @Test
    fun formatterUsesRootLocaleAndRelativeMetricTime() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val row = sampleRow(
                sampleIndex = 799,
                heartRate = CsvMetricCell(72.1256789, true, 800),
                oxygenSaturation = CsvMetricCell(null, false, null),
                signalQuality = CsvMetricCell(null, false, null),
                ratio = CsvMetricCell(1.25, true, 100),
            )

            val csv = CaptureCsvFormatter.format(row, firstStreamSampleIndex = 0)

            assertTrue(csv.contains(",799,7.990000,72623859790382856,255,49,"))
            assertTrue(csv.contains(",72.125679,true,8.000000,,false,,0,false,,"))
            assertTrue(csv.contains(",1.250000,true,1.000000\n"))
            assertTrue(csv.contains("7.990000"))
            assertTrue(!csv.contains("7,990000"))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun invalidMetricValuesUseEmptyOrZeroPolicyAndFalseValidity() {
        val row = sampleRow(
            heartRate = CsvMetricCell(99.0, false, 50),
            oxygenSaturation = CsvMetricCell(98.0, false, 50),
            signalQuality = CsvMetricCell(0.75, false, 50),
            ratio = CsvMetricCell(Double.NaN, true, 50),
        )

        val fields = CaptureCsvFormatter.format(row, firstStreamSampleIndex = 0)
            .trimEnd()
            .split(',')

        assertEquals(25, fields.size)
        assertEquals("", fields[9])
        assertEquals("false", fields[10])
        assertEquals("", fields[11])
        assertEquals("", fields[12])
        assertEquals("false", fields[13])
        assertEquals("", fields[14])
        assertEquals("0", fields[15])
        assertEquals("false", fields[16])
        assertEquals("", fields[17])
        assertEquals("", fields[22])
        assertEquals("false", fields[23])
        assertEquals("", fields[24])
    }

    @Test
    fun formatterAndParserPreserveRfc4180QuotedFields() {
        val row = sampleRow(
            sessionId = "capture, \"quoted\"\npart",
            softVersion = "soft,1",
            algorithmVersion = "alg\"1",
            preprocessProfile = "pre\nprofile",
        )
        val csv = CaptureCsvFormatter.format(row, firstStreamSampleIndex = 0)
        val parsed = CaptureCsvParser.parseRow(csv)

        assertEquals(row.sessionId, parsed.sessionId)
        assertEquals(row.softVersion, parsed.softVersion)
        assertEquals(row.algorithmVersion, parsed.algorithmVersion)
        assertEquals(row.preprocessProfile, parsed.preprocessProfile)
        assertEquals(row.sampleIndex, parsed.sampleIndex)
        assertEquals(row.red, parsed.red)
        assertEquals(row.ir, parsed.ir)
        assertEquals(row.heartRateBpm.value, parsed.heartRateBpm.value)
        assertTrue(parsed.heartRateBpm.isValid)
    }

    @Test
    fun parserValidatesHeaderFieldCountAndBooleanValues() {
        CaptureCsvParser.requireHeader(CaptureCsvSchema.header)
        CaptureCsvParser.requireHeader(CaptureCsvSchema.header.trimEnd() + "\r\n")

        assertParseFailure { CaptureCsvParser.requireHeader("wrong") }
        assertParseFailure { CaptureCsvParser.parseRow("one,two") }
        assertParseFailure {
            CaptureCsvParser.parseRow(
                CaptureCsvFormatter.format(sampleRow(), 0).replace(",true,", ",maybe,"),
            )
        }
        assertParseFailure {
            CaptureCsvParser.parseRow(
                CaptureCsvFormatter
                    .format(sampleRow(sessionId = "capture,1"), 0)
                    .replace("\"capture,1\"", "\"unterminated,1"),
            )
        }
        assertParseFailure {
            CaptureCsvParser.parseRow(
                CaptureCsvFormatter.format(sampleRow(), 0)
                    .replace(",0.490000,", ",0.500000,"),
            )
        }
    }

    @Test
    fun versionTwoPreservesUInt32FrameSequenceWhileVersionOneRemainsUInt8() {
        val versionTwo = sampleRow().copy(
            schemaVersion = CaptureCsvSchema.version2,
            frameSequence = 0xFEDC_BA98u,
        )
        assertEquals(
            0xFEDC_BA98u,
            CaptureCsvParser.parseRow(CaptureCsvFormatter.format(versionTwo, 0)).frameSequence,
        )
        assertParseFailure {
            CaptureCsvParser.parseRow(
                CaptureCsvFormatter.format(
                    versionTwo.copy(schemaVersion = CaptureCsvSchema.version1),
                    0,
                ),
            )
        }
    }

    private fun sampleRow(
        sessionId: String = "capture-001",
        sampleIndex: Long = 49,
        heartRate: CsvMetricCell = CsvMetricCell(72.0, true, 50),
        oxygenSaturation: CsvMetricCell = CsvMetricCell(null, false, null),
        signalQuality: CsvMetricCell = CsvMetricCell(0.92, true, 49),
        ratio: CsvMetricCell = CsvMetricCell(null, false, null),
        softVersion: String = "soft-0.1",
        algorithmVersion: String = "unavailable",
        preprocessProfile: String = "raw-only-0.1",
        protocolProfile: String = "cup_batch_v1_draft",
    ) = CaptureCsvRow(
        schemaVersion = "ppgcollector_samples_v1",
        sessionId = sessionId,
        sampleIndex = sampleIndex,
        hostFrameTimeNanoseconds = 72_623_859_790_382_856u,
        frameSequence = 255u,
        sampleInFrame = 49,
        red = 100_000u,
        ir = 120_000u,
        heartRateBpm = heartRate,
        oxygenSaturationPercent = oxygenSaturation,
        signalQuality = signalQuality,
        softVersion = softVersion,
        algorithmVersion = algorithmVersion,
        preprocessProfile = preprocessProfile,
        protocolProfile = protocolProfile,
        ratioOfRatios = ratio,
    )

    private fun assertParseFailure(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected CSV parse failure")
        } catch (_: CaptureCsvParseException) {
            // expected
        }
    }
}
