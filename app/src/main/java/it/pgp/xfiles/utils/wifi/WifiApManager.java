package it.pgp.xfiles.utils.wifi;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.lang.reflect.Method;

import it.pgp.xfiles.R;

class WifiApManager {

    public enum WIFI_STATE {
        NO_ADAPTER_FOUND(R.drawable.xfiles_wifi_unavailable),
        ADAPTER_OFF(R.drawable.xfiles_wifi_off),
        ADAPTER_ON(R.drawable.xfiles_wifi_enabled_not_connected),
        CONNECTED(R.drawable.xfiles_wifi_on);

        int resId;

        WIFI_STATE(int resId) {
            this.resId = resId;
        }
    }

    private enum AP_STATE {
        WIFI_AP_STATE_DISABLING,
        WIFI_AP_STATE_DISABLED,
        WIFI_AP_STATE_ENABLING,
        WIFI_AP_STATE_ENABLED,
        WIFI_AP_STATE_FAILED
    }

    protected final WifiManager mWifiManager;
    protected final Context context;

    WifiApManager(Context context) {
        this.context = context;
        mWifiManager = (WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    private AP_STATE getWifiApState() {
        try {
            Method method = mWifiManager.getClass().getMethod("getWifiApState");
            int tmp = ((Integer) method.invoke(mWifiManager));

            // Fix for Android 4
            if (tmp > 10) {
                tmp = tmp - 10;
            }

            return AP_STATE.values()[tmp];
        } catch (Exception e) {
            Log.e(this.getClass().toString(), "", e);
            return AP_STATE.WIFI_AP_STATE_FAILED;
        }
    }

    boolean isWifiApEnabled() {
        return getWifiApState() == AP_STATE.WIFI_AP_STATE_ENABLED;
    }

    ///////////////////////////////

    //check whether wifi hotspot on or off
    boolean isApOn() {
        WifiManager wifimanager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        try {
            Method method = wifimanager.getClass().getDeclaredMethod("isWifiApEnabled");
            method.setAccessible(true);
            return (Boolean) method.invoke(wifimanager);
        }
        catch (Throwable ignored) {}
        return false;
    }

    // toggle wifi hotspot on or off
    boolean configApState(boolean expectedState) {
        WifiManager wifimanager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiConfiguration wificonfiguration = null;
        try {
            // if WiFi is on, turn it off // FIXME isApOn checks ap, not wifi
            if(isApOn()) {
                wifimanager.setWifiEnabled(false);
            }
            Method method = wifimanager.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
//            method.invoke(wifimanager, wificonfiguration, !isApOn(context));
            method.invoke(wifimanager, wificonfiguration, expectedState);
            return true;
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    ///////////////////////////////////////////////
    /**
     * Web source:
     * https://stackoverflow.com/questions/3841317/how-do-i-see-if-wi-fi-is-connected-on-android
     */

    WIFI_STATE checkWifiOnAndConnected() {
        WifiManager wifimanager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifimanager == null) return WIFI_STATE.NO_ADAPTER_FOUND;
        if (wifimanager.isWifiEnabled()) { // Wi-Fi adapter is ON
            WifiInfo wifiInfo = wifimanager.getConnectionInfo();
            if( wifiInfo.getNetworkId() == -1 ) return WIFI_STATE.ADAPTER_ON; // Not connected to an access point
            else return WIFI_STATE.CONNECTED; // Connected to an access point
        }
        else return WIFI_STATE.ADAPTER_OFF; // Wi-Fi adapter is OFF
    }
}