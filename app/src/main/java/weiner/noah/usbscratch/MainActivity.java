package weiner.noah.usbscratch;

import androidx.appcompat.app.AppCompatActivity;

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
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private UsbController usbController;
    private static final int VID = 0x2341;
    private static final int PID = 0x0043;

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


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (usbController == null) {
            //if there's no usb controller, create one now
            usbController = new UsbController(this, mConnectionHandler)
        }
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();

        //print out all connected USB devices found
        UsbDevice device = null;
        for (Map.Entry<String, UsbDevice> entry : devices.entrySet()) {
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
    }
}
