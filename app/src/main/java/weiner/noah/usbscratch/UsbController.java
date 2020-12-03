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
import android.hardware.usb.UsbRequest;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;

import java.nio.ByteBuffer;
import java.nio.channels.InterruptedByTimeoutException;
import java.time.chrono.MinguoChronology;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

public class UsbController {
    public final Context mApplicationContext;
    public final UsbManager mUsbManager;
    private UsbDevice device;
    private UsbDeviceConnection connection;
    public final IUsbConnectionHandler mConnectionHandler;
    private UsbEndpoint in, out;
    private final int VID;
    private final int PID;
    protected static final String ACTION_USB_PERMISSION = "weiner.noah.USB_PERMISSION";
    private volatile int direction = 0, transferring = 0;
    public final Activity activity;
    public int error;

    //keep track of USB data transfer latency
    private long sendTimeValue, receiveTimeValue;
    private long latency;

    //textviews for timestamps
    public TextView sendTime, receiveTime, latencyText;

    public byte b;

    //constant variable for the UsbRunnable (data transfer loop)
    private UsbRunnable mLoop;
    private ReadRunnable mReceiver;

    private UsbRequest readingRequest;

    //separate thread for usb data transfer
    private Thread mUsbThread, mReceiveThread;

    //instantiate a new IPermissionReceiver interface, implementing the perm denied fxn
    IPermissionListener mPermissionListener = new IPermissionListener() {
        @Override
        public void onPermissionDenied(UsbDevice d) {
            Log.e("USBERROR", "Permission denied for device " + d.getDeviceId());
        }
    };

    //instantiate a new PermissionReceiver for registering in init()
    private BroadcastReceiver mPermissionReceiver = new PermissionReceiver(mPermissionListener);


