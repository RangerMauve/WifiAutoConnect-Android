# Android WifiAutoConnect

Uses WifiP2P on Android to atomatically establish and connect to wifi access points using a "seed" phrase.

Only supported in Android 10+ (API 29+).

For an example of usage check out [this repository](https://github.com/RangerMauve/WifiP2P-MDNS-Test/blob/default/app/src/main/java/com/example/wifip2p_mdns_test/WifiP2PActivity.java).

## tl;dr

```java
WifiAutoConnect wifiAutoConnect = new WifiAutoConnect(someContextOrActivity);
wifiAutoConnect.setStateChangeListener(new WifiAutoConnect.WifiAutoConnectStateChangeListener(){
  @Override
  public void onStateChange(int state) {
  	if(state === WifiAutoConnect.STATE_CONNECTED) {
			// Start your MulticastSocket to find peers
  	}
  }
}
wifiAutoConnect.autoConnect();
```
