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
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private UsbController usbController;
    private static final int VID = 0x2341;
    private static final int PID = 0x0043;


    //implement the interface/create an instance of it here
    private final IUsbConnectionHandler mConnectionHandler = new IUsbConnectionHandler() {
        @Override
        public void onUsbStopped() {
            Log.e("USBTAG", "Usb has stopped");
        }

        @Override
        public void onErrorLooperRunningAlready() {
            Log.e("USBTAG", "Looper already running");
        }

        @Override
        public void onDeviceNotFound() {
            //stop the controller and set it to null
            if (usbController!=null) {
                usbController.stop();
                usbController=null;
            }
        }
    };


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (usbController == null) {
            //if there's no usb controller, create one now
            usbController = new UsbController(this, mConnectionHandler, VID, PID);
        }

        //set up the seekbar listener
        ((SeekBar)findViewById(R.id.seekBar1)).setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                //if the change is from the user, we need to send update to Arduino
                if (fromUser) {
                    if (usbController!=null) {
                        //send over one byte that's a bitwise and of progress and 11111111
                        //in other words, convert progress to a whole 8 bits
                        usbController.send((byte) (progress & 0xFF));
                    }
                }
            }
        });

        //set up the button click listener
        ((Button)findViewById(R.id.button1)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (usbController==null) {
                    //if we don't already have controller set up, do it now
                    usbController = new UsbController(MainActivity.this, mConnectionHandler, VID, PID);
                }
                else {
                    //scrap old controller and "reset" the controller by making new one
                    usbController.stop();
                    usbController = new UsbController(MainActivity.this, mConnectionHandler, VID, PID);
                }
            }
        });
    }
}
