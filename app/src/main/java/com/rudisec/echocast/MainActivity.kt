package com.rudisec.echocast

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import com.rudisec.echocast.databinding.ActivityMainBinding
import java.io.InputStream

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Preferences

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            showPermissionDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setSupportActionBar(binding.toolbar)
            supportActionBar?.title = "" // Empty title as requested

            prefs = Preferences(this)

            loadLogo()
            setupViewPager()
            checkPermissions()
        } catch (e: Exception) {
            android.util.Log.e("EchoCast", "Error in onCreate", e)
            e.printStackTrace()
            Toast.makeText(this, "Error: ${e.javaClass.simpleName}: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun loadLogo() {
        try {
            // Try to load logo from assets
            val inputStream: InputStream = assets.open("Logo.png")
            val bitmap = BitmapFactory.decodeStream(inputStream)
            if (bitmap != null) {
                binding.logoImageView.setImageBitmap(bitmap)
                android.util.Log.d("EchoCast", "Logo loaded successfully")
            } else {
                android.util.Log.e("EchoCast", "Failed to decode logo bitmap")
                // Set visibility to gone if logo fails to load
                binding.logoImageView.visibility = android.view.View.GONE
            }
            inputStream.close()
        } catch (e: java.io.FileNotFoundException) {
            android.util.Log.e("EchoCast", "Logo file not found in assets", e)
            // Hide logo if file not found
            binding.logoImageView.visibility = android.view.View.GONE
        } catch (e: Exception) {
            android.util.Log.e("EchoCast", "Error loading logo", e)
            e.printStackTrace()
            // Hide logo on any error
            binding.logoImageView.visibility = android.view.View.GONE
        }
    }

    private fun setupViewPager() {
        val adapter = ViewPagerAdapter(this)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_audio_player)
                1 -> getString(R.string.tab_ai)
                else -> ""
            }
        }.attach()
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                    showPermissionDialog()
                } else {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permissions_required)
            .setMessage(R.string.notification_permission_required)
            .setPositiveButton(R.string.grant_permission) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    class ViewPagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> AudioPlayerFragment()
                1 -> AiFragment()
                else -> throw IllegalArgumentException("Invalid position: $position")
            }
        }
    }
}
