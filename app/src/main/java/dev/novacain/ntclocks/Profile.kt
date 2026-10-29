package dev.novacain.ntclocks

import android.content.Context
import android.content.ContentResolver
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream
import java.util.zip.GZIPInputStream

/**
 * Профиль настроек derealization: полный снимок всех ключей Settings.Global/Secure + SharedPreferences (часы).
 * Экспорт/импорт — один gzip-compressed JSON файл.
 */
data class Profile(
    val name: String,
    val created: Long = System.currentTimeMillis(),
    val version: Int = 1,
    val data: JSONObject = JSONObject()
) {
    companion object {
        private const val PREFS_FILE = "profiles"
        private const val MAX_PROFILES = 20

        fun list(ctx: Context): List<Profile> {
            val store = ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            val names = store.getString("list", "[]")
            val result = mutableListOf<Profile>()
            try {
                val arr = JSONArray(names)
                for (i in 0 until arr.length()) {
                    val name = arr.getString(i)
                    val json = store.getString(name, null)
                    if (json != null) {
                        val p = Profile.fromJson(json)
                        if (p != null) result.add(p)
                    }
                }
            } catch (e: JSONException) {
                // corrupted list — reset
                store.edit().putString("list", "[]").apply()
            }
            return result.sortedByDescending { it.created }
        }

        fun get(ctx: Context, name: String): Profile? {
            val store = ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            val json = store.getString(name, null)
            return if (json != null) Profile.fromJson(json) else null
        }

        fun save(ctx: Context, profile: Profile) {
            val store = ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            val names = try { JSONArray(store.getString("list", "[]")) } catch (_: JSONException) { JSONArray() }
            if (!names.toList().contains(profile.name)) {
                if (names.length() >= MAX_PROFILES) return // limit
                names.put(profile.name)
                store.edit().putString("list", names.toString()).apply()
            }
            store.edit().putString(profile.name, profile.toJson()).apply()
        }

        fun delete(ctx: Context, name: String) {
            val store = ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            val names = try { JSONArray(store.getString("list", "[]")) } catch (_: JSONException) { JSONArray() }
            val filtered = JSONArray()
            for (i in 0 until names.length()) {
                val n = names.getString(i)
                if (n != name) filtered.put(n)
            }
            store.edit().putString("list", filtered.toString()).remove(name).apply()
        }

        fun rename(ctx: Context, oldName: String, newName: String): Boolean {
            val store = ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            if (store.contains(newName)) return false
            val json = store.getString(oldName, null) ?: return false
            val names = try { JSONArray(store.getString("list", "[]")) } catch (_: JSONException) { JSONArray() }
            val filtered = JSONArray()
            for (i in 0 until names.length()) {
                val n = names.getString(i)
                filtered.put(if (n == oldName) newName else n)
            }
            val profile = Profile.fromJson(json)?.copy(name = newName) ?: return false
            store.edit()
                .putString("list", filtered.toString())
                .remove(oldName)
                .putString(newName, profile.toJson())
                .apply()
            return true
        }
    }

    fun toJson(): String = try {
        JSONObject().put("name", name).put("created", created).put("version", version).put("data", data).toString()
    } catch (e: JSONException) {
        "{}"
    }

    @Suppress("UNUSED_PARAMETER")
    fun copy(name: String = this.name, created: Long = this.created, version: Int = this.version, data: JSONObject = this.data): Profile {
        return Profile(name, created, version, data)
    }

    private constructor(name: String, created: Long, version: Int, data: JSONObject) : this(name, created, version, data)

    companion object FromJson {
        fun fromJson(json: String): Profile? {
            return try {
                val o = JSONObject(json)
                Profile(
                    name = o.getString("name"),
                    created = o.optLong("created", System.currentTimeMillis()),
                    version = o.optInt("version", 1),
                    data = o.optJSONObject("data") ?: JSONObject()
                )
            } catch (e: JSONException) {
                null
            }
        }
    }
}

/**
 * Менеджер бэкапов: собирает все настройки из Settings.Global/Secure и SharedPreferences,
 * сохраняет в профиль, экспортирует в файл, импортирует из файла, применяет профиль.
 */
