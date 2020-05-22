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

    //instantiate a new IPermissionReceiver interface, implementing the perm denied fxn
    IPermissionListener mPermissionListener = new IPermissionListener() {
        @Override
        public void onPermissionDenied(UsbDevice d) {
            Log.e("USBERROR", "Permission denied for device " + d.getDeviceId());
        }
    };

    //instantiate a new PermissionReceiver for registering in init()
    private BroadcastReceiver mPermissionReceiver = new PermissionReceiver(mPermissionListener);


    public UsbController(Activity parentActivity, IUsbConnectionHandler connectionHandler, int vid, int pid) {
        mApplicationContext = parentActivity.getApplicationContext();
        mConnectionHandler = connectionHandler;
        mUsbManager = (UsbManager) mApplicationContext.getSystemService(Context.USB_SERVICE);
        VID = vid;
        PID = pid;
        init();
    }

    private class PermissionReceiver extends BroadcastReceiver {
        private final IPermissionListener permissionListener;

        //constructor
        public PermissionReceiver(IPermissionListener listener) {
            permissionListener = listener;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            //unregister this broadcast receiver
            mApplicationContext.unregisterReceiver(this);

            String action = intent.getAction();

            //check to see if this action was regarding USB permission
            if (ACTION_USB_PERMISSION.equals(action)) {
                //check granted
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);

                //get the specific device through intent extra
                UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                //if permission was not granted, call permission denied with device
                if (!granted) {
                    permissionListener.onPermissionDenied(device);
                }
                else {
                    //otherwise we can set up communication with the device
                    Log.d("USBTAG", "Permission granted");
                    //first check if device is null
                    if (device!=null) {
                        //make sure this is the Arduino
                        if (device.getVendorId() == VID && device.getProductId() == PID) {
                            //start USB setup in new thread
                            startHandler(device);
                        }
                        else {
                            //Arduino not present
                            Log.e("USBERROR", "Arduino not found");
                        }
                    }
                }
            }
        }
    }


    /*
    call method to set up device communication
        UsbDeviceConnection connection = usbManager.openDevice(device);

        UsbEndpoint endpoint = device.getInterface(0).getEndpoint(0);

        connection.claimInterface(device.getInterface(0), true);
        connection.bulkTransfer(endpoint, DATA, DATA.length, TIMEOUT);
     */



    private void init() {
        listDevices(new IPermissionListener() {
            @Override
            public void onPermissionDenied(UsbDevice d) {
                UsbManager usbManager = (UsbManager) mApplicationContext.getSystemService(Context.USB_SERVICE);

                //sends permission intent in the broadcast ("the getBroadcast method retrieves a PendingIntent that WILL perform a broadcast(it's waiting)")
                PendingIntent pi = PendingIntent.getBroadcast(mApplicationContext, 0, new Intent(ACTION_USB_PERMISSION), 0);

                //register a broadcast receiver to listen
                //Register a BroadcastReceiver to be run in the main activity thread. The receiver will be called with any
                // broadcast Intent that matches filter, in the main application thread.
                mApplicationContext.registerReceiver(mPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));

                //request permission to access last USB device found in map, store result in permissionIntent
                usbManager.requestPermission(d, pi);
            }
        });
    }

    private void listDevices(IPermissionListener permissionListener) {
        HashMap<String, UsbDevice> devices = mUsbManager.getDeviceList();

        //print out all connected USB devices found
        UsbDevice device = null;
        int prodId, vendId;

        for (Map.Entry<String, UsbDevice> entry : devices.entrySet()) {
            device = (UsbDevice)entry.getValue();
            prodId = device.getProductId();
            vendId = device.getVendorId();

            //print out the device found
            Toast.makeText(mApplicationContext, "Found device:" + device.getDeviceName() + " with ID "+
                    String.format("%04X:%04X", device.getVendorId(), device.getProductId()), Toast.LENGTH_SHORT).show();

            //check to see if this device is Arduino we're looking for
            if (vendId == VID && prodId == PID) {
                Toast.makeText(mApplicationContext, "Device found: "+device.getDeviceName(), Toast.LENGTH_SHORT);

                //if we don't have permission to access the device, error out
                if (!mUsbManager.hasPermission(device)) {
                    permissionListener.onPermissionDenied(device);
                }
                else {
                    //start setting up the USB device in new thread
                    startHandler(device);
                    return;
                }
                break;
            }
        }

        //if reached here with no return, we couldn't lock onto a found device or couldn't find, ERROR
        Log.e("USBERROR", "No more devices to list");
        mConnectionHandler.onDeviceNotFound();
    }

    private static interface IPermissionListener {
        void onPermissionDenied(UsbDevice d);
    }
}