    public UsbController (Activity parentActivity, IUsbConnectionHandler connectionHandler, int vid, int pid, Activity act) {
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

                //if permission was not granted, call onPermDenied method of the passed IPermissionListener interface to display Log error message
                if (!granted) {
                    permissionListener.onPermissionDenied(device);
                }
                else {
                    //otherwise we can set up communication with the device
                    Log.d("USBTAG", "Permission granted for the device");

                    //first check if device is null
                    if (device!=null) {
                        //make sure this is the Arduino
                        if (device.getVendorId() == VID && device.getProductId() == PID) {
                            //locked onto the Arduino, now start the USB protocol setup
                            openConnectionOnReceivedPermission();
                        }
                        else {
                            //Arduino not present
                            Log.e("USBERROR", "USB permission granted, but this device is not Arduino");
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
                //get a USB manager instance
                UsbManager usbManager = (UsbManager) mApplicationContext.getSystemService(Context.USB_SERVICE);

                //sends permission intent in the broadcast ("the getBroadcast method retrieves a PendingIntent that WILL perform a broadcast(it's waiting)")
                //basically broadcasts the given Intent to all interested BroadcastReceivers
                PendingIntent pi = PendingIntent.getBroadcast(mApplicationContext, 0, new Intent(ACTION_USB_PERMISSION), 0); //asynchronous, returns immediately

                //register a broadcast receiver to listen
                //Register a BroadcastReceiver to be run in the main activity thread. The receiver will be called with any
                // broadcast Intent that matches filter, in the main application thread.
                mApplicationContext.registerReceiver(mPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));

                //request permission to access last USB device found in map, store result (success or failure) in permissionIntent, results in system dialog displayed
                usbManager.requestPermission(d, pi); //extras that will be added to pi: EXTRA_DEVICE containing device passed, and EXTRA_PERMISSION_GRANTED containing bool of result
            }
        });
    }

    private void openConnectionOnReceivedPermission() {
        if (error==0) {
            //open communication with the device
            connection = mUsbManager.openDevice(device);

            Log.i("USBTAG", "Getting interface...");
            UsbInterface usb2serial = device.getInterface(0);
            Log.i("USBTAG", "Interface gotten");


            Log.i("USBTAG", "Claiming interface...");
            //claim interface 1 (Usb-serial) of the Duino, disconnecting kernel driver if necessary
            if (!connection.claimInterface(usb2serial, true)) {
                //if we can't claim exclusive access to this UART line, then FAIL
                Log.e("CONNECTION", "Failed to claim exclusive access to the USB interface.");
                return;
            }
            Log.i("USBTAG", "Interface claimed");


            //USB CONTROL INITIALIZATION


            Log.i("USBTAG", "Control transfer start...");
            //set control line state, as defined in https://cscott.net/usb_dev/data/devclass/usbcdc11.pdf, p. 51
            connection.controlTransfer(0x21, 34, 0, 0, null, 0, 10);

            //set line encoding: 9600 bits/sec, 8data bits, no parity bit, 1 stop bit for UART
            connection.controlTransfer(0x21, 32, 0, 0, new byte[] { (byte) 0x80, 0x25, 0x00, 0x00, 0x00, 0x00, 0x08 }, 7, 10);

            Log.i("USBTAG", "Control transfer end...");

            in = null;
            out = null;

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
            Log.d("STARTTHREADS", "Starting data transfer threads...");
            //start setting up the USB device in new thread
            startDataTransferThreads(device);
        }
        else {
            Log.d("ERROR", "Error found");
        }
    }

    private void listDevices(IPermissionListener permissionListener) {
        Log.d("DBUG", "Welcome to listDevices");
        HashMap<String, UsbDevice> devices = mUsbManager.getDeviceList();

        //print out all connected USB devices found
        device = null;
        int prodId, vendId;

        for (Map.Entry<String, UsbDevice> entry : devices.entrySet()) {
            device = (UsbDevice)entry.getValue();
            prodId = device.getProductId();
            vendId = device.getVendorId();

            //print out the device found
            Toast.makeText(mApplicationContext, "Found device:" + device.getDeviceName() + " with ID "+
                    String.format("%04X:%04X", device.getVendorId(), device.getProductId()), Toast.LENGTH_SHORT).show();

            //check to see if this device is one we're looking for
            if (vendId == VID && prodId == PID) {
                Log.d("DEVICE", "listDevices found the expected device");
                Toast.makeText(mApplicationContext, "Device found: " + device.getDeviceName(), Toast.LENGTH_SHORT).show();

                //if we don't have permission to access the device, try getting permission by calling onPermDenied method of the passed IPermissionListener interface
                if (!mUsbManager.hasPermission(device)) {
                    Log.d("PERM", "Asking user for USB permission...");
                    permissionListener.onPermissionDenied(device);
                    return;
                }
                else {
                    //start the setup and return
                    openConnectionOnReceivedPermission();
                    return;
                }
            }
        }

        //if reached here with no return, we couldn't lock onto a found device or couldn't find, ERROR
        Log.e("USBERROR", "No more devices to list");

        //set error flag
        error = 1;

        //It's important to note that Java constructor's CANNOT return null (can't set the instance of the object to null when they return), so the onDeviceNotFound() method
        //isn't sufficient for setting usbController back to null. Hence the error flag that we set above
        mConnectionHandler.onDeviceNotFound();
    }

    //small interface for the USB permission listener
    private static interface IPermissionListener {
        void onPermissionDenied(UsbDevice d);
    }

    //This is the meat. We set up the USB communication interface similar to how we did in the PC to Arduino interface

    //an empty array is less overhead space than an actual instantiation of a new Object()
    private static final Object[] sSendLock = new Object[]{};
    private static final Object[] killLock = new Object[]{};
    private volatile boolean mStop = false, mKillReceiver = false;

    //the byte for sending
    private byte mData = 0x00;

    //public data received from Arduino for parsing
    public byte[] dataIn = new byte[1];

    private class UsbRunnable implements Runnable {
        private final UsbDevice device;
        private final int sens;

        //constructor
        UsbRunnable(UsbDevice dev, int way) {device = dev; sens = way;}

        @Override
        //implement main USB functionality
        public void run() {
            if (sens == 1) {
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

                    Log.d("THREAD", String.format("Value of direction is: %d", direction));

                    if (mStop) {
                        Log.e("ERROR", "Stopped after the sending thread was notify()ed, returning...");
                        mConnectionHandler.onUsbStopped();
                        transferring = 0;
                        return;
                    }

                    if (direction == 1) {
                        //transfer the byte of length 1, sending or receiving as specified
                        connection.bulkTransfer(out, new byte[]{mData}, 1, 0);
                    }

                    else { //never reached
                        /*
                        //transfer the byte of length 1, sending or receiving as specified
                        Log.e("TRANSFER", "Beginning receive transfer...");

                        int bytesTransferred = connection.bulkTransfer(in, dataIn, 22, 1000);

                        Log.e("TRANSFER", String.format("# of bytes received: %d", bytesTransferred));
                        if (bytesTransferred<0) {
                            mStop = true;
                        }
                        */
                    }

                    Log.d("THREAD", "Setting |transferring| back to 0");
                    transferring = 0;
                }
            }
            else {
                Log.d("QUEUE", "QUEUEING UP");
                //queue up

                ByteBuffer buffer = ByteBuffer.allocate(1);

                UsbRequest request = new UsbRequest();
                request.initialize(connection, in);

                //wait for data to become available for receiving from the Arduino
                while (true) {
                    if (request.queue(buffer, 1)) {
                        Log.d("QUEUE", "WAITING FOR DATA...");
                        connection.requestWait();
                        // wait for this request to be completed
                        // at this point buffer contains the data received
                    }
                    dataIn[0] = buffer.get(0);
                }
            }
        }
    }

    //function to send a byte of data (wakes up data transfer thread)
    public void send (byte data) {
        if (mStop) {
            return;
        }

        //set data out byte to the passed byte
        mData = data;
        direction = 1;

        //display sending timestamp
        sendTimeValue = System.currentTimeMillis();

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ((TextView)activity.findViewById(R.id.sent_time)).setText(String.format("Data sent timestamp: %d", sendTimeValue));
            }
        });

        synchronized (sSendLock) {
            //wake up sSendLock for bulk transfer
            sSendLock.notify();
        }
    }

    //receive data
    public void receive () {
        if (mStop) {
            return;
        }
        direction = 0;
        synchronized (sSendLock) {
            //wake up sReceiveLock for receiving
            sSendLock.notify();
        }
        transferring = 1;
        Log.d("TRANSFERRING VAL", String.format("%d", transferring));

        //wait until all receiving finished
        while (transferring==1) {
            ;
        }

        //Log debugging statements
        for (byte thisByte : dataIn) {
            Log.d("BYTEREAD", String.format("%x", thisByte));
        }
        Log.d("TRANSFERRING VAL", String.format("%d", transferring));
    }

    //stop usb data transfer
    public void stop() {
        synchronized (killLock) {
            mKillReceiver = true;

            //ping a kill signal off of the STM32 over to the requestWait() blocking function
            send((byte) 0xFF);


            try {
                //wait to make sure sending of kill signal is done and receiver has shut down
                killLock.wait();
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        synchronized (sSendLock) {
            //wake up sending thread to make it return
            mStop = true;
            sSendLock.notify();
        }

        //readingRequest.close();
        //connection.close();

        //terminate the data transfer thread by joining it to main UI thread, also terminate receiving thread
        try { //cleaning up threads
            if (mUsbThread!=null) {
                Log.d("DBUG", "Joining UsbThread...");
                mUsbThread.join();
            }
            if (mReceiveThread!=null) {
                Log.d("DBUG", "Joining ReceiveThread...");
                mReceiveThread.join();
            }
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }

        //reset stop flag, current usbrunnable and readrunnable instance, and both data transfer threads
        mStop = false;
        mLoop = null;
        mReceiver = null;
        mUsbThread = null;
        mReceiveThread = null;

        //try to unregister the permission receiver
        try {
            mApplicationContext.unregisterReceiver(mPermissionReceiver);
        }
        catch (IllegalArgumentException e) {
            e.printStackTrace();
        }
    }

    public void clearData() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ((TextView)activity.findViewById(R.id.received_time)).setText("Echo received timestamp: ");
                ((TextView)activity.findViewById(R.id.latency)).setText("Approximate latency: ");
                ((TextView)activity.findViewById(R.id.sent_time)).setText("Data sent timestamp: ");
                ((TextView)activity.findViewById(R.id.test)).setText("Received echo LED status: ");
            }
        });
    }

    //start up a new thread for USB comms with the given device
    private void startDataTransferThreads(UsbDevice device) {
        if (mLoop !=null) {
            //USB data transfer thread already running
            mConnectionHandler.onErrorLooperRunningAlready();
            return;
        }
        //make new UsbRunnable and thread for comms with the device
        mLoop = new UsbRunnable(device, 1);
        mReceiver = new ReadRunnable();

        //assign the new runnable to new thread
        mUsbThread = new Thread(mLoop);
        mReceiveThread = new Thread(mReceiver);

        //start new threads in background
        mUsbThread.start();
        mReceiveThread.start();
    }



    private class ReadRunnable implements Runnable {
        private byte[] incomingData = new byte[1];
        @Override
        public void run() {
            //queue up
            final ByteBuffer buffer = ByteBuffer.allocate(22);

            readingRequest = new UsbRequest();

            //intialize an asynchronous request for USB data from the connected device
            readingRequest.initialize(connection, in);

            //wait for data to become available to receive
            while (true) {
                if (readingRequest.queue(buffer, 22)) {
                    Log.d("QUEUE", "WAITING FOR DATA...");
                    connection.requestWait();

                    //stamp time of data reception
                    receiveTimeValue = System.currentTimeMillis();
                    latency = receiveTimeValue - sendTimeValue;

                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            ((TextView)activity.findViewById(R.id.received_time)).setText(String.format("Echo received timestamp: %d", receiveTimeValue));
                            ((TextView)activity.findViewById(R.id.latency)).setText(String.format("Approximate latency: %d ms", latency));
                        }
                    });

                    //wait for this request to be completed
                    //at this point buffer contains the data received
                    final byte firstChar = buffer.get(0);
                    Log.d("BUFFER", String.format("Got: Hex value %x", firstChar));

                    if (firstChar!=-1 && firstChar != 0x00) {
                        Log.d("BUFFDATA", String.format("Valid character %c", firstChar));
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                //((TextView)activity.findViewById(R.id.test)).append(String.format("%c ", firstChar));
                                ((TextView)activity.findViewById(R.id.test)).append(new String(buffer.array()));
                            }
                        });
                    }

                    //if signal to kill has been sent by stop function, then end the thread so that we can reset
                    if (mKillReceiver) {
                        Log.d("DBUG", "Receiver flagged to stop, returning...");
                        mConnectionHandler.onUsbStopped();

                        synchronized ((killLock)) {
                            killLock.notify();
                        }

                        return;
                    }
                }
            }
        }
    }
}