object BackupManager {
    // Все ключи Settings.Global/Secure, которые использует derealization
    private val GLOBAL_KEYS = listOf(
        AnimPrefs.KEY,           // ntweaker_anim
        GlassPrefs.KEY,          // ntweaker_glass
        ColorPrefs.KEY,          // ntweaker_color
        HomePrefs.KEY,           // ntweaker_home
        NavPrefs.KEY,            // ntweaker_nav
        DepthPrefs.KEY,          // ntweaker_depth
        WidgetPrefs.KEY,         // ntweaker_lockwidgets
        UnlockAnim.KEY,          // ntweaker_unlock
        LucentPrefs.KEY,         // ntweaker_lucent
        "theme_customization_overlay_packages"  // Material You theme
    )

    private val SECURE_KEYS = listOf(
        "lock_screen_custom_clock_face"  // текущие часы на экране блокировки
    )

    /** Собрать текущее состояние всех настроек в JSONObject. */
    fun snapshot(ctx: Context): JSONObject {
        val root = JSONObject()
        val cr = ctx.contentResolver
        val global = JSONObject()
        val secure = JSONObject()

        for (key in GLOBAL_KEYS) {
            try {
                val v = Settings.Global.getString(cr, key)
                if (v != null && v.isNotEmpty()) global.put(key, v)
            } catch (_: Throwable) {}
        }
        for (key in SECURE_KEYS) {
            try {
                val v = Settings.Secure.getString(cr, key)
                if (v != null && v.isNotEmpty()) secure.put(key, v)
            } catch (_: Throwable) {}
        }

        // Часы: все сохранённые пресеты из SharedPreferences (Store)
        val clocks = JSONObject()
        val store = ctx.createDeviceProtectedStorageContext()
            .getSharedPreferences("clocks", Context.MODE_PRIVATE)
        val all = store.all
        for ((k, v) in all) {
            if (v is String && v.isNotEmpty()) clocks.put(k, v)
        }

        root.put("global", global)
        root.put("secure", secure)
        root.put("clocks", clocks)
        root.put("timestamp", System.currentTimeMillis())
        root.put("version", 1)
        return root
    }

    /** Создать профиль из текущих настроек. */
    fun createProfile(ctx: Context, name: String): Profile {
        val data = snapshot(ctx)
        return Profile(name = name, data = data)
    }

    /** Применить профиль: писать все ключи в Settings + SharedPreferences. */
    fun applyProfile(ctx: Context, profile: Profile): Boolean {
        val cr = ctx.contentResolver
        val data = profile.data

        val global = data.optJSONObject("global")
        if (global != null) {
            for (key in GLOBAL_KEYS) {
                val v = global.optString(key, null)
                if (v != null) {
                    try { Settings.Global.putString(cr, key, v) } catch (_: Throwable) {}
                }
            }
        }

        val secure = data.optJSONObject("secure")
        if (secure != null) {
            for (key in SECURE_KEYS) {
                val v = secure.optString(key, null)
                if (v != null) {
                    try { Settings.Secure.putString(cr, key, v) } catch (_: Throwable) {}
                }
            }
        }

        val clocks = data.optJSONObject("clocks")
        if (clocks != null) {
            val store = ctx.createDeviceProtectedStorageContext()
                .getSharedPreferences("clocks", Context.MODE_PRIVATE).edit()
            for (k in clocks.keys()) {
                val v = clocks.optString(k, null)
                if (v != null) store.putString(k, v)
            }
            store.apply()
        }

        // Уведомить ContentProvider об изменении часов
        ctx.contentResolver.notifyChange(Uri.parse("content://dev.novacain.ntclocks.prefs/clock"), null)

        return true
    }

    /** Экспорт профиля в файл (gzip + JSON). */
    fun exportProfile(ctx: Context, profile: Profile, file: File): Boolean {
        return try {
            val json = profile.toJson()
            val fos = FileOutputStream(file)
            val gzos = GZIPOutputStream(fos)
            gzos.write(json.toByteArray())
            gzos.finish()
            gzos.close()
            true
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }

    /** Импорт профиля из файла. */
    fun importProfile(ctx: Context, file: File): Profile? {
        return try {
            val fis = FileInputStream(file)
            val gis = GZIPInputStream(fis)
            val bytes = gis.readAllBytes()
            gis.close()
            val json = String(bytes)
            Profile.fromJson(json)
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    /** Экспорт в публичную папку Downloads для передачи пользователю. */
    fun exportToDownloads(ctx: Context, profile: Profile): File? {
        val downloads = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?: ctx.getExternalFilesDir(null)
            ?: ctx.filesDir
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val file = File(downloads, "derealization_${profile.name.replace(" ", "_")}_$ts.drp")
        return if (exportProfile(ctx, profile, file)) file else null
    }

    /** Форматированная дата для UI. */
    fun formatDate(ts: Long): String =
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ts))
}