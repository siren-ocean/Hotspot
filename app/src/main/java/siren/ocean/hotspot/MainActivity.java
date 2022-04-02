package siren.ocean.hotspot;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.android.dx.stock.ProxyBuilder;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 热点启动
 * Created by Siren on 2022/02/30.
 */
public class MainActivity extends AppCompatActivity {

    private final static String TAG = "Hotspot";
    private final static int WIFI_AP_STATE_ENABLED = 13;//WifiManager.WIFI_AP_STATE_ENABLED= 13

    private WifiManager mWifiManager;
    private ConnectivityManager mConnectivityManager;
    private EditText etName, etPassword;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mWifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        mConnectivityManager = ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE));
        etName = findViewById(R.id.et_name);
        etPassword = findViewById(R.id.et_password);
    }

    private String getName() {
        return etName.getText().toString().trim();
    }

    private String getPassword() {
        return etPassword.getText().toString().trim();
    }

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_start:
                if (getWifiApState() == WIFI_AP_STATE_ENABLED) {
                    stopHotspot();
                } else {
                    startHotspot();
                }
                break;
        }
    }

    private void startHotspot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.System.canWrite(this)) {
                dealWithAp();
            } else {
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        } else {
            dealWithAp();
        }
    }

    private void stopHotspot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopTethering();
        } else {
            closeWifiAp();
        }
    }

    private void dealWithAp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startTethering(true);
        } else {
            setWifiApEnabled();
        }
    }

    /**
     * 启动热点 android7及以下
     */
    private void setWifiApEnabled() {
        boolean isSuccess;
        try {
            mWifiManager.setWifiEnabled(false);
            WifiConfiguration apConfig = new WifiConfiguration();
            apConfig.SSID = getName();
            apConfig.preSharedKey = getPassword();
            apConfig.allowedKeyManagement.set(4);
            Method method = mWifiManager.getClass().getMethod(
                    "setWifiApEnabled", WifiConfiguration.class, Boolean.TYPE);
            isSuccess = (Boolean) method.invoke(mWifiManager, apConfig, true);
        } catch (Exception e) {
            isSuccess = false;
            e.printStackTrace();
        }
        Toast.makeText(this, isSuccess ? "开启成功" : "开启失败", Toast.LENGTH_SHORT).show();
    }

    /**
     * 关闭热点 android7及以下
     */
    public void closeWifiAp() {
        try {
            Method method = mWifiManager.getClass().getMethod("getWifiApConfiguration");
            method.setAccessible(true);
            WifiConfiguration config = (WifiConfiguration) method.invoke(mWifiManager);
            Method method2 = mWifiManager.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
            method2.invoke(mWifiManager, config, false);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * android 9 启动热点
     */
    public void startTethering(boolean isFirstStart) {
        //需要判断一下是否已经开启，如果已经开启则不必再启动监听
        if (getWifiApState() == WIFI_AP_STATE_ENABLED) {
            updateApConfig();
            return;
        }
        try {
            Class classOnStartTetheringCallback = Class.forName("android.net.ConnectivityManager$OnStartTetheringCallback");
            Method startTethering = mConnectivityManager.getClass().getDeclaredMethod("startTethering", int.class, boolean.class, classOnStartTetheringCallback);
            Object proxy = ProxyBuilder.forClass(classOnStartTetheringCallback).handler(new InvocationHandler() {
                @Override
                public Object invoke(Object o, Method method, Object[] objects) throws Throwable {
                    if (method.getName().equals("onTetheringStarted")) {
                        if (isFirstStart) {
                            updateApConfig();
                            restartTethering();
                        }
                    }
                    return null;
                }
            }).build();
            startTethering.invoke(mConnectivityManager, 0, true, proxy);
        } catch (Exception e) {
            Log.e(TAG, "打开热点失败");
            e.printStackTrace();
        }
    }

    private void restartTethering() {
        stopTethering();
        TaskUtils.handler().postDelayed(() -> {
            Toast.makeText(MainActivity.this, "开启成功", Toast.LENGTH_SHORT).show();
            startTethering(false);
        }, 200);
    }

    /**
     * android 9 关闭热点
     */
    public void stopTethering() {
        try {
            Method method = mConnectivityManager.getClass().getMethod("stopTethering", int.class);
            method.invoke(mConnectivityManager, 0);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 更新热点信息
     */
    private boolean updateApConfig() {
        try {
            WifiConfiguration apConfig = new WifiConfiguration();
            apConfig.SSID = getName();
            apConfig.preSharedKey = getPassword();
            apConfig.allowedKeyManagement.set(4);
            apConfig.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            Field apChannelField = WifiConfiguration.class.getDeclaredField("apChannel");
            apChannelField.set(apConfig, 11);
            Method configMethod = mWifiManager.getClass().getMethod("setWifiApConfiguration", WifiConfiguration.class);
            return (boolean) (Boolean) configMethod.invoke(mWifiManager, apConfig);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * WifiManager.WIFI_AP_STATE_ENABLED= 13
     * WifiApState = WIFI_AP_STATE_ENABLED 则热点已经打开
     */
    private int getWifiApState() {
        try {
            Method method = mWifiManager.getClass().getMethod("getWifiApState");
            int value = (Integer) method.invoke(mWifiManager);
            return value;
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }
}