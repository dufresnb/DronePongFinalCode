package com.parrot.sdksample.activity;

import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.parrot.arsdk.arcommands.ARCOMMANDS_ARDRONE3_MEDIARECORDEVENT_PICTUREEVENTCHANGED_ERROR_ENUM;
import com.parrot.arsdk.arcommands.ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_ENUM;
import com.parrot.arsdk.arcontroller.ARCONTROLLER_DEVICE_STATE_ENUM;
import com.parrot.arsdk.arcontroller.ARControllerCodec;
import com.parrot.arsdk.arcontroller.ARFrame;
import com.parrot.arsdk.ardiscovery.ARDiscoveryDeviceService;
import com.parrot.sdksample.R;
import com.parrot.sdksample.drone.BebopDrone;
import com.parrot.sdksample.view.BebopVideoView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

public class BebopActivity extends AppCompatActivity {
    BluetoothAdapter mBluetoothAdapter;
    BluetoothSocket mmSocket;
    BluetoothDevice mmDevice;
    OutputStream mmOutputStream;
    InputStream mmInputStream;
    Thread workerThread;
    byte[] readBuffer;
    int readBufferPosition;
    int counter;
    volatile boolean stopWorker;

    private static final String TAG = "BebopActivity";
    private BebopDrone mBebopDrone;

    private ProgressDialog mConnectionProgressDialog;
    private ProgressDialog mDownloadProgressDialog;

    private BebopVideoView mVideoView;

    private TextView mBatteryLabel;
    private Button mTakeOffLandBt;
    private Button mStopBt;
    private Button mDownloadBt;

    private Double wallAngle = 1.0;
    private int turnAngle = 0;              //angle that drone must turn
    private boolean inPlay = false;         //bool to determine game if in play or not.
    private int Score1 = 0;
    private int Score2 = 0;

    private int mNbMaxDownload;
    private int mCurrentDownloadIndex;

    private ImageView camera_image;

