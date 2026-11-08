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
        "一", "二", "三", "四", "五", "六", "日", "天",
        "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日", "星期天",
        "周一", "周二", "周三", "周四", "周五", "周六", "周日", "周天",
        "礼拜一", "礼拜二", "礼拜三", "礼拜四", "礼拜五", "礼拜六", "礼拜日", "礼拜天",
        "星期", "周", "礼拜"
    )

    private val nonNameKeywords = setOf(
        "休息", "公休", "休", "年假", "事假", "病假", "调休", "班", "值班",
        "白班", "夜班", "晚班", "早班", "中班", "正常", "无", "班次", "姓名",
        "人员", "日期", "时间", "备注", "到岗", "上岗", "前到岗", "后到岗"
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
                // 1. 读取 sharedStrings.xml（忽略 <rPh> 拼音指南标签，防止中文单元格末尾被拼接拼音）
                val sharedStrings = mutableListOf<String>()
                val sstEntry = zipFile.getEntry("xl/sharedStrings.xml")
                if (sstEntry != null) {
                    zipFile.getInputStream(sstEntry).use { input ->
                        val parser = Xml.newPullParser()
                        parser.setInput(input, "UTF-8")
                        var event = parser.eventType
                        var inSi = false
                        var inT = false
                        var inRPh = false
                        val curStr = StringBuilder()
                        while (event != XmlPullParser.END_DOCUMENT) {
                            when (event) {
                                XmlPullParser.START_TAG -> {
                                    when (parser.name) {
                                        "si" -> {
                                            inSi = true
                                            curStr.clear()
                                        }
                                        "rPh" -> inRPh = true
                                        "t" -> if (!inRPh) inT = true
                                    }
                                }
                                XmlPullParser.TEXT -> {
                                    if (inSi && inT && !inRPh) {
                                        curStr.append(parser.text)
                                    }
                                }
                                XmlPullParser.END_TAG -> {
                                    when (parser.name) {
                                        "t" -> inT = false
                                        "rPh" -> inRPh = false
                                        "si" -> {
                                            inSi = false
                                            sharedStrings.add(curStr.toString())
                                        }
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

                val rowsByNum = linkedMapOf<Int, MutableMap<String, String>>()
                val mergeRanges = mutableListOf<String>()
                var autoRowCounter = 0

                zipFile.getInputStream(sheetEntry).use { input ->
                    val parser = Xml.newPullParser()
                    parser.setInput(input, "UTF-8")
                    var event = parser.eventType
                    var currentRowNum = 0
                    var currentRef = ""
                    var currentType = ""
                    val currentVal = StringBuilder()
                    var inV = false
                    var inT = false
                    var inRPh = false
                    var currentRow = mutableMapOf<String, String>()

                    while (event != XmlPullParser.END_DOCUMENT) {
                        when (event) {
                            XmlPullParser.START_TAG -> {
                                when (parser.name) {
                                    "row" -> {
                                        autoRowCounter++
                                        currentRowNum = parser.getAttributeValue(null, "r")?.toIntOrNull() ?: autoRowCounter
                                        currentRow = mutableMapOf()
                                    }
                                    "c" -> {
                                        currentRef = parser.getAttributeValue(null, "r") ?: ""
                                        currentType = parser.getAttributeValue(null, "t") ?: ""
                                        currentVal.clear()
                                    }
                                    "rPh" -> inRPh = true
                                    "v" -> inV = true
                                    "t" -> if (!inRPh) inT = true
                                    "mergeCell" -> {
                                        val ref = parser.getAttributeValue(null, "ref")
                                        if (!ref.isNullOrBlank()) {
                                            mergeRanges.add(ref)
                                        }
                                    }
                                }
                            }
                            XmlPullParser.TEXT -> {
                                if ((inV || inT) && !inRPh) {
                                    currentVal.append(parser.text)
                                }
                            }
                            XmlPullParser.END_TAG -> {
                                when (parser.name) {
                                    "v" -> inV = false
                                    "t" -> inT = false
                                    "rPh" -> inRPh = false
                                    "c" -> {
                                        val col = currentRef.filter { it.isLetter() }
                                        val rawText = currentVal.toString()
                                        val resolved = if (currentType == "s") {
                                            val idx = rawText.toIntOrNull()
                                            if (idx != null && idx in sharedStrings.indices) sharedStrings[idx] else rawText
                                        } else {
                                            rawText
                                        }
                                        if (col.isNotEmpty() && resolved.isNotEmpty()) {
                                            currentRow[col] = resolved
                                        }
                                    }
                                    "row" -> {
                                        if (currentRow.isNotEmpty()) {
                                            rowsByNum[currentRowNum] = currentRow
                                        }
                                    }
                                }
                            }
                        }
                        event = parser.next()
                    }
                }

                // 处理纵向合并单元格（仅针对单列纵向合并，如日期列上下两行合并）
                for (range in mergeRanges) {
                    val parts = range.split(":")
                    if (parts.size == 2) {
                        val startCol = parts[0].filter { it.isLetter() }
                        val endCol = parts[1].filter { it.isLetter() }
                        val startRow = parts[0].filter { it.isDigit() }.toIntOrNull()
                        val endRow = parts[1].filter { it.isDigit() }.toIntOrNull()
                        if (startCol.isNotEmpty() && startCol == endCol && startRow != null && endRow != null && endRow > startRow) {
                            val topVal = rowsByNum[startRow]?.get(startCol)
                            if (!topVal.isNullOrBlank()) {
                                for (rNum in (startRow + 1)..endRow) {
                                    val targetRow = rowsByNum[rNum] ?: continue
                                    if (targetRow[startCol].isNullOrBlank()) {
                                        targetRow[startCol] = topVal
                                    }
                                }
                            }
                        }
                    }
                }

                val rows = rowsByNum.values.toList()
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

                // 所有包含至少 1 个有效人名的列（按自然列序从左到右排序）
                val allPersonCols = colNameCount.keys.sortedBy { colToIndex(it) }

                // 主排班候选列（过滤掉偶发杂项列，用于定位白班主列）
                val maxCount = colNameCount.values.maxOrNull() ?: 0
                val threshold = maxOf(1, (maxCount * 0.15).toInt())
                val primaryPersonCols = colNameCount
                    .filter { it.value >= threshold }
                    .keys
                    .sortedBy { colToIndex(it) }

                if (primaryPersonCols.isEmpty()) return null

                // 检查表头中是否有明确的“白班”或“夜班”标识（在所有有人名的列中查找）
                val headerDayCols = mutableSetOf<String>()
                val headerNightCols = mutableSetOf<String>()
                for (i in 0 until firstDateRowIndex) {
                    val headerRow = rows[i]
                    for ((col, text) in headerRow) {
                        if (col in allPersonCols) {
                            val hasDay = text.contains("白")
                            val hasNight = text.contains("夜") || text.contains("晚班")
                            if (hasNight && !hasDay) {
                                headerNightCols.add(col)
                            } else if (hasDay && !hasNight) {
                                headerDayCols.add(col)
                            }
                        }
                    }
                }

                // 确定白班列
                val dayCol: String = headerDayCols.minByOrNull { colToIndex(it) } ?: primaryPersonCols.first()
                val dayColIndex = colToIndex(dayCol)

                // 5. 提取所有日期与人员排班映射
                val allDates = mutableListOf<LocalDate>()
                val personMap = mutableMapOf<String, MutableList<LocalDate>>()
                val shiftTypes = mutableMapOf<Pair<String, LocalDate>, String>()

                fun recordShift(name: String, date: LocalDate, shift: String) {
                    val dates = personMap.getOrPut(name) { mutableListOf() }
                    if (!dates.contains(date)) {
                        dates.add(date)
                    }
                    val key = Pair(name, date)
                    if (shift == "NIGHT" || shiftTypes[key] != "NIGHT") {
                        shiftTypes[key] = shift
                    }
                }

                // 第一步：先从白班列提取所有排班日期及全部值班人员名单（knownNames）
                for (r in rows) {
                    val dateStr = r[dateCol] ?: continue
                    val date = parseCellDate(dateStr, currentYearMonth) ?: continue
                    if (!allDates.contains(date)) {
                        allDates.add(date)
                    }

                    val dayDutyStr = r[dayCol]?.trim() ?: ""
                    if (dayDutyStr.isNotEmpty()) {
                        for (name in extractNamesFromCell(dayDutyStr)) {
                            recordShift(name, date, "DAY")
                        }
                    }
                }

                // 已知的所有值班人员姓名集合
                val knownNames = personMap.keys.toSet()

                // 第二步：检测白班单元格右边的单元格
                // - 若为空：当天无夜班
                // - 若非空且包含任意已知值班人员的名字文本：该值班人员当天记为夜班（NIGHT）
                // - 若非空但未出现任何已知值班人员名字：当天无夜班
                if (knownNames.isNotEmpty()) {
                    for (r in rows) {
                        val dateStr = r[dateCol] ?: continue
                        val date = parseCellDate(dateStr, currentYearMonth) ?: continue

                        val rightCellText = r.entries
                            .filter { colToIndex(it.key) > dayColIndex && it.value.isNotBlank() }
                            .minByOrNull { colToIndex(it.key) }
                            ?.value
                            ?.trim() ?: ""
                        if (rightCellText.isEmpty()) continue

                        // 去除单元格中的所有空白字符后再匹配姓名，兼容“吴   迪”、“丁   琪”等名字中间带空格对齐的写法
                        val compactRightText = rightCellText.replace(Regex("[\\s\\u00A0\\u3000\\u200B\\uFEFF]+"), "")
                        for (name in knownNames) {
                            val compactName = name.replace(Regex("[\\s\\u00A0\\u3000\\u200B\\uFEFF]+"), "")
                            if (compactName.isNotEmpty() && compactRightText.contains(compactName)) {
                                recordShift(name, date, "NIGHT")
                            }
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

    private val shiftPrefixes = listOf(
        "夜班", "白班", "晚班", "早班", "中班", "值班", "前到岗", "后到岗", "到岗", "上岗", "上班"
    )

    private fun extractNamesFromCell(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        // 1. 用逗号替换各种括号及其内部内容（含跨行内容），如“（13点前到岗）”
        var clean = text.replace(Regex("[（(\\[【〔［﹙<《〈][\\s\\S]*?[)）\\]】〕］﹚>》〉]"), ",")
        // 2. 去除可能未加括号或括号不匹配的时间/到岗描述，如“13点前到岗”、“13:00前到岗”
        clean = clean.replace(Regex("\\d+\\s*(?:[:：点时]\\s*\\d*\\s*(?:分|半)?)?\\s*(?:前|后|左右)?\\s*(?:到岗|上岗|上班|值班|到)?"), ",")
        // 3. 合并两字中文姓名中间为了对齐而输入的空格（如“吴   迪” -> “吴迪”、“丁   琪” -> “丁琪”）
        clean = clean.replace(Regex("(?<![\\u4e00-\\u9fa5])([\\u4e00-\\u9fa5])[ \\t\\u00A0\\u3000]+([\\u4e00-\\u9fa5])(?![\\u4e00-\\u9fa5])"), "$1$2")
        clean = clean.trim()
        if (clean.isEmpty() || clean in weekdayWords || clean in nonNameKeywords) return emptyList()

        // 3. 按所有非字母/非汉字字符（含标点、括号、空白、特殊不可见字符等）切分
        return clean.split(Regex("[^\\p{L}]+"))
            .map { token ->
                var t = token.trim()
                var changed = true
                while (changed) {
                    changed = false
                    for (prefix in shiftPrefixes) {
                        if (t.startsWith(prefix) && t.length > prefix.length) {
                            t = t.removePrefix(prefix).trim()
                            changed = true
                            break
                        }
                    }
                }
                t
            }
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
