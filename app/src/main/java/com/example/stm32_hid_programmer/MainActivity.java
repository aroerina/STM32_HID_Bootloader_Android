package com.example.stm32_hid_programmer;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.view.View.OnClickListener;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;

public class MainActivity extends AppCompatActivity implements OnClickListener {

    // in decimal
    private static final int USB_DEVICE_VID = 0x1209;        // Vendor ID  0x1209
    private static final int USB_DEVICE_PID = 0xBEBA;        // Product ID 0xBEBA

    private final static int MCU_PAGE_SIZE = 2048;
    private final static int FIRM_TRANSFER_SIZE = 1024;
    private final static int EP1_PACKET_SIZE = 8;
    private final static int COMMAND_SIZE = 64;
    private final static int BUFFER_COMMAND_INDEX = 7;
    private final static byte COMMAND_PAGE_RESET = 0x0;
    private final static byte COMMAND_MCU_REBOOT = 0x1;
    private final static byte COMMAND_WRITE_DONE = 0x2;

    private final static int USB_REQUEST_GET_INTERFACE = 0x0A;

    private boolean D = true;
    private String TAG = "MainActivity";
    private static final String ACTION_USB_PERMISSION = "com.google.android.HID.action.USB_PERMISSION";
    private UsbManager mUsbManager;
    private UsbEndpoint ep1_rx;
    private UsbDeviceConnection deviceConnection;
    private InputStream f_stream;
    private final byte[] command_write_done = {'B','T','L','D','C','M','D',2};
    private byte[] command_buffer = new byte[COMMAND_SIZE];

    private boolean isConnected = false;
    private int file_size;

    // UI Object
    private Button btn_program;
    private TextView txt_status;
    private TextView txt_progress;
    private ProgressBar bar_progress;


    //
    //		USB Intent
    //
    IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Arrays.fill(command_buffer,(byte)0);    // 0 fill
        System.arraycopy(command_write_done,0,command_buffer,0,BUFFER_COMMAND_INDEX);       // copy "BTLDCMD"