    long Starttime;
    int num = 1;
    long Endtime;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bebop);

        camera_image = (ImageView) findViewById(R.id.camera_image);

        initIHM();

        try
        {
            initBluetooth();
        }
        catch(IOException ex){}

        Intent intent = getIntent();
        ARDiscoveryDeviceService service = intent.getParcelableExtra(DeviceListActivity.EXTRA_DEVICE_SERVICE);
        mBebopDrone = new BebopDrone(this, service);
        mBebopDrone.addListener(mBebopListener);

    }

    public void checkWinner() {
        if (Score1 >= 5 || Score2 >= 5) {
            Score1 = 0;
            Score2 = 0;
            try {
                TextView z = (TextView) findViewById(R.id.Player1_Score);
                z.setText(Integer.toString(Score1));
                TextView z2 = (TextView) findViewById(R.id.Player2_Score);
                z2.setText(Integer.toString(Score2));
            } catch (Exception e) {
            }
            inPlay=false;
            mBebopDrone.land();
        }
    }

    public void onMinus1Click(View view){
        if(Score1>0)Score1--;
        TextView z = (TextView)findViewById(R.id.Player1_Score);
        z.setText(Integer.toString(Score1));
    }
    public void onPlus1Click(View view){
        Score1++;
        TextView z = (TextView)findViewById(R.id.Player1_Score);
        z.setText(Integer.toString(Score1));
        checkWinner();
    }
    public void onMinus2Click(View view){
        if(Score2>0)Score2--;
        TextView z = (TextView)findViewById(R.id.Player2_Score);
        z.setText(Integer.toString(Score2));
    }
    public void onPlus2Click(View view){
        Score2++;
        TextView z = (TextView)findViewById(R.id.Player2_Score);
        z.setText(Integer.toString(Score2));
        checkWinner();
    }
    @Override
    protected void onStart() {
        super.onStart();

        // show a loading view while the bebop drone is connecting
        if ((mBebopDrone != null) && !(ARCONTROLLER_DEVICE_STATE_ENUM.ARCONTROLLER_DEVICE_STATE_RUNNING.equals(mBebopDrone.getConnectionState())))
        {
            mConnectionProgressDialog = new ProgressDialog(this, R.style.AppCompatAlertDialogStyle);
            mConnectionProgressDialog.setIndeterminate(true);
            mConnectionProgressDialog.setMessage("Connecting Your DronePong Game! ....");
            mConnectionProgressDialog.setCancelable(false);
            mConnectionProgressDialog.show();

            // if the connection to the Bebop fails, finish the activity
            if (!mBebopDrone.connect()) {
                finish();
            }
        }
    }

    void initBluetooth() throws IOException
    {
        try {
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

            if (!mBluetoothAdapter.isEnabled()) {
                Intent enableBluetooth = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBluetooth, 0);
            }
            Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
            if (pairedDevices.size() > 0) {
                for (BluetoothDevice device : pairedDevices) {
                    if (device.getName().equals("HC-05")) {
                        mmDevice = device;
                        break;
                    }
                }
            }

            UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); //Standard SerialPortService ID
            mmSocket = mmDevice.createRfcommSocketToServiceRecord(uuid);
            mmSocket.connect();
            mmOutputStream = mmSocket.getOutputStream();
            mmInputStream = mmSocket.getInputStream();

            beginListenForData();
        }
        catch(Exception e)
        {

        }
    }

    void beginListenForData()
    {
        try {
            final Handler handler = new Handler();
            final byte delimiter = 10; //This is the ASCII code for a newline character

            final TextView angleText = (TextView) findViewById(R.id.angleText);
            final TextView turnText = (TextView) findViewById(R.id.turnText);

            stopWorker = false;
            readBufferPosition = 0;
            readBuffer = new byte[1024];
            workerThread = new Thread(new Runnable() {
                public void run() {
                    while (!Thread.currentThread().isInterrupted() && !stopWorker) {
                        try {
                            int bytesAvailable = mmInputStream.available();
                            if (bytesAvailable > 0) {
                                byte[] packetBytes = new byte[bytesAvailable];
                                mmInputStream.read(packetBytes);
                                boolean isNumber = true;
                                for (int i = 0; i < bytesAvailable; i++) {
                                    if ((packetBytes[i] < '0' || packetBytes[i] > '9') && packetBytes[i] != '.' && packetBytes[i]!='\n' && packetBytes[i]!='\r') {
                                        isNumber = false;
                                    }
                                }
                                if(bytesAvailable==0)isNumber=false;
                                if (isNumber) {
                                    for (int i = 0; i < bytesAvailable; i++) {
                                        byte b = packetBytes[i];
                                        if (b == delimiter) {
                                            byte[] encodedBytes = new byte[readBufferPosition];
                                            System.arraycopy(readBuffer, 0, encodedBytes, 0, encodedBytes.length);
                                            final String data = new String(encodedBytes, "US-ASCII");
                                            readBufferPosition = 0;
                                            Log.i("hello lily", data);
                                            wallAngle = Double.parseDouble(data);

                                            turnAngle = wallAngle.intValue();           //converts string to whole number int

                                            if (turnAngle < 0) {                           //if angle is negative,
                                                turnAngle = -180 - (2 * turnAngle);
                                            } else                                        //if angle is positive,
                                                turnAngle = 180 - (2 * turnAngle);

                                            handler.post(new Runnable() {
                                                public void run() {                           //print out too app screen
                                                    angleText.setText(data);
                                                    turnText.setText(Integer.toString(turnAngle));
                                                }
                                            });
                                        } else {
                                            readBuffer[readBufferPosition++] = b;
                                        }
                                    }
                                }
                            }
                        } catch (IOException ex) {
                            mBebopDrone.land();
                            stopWorker = true;
                        }
                    }
                }
            });

            workerThread.start();
        }
        catch(Exception e)
        {

        }
    }



    @Override
    public void onBackPressed() {
        if (mBebopDrone != null)
        {
            mConnectionProgressDialog = new ProgressDialog(this, R.style.AppCompatAlertDialogStyle);
            mConnectionProgressDialog.setIndeterminate(true);
            mConnectionProgressDialog.setMessage("Disconnecting DronePong App ....");
            mConnectionProgressDialog.setCancelable(false);
            mConnectionProgressDialog.show();

            if (!mBebopDrone.disconnect()) {
                finish();
            }
        }
    }

    private void initIHM() {
        mVideoView = (BebopVideoView) findViewById(R.id.videoView);

        findViewById(R.id.emergencyBt).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mBebopDrone.emergency();
            }
        });

        mTakeOffLandBt = (Button) findViewById(R.id.PlayOrPauseBtn);
        mTakeOffLandBt.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                switch (mBebopDrone.getFlyingState()) {
                    case ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_LANDED:
                        mBebopDrone.takeOff();
                        inPlay = true;

                        break;
                    case ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_FLYING:
                    case ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_HOVERING:
                        inPlay = false;
                        mBebopDrone.land();
                        break;
                    default:
                }
            }
        });

        mStopBt = (Button) findViewById(R.id.stopButton);
        mStopBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switch (mBebopDrone.getFlyingState()){
                    case ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_LANDED:
                    case ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_FLYING:
                    case ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_HOVERING:
                        inPlay = false;
                        mBebopDrone.land();
                        Reset();
                }
            }
        });

        findViewById(R.id.takePictureBt).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mBebopDrone.takePicture();
            }
        });

        mDownloadBt = (Button)findViewById(R.id.downloadBt);
        mDownloadBt.setEnabled(false);
        mDownloadBt.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mBebopDrone.getLastFlightMedias();

                mDownloadProgressDialog = new ProgressDialog(BebopActivity.this, R.style.AppCompatAlertDialogStyle);
                mDownloadProgressDialog.setIndeterminate(true);
                mDownloadProgressDialog.setMessage("Fetching medias");
                mDownloadProgressDialog.setCancelable(false);
                mDownloadProgressDialog.setButton(DialogInterface.BUTTON_NEGATIVE, "Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mBebopDrone.cancelGetLastFlightMedias();
                    }
                });
                mDownloadProgressDialog.show();
            }
        });

        findViewById(R.id.gazUpBt).setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        mBebopDrone.setGaz((byte) 50);
                        break;

                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        mBebopDrone.setGaz((byte) 0);
                        break;

                    default:

                        break;
                }

                return true;
            }
        });

        findViewById(R.id.gazDownBt).setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        mBebopDrone.setGaz((byte) -50);
                        break;

                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        mBebopDrone.setGaz((byte) 0);
                        break;

                    default:

                        break;
                }

                return true;
            }
        });

        findViewById(R.id.yawLeftBt).setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        mBebopDrone.setYaw((byte) -50);
                        break;

                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        mBebopDrone.setYaw((byte) 0);
                        break;

                    default:

                        break;
                }

                return true;
            }
        });

        findViewById(R.id.yawRightBt).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {

                //mBebopDrone.setYaw((byte) 60);
                int count = 0;
                int max = turnAngle;                //variable max must be positive, used for loop count.
                int directionYaw = 60;              //variable to turn left or right when calling setYaw()
                if (turnAngle < 0) {                //if negative, turns left. and max gets set to positive value.
                    max = turnAngle * -1;
                    directionYaw = -60;
                }
                while (count < max * 27778) {
                    mBebopDrone.setYaw((byte) directionYaw);
                    count++;
                }
                mBebopDrone.setYaw((byte) 0);

                int count2 = 0;
                while (count2 < 1500000) {
                    mBebopDrone.setPitch((byte) 50);
                    mBebopDrone.setFlag((byte) 1);
                    count2++;
                }

                mBebopDrone.setPitch((byte) 0);
                mBebopDrone.setFlag((byte) 0);

            }
        });




        /*findViewById(R.id.yawRightBt).setOnTouchListener(new View.OnTouchListener() {
            @Override

            public boolean onTouch(View v, MotionEvent event) {

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);

                        while (num < 8000)
                        {
                            mBebopDrone.setYaw((byte) 50);
                            num++;
                        }
                        mBebopDrone.land();

                        break;

                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        mBebopDrone.setYaw((byte) 0);
                        mBebopDrone.land();
                        break;

                    default:

                        break;
                }

                return true;
            }
        });*/


        findViewById(R.id.forwardBt).setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        mBebopDrone.setPitch((byte) 50);
                        mBebopDrone.setFlag((byte) 1);
                        break;

                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        mBebopDrone.setPitch((byte) 0);
                        mBebopDrone.setFlag((byte) 0);
                        break;

                    default:

                        break;
                }

                return true;
            }
        });

        findViewById(R.id.backBt).setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        mBebopDrone.setPitch((byte) -50);
                        mBebopDrone.setFlag((byte) 1);
                        break;

                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        mBebopDrone.setPitch((byte) 0);
                        mBebopDrone.setFlag((byte) 0);
                        break;

                    default:

                        break;
                }

                return true;
            }
        });

        findViewById(R.id.rollLeftBt).setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        mBebopDrone.setRoll((byte) -50);
                        mBebopDrone.setFlag((byte) 1);
                        break;

                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        mBebopDrone.setRoll((byte) 0);
                        mBebopDrone.setFlag((byte) 0);
                        break;

                    default:

                        break;
                }

                return true;
            }
        });

        findViewById(R.id.rollRightBt).setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        mBebopDrone.setRoll((byte) 50);
                        mBebopDrone.setFlag((byte) 1);
                        break;

                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        mBebopDrone.setRoll((byte) 0);
                        mBebopDrone.setFlag((byte) 0);
                        break;

                    default:

                        break;
                }

                return true;
            }
        });

        mBatteryLabel = (TextView) findViewById(R.id.batteryLabel);
    }

    private int playCounter=0;

    /**
     *  GamePlay(){}
     * Implementing the Game Logic. Called upon pressing 'Play.'
     */
    public void GamePlay(){
         //KEEP GOING STRAIGHT UNTIL WALL DETECTED*********
         if (wallAngle == 0) {
             playCounter+=1;
             if(playCounter%3==0) {
                 mBebopDrone.setPitch((byte) 2);//50);
                 mBebopDrone.setFlag((byte) 1);
                 playCounter=0;
             }
         }
        else {
             mBebopDrone.setPitch((byte) 0);//stop
             mBebopDrone.setFlag((byte) 0);
             //************************************************
             //TURN********************************************
             int count = 0;                         //TURN
             int max = turnAngle;                //variable max must be positive, used for loop count.
             int directionYaw = 60;              //variable to turn left or right when calling setYaw()
             if (turnAngle < 0) {                //if negative, turns left. and max gets set to positive value.
                 max = turnAngle * -1;
                 directionYaw = -60;
             }
             while (count < max * 27800 /2) {
                 mBebopDrone.setYaw((byte) directionYaw);
                 count++;
             }
             mBebopDrone.setYaw((byte) 0);
         }
         //************************************************

    }

    /**
     * Reset(){}
     * Reset the game and scores.
     */
    public void Reset(){

    }

    private final BebopDrone.Listener mBebopListener = new BebopDrone.Listener() {
        @Override
        public void onDroneConnectionChanged(ARCONTROLLER_DEVICE_STATE_ENUM state) {
            switch (state)
            {
                case ARCONTROLLER_DEVICE_STATE_RUNNING:
                    mConnectionProgressDialog.dismiss();
                    break;

                case ARCONTROLLER_DEVICE_STATE_STOPPED:
                    // if the deviceController is stopped, go back to the previous activity
                    mConnectionProgressDialog.dismiss();
                    finish();
                    break;

                default:
                    break;
            }
        }

        @Override
        public void onBatteryChargeChanged(int batteryPercentage) {
            mBatteryLabel.setText(String.format("%d%%", batteryPercentage));
        }

        @Override
        public void onPilotingStateChanged(ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_ENUM state) {
            switch (state) {
                case ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_LANDED:
                    mTakeOffLandBt.setText("Play");
                    mTakeOffLandBt.setEnabled(true);
                    mDownloadBt.setEnabled(true);
                    break;
                case ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_FLYING:
                case ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_HOVERING:
                    mTakeOffLandBt.setText("Pause");
                    mTakeOffLandBt.setEnabled(true);
                    mDownloadBt.setEnabled(false);
                    break;
                default:
                    mTakeOffLandBt.setEnabled(false);
                    mDownloadBt.setEnabled(false);
            }
        }

        @Override
        public void onPictureTaken(ARCOMMANDS_ARDRONE3_MEDIARECORDEVENT_PICTUREEVENTCHANGED_ERROR_ENUM error) {
            Log.i(TAG, "Picture has been taken");
        }

        @Override
        public void configureDecoder(ARControllerCodec codec) {
            mVideoView.configureDecoder(codec);
        }

        @Override
        public void onFrameReceived(ARFrame frame) {
            mVideoView.displayFrame(frame);

            if(inPlay==true)
            {
                GamePlay();
            }
            else
            {
                //PRESSED PAUSE************************************
                mBebopDrone.setPitch((byte) 0);
                mBebopDrone.setFlag((byte) 0);
                //*************************************************
            }
            /*
            try{
                byte[] data = frame.getByteData();

                String path = Environment.getExternalStorageDirectory().getPath() + "/droneTemp.jpeg";
                File filename = new File(path);
                try {
                    FileOutputStream fos = new FileOutputStream(filename);
                    fos.write(data);
                    fos.close();
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bmp1 = BitmapFactory.decodeFile(path, options);
                camera_image.setImageBitmap(bmp1);

                camera_image.setDrawingCacheEnabled(true);
                camera_image.buildDrawingCache(true);
                Bitmap bmp2 = Bitmap.createBitmap(camera_image.getDrawingCache());
                camera_image.setDrawingCacheEnabled(false);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bmp2.compress(Bitmap.CompressFormat.JPEG,100,bos);
                byte[] bitmapdata = bos.toByteArray();
                ByteArrayInputStream fis = new ByteArrayInputStream(bitmapdata);

                String path2 = Environment.getExternalStorageDirectory().getPath() + "/dronePic.jpeg";
                File myFile = new File(path2);

                Log.i("save","trying to save frame as jpg...");
                try{
                    FileOutputStream fos = new FileOutputStream(myFile);
                    byte[] buf = new byte[1024];
                    int len;
                    while((len=fis.read(buf))>0)
                    {
                        fos.write(buf,0,len);
                    }
                    fis.close();
                    fos.close();
                    bmp2=null;
                }
                catch(FileNotFoundException e)
                {
                    e.printStackTrace();
                }
                catch(IOException e)
                {
                    e.printStackTrace();
                }

            }
            catch(Exception e)
            {

            }*/
        }

        @Override
        public void onMatchingMediasFound(int nbMedias) {
            mDownloadProgressDialog.dismiss();

            mNbMaxDownload = nbMedias;
            mCurrentDownloadIndex = 1;

            if (nbMedias > 0) {
                mDownloadProgressDialog = new ProgressDialog(BebopActivity.this, R.style.AppCompatAlertDialogStyle);
                mDownloadProgressDialog.setIndeterminate(false);
                mDownloadProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                mDownloadProgressDialog.setMessage("Downloading medias");
                mDownloadProgressDialog.setMax(mNbMaxDownload * 100);
                mDownloadProgressDialog.setSecondaryProgress(mCurrentDownloadIndex * 100);
                mDownloadProgressDialog.setProgress(0);
                mDownloadProgressDialog.setCancelable(false);
                mDownloadProgressDialog.setButton(DialogInterface.BUTTON_NEGATIVE, "Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mBebopDrone.cancelGetLastFlightMedias();
                    }
                });
                mDownloadProgressDialog.show();
            }
        }

        @Override
        public void onDownloadProgressed(String mediaName, int progress) {
            mDownloadProgressDialog.setProgress(((mCurrentDownloadIndex - 1) * 100) + progress);
        }

        @Override
        public void onDownloadComplete(String mediaName) {
            mCurrentDownloadIndex++;
            mDownloadProgressDialog.setSecondaryProgress(mCurrentDownloadIndex * 100);

            if (mCurrentDownloadIndex > mNbMaxDownload) {
                mDownloadProgressDialog.dismiss();
                mDownloadProgressDialog = null;
            }
        }
    };
}
