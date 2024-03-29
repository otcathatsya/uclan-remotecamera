package com.uclan.remotecamera.androidApp

import android.Manifest
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.NavDeepLinkBuilder
import androidx.navigation.fragment.NavHostFragment
import com.uclan.remotecamera.androidApp.databinding.ActivityMainBinding
import com.uclan.remotecamera.androidApp.p2p.P2PConnectionActions
import com.uclan.remotecamera.androidApp.p2p.WiFiDirectBroadcastReceiver
import com.uclan.remotecamera.androidApp.p2p.WiFiDirectFragment
import com.uclan.remotecamera.androidApp.utility.GenericAlert
import java.util.*

class MainActivity : AppCompatActivity(), P2PConnectionActions, WifiP2pManager.ChannelListener {

    companion object {
        const val GENERIC_PERMISSIONS_CODE = 1001

        private val REQUIRED_PERMISSIONS =
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )

        fun hasPermissions(context: Context) = REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private val intentFilter = IntentFilter()

    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var manager: WifiP2pManager
    private lateinit var receiver: WiFiDirectBroadcastReceiver
    private lateinit var navController: NavController

    var shouldRetry: Boolean = true
    var isWifiP2pEnabled: Boolean = false

    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            GENERIC_PERMISSIONS_CODE -> if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Log.e("MainActivity", "Permission was denied")
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)

        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)
        channel.also { receiver = WiFiDirectBroadcastReceiver(this, manager, channel) }

        _binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        _binding = ActivityMainBinding.inflate(layoutInflater)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragmentContainer) as NavHostFragment
        navController = navHostFragment.navController

        if (!isSupported() && isWifiP2pEnabled)
            GenericAlert().create(this@MainActivity, "Error", "Device does not support WiFi Direct")
                .show()
        else if (!hasPermissions(this)) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ),
                GENERIC_PERMISSIONS_CODE
            )
        }
    }

    @SuppressWarnings("MissingPermission")
    fun discoverPeers() {
        //Only used for progression status
        val wifiFrag =
            supportFragmentManager.findFragmentById(R.id.fragmentContainer)?.childFragmentManager?.primaryNavigationFragment as? WiFiDirectFragment

        if (isWifiP2pEnabled) {
            if (isLocationEnabled()) {
                wifiFrag?.showProgressDialogue()
                manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.v("MainActivity", "peer discovery success")
                    }

                    override fun onFailure(reasonCode: Int) {
                        Log.v(
                            "MainActivity",
                            "peer discovery failure, error code $reasonCode"
                        )
                        wifiFrag?.hideProgressDialogue()
                    }
                })
            } else {
                GenericAlert().create(
                    this@MainActivity,
                    "Error",
                    "Please enable Location Services!"
                )
                    .show()
                wifiFrag?.hideProgressDialogue()
            }
        } else {
            GenericAlert().create(
                this@MainActivity,
                "Error",
                "Please enable WiFi!"
            )
                .show()
            wifiFrag?.hideProgressDialogue()
        }
    }

    fun updateAll(newList: List<WifiP2pDevice>) {
        val fragment = supportFragmentManager.findFragmentByTag("WiFiDirectFragment")
        if (fragment != null && fragment.isVisible)
            (supportFragmentManager.findFragmentByTag("WiFiDirectFragment") as WiFiDirectFragment).updateAll(
                newList
            )
    }

    @SuppressWarnings("MissingPermission")
    override fun connect(config: WifiP2pConfig?) {
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {

            override fun onSuccess() {
                Log.v("MainActivity", "connection success")
            }

            override fun onFailure(reason: Int) {
                GenericAlert().create(this@MainActivity, "Error", "Connection failed!").show()
            }
        })

    }

    override fun disconnect() {
        Log.d("MainActivity", "Disconnecting")

        NavDeepLinkBuilder(applicationContext)
            .setGraph(R.navigation.nav_graph)
            .setDestination(R.id.wiFiDirectFragment)
            .createPendingIntent()
            .send()

        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Toast.makeText(
                    this@MainActivity, "Disconnected",
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun onFailure(reason: Int) {
                Log.d("MainActivity", "Cannot disconnect: error code $reason")
                GenericAlert().create(
                    this@MainActivity,
                    "Notification",
                    "Cannot disconnect; are you connected?"
                ).show()
            }

        })
    }

    override fun abort() {
        val wifiFrag: WiFiDirectFragment? = currentNavigationFragment() as? WiFiDirectFragment
        if (wifiFrag?.connectingDevice?.status == WifiP2pDevice.CONNECTED)
            disconnect()
        else if (wifiFrag?.connectingDevice?.status == WifiP2pDevice.AVAILABLE
            || wifiFrag?.connectingDevice?.status == WifiP2pDevice.INVITED
        )
            manager.cancelConnect(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Toast.makeText(
                        this@MainActivity, "Aborting connection",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onFailure(reason: Int) {
                    Toast.makeText(
                        this@MainActivity, "Connection cannot be aborted, error code $reason",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            })
    }

    override fun onChannelDisconnected() {
        if (shouldRetry) {
            Toast.makeText(this, "Lost connection, retrying", Toast.LENGTH_LONG).show()
            shouldRetry = false
            manager.initialize(this, mainLooper, this)
        } else {
            Toast.makeText(
                this,
                "Connection lost",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        receiver.also { receiver ->
            registerReceiver(receiver, intentFilter)
        }
    }

    override fun onPause() {
        super.onPause()
        receiver.also { receiver ->
            unregisterReceiver(receiver)
        }
    }

    fun setIsWifiP2pEnabled(isWifiP2pEnabled: Boolean) {
        this.isWifiP2pEnabled = isWifiP2pEnabled
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager =
            this@MainActivity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return LocationManagerCompat.isLocationEnabled(locationManager)
    }

    private fun isSupported(): Boolean {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            Log.e("MainActivity", "No WifiDirect support")
            return false
        }

        val wifiManager = getSystemService(WIFI_SERVICE) as WifiManager? ?: return false
        if (!wifiManager.isP2pSupported) {
            Log.e("MainActivity", "WifiDirect not supported by hardware or device is offline")
            return false
        }

        manager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager? ?: run {
            Log.e("MainActivity", "WifiDirect system service not available")
            return false
        }
        channel = manager.initialize(this, mainLooper, null) ?: run {
            Log.e("MainActivity", "WifiDirect initialization failure")
            return false
        }

        return true
    }

    fun currentNavigationFragment(): Fragment? =
        supportFragmentManager.findFragmentById(R.id.fragmentContainer)?.childFragmentManager?.primaryNavigationFragment

    fun currentFragmentId(): Int? = navController.currentDestination?.id
}