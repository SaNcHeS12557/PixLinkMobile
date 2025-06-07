package com.example.pixlinkmobile;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TransportInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.util.List;
import java.util.Set;

public class DeviceMetricsCollector {
    Context context;

    JSONObject batteryInfo;
    JSONObject networkInfo;
    JSONObject bluetoothInfo;
    JSONObject wifiInfo;

    public DeviceMetricsCollector(Context context) {
        this.context = context;

        batteryInfo = new JSONObject();
        networkInfo = new JSONObject();
        bluetoothInfo = new JSONObject();
        wifiInfo = new JSONObject();

        collectMetrics();
    }

    protected void collectMetrics() {
        batteryInfo = collectBatteryInfo();
        networkInfo = collectNetworkInfo();
        bluetoothInfo = collectBluetoothInfo();
        wifiInfo = collectWifiInfo();
    }

    // COLLECTORS:
    private JSONObject collectBatteryInfo() {
        JSONObject batteryJson = new JSONObject();
        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, intentFilter);

        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL;

            try {
                batteryJson.put("level", level);
                batteryJson.put("isCharging", isCharging);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return batteryJson;
    }

    private JSONObject collectNetworkInfo() {
        JSONObject networkJson = new JSONObject();

        // TODO: collect all networks

        return networkJson;
    }

    private JSONObject collectWifiInfo() {
        JSONObject wifiJson = new JSONObject();
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (cm != null) {
            Network network = cm.getActiveNetwork();
            LinkProperties linkProps = cm.getLinkProperties(network);
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);

            try {
                boolean isWifi = capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                wifiJson.put("enabled", isWifi);

                if (linkProps != null) {
                    List<LinkAddress> addresses = linkProps.getLinkAddresses();

                    for (LinkAddress addr : addresses) {
                        if (addr.getAddress() instanceof Inet4Address) {
                            wifiJson.put("ip", addr.getAddress().getHostAddress());
                            break;
                        }
                    }

                    wifiJson.put("interface", linkProps.getInterfaceName());
                }

                if (isWifi) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if(capabilities !=null) {
                            TransportInfo transportInfo = capabilities.getTransportInfo();
                            if (transportInfo  instanceof WifiInfo) {
                                WifiInfo wifiInfo = (WifiInfo) transportInfo;
                                wifiJson.put("ssid", wifiInfo.getSSID());
                                wifiJson.put("bssid", wifiInfo.getBSSID());
                            }
                        }
                    }
                }

            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        return wifiJson;
    }

    private JSONObject collectBluetoothInfo() {
        JSONObject bluetoothJson = new JSONObject();
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bluetoothManager.getAdapter();

        if(adapter == null) {
            try {
                bluetoothJson.put("enabled", false);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return bluetoothJson;
        }

        try {
            bluetoothJson.put("enabled", adapter.isEnabled());

            String state;
            switch (adapter.getState()) {
                case BluetoothAdapter.STATE_ON: state="on";break;
                case BluetoothAdapter.STATE_TURNING_OFF: state="turning_off"; break;
                case BluetoothAdapter.STATE_TURNING_ON:  state="turning_on"; break;

                case BluetoothAdapter.STATE_OFF:
                default: state = "off"; break;
            }
            bluetoothJson.put("state", state);
            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                bluetoothJson.put("device_name", adapter.getName());
            } else {
                bluetoothJson.put("device_name", JSONObject.NULL);
            }

            Set<BluetoothDevice> pairedDevices = adapter.getBondedDevices();

            JSONArray devicesArray = new JSONArray();
            for(BluetoothDevice device : pairedDevices) {
                devicesArray.put(device.getName());
            }

            bluetoothJson.put("connected_devices", devicesArray);

        } catch (JSONException e) {
            throw new RuntimeException(e);
        }

        return bluetoothJson;
    }

    // GETTERS:

    public JSONObject getBatteryInfo() {
        return this.batteryInfo;
    }

    public JSONObject getNetworkInfo() {
        return this.networkInfo;
    }

    public JSONObject getBluetoothInfo() {
        return this.bluetoothInfo;
    }

    public JSONObject getWifiInfo() {
        return this.wifiInfo;
    }
}
