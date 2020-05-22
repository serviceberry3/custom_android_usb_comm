package weiner.noah.usbscratch;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbManager;
import android.util.Log;
import android.widget.Toast;

import java.nio.channels.InterruptedByTimeoutException;
import java.util.HashMap;
import java.util.Map;

public class UsbController {
    private final Context mApplicationContext;
    private final UsbManager mUsbManager;
    private final IUsbConnectionHandler mConnectionHandler;
    private final int VID;
    private final int PID;
    protected static final String ACTION_USB_PERMISSION = "weiner.noah.USB_PERMISSION";


    public UsbController(Activity parentActivity, IUsbConnectionHandler connectionHandler, int vid, int pid) {
        mApplicationContext = parentActivity.getApplicationContext();
        mConnectionHandler = connectionHandler;
        mUsbManager = (UsbManager) mApplicationContext.getSystemService(Context.USB_SERVICE);
        VID = vid;
        PID = pid;
        init();
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            //check to see if this action was regarding USB permission
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    //get the specific device through intent extra
                    UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    //if permission given, then we can set up communication with the device
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            //call method to set up device communication
                            UsbDeviceConnection connection = usbManager.openDevice(device);

                            UsbEndpoint endpoint = device.getInterface(0).getEndpoint(0);

                            connection.claimInterface(device.getInterface(0), true);
                            connection.bulkTransfer(endpoint, DATA, DATA.length, TIMEOUT);
                        }
                    }
                    else {
                        Log.d("THISTAG", "permission denied for device " + device);
                    }
                }
            }
        }
    };

    HashMap<String, UsbDevice> devices = usbManager.getDeviceList();

    //print out all connected USB devices found
    UsbDevice device = null;
        for (
    Map.Entry<String, UsbDevice> entry : devices.entrySet()) {
        Toast.makeText(this, ((UsbDevice)entry.getValue()).getDeviceName(), Toast.LENGTH_SHORT).show();
        device = entry.getValue();
    }

    //sends permission intent in the broadcast ("the getBroadcast method retrieves a PendingIntent that WILL perform a broadcast(it's waiting)")
    PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);

    IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);

    //register a broadcast receiver to listen
    //Register a BroadcastReceiver to be run in the main activity thread. The receiver will be called with any
    // broadcast Intent that matches filter, in the main application thread.
    registerReceiver(usbReceiver, filter);

    //request permission to access last USB device found in map, store result in permissionIntent
        assert (!devices.isEmpty());
        assert device != null;

        if (device!=null) {
        usbManager.requestPermission(device, permissionIntent);
    }
        else {
        Log.d("TESTTAG", "Device came up NULL");
    }

    private void init() {
        enumerate(new IPermissionListener() {
            @Override
            public void onPermissionDenied(UsbDevice d) {
                UsbManager usbManager = (UsbManager) mApplicationContext.getSystemService(Context.USB_SERVICE);
                PendingIntent pi = PendingIntent.getBroadcast(mApplicationContext, 0, new Intent(ACTION_USB_PERMISSION), 0);
                mApplicationContext.registerReceiver(mPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));
                usbManager.requestPermission(d, pi);
            }
        }
    }

    private static interface IPermissionListener {
        void onPermissionDenied(UsbDevice d);
    }

}
