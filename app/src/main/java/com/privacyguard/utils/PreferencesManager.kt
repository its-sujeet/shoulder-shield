package com.privacyguard.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "privacy_guard_prefs")

class PreferencesManager(private val context: Context) {

    private val awayTimeoutKey = intPreferencesKey(Constants.PREF_AWAY_TIMEOUT)
    private val autostartKey = booleanPreferencesKey(Constants.PREF_AUTOSTART)
    private val enrollmentDataKey = stringPreferencesKey(Constants.PREF_ENROLLMENT_DATA)
    private val isMonitoringKey = booleanPreferencesKey(Constants.PREF_IS_MONITORING)
    private val isEnrolledKey = booleanPreferencesKey(Constants.PREF_IS_ENROLLED)
    private val isRootedKey = booleanPreferencesKey(Constants.PREF_IS_ROOTED)

    val awayTimeout: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[awayTimeoutKey] ?: 5
    }

    val autostart: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[autostartKey] ?: false
    }

    val isMonitoring: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[isMonitoringKey] ?: false
    }

    val isEnrolled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[isEnrolledKey] ?: false
    }

    val enrollmentData: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[enrollmentDataKey]
    }

    val isRooted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[isRootedKey] ?: false
    }

    suspend fun setAwayTimeout(seconds: Int) {
        context.dataStore.edit { prefs ->
            prefs[awayTimeoutKey] = seconds
        }
    }

    suspend fun setAutostart(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[autostartKey] = enabled
        }
    }

    suspend fun setMonitoring(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[isMonitoringKey] = enabled
        }
    }

    suspend fun setEnrolled(enrolled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[isEnrolledKey] = enrolled
        }
    }

    suspend fun setEnrollmentData(data: String) {
        context.dataStore.edit { prefs ->
            prefs[enrollmentDataKey] = data
        }
    }

    suspend fun setRooted(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[isRootedKey] = enabled
        }
    }

    suspend fun getAwayTimeoutMs(): Long {
        return (context.dataStore.data.first()[awayTimeoutKey] ?: 5) * 1000L
    }
}
