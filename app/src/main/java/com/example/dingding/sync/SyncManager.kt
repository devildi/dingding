package com.example.dingding.sync

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.dingding.PunchWidgetProvider
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val PREF_NAME = "dingding_calendar"
private const val PREF_OVERRIDES = "overrides"
private const val PREF_PUNCHES = "punches"
private const val PREF_DAILY_HOURS = "daily_hours"
private const val PREF_PLANNED_DAILY_HOURS = "planned_daily_hours"
private const val PREF_PLANNED_SET_DATE = "planned_set_date"
private const val PREF_SELECTED_CALENDAR_DATES = "selected_calendar_dates"
private const val PREF_ADJUST_MONTH = "adjust_month"
private const val PREF_ADJUST_HOURS = "adjust_hours"
private const val PREF_ADJUST_END = "adjust_end"
private const val PREF_PUNCH_MODE = "punch_mode"

data class AdjustInfoBackup(
    val month: String,
    val hours: Double,
    val endMillis: Long
)

data class DingdingBackupData(
    val version: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val punches: List<Long> = emptyList(),
    val overrides: Map<String, Boolean> = emptyMap(),
    val dailyHours: Double = 7.5,
    val plannedDailyHours: Double = 7.5,
    val plannedSetDate: String? = null,
    val selectedCalendarDates: List<String> = emptyList(),
    val adjustInfo: AdjustInfoBackup? = null,
    val punchMode: Int = 0
)

data class BackupSummary(
    val file: File,
    val punchCount: Int,
    val overrideCount: Int,
    val calendarDateCount: Int,
    val hasAdjustInfo: Boolean,
    val totalSizeBytes: Long
)

data class ImportSummary(
    val totalSizeBytes: Long,
    val punchCount: Int,
    val overrideCount: Int,
    val calendarDateCount: Int,
    val hasAdjustInfo: Boolean
)

sealed class SyncServerState {
    object Idle : SyncServerState()
    data class Ready(val serverIp: String) : SyncServerState()
    data class Transferring(val clientIp: String) : SyncServerState()
    data class Success(val clientIp: String) : SyncServerState()
    data class Error(val clientIp: String?, val message: String) : SyncServerState()
}

class SyncManager(private val context: Context) {
    private val gson: Gson = GsonBuilder()
        .serializeNulls()
        .setPrettyPrinting()
        .create()

    private val backupDir = File(context.cacheDir, "sync_backup")
    private val backupJsonFile = File(backupDir, "data.json")
    private val zipFile = File(backupDir, "backup.zip")

    private var server: SimpleWebServer? = null
    var onServerStateChanged: ((SyncServerState) -> Unit)? = null

    suspend fun exportData(): BackupSummary = withContext(Dispatchers.IO) {
        if (!backupDir.exists()) backupDir.mkdirs()

        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        // 1. Punches
        val rawPunches = prefs.getString(PREF_PUNCHES, "") ?: ""
        val punchesList = rawPunches.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it > 0 }

        // 2. Overrides
        val rawOverrides = prefs.getString(PREF_OVERRIDES, "") ?: ""
        val overridesMap = mutableMapOf<String, Boolean>()
        if (rawOverrides.isNotBlank()) {
            rawOverrides.split(",").forEach { entry ->
                val parts = entry.split("=")
                if (parts.size == 2 && parts[0].isNotBlank()) {
                    overridesMap[parts[0]] = parts[1] == "1"
                }
            }
        }

        // 3. Other fields
        val dailyHours = prefs.getFloat(PREF_DAILY_HOURS, 7.5f).toDouble()
        val plannedDailyHours = prefs.getFloat(PREF_PLANNED_DAILY_HOURS, 7.5f).toDouble()
        val plannedSetDate = prefs.getString(PREF_PLANNED_SET_DATE, null)
        val rawSelectedDates = prefs.getString(PREF_SELECTED_CALENDAR_DATES, "") ?: ""
        val selectedDatesList = if (rawSelectedDates.isNotBlank()) {
            rawSelectedDates.split(",").filter { it.isNotBlank() }
        } else {
            emptyList()
        }

