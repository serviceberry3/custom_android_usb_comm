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
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    public UsbController usbController;
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
                Log.d("NULL", "Came up not null");
                usbController.stop();
                usbController=null;
            }
            else {
                Log.e("NULL", "Came up null");
            }
        }
    };


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (usbController == null) {
            Log.d("START", "LOOKING");
            //if there's no usb controller, create one now using the connection handler interface we implemented above and the vendor and product IDs we want for Arduino
            usbController = new UsbController(this, mConnectionHandler, VID, PID, MainActivity.this);
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
                    if (usbController!=null && usbController.error==1) {
                        Toast.makeText(MainActivity.this, "Please open a connection first using List Devices button.", Toast.LENGTH_SHORT).show();
                    }
                    else if (usbController!=null) {
                        //send over one byte that's a bitwise and of progress and 11111111
                        //in other words, convert progress to a whole 8 bits
                        usbController.send((byte) (progress & 0xFF));
                    }
                }
            }
        });

        //set up the button click listener for list devices
        ((Button)findViewById(R.id.button1)).setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                if (usbController!=null && usbController.error==1) {
                    //if we don't already have controller set up, do it now
                    usbController = new UsbController(MainActivity.this, mConnectionHandler, VID, PID, MainActivity.this);
                }
                else {
                    //scrap old controller and "reset" the controller by making new one
                    usbController.stop();
                    usbController = new UsbController(MainActivity.this, mConnectionHandler, VID, PID, MainActivity.this);
                }
            }
        });

        //set up LED button click listener
        final Button ledButton = ((Button)findViewById(R.id.led_button));

        ledButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //make sure we've initialized a controller; if not, we need to open one
                //Log.d("LISTDEVICES", String.format("Pressed, controller direction is %d", usbController.direction));
                if (usbController!=null && usbController.error==1) {
                    Log.e("PRESS", "Pressed");
                    Toast.makeText(MainActivity.this, "Please open a connection first using List Devices button.", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (ledButton.getText().equals("LED Off (Arduino Pin 2)")) {
                    usbController.send((byte)0x00);
                    ledButton.setText("LED On (Arduino Pin 2)");
                }
                else {
                    usbController.send((byte) 0xFF);
                    ledButton.setText("LED Off (Arduino Pin 2)");
                }
                ((TextView)findViewById(R.id.test)).append(String.format("%c ", usbController.dataIn[0]));
            }
        });

        //set up receive data click listener
        ((Button)findViewById(R.id.receive_button)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (usbController!=null && usbController.error==1) {
                    Log.e("USBCONTROL", "NULL");
                    Toast.makeText(MainActivity.this, "Please open a connection first using List Devices button.", Toast.LENGTH_SHORT).show();
                    return;
                }
                usbController.receive();
                if (usbController.dataIn.length!=0) {
                    Toast.makeText(MainActivity.this, String.format("Received: %x", usbController.dataIn[0]), Toast.LENGTH_SHORT).show();
                    ((TextView)findViewById(R.id.test)).append(String.format("%c ", usbController.dataIn[0]));
                }
                else {
                    Toast.makeText(MainActivity.this, "No data was received from the Arduino.", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }
}
