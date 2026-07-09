package com.privacyguard.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.snackbar.Snackbar
import com.privacyguard.R
import com.privacyguard.data.AppDatabase
import com.privacyguard.system.BiometricHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Activity displaying a log of intrusion detection events.
 * Protected by biometric authentication — content is hidden until the user
 * authenticates with their fingerprint or device credential.
 */
class IntrusionLogActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var emptyState: TextView
    private lateinit var adapter: IntrusionLogAdapter

    private val dao by lazy {
        AppDatabase.getInstance(applicationContext).intrusionDao()
    }

    /** Biometric gate: has the user authenticated this session? */
    private var authenticated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intrusion_log)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = findViewById(R.id.recyclerView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        emptyState = findViewById(R.id.emptyState)

        adapter = IntrusionLogAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        swipeRefresh.setOnRefreshListener { loadData() }
    }

    override fun onResume() {
        super.onResume()
        if (!authenticated) {
            checkBiometricAndShow()
        } else {
            loadData()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_intrusion_log, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_clear_all -> {
                clearAllEntries()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun checkBiometricAndShow() {
        if (!BiometricHelper.canAuthenticate(this)) {
            // Device doesn't support biometric auth — show content anyway
            authenticated = true
            loadData()
            return
        }

        // Gate content behind biometric prompt
        showEmptyState(false)
        emptyState.text = getString(R.string.intrusion_log_auth_required)

        BiometricHelper.authenticate(
            activity = this,
            title = getString(R.string.intrusion_log_auth_title),
            subtitle = getString(R.string.intrusion_log_auth_subtitle),
            onSuccess = {
                authenticated = true
                runOnUiThread {
                    loadData()
                }
            },
            onError = { _, err ->
                runOnUiThread {
                    Snackbar.make(
                        recyclerView,
                        "Authentication error: $err",
                        Snackbar.LENGTH_LONG
                    ).show()
                    finish()  // Can't view without auth
                }
            },
            onFailed = {
                runOnUiThread {
                    Snackbar.make(
                        recyclerView,
                        getString(R.string.intrusion_log_auth_failed),
                        Snackbar.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        )
    }

    private fun loadData() {
        lifecycleScope.launch {
            try {
                dao.observeAll().collectLatest { entries ->
                    adapter.submitList(entries)
                    updateEmptyState(entries.isEmpty())
                    swipeRefresh.isRefreshing = false
                }
            } catch (_: Exception) {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        showEmptyState(isEmpty)
        if (isEmpty) {
            emptyState.text = getString(R.string.intrusion_log_empty)
        }
        recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun clearAllEntries() {
        lifecycleScope.launch {
            try {
                dao.deleteAll()
                Snackbar.make(recyclerView, "All entries cleared", Snackbar.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Snackbar.make(recyclerView, "Failed to clear entries", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun showEmptyState(show: Boolean) {
        emptyState.visibility = if (show) View.VISIBLE else View.GONE
    }
}
