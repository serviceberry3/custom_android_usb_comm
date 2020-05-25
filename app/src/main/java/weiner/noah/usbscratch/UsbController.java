package weiner.noah.usbscratch;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;
import android.widget.Toast;

import java.nio.channels.InterruptedByTimeoutException;
import java.time.chrono.MinguoChronology;
import java.util.HashMap;
import java.util.Map;

public class UsbController {
    public final Context mApplicationContext;
    public final UsbManager mUsbManager;
    public final IUsbConnectionHandler mConnectionHandler;
    private final int VID;
    private final int PID;
    protected static final String ACTION_USB_PERMISSION = "weiner.noah.USB_PERMISSION";
    public volatile int direction=0;
    public volatile int transferring=0;
    public final Activity activity;
    public int error;


    //constant variable for the UsbRunnable (data transfer loop)
    private UsbRunnable mLoop;

    //separate thread for usb data transfer
    private Thread mUsbThread;

    //instantiate a new IPermissionReceiver interface, implementing the perm denied fxn
    IPermissionListener mPermissionListener = new IPermissionListener() {
        @Override
        public void onPermissionDenied(UsbDevice d) {
            Log.e("USBERROR", "Permission denied for device " + d.getDeviceId());
        }
    };

    //instantiate a new PermissionReceiver for registering in init()
    private BroadcastReceiver mPermissionReceiver = new PermissionReceiver(mPermissionListener);