        val adjustMonth = prefs.getString(PREF_ADJUST_MONTH, null)
        val adjustHours = Double.fromBits(prefs.getLong(PREF_ADJUST_HOURS, Double.NaN.toRawBits()))
        val adjustEnd = prefs.getLong(PREF_ADJUST_END, -1L)
        val adjustInfoBackup = if (!adjustMonth.isNullOrBlank() && !adjustHours.isNaN() && adjustEnd > 0) {
            AdjustInfoBackup(adjustMonth, adjustHours, adjustEnd)
        } else null

        val punchMode = prefs.getInt(PREF_PUNCH_MODE, 0)

        val backupData = DingdingBackupData(
            version = 1,
            timestamp = System.currentTimeMillis(),
            punches = punchesList,
            overrides = overridesMap,
            dailyHours = dailyHours,
            plannedDailyHours = plannedDailyHours,
            plannedSetDate = plannedSetDate,
            selectedCalendarDates = selectedDatesList,
            adjustInfo = adjustInfoBackup,
            punchMode = punchMode
        )

        val json = gson.toJson(backupData)
        backupJsonFile.writeText(json)

        zipSingleFile(backupJsonFile, zipFile)

        return@withContext BackupSummary(
            file = zipFile,
            punchCount = punchesList.size,
            overrideCount = overridesMap.size,
            calendarDateCount = selectedDatesList.size,
            hasAdjustInfo = adjustInfoBackup != null,
            totalSizeBytes = zipFile.length()
        )
    }

    private fun zipSingleFile(inputFile: File, outputZip: File) {
        ZipOutputStream(FileOutputStream(outputZip)).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("data.json"))
            FileInputStream(inputFile).use { input ->
                input.copyTo(zipOut)
            }
            zipOut.closeEntry()
        }
    }

    suspend fun importData(serverIp: String): ImportSummary = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val finalIp = if (serverIp.contains(":")) serverIp else "$serverIp:8080"
        val request = Request.Builder()
            .url("http://$finalIp/backup.zip")
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw Exception("无法连接到发送端 ($finalIp)，请确保在同一网络并检查 IP 地址")
        }

        if (!response.isSuccessful) {
            throw Exception("连接失败 HTTP code: ${response.code}")
        }

        try {
            val tempZip = File(context.cacheDir, "import_temp.zip")
            val destDir = File(context.cacheDir, "import_temp_extracted")
            if (destDir.exists()) destDir.deleteRecursively()
            destDir.mkdirs()

            FileOutputStream(tempZip).use { output ->
                response.body?.byteStream()?.copyTo(output) ?: throw Exception("响应体为空")
            }

            ZipInputStream(FileInputStream(tempZip)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val filePath = File(destDir, entry.name)
                    if (!entry.isDirectory) {
                        filePath.parentFile?.mkdirs()
                        FileOutputStream(filePath).use { output ->
                            zipIn.copyTo(output)
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }

            val jsonFile = File(destDir, "data.json")
            if (!jsonFile.exists()) {
                throw Exception("未找到 data.json 备份数据文件")
            }

            val json = jsonFile.readText()
            val backupData = gson.fromJson(json, DingdingBackupData::class.java)
                ?: throw Exception("解析备份数据失败")

            // 写入 SharedPreferences
            val edit = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()

            // 1. Punches
            val serializedPunches = backupData.punches.joinToString(",")
            edit.putString(PREF_PUNCHES, serializedPunches)

            // 2. Overrides
            val serializedOverrides = backupData.overrides.entries.joinToString(",") { (date, isWorkday) ->
                "$date=${if (isWorkday) "1" else "0"}"
            }
            edit.putString(PREF_OVERRIDES, serializedOverrides)

            // 3. Daily Hours
            edit.putFloat(PREF_DAILY_HOURS, backupData.dailyHours.toFloat())
            edit.putFloat(PREF_PLANNED_DAILY_HOURS, backupData.plannedDailyHours.toFloat())

            // 4. Planned set date
            if (!backupData.plannedSetDate.isNullOrBlank()) {
                edit.putString(PREF_PLANNED_SET_DATE, backupData.plannedSetDate)
            } else {
                edit.remove(PREF_PLANNED_SET_DATE)
            }

            // 5. Selected calendar dates
            val serializedSelectedDates = backupData.selectedCalendarDates.joinToString(",")
            edit.putString(PREF_SELECTED_CALENDAR_DATES, serializedSelectedDates)

            // 6. Adjust info
            val adj = backupData.adjustInfo
            if (adj != null && adj.month.isNotBlank() && !adj.hours.isNaN() && adj.endMillis > 0) {
                edit.putString(PREF_ADJUST_MONTH, adj.month)
                edit.putLong(PREF_ADJUST_HOURS, adj.hours.toRawBits())
                edit.putLong(PREF_ADJUST_END, adj.endMillis)
            } else {
                edit.remove(PREF_ADJUST_MONTH)
                edit.remove(PREF_ADJUST_HOURS)
                edit.remove(PREF_ADJUST_END)
            }

            // 7. Punch mode
            edit.putInt(PREF_PUNCH_MODE, backupData.punchMode)

            edit.apply()

            // 发送广播触发桌面 Widget 更新状态
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, PunchWidgetProvider::class.java)
            val ids = appWidgetManager.getAppWidgetIds(component)
            val updateIntent = Intent(context, PunchWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(updateIntent)

            // 通知发送端：同步成功
            try {
                val confirmReq = Request.Builder()
                    .url("http://$finalIp/sync_complete?status=success")
                    .build()
                client.newCall(confirmReq).execute().close()
            } catch (e: Exception) {
                Log.w("SyncManager", "Failed to send sync success confirmation: ${e.message}")
            }

            return@withContext ImportSummary(
                totalSizeBytes = tempZip.length(),
                punchCount = backupData.punches.size,
                overrideCount = backupData.overrides.size,
                calendarDateCount = backupData.selectedCalendarDates.size,
                hasAdjustInfo = backupData.adjustInfo != null
            )
        } catch (e: Exception) {
            // 通知发送端：同步失败
            try {
                val encodedErr = java.net.URLEncoder.encode(e.message ?: "数据导入失败", "UTF-8")
                val failReq = Request.Builder()
                    .url("http://$finalIp/sync_complete?status=fail&error=$encodedErr")
                    .build()
                client.newCall(failReq).execute().close()
            } catch (ignored: Exception) {
            }
            throw e
        }
    }

    fun startServer(startPort: Int = 8080): String {
        stopServer()

        var port = startPort
        val maxPort = startPort + 10
        var lastException: Exception? = null

        while (port <= maxPort) {
            try {
                val myServer = SimpleWebServer(port, backupDir) { state ->
                    onServerStateChanged?.invoke(state)
                }
                myServer.start()
                server = myServer
                val ip = getDeviceIpAddress()
                val fullAddress = if (port == 8080) ip else "$ip:$port"
                onServerStateChanged?.invoke(SyncServerState.Ready(fullAddress))
                return "$ip:$port"
            } catch (e: Exception) {
                lastException = e
                Log.w("SyncManager", "Port $port occupied, trying next...")
                port++
            }
        }

        val err = lastException ?: Exception("未能找到可用端口（$startPort-$maxPort）")
        onServerStateChanged?.invoke(SyncServerState.Error(null, err.message ?: "服务启动失败"))
        throw err
    }

    fun stopServer() {
        server?.stop()
        server = null
        onServerStateChanged?.invoke(SyncServerState.Idle)
    }

    fun deleteBackup() {
        if (backupDir.exists()) {
            backupDir.deleteRecursively()
        }
    }

    private fun getDeviceIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val hostAddress = addr.hostAddress
                        if (hostAddress != null && hostAddress.isNotBlank()) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "Unknown"
    }

    private class SimpleWebServer(
        port: Int,
        private val rootDir: File,
        private val onStateChange: (SyncServerState) -> Unit
    ) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val clientIp = session.remoteIpAddress ?: "未知设备"

            if (uri == "/backup.zip") {
                val file = File(rootDir, "backup.zip")
                if (file.exists()) {
                    onStateChange(SyncServerState.Transferring(clientIp))
                    return newFixedLengthResponse(Response.Status.OK, "application/zip", FileInputStream(file), file.length())
                } else {
                    onStateChange(SyncServerState.Error(clientIp, "备份数据文件不存在"))
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Backup file not found")
                }
            } else if (uri == "/sync_complete") {
                val status = session.parameters["status"]?.firstOrNull()
                val error = session.parameters["error"]?.firstOrNull()
                if (status == "success") {
                    onStateChange(SyncServerState.Success(clientIp))
                } else {
                    val msg = error ?: "接收端处理失败"
                    onStateChange(SyncServerState.Error(clientIp, msg))
                }
                return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
            }

            return newFixedLengthResponse("Hello from Dingding Sync Server!")
        }
    }
}
