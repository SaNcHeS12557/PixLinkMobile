package com.example.pixlinkmobile;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

public class DeviceStatusBuilder {
    public static JSONObject buildStatus(Context context, DeviceMetricsCollector deviceMetricsCollector) {
        JSONObject root = new JSONObject();
        JSONObject data = new JSONObject();

        try {
            root.put("type","device_status");
            root.put("timestamp",System.currentTimeMillis() / 1000);

            data.put("battery", deviceMetricsCollector.getBatteryInfo());
            data.put("wifi",deviceMetricsCollector.getWifiInfo());
            data.put("network",deviceMetricsCollector.getNetworkInfo());
            data.put("bluetooth",deviceMetricsCollector.getBluetoothInfo());

            root.put("data",data);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        return root;
    }

}