    public UsbController(Activity parentActivity, IUsbConnectionHandler connectionHandler, int vid, int pid, Activity act) {
        mApplicationContext = parentActivity.getApplicationContext();
        mConnectionHandler = connectionHandler;
        mUsbManager = (UsbManager) mApplicationContext.getSystemService(Context.USB_SERVICE);
        VID = vid;
        PID = pid;
        activity = act;
        error=0;
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
                            startDataTransferThreads(device);
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
                Toast.makeText(mApplicationContext, "Device found: "+device.getDeviceName(), Toast.LENGTH_SHORT).show();

                //if we don't have permission to access the device, error out
                if (!mUsbManager.hasPermission(device)) {
                    permissionListener.onPermissionDenied(device);
                }
                else {
                    //start setting up the USB device in new thread
                    startDataTransferThreads(device);
                    return;
                }
                break;
            }
        }

        //if reached here with no return, we couldn't lock onto a found device or couldn't find, ERROR
        Log.e("USBERROR", "No more devices to list");
        error=1;
        mConnectionHandler.onDeviceNotFound();
    }

    private static interface IPermissionListener {
        void onPermissionDenied(UsbDevice d);
    }

    //This is the meat. We set up the USB communication interface similar to how we did in the PC to Arduino interface

    //an empty array is less overhead space than an actual instantiation of a new Object()
    private static final Object[] sSendLock = new Object[]{};
    private boolean mStop = false;

    //the byte for sending
    private byte mData = 0x00;

    //public data received from Arduino for parsing
    public byte[] dataIn = new byte[64];

    private class UsbRunnable implements Runnable {
        private final UsbDevice device;

        //constructor
        UsbRunnable(UsbDevice dev) {device = dev;}

        @Override
        //implement main USB functionality
        public void run() {
            //open communication with the device
            UsbDeviceConnection connection = mUsbManager.openDevice(device);

            UsbInterface usb2serial = device.getInterface(1);
            //claim interface 1 (Usb-serial) of the Duino, disconnecting kernel driver if necessary
            if (!connection.claimInterface(usb2serial, true)) {
                //if we can't claim exclusive access to this UART line, then FAIL
                Log.e("CONNECTION", "Failed to claim exclusive access to the USB interface.");
                return;
            }

            //ARDUINO SPECIFIC INITIALIZATION - USB-Serial conversion for Arduino

            //set control line state
            connection.controlTransfer(0x21, 34, 0, 0, null, 0, 0);

            //set line encoding: 9600 bits/sec, 8data bits, no parity bit, 1 stop bit for UART
            connection.controlTransfer(0x21, 32, 0, 0, new byte[] { (byte) 0x80,
                    0x25, 0x00, 0x00, 0x00, 0x00, 0x08 }, 7, 0);

            UsbEndpoint in = null;
            UsbEndpoint out = null;

            //iterate through all USB endpoints on this interface, looking for bulk transfer endpoints
            for (int i=0; i<usb2serial.getEndpointCount(); i++) {
                UsbEndpoint thisEndpoint = usb2serial.getEndpoint(i);
                if (thisEndpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    //found bulk endpoint, now distinguish which are read and write points
                    if (thisEndpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                        Log.d("ENDPTS", "Found in point");
                        Log.d("ENDPTS", String.format("In address: %d", thisEndpoint.getAddress()));
                        in = thisEndpoint;
                    }
                    else {
                        Log.d("ENDPTS", "Found out point");
                        Log.d("ENDPTS", String.format("Out address: %d", thisEndpoint.getAddress()));
                        out = thisEndpoint;
                    }
                }
            }

            //data transferring loop
            while (true) {
                //synchronized means only one thread at a time can do this stuff. Basically no other thread can do stuff to the sSendLock object because this thread has the lock on it
                    synchronized (sSendLock) { //create an output queue
                        try {
                            //have this thread wait until another thread invokes notify (sSendLock)
                            sSendLock.wait();
                        } catch (InterruptedException e) {
                            //on interrupt exception, if stop is set, then call onStopped()
                            if (mStop) {
                                Log.e("ERROR", "InterruptedException in synchron");
                                mConnectionHandler.onUsbStopped();
                                return;
                            }
                            e.printStackTrace();
                        }
                    }
                Log.e("THREAD", String.format("Value of direction is: %d", direction));
                    if (mStop) {
                        Log.e("ERROR", "Stopped after notify()");
                        mConnectionHandler.onUsbStopped();
                        transferring=0;
                        return;
                    }
                    if (direction==1) {
                        //transfer the byte of length 1, sending or receiving as specified
                        connection.bulkTransfer(out, new byte[]{mData}, 1, 0);
                    }
                    else {
                        //transfer the byte of length 1, sending or receiving as specified
                        Log.e("TRANSFER", "Beginning transfer...");
                        int bytesTransferred = connection.bulkTransfer(in, dataIn, 64, 0);
                        Log.e("TRANSFER", String.format("# of bytes transferred: %d", bytesTransferred));
                        if (bytesTransferred<0) {
                            mStop = true;
                        }
                    }
                    Log.e("THREAD", "Setting |transferring| back to 0");
                    transferring=0;
            }
        }
    }

    //function to send a byte of data (wakes up data transfer thread)
    public void send (byte data) {
        if (mStop) {
            return;
        }
        mData = data;
        direction = 1;
        synchronized (sSendLock) {
            //wake up sSendLock for bulk transfer
            sSendLock.notify();
        }
    }

    public void receive () {
        if (mStop) {
            return;
        }
        direction = 0;
        synchronized (sSendLock) {
            //wake up sReceiveLock for receiving
            sSendLock.notify();
        }
        transferring=1;
        Log.d("TRANSFERRING VAL", String.format("%d", transferring));

        //wait until all receiving finished
        while (transferring==1) {
            ;
        }
        for (byte thisByte : dataIn) {
            Log.d("BYTEREAD", String.format("%x", thisByte));
        }
        Log.d("TRANSFERRING VAL", String.format("%d", transferring));
    }

    //stop usb data transfer
    public void stop() {
        //flag the thread to stop
        mStop = true;
        synchronized (sSendLock) {
            //wake up both of the data transfer threads to make them return
            sSendLock.notify();
        }
        //terminate the data transfer thread by joining it to main UI thread
        try {
            if (mUsbThread!=null)
                mUsbThread.join();
        }
        catch (InterruptedException e) {
            Log.e("THREADERROR", String.valueOf(e));
        }

        //reset stop flag, current usbrunnable instance, and data thread
        mStop = false;
        mLoop = null;
        mUsbThread = null;

        //try to unregister the permission receiver
        try {
            mApplicationContext.unregisterReceiver(mPermissionReceiver);
        }
        catch (IllegalArgumentException e) {};
    }

    //start up a new thread for USB comms with the given device
    private void startDataTransferThreads(UsbDevice device) {
        if (mLoop !=null) {
            //USB data transfer thread already running
            mConnectionHandler.onErrorLooperRunningAlready();
            return;
        }
        //make new UsbRunnable and thread for comms with the device
        mLoop = new UsbRunnable(device);

        //assign the new runnable to new thread
        mUsbThread = new Thread(mLoop);

        //start new thread in background
        mUsbThread.start();
    }
}