        if (D) Log.d(TAG, "onCreate");

        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        USBBroadcastReceiver mUsbReceiver = new USBBroadcastReceiver();
        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        registerReceiver(mUsbReceiver, filter);
        PendingIntent mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_UPDATE_CURRENT);

        btn_program = (Button) findViewById(R.id.btn_program);
        btn_program.setOnClickListener(this);

        txt_status = (TextView) findViewById(R.id.txt_status);
        txt_progress = (TextView) findViewById(R.id.txt_progress);
        bar_progress = (ProgressBar) findViewById(R.id.bar_prog_progress);

        InputStream user_app_image = getResources().openRawResource(R.raw.user_app);

        // firmware file open
        f_stream = getResources().openRawResource(R.raw.user_app);
        int required_kb = 0;          // ファイルサイズ切り上げで何KB?
        try {
            file_size = f_stream.available();
        } catch (IOException e) {
            e.printStackTrace();
        }
        required_kb = (file_size + FIRM_TRANSFER_SIZE-1) / FIRM_TRANSFER_SIZE;
        bar_progress.setMax(required_kb);
        bar_progress.setProgress(0);
        txt_progress.setText("Programming progress:"+String.format(" 0/%d bytes",file_size));


        // Call SetDevice()
        Intent intent = getIntent();
        UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device != null) {
            mUsbReceiver.onReceive(this, intent);            // When launch from USB device connection intent
        } else {

            // Check if the device is already connected when launching from the drawer
            HashMap<String, UsbDevice> deviceList = mUsbManager.getDeviceList();
            Iterator<UsbDevice> deviceIterator = deviceList.values().iterator();
            while (deviceIterator.hasNext()) {
                device = deviceIterator.next();
                if (device.getProductId() == USB_DEVICE_PID && device.getVendorId() == USB_DEVICE_VID) {    // VID PID 確認
                    setDevice(device);
                    break;
                }
            }
        }
    }

    public void setDevice(UsbDevice device) {

        if (D) Log.d(TAG, "SET DEVICE");

        if (mUsbManager.hasPermission(device) == false) {    // Check USB connect permeation
            Log.e(TAG, "DEVICE has no parmission!");
        }

        //
        //	device initialize
        //

        deviceConnection = mUsbManager.openDevice(device);
        if (deviceConnection == null) {
            if (D) Log.e(TAG, "Open device failed !");
            return;
        }

        UsbInterface intf = device.getInterface(0);
        deviceConnection.claimInterface(intf, true);

        try {
            if (UsbConstants.USB_DIR_IN == intf.getEndpoint(0).getDirection()) {
                ep1_rx = intf.getEndpoint(0);
            }
        } catch (Exception e) {
            if (D) Log.e(TAG, "Device have no ep1_in", e);
        }

        isConnected = true;
        btn_program.setClickable(true);
        txt_status.setText(R.string.text_connected);
        bar_progress.setProgress(0);
        txt_progress.setText("Programming progress:"+String.format(" 0/%d bytes",file_size));

    }

    @Override
    public void onClick(View v) {

        if(v == btn_program){

            if(!isConnected)return;

            try {
                startProggramingSequense();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private class USBBroadcastReceiver extends BroadcastReceiver {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (D) Log.i(TAG, "USBBroadcastReceiver ON RECEIVE");

            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {        // When the device is connected when the application is running
                if (D) Log.i(TAG, "ACTION_USB_DEVICE_ATTACHED");

                UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                if (device == null) {
                    Log.e(TAG, "device pointer is null");
                    return;
                }

                if (device.getProductId() != USB_DEVICE_PID || device.getVendorId() != USB_DEVICE_VID) {    // Confirm VID and PID
                    Log.e(TAG, "incorrect usb device");
                    return;
                }

                setDevice(device);
            }

            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {        // If the device is disconnected while it is running
                Log.i(TAG, "device disconnected");

                isConnected = false;
                txt_status.setText(R.string.text_disconnect);
                btn_program.setClickable(false);
            }


            // Receive USB connection permeation intent
            if (ACTION_USB_PERMISSION.equals(action)) {
                if (D) Log.i(TAG, "ACTION_USB_PERMISSION");
                synchronized (this) {
                    UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            //call method to set up device communication
                            setDevice(device);
                        }
                    } else {
                        if (D) Log.d(TAG, "permission denied for device " + device);
                    }
                }
            }
        }
    }


    private void startProggramingSequense() throws IOException {

        txt_status.setText(R.string.text_programming);

        // send Page Reset
        command_buffer[BUFFER_COMMAND_INDEX] = COMMAND_PAGE_RESET;
        deviceConnection.controlTransfer(UsbConstants.USB_DIR_OUT,USB_REQUEST_GET_INTERFACE,
                0,0,command_buffer,COMMAND_SIZE,100);


        int file_size = f_stream.available();
        if(D)Log.d(String.format("file size = %d byte",file_size),TAG);

        byte[] txBuffer = new byte[MCU_PAGE_SIZE];
        byte[] rxBuffer = new byte[EP1_PACKET_SIZE];
        int tx_kbytes=0;

        while(true) {
            Arrays.fill(txBuffer,(byte)0);  // 0 fill
            int numRead = f_stream.read(txBuffer);
            if(numRead < 0)break;

            for(int i=0;i<(MCU_PAGE_SIZE/FIRM_TRANSFER_SIZE);i++) {
                deviceConnection.controlTransfer(UsbConstants.USB_DIR_OUT, USB_REQUEST_GET_INTERFACE,
                        0, 0, txBuffer,i*FIRM_TRANSFER_SIZE, FIRM_TRANSFER_SIZE, 100);  //Transmit 1024 byte

                // wait receive "BTLDCMD2"
                deviceConnection.bulkTransfer(ep1_rx,rxBuffer,EP1_PACKET_SIZE,100);

                if (rxBuffer[EP1_PACKET_SIZE - 1] == COMMAND_WRITE_DONE) {  // check packet simply
                    tx_kbytes++;
                    bar_progress.setProgress(tx_kbytes);

                    int num_send_bytes = tx_kbytes*FIRM_TRANSFER_SIZE;
                    if(num_send_bytes > file_size){
                        num_send_bytes = file_size;
                    }
                    txt_progress.setText("Programming progress:"+String.format(" %d/%d bytes",num_send_bytes,file_size));
                }
            }
        }

        if(D)Log.d(TAG,"Transmit complete");
        txt_status.setText(R.string.text_done);

        //send MCU Reboot
        command_buffer[BUFFER_COMMAND_INDEX] = COMMAND_MCU_REBOOT;
        deviceConnection.controlTransfer(UsbConstants.USB_DIR_OUT,USB_REQUEST_GET_INTERFACE,
                0,0,command_buffer,COMMAND_SIZE,100);
    }
}