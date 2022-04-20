package moe.mauve.agregore.wifiautoconnect;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class WifiAutoConnect {
    static final String TAG = "WifiAutoConnect";
    static final String DEFAULT_SEED = "WifiAutoConnect";
    static final int DEFAULT_SEARCH_TIMEOUT = 10 * 1000;
    // Wait a second after group creation for the network interface to be set up
    // Necessary because we don't get a notification for it anymore.
    static final int CREATE_WAIT = 1000;

    public static final int STATE_ERROR = -1;
    public static final int STATE_UNINITIALIZED = 0;
    public static final int STATE_INITIALIZED = 1;
    public static final int STATE_CONNECTING = 2;
    public static final int STATE_CONNECTED = 3;
    public static final int STATE_DISCONNECTED = 4;

    String seed;
    boolean hasRegisteredReceiver = false;
    boolean hasCreatedGroup = false;
    int searchTimeout = DEFAULT_SEARCH_TIMEOUT;
    int state = STATE_UNINITIALIZED;

    WifiAutoConnectStateChangeListener stateChangeListener = null;
    Handler handler;

    Context context;
    WifiManager wifiManager;
    WifiP2pManager p2pManager;
    WifiP2pManager.Channel channel;
    IntentFilter intentFilter;
    WiFiDirectBroadcastReceiver receiver;

    public static class WifiAutoConnectActionListener {
        private WifiAutoConnectActionListener parentListener = null;
        public WifiAutoConnectActionListener() {}
        public WifiAutoConnectActionListener(WifiAutoConnectActionListener parentListener) {
            this.parentListener = parentListener;
        }
        public void onSuccess() {
            if(parentListener != null) parentListener.onSuccess();
        }
        public void onFailure(Exception e) {
            if(parentListener != null) parentListener.onFailure(e);
        }
    }

    public static class WifiAutoConnectStateChangeListener {
        public void onStateChange(int state) {}
    }

    // No-operation. Can be used to set defaults where we don't care
    static WifiAutoConnectActionListener noOp = new WifiAutoConnectActionListener();

    public static class GroupNotFoundException extends Exception {
        GroupNotFoundException() {
            super("Wifi Group Not Found");
        }
    }

    public WifiAutoConnect(Context context) {
        this(context, DEFAULT_SEED);
    }
    public WifiAutoConnect(Context context, String seed) {
        this.context = context;
        this.seed = seed;
        handler = new Handler(context.getMainLooper());
    }

    public void setSearchTimeout(int searchTimeout) {
        if(searchTimeout <= 0) {
            this.searchTimeout = DEFAULT_SEARCH_TIMEOUT;
            return;
        }
        this.searchTimeout = searchTimeout;
    }

    public void setStateChangeListener(WifiAutoConnectStateChangeListener stateChangeListener) {
        this.stateChangeListener = stateChangeListener;
    }

    public int getState() {
        return state;
    }

    void setState(int newState) {
        // TODO: Log invalid state transitions?
        if(newState == state) return;
        // TODO: Emit state changes to callback
        state = newState;
        if(stateChangeListener != null) {
            handler.post(() -> stateChangeListener.onStateChange(newState));
        }
    }

    public void onResume() {
        if(isInitialized() && !hasRegisteredReceiver) {
            context.registerReceiver(receiver, intentFilter);
            hasRegisteredReceiver = true;
        }
    }

    public void onPause() {
        if(isInitialized() && hasRegisteredReceiver) {
            context.unregisterReceiver(receiver);
            hasRegisteredReceiver = false;
        }
    }

    public boolean isInitialized() {
        return channel != null;
    }

    public boolean isHost() {
        return hasCreatedGroup;
    }

    public String deriveSSID() {
        String derived = this.deriveBytes(seed + ":SSID");
        String usefulBytes = derived.substring(0, 16);
        return "DIRECT-" + usefulBytes;
    }

    public String derivePassword() {
        return this.deriveBytes(seed + ":PASSWORD");
    }

    private String deriveBytes(String seed) {
        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            // This should never happen?
            return "";
        }
        md5.update(seed.getBytes(StandardCharsets.UTF_8));
        byte[] hash = md5.digest();
        StringBuilder hex = new StringBuilder();
        for(byte hashByte : hash) {
            String h = Integer.toHexString(0xFF & hashByte);
            if (h.length() == 1 ) hex.append("0");
            hex.append(h);
        }
        return hex.toString();
    }

    private void initialize(WifiAutoConnectActionListener onInitialized) {
        Log.i(TAG, "Initializing");
        if(isInitialized()) {
            onInitialized.onSuccess();
            return;
        }
        // TODO: add onConnect callback to receiver for once it's initialized
        receiver = new WiFiDirectBroadcastReceiver(this);

        receiver.setOnInitialized(new WifiAutoConnectActionListener(onInitialized){
            @Override
            public void onSuccess() {
                Log.i(TAG, "Initialized");
                setState(STATE_INITIALIZED);
                super.onSuccess();
            }

            @Override
            public void onFailure(Exception e) {
                Log.e(TAG, "Error Initializing", e);
                setState(STATE_ERROR);
                super.onFailure(e);
            }
        });

        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);

        context.registerReceiver(receiver, intentFilter);
        hasRegisteredReceiver = true;

        wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        if(!wifiManager.isWifiEnabled()) {
            onInitialized.onFailure(new Exception("Wifi not enabled"));
            return;
        }

        p2pManager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        channel = p2pManager.initialize(context, context.getMainLooper(), null);
    }

    public void autoConnect() {
        autoConnect(noOp);
    }

    public void autoConnect(WifiAutoConnectActionListener onConnect) {
        Log.i(TAG, "Auto-Connecting");
        initialize(new WifiAutoConnectActionListener(onConnect){
            @Override
            public void onSuccess() {
                connectToGroup(new WifiAutoConnectActionListener(onConnect){
                    @Override
                    public void onFailure(Exception e) {
                        Log.e(TAG, "Unable to connect to group", e);
                        if(e instanceof GroupNotFoundException) {
                            startGroup(onConnect);
                        } else {
                            onConnect.onFailure(e);
                        }
                    }
                });
            }
        });
    }

    public void disconnect() {
        disconnect(noOp);
    }

    public void disconnect(WifiAutoConnectActionListener onDisconnected) {
        if(isInitialized()) {
            // TODO: Disconnect when connected to access point as client?
            // Use releaseNetworkRequest
            // https://developer.android.com/reference/android/net/ConnectivityManager#releaseNetworkRequest(android.app.PendingIntent)
            if(hasCreatedGroup) stopGroup(onDisconnected);
            else onDisconnected.onSuccess();
        } else {
            onDisconnected.onSuccess();
        }
    }

    private void connectToGroup(WifiAutoConnectActionListener onConnect) {
        String ssid = deriveSSID();
        String password = derivePassword();

        final NetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build();
        final NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build();
        final ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        setState(STATE_CONNECTING);

        connectivityManager.requestNetwork(request, new ConnectivityManager.NetworkCallback() {
            @Override
            public void onUnavailable() {
                setState(STATE_DISCONNECTED);
                onConnect.onFailure(new GroupNotFoundException());
            }

            @Override
            public void onAvailable(Network network) {
                Log.i(TAG, "Connected to existing hotspot");
                setState(STATE_CONNECTED);
                onConnect.onSuccess();
            }

            @Override
            public void onLost(Network network) {
                Log.i(TAG, "Disconnected from hotspot");
                // TODO: DO we care?
                setState(STATE_DISCONNECTED);
            }
        }, searchTimeout);

    }

    @SuppressLint("MissingPermission")
    private void startGroup(WifiAutoConnectActionListener onStarted){
        Log.i(TAG, "Starting group");
        p2pManager.requestGroupInfo(channel, wifiP2pGroup -> {
            if(wifiP2pGroup != null) {
                Log.i(TAG, "Group already exists " + wifiP2pGroup);
                hasCreatedGroup = true;
                setState(STATE_CONNECTED);
                onStarted.onSuccess();
                return;
            }

            String ssid = deriveSSID();
            String password = derivePassword();

            Log.i(TAG, "Creating new group: " + ssid);

            WifiP2pConfig config = new WifiP2pConfig
                    .Builder()
                    .setNetworkName(ssid)
                    .setPassphrase(password)
                    // .setDeviceAddress(MacAddress.fromString(HOST_MAC_ADDRESS))
                    .build();

            setState(STATE_CONNECTING);

            p2pManager.createGroup(channel, config, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "Created new group");
                    hasCreatedGroup = true;
                    handler.postDelayed(() -> setState(STATE_CONNECTED), CREATE_WAIT);
                }

                @Override
                public void onFailure(int i) {
                    String reason = getActionFailureReason(i);
                    setState(STATE_ERROR);
                    onStarted.onFailure(new Exception("Unable to create group: " + reason));
                }
            });
        });
    }

    private void stopGroup(WifiAutoConnectActionListener onStopped) {
        Log.i(TAG, "Stopping group");
        p2pManager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "Stopped group");
                setState(STATE_DISCONNECTED);
                onStopped.onSuccess();
            }

            @Override
            public void onFailure(int i) {
                String reason = getActionFailureReason(i);
                Exception e = new Exception("Unable to stop group: " + reason);
                Log.e(TAG, "Unable to stop group", e);
                onStopped.onFailure(e);
            }
        });
    }

    private static String getActionFailureReason(int i) {
        String reason;
        reason = "Unknown";
        if(i == WifiP2pManager.P2P_UNSUPPORTED) reason = "P2P unsupported";
        else if(i == WifiP2pManager.ERROR) reason = "Error";
        else if(i == WifiP2pManager.BUSY) reason = "Busy";
        return reason;
    }
}
