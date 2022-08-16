package com.limelight.binding.input.driver;

import android.annotation.SuppressLint;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import com.limelight.LimeLog;

import java.util.HashMap;

public class BluetoothDriverService extends Service {

    private static final String TAG = "bluetoothdriver";
    private BluetoothManager mBluetoothManager;
    private final BroadcastReceiver mBluetoothBroadcast = new BluetoothEventReceiver();
    private final BluetoothDriverBinder binder = new BluetoothDriverBinder();

    private UsbDriverListener listener;

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    private final HashMap<BluetoothDevice, SteamController> mBluetoothDevices = new HashMap<>();


    public class BluetoothDriverBinder extends Binder {
        public void start() {
            BluetoothDriverService.this.initializeBluetooth();
        }

        public void stop() {
            BluetoothDriverService.this.shutdownBluetooth();
        }

        public void setListener(UsbDriverListener listener) {
            BluetoothDriverService.this.listener = listener;

            // Report all controllerMap that already exist
            if (listener != null) {
                for (AbstractController controller : mBluetoothDevices.values()) {
                    listener.deviceAdded(controller);
                }
            }
        }
    }

    private void initializeBluetooth() {
        if (Build.VERSION.SDK_INT <= 30 &&
                getPackageManager().checkPermission(android.Manifest.permission.BLUETOOTH, getPackageName()) != PackageManager.PERMISSION_GRANTED) {
            LimeLog.warning("Couldn't initialize Bluetooth, missing android.permission.BLUETOOTH");
            return;
        }

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) || (Build.VERSION.SDK_INT < 18)) {
            LimeLog.warning("Couldn't initialize Bluetooth, this version of Android does not support Bluetooth LE");
            return;
        }

        // Find bonded bluetooth controllers and create SteamControllers for them
        mBluetoothManager = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
        if (mBluetoothManager == null) {
            // This device doesn't support Bluetooth.
            return;
        }

        BluetoothAdapter btAdapter = mBluetoothManager.getAdapter();
        if (btAdapter == null) {
            // This device has Bluetooth support in the codebase, but has no available adapters.
            return;
        }

        // Get our bonded devices.
        for (BluetoothDevice device : btAdapter.getBondedDevices()) {
            if (isSteamController(device)) {
                connectBluetoothDevice(device);
            }

        }

        // NOTE: These don't work on Chromebooks, to my undying dismay.
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        registerReceiver(mBluetoothBroadcast, filter);
    }

    private void shutdownBluetooth() {
        try {
            unregisterReceiver(mBluetoothBroadcast);
        } catch (Exception e) {
            // We may not have registered, that's okay
        }
    }

    public boolean connectBluetoothDevice(BluetoothDevice bluetoothDevice) {
        synchronized (this) {
            if (mBluetoothDevices.containsKey(bluetoothDevice)) {
                LimeLog.info("Steam controller with address " + bluetoothDevice + " already exists, attempting reconnect");

                SteamController device = mBluetoothDevices.get(bluetoothDevice);
                device.reconnect();

                return false;
            }
            SteamController device = new SteamController(listener, this, bluetoothDevice);
            mBluetoothDevices.put(bluetoothDevice, device);

            // The Steam Controller will mark itself connected once initialization is complete
        }
        return true;
    }

    public void disconnectBluetoothDevice(BluetoothDevice bluetoothDevice) {
        synchronized (this) {
            SteamController device = mBluetoothDevices.get(bluetoothDevice);
            if (device == null)
                return;

            mBluetoothDevices.remove(bluetoothDevice);
            device.stop();
        }
    }

    @SuppressLint({"MissingPermission", "NewApi"})
    public boolean isSteamController(BluetoothDevice bluetoothDevice) {
        // Sanity check.  If you pass in a null device, by definition it is never a Steam Controller.
        if (bluetoothDevice == null) {
            return false;
        }
        if (bluetoothDevice.getName() == null) {
            return false;
        }

        return bluetoothDevice.getName().equals("SteamController") && ((bluetoothDevice.getType() & BluetoothDevice.DEVICE_TYPE_LE) != 0);
    }

    @Override
    public void onDestroy() {
        shutdownBluetooth();
    }

    public class BluetoothEventReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            // Bluetooth device was connected. If it was a Steam Controller, handle it
            if (action.equals(BluetoothDevice.ACTION_ACL_CONNECTED)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

                if (isSteamController(device)) {
                    connectBluetoothDevice(device);
                }
            }

            // Bluetooth device was disconnected, remove from controller manager (if any)
            if (action.equals(BluetoothDevice.ACTION_ACL_DISCONNECTED)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

                disconnectBluetoothDevice(device);
            }
        }
    }
}
