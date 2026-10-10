package com.example.dingding

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.util.zip.ZipFile

data class ParsedScheduleData(
    val allDates: List<LocalDate>,
    val personSchedules: Map<String, List<LocalDate>>,
    val shiftTypes: Map<Pair<String, LocalDate>, String> = emptyMap()
)

object ScheduleExcelParser {

    private val weekdayWords = setOf(
        "一", "二", "三", "四", "五", "六", "日",
        "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日",
        "周一", "周二", "周三", "周四", "周五", "周六", "周日",
        "星期", "周"
    )

    private val nonNameKeywords = setOf(
        "休息", "公休", "休", "年假", "事假", "病假", "调休", "班", "值班",
        "白班", "夜班", "正常", "无", "班次", "姓名", "人员", "日期", "时间"
    )

    fun parse(context: Context, uri: Uri, currentYearMonth: YearMonth): ParsedScheduleData? {
        val tempFile = File(context.cacheDir, "temp_import_${System.currentTimeMillis()}.xlsx")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            return parseXlsxFile(tempFile, currentYearMonth)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    private fun parseXlsxFile(file: File, currentYearMonth: YearMonth): ParsedScheduleData? {
        return runCatching {
            val zipFile = ZipFile(file)
            try {
                // 1. 读取 sharedStrings.xml
                val sharedStrings = mutableListOf<String>()
                val sstEntry = zipFile.getEntry("xl/sharedStrings.xml")
                if (sstEntry != null) {
                    zipFile.getInputStream(sstEntry).use { input ->
                        val parser = Xml.newPullParser()
                        parser.setInput(input, "UTF-8")
                        var event = parser.eventType
                        var inSi = false
                        var inT = false
                        val curStr = StringBuilder()
                        while (event != XmlPullParser.END_DOCUMENT) {
                            when (event) {
                                XmlPullParser.START_TAG -> {
                                    if (parser.name == "si") {
                                        inSi = true
                                        curStr.clear()
                                    } else if (parser.name == "t") {
                                        inT = true
                                    }
                                }
                                XmlPullParser.TEXT -> {
                                    if (inSi && inT) {
                                        curStr.append(parser.text)
                                    }
                                }
                                XmlPullParser.END_TAG -> {
                                    if (parser.name == "t") {
                                        inT = false
                                    } else if (parser.name == "si") {
                                        inSi = false
                                        sharedStrings.add(curStr.toString())
                                    }
                                }
                            }
                            event = parser.next()
                        }
                    }
                }

                // 2. 找到工作表
                val sheetEntry = zipFile.getEntry("xl/worksheets/sheet1.xml")
                    ?: zipFile.entries().asSequence().firstOrNull {
                        it.name.startsWith("xl/worksheets/") && it.name.endsWith(".xml")
                    } ?: return null

                val rows = mutableListOf<Map<String, String>>()
                zipFile.getInputStream(sheetEntry).use { input ->
                    val parser = Xml.newPullParser()
                    parser.setInput(input, "UTF-8")
                    var event = parser.eventType
                    var currentRef = ""
                    var currentType = ""
                    val currentVal = StringBuilder()
                    var inV = false
                    var inT = false
                    var currentRow = mutableMapOf<String, String>()

                    while (event != XmlPullParser.END_DOCUMENT) {
                        when (event) {
                            XmlPullParser.START_TAG -> {
                                when (parser.name) {
                                    "row" -> currentRow = mutableMapOf()
                                    "c" -> {
                                        currentRef = parser.getAttributeValue(null, "r") ?: ""
                                        currentType = parser.getAttributeValue(null, "t") ?: ""
                                        currentVal.clear()
                                    }
                                    "v" -> inV = true
                                    "t" -> inT = true
                                }
                            }
                            XmlPullParser.TEXT -> {
                                if (inV || inT) {
                                    currentVal.append(parser.text)
                                }
                            }
                            XmlPullParser.END_TAG -> {
                                when (parser.name) {
                                    "v" -> inV = false
                                    "t" -> inT = false
                                    "c" -> {
                                        val col = currentRef.filter { it.isLetter() }
                                        val rawText = currentVal.toString()
                                        val resolved = if (currentType == "s") {
                                            val idx = rawText.toIntOrNull()
                                            if (idx != null && idx in sharedStrings.indices) sharedStrings[idx] else rawText
                                        } else {
                                            rawText
                                        }
                                        if (col.isNotEmpty()) {
                                            currentRow[col] = resolved
                                        }
                                    }
                                    "row" -> {
                                        if (currentRow.isNotEmpty()) {
                                            rows.add(currentRow)
                                        }
                                    }
                                }
                            }
                        }
                        event = parser.next()
                    }
                }

                if (rows.isEmpty()) return null

                // 3. 识别日期列
                val colDateCount = mutableMapOf<String, Int>()
                rows.forEach { r ->
                    r.forEach { (col, v) ->
                        if (parseCellDate(v, currentYearMonth) != null) {
                            colDateCount[col] = (colDateCount[col] ?: 0) + 1
                        }
                    }
                }
                val dateCol = colDateCount.maxByOrNull { it.value }?.key ?: return null
                val firstDateRowIndex = rows.indexOfFirst { parseCellDate(it[dateCol] ?: "", currentYearMonth) != null }
                if (firstDateRowIndex < 0) return null

                // 4. 统计各列有效名字出现次数，找出排班人员候选列
                val colNameCount = mutableMapOf<String, Int>()
                for (i in firstDateRowIndex until rows.size) {
                    val r = rows[i]
                    if (parseCellDate(r[dateCol] ?: "", currentYearMonth) == null) continue
                    for ((col, text) in r) {
                        if (col == dateCol) continue
                        val names = extractNamesFromCell(text)
                        if (names.isNotEmpty()) {
                            colNameCount[col] = (colNameCount[col] ?: 0) + 1
                        }
                    }
                }

                if (colNameCount.isEmpty()) return null

                // 候选人员列按自然列序（从左到右）排序
                val maxCount = colNameCount.values.maxOrNull() ?: 0
                val threshold = maxOf(1, (maxCount * 0.15).toInt())
                val sortedPersonCols = colNameCount
                    .filter { it.value >= threshold }
                    .keys
                    .sortedBy { colToIndex(it) }

                if (sortedPersonCols.isEmpty()) return null

                // 检查表头中是否有明确的“白班”或“夜班”标识
                var headerDayCol: String? = null
                var headerNightCol: String? = null
                for (i in 0 until firstDateRowIndex) {
                    val headerRow = rows[i]
                    for ((col, text) in headerRow) {
                        if (col in sortedPersonCols) {
                            if (text.contains("夜")) {
                                headerNightCol = col
                            } else if (text.contains("白")) {
                                headerDayCol = col
                            }
                        }
                    }
                }

                // 确定白班列和夜班列：
                // 规则：
                // 1) 若只有 1 列候选列，则只有白班；
                // 2) 若有 2 列候选列，若表头有显式“白”/“夜”，按表头分配；否则左侧第 1 列为白班，右侧第 2 列为夜班
                val dayCol: String?
                val nightCol: String?
                if (headerDayCol != null && headerNightCol != null) {
                    dayCol = headerDayCol
                    nightCol = headerNightCol
                } else if (sortedPersonCols.size == 1) {
                    dayCol = sortedPersonCols[0]
                    nightCol = null
                } else {
                    dayCol = sortedPersonCols[0]
                    nightCol = sortedPersonCols[1]
                }

                // 5. 提取所有日期与人员排班映射
                val allDates = mutableListOf<LocalDate>()
                val personMap = mutableMapOf<String, MutableList<LocalDate>>()
                val shiftTypes = mutableMapOf<Pair<String, LocalDate>, String>()

                for (r in rows) {
                    val dateStr = r[dateCol] ?: continue
                    val date = parseCellDate(dateStr, currentYearMonth) ?: continue
                    if (!allDates.contains(date)) {
                        allDates.add(date)
                    }

                    // 白班人员提取
                    if (dayCol != null) {
                        val dayDutyStr = r[dayCol]?.trim() ?: ""
                        val dayNames = extractNamesFromCell(dayDutyStr)
                        for (name in dayNames) {
                            personMap.getOrPut(name) { mutableListOf() }.add(date)
                            shiftTypes[Pair(name, date)] = "DAY"
                        }
                    }

                    // 夜班人员提取
                    if (nightCol != null) {
                        val nightDutyStr = r[nightCol]?.trim() ?: ""
                        val nightNames = extractNamesFromCell(nightDutyStr)
                        for (name in nightNames) {
                            val dates = personMap.getOrPut(name) { mutableListOf() }
                            if (!dates.contains(date)) {
                                dates.add(date)
                            }
                            shiftTypes[Pair(name, date)] = "NIGHT"
                        }
                    }
                }

                if (personMap.isEmpty()) return null

                allDates.sort()
                val sortedPersonSchedules = personMap.mapValues { it.value.sorted() }
                ParsedScheduleData(allDates, sortedPersonSchedules, shiftTypes)
            } finally {
                zipFile.close()
            }
        }.getOrNull()
    }

    private fun extractNamesFromCell(text: String): List<String> {
        val clean = text.replace(Regex("[（(].*?[)）]"), "").trim()
        if (clean.isEmpty() || clean in weekdayWords || clean in nonNameKeywords) return emptyList()
        return clean.split(Regex("[,/、\\s+]+"))
            .map { it.trim() }
            .filter { name ->
                name.isNotEmpty() &&
                name !in weekdayWords &&
                name !in nonNameKeywords &&
                name.length in 2..10 &&
                name.any { it.isLetter() }
            }
    }

    private fun colToIndex(col: String): Int {
        var index = 0
        for (ch in col.uppercase()) {
            if (ch in 'A'..'Z') {
                index = index * 26 + (ch - 'A' + 1)
            }
        }
        return index - 1
    }

    fun parseCellDate(cellValue: String, currentYearMonth: YearMonth): LocalDate? {
        val trimmed = cellValue.trim()
        if (trimmed.isEmpty()) return null

        // 1. Excel serial number (e.g. 46296 or 46296.0)
        val serial = trimmed.toLongOrNull() ?: trimmed.toDoubleOrNull()?.toLong()
        if (serial != null && serial in 30000..80000) {
            return runCatching { LocalDate.of(1899, 12, 30).plusDays(serial) }.getOrNull()
        }

        // 2. Full date: yyyy-MM-dd, yyyy/MM/dd, yyyy.MM.dd, yyyy年MM月dd日
        val cleanDate = trimmed.replace("/", "-").replace(".", "-").replace("年", "-").replace("月", "-").replace("日", "")
        val parts = cleanDate.split("-").mapNotNull { it.trim().toIntOrNull() }
        if (parts.size == 3) {
            val y = if (parts[0] < 100) parts[0] + 2000 else parts[0]
            val m = parts[1]
            val d = parts[2]
            return runCatching { LocalDate.of(y, m, d) }.getOrNull()
        }

        // 3. Month-Day: MM-dd, MM/dd
        if (parts.size == 2) {
            val m = parts[0]
            val d = parts[1]
            return runCatching { LocalDate.of(currentYearMonth.year, m, d) }.getOrNull()
        }

        // 4. Day of month: 1..31
        if (parts.size == 1 && parts[0] in 1..31) {
            return runCatching { currentYearMonth.atDay(parts[0]) }.getOrNull()
        }

        return null
    }
}
