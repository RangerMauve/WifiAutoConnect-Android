package moe.mauve.agregore.wifiautoconnect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.util.Log;

public class WiFiDirectBroadcastReceiver extends BroadcastReceiver {
    static final String TAG = "WifiAutoConnect";

    WifiAutoConnect autoConnect;
    WifiAutoConnect.WifiAutoConnectActionListener onInitialized;

    public WiFiDirectBroadcastReceiver(WifiAutoConnect autoConnect) {
        super();
        this.autoConnect = autoConnect;
    }

    public void setOnInitialized(WifiAutoConnect.WifiAutoConnectActionListener onInitialized) {
        this.onInitialized = onInitialized;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // TODO: This method is called when the BroadcastReceiver is receiving
        // an Intent broadcast.
        String action = intent.getAction();

        if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
            // Check to see if Wi-Fi is enabled and notify appropriate activity
            int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
            Log.i(TAG, "P2P State Changed " + state);
            if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                // Wifi P2P is enabled
                if(onInitialized != null) {
                    onInitialized.onSuccess();
                    onInitialized = null;
                }
            } else {
                // Wi-Fi P2P is not enabled
                if(onInitialized !=null) {
                    onInitialized.onFailure(new Exception("Unable to set up WiFi P2P"));
                    onInitialized = null;
                }
            }
        } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
            Log.i(TAG, "P2P Connection State Changed. Likely got a peer");
        }
    }
}