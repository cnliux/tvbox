package com.tvbox.app.data

import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import java.util.Collections

/**
 * 轻量级 SharedPreferences 模拟实现，用于纯 JVM 单测环境。
 * 所有操作均在主线程同步完成，不依赖 Android 框架。
 */
class MockSharedPreferences : SharedPreferences {
    private val store =Collections.synchronizedMap(mutableMapOf<String, Any>())
    private val listeners = mutableListOf<OnSharedPreferenceChangeListener>()

    override fun getString(key: String, defaultValue: String?): String? = store[key] as? String ?: defaultValue

    override fun getStringSet(key: String, defaultValue: MutableSet<String>?): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return (store[key] as? MutableSet<String>) ?: defaultValue
    }

    override fun getInt(key: String, defaultValue: Int): Int = store[key] as? Int ?: defaultValue

    override fun getLong(key: String, defaultValue: Long): Long = store[key] as? Long ?: defaultValue

    override fun getFloat(key: String, defaultValue: Float): Float = store[key] as? Float ?: defaultValue

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = store[key] as? Boolean ?: defaultValue

    override fun getAll(): Map<String, Any> = Collections.unmodifiableMap(store)

    override fun contains(key: String): Boolean = store.containsKey(key)

    override fun edit(): SharedPreferences.Editor = MockEditor()

    override fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {
        listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {
        listeners.remove(listener)
    }

    inner class MockEditor : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var removeAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            changes[key] = value; return this
        }

        override fun putStringSet(key: String, value: MutableSet<String>?): SharedPreferences.Editor {
            changes[key] = value; return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            changes[key] = value; return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            changes[key] = value; return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            changes[key] = value; return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            changes[key] = value; return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            changes[key] = null; return this
        }

        override fun clear(): SharedPreferences.Editor {
            removeAll = true; return this
        }

        override fun commit(): Boolean {
            if (removeAll) store.clear() else changes.forEach { (k, v) ->
                if (v == null) store.remove(k) else store[k] = v
            }
            return true
        }

        override fun apply() { commit() }
    }
}
