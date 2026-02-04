package me.timschneeberger.appdatabackup

import android.os.Bundle
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import me.timschneeberger.appdatabackup.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater).also { setContentView(it.root) }

        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> { leftMargin = insets.left; topMargin = insets.top; rightMargin = insets.right }
            WindowInsetsCompat.CONSUMED
        }

        if (savedInstanceState == null) {
            val pkgs = intent
                .getStringArrayExtra(EXTRA_PACKAGE_NAMES)

            AppDataListFragment.newInstance(pkgs).also {
                supportFragmentManager.beginTransaction().replace(R.id.container, it).commit()
            }
        }

        binding.toolbar.apply {
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            setSupportActionBar(this)
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(supportFragmentManager.backStackEntryCount > 0)

        supportFragmentManager.addOnBackStackChangedListener {
            supportActionBar?.setDisplayHomeAsUpEnabled(
                supportFragmentManager.backStackEntryCount > 0
            )
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle the action bar Up/Home button explicitly so it always navigates back
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        private const val EXTRA_PACKAGE_NAMES = "${BuildConfig.APPLICATION_ID}.package_names"

    }
}
