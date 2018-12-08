package com.foamyguy.circuitpythoneditor;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AppCompatActivity;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    final char ctrlC = '\u0003';
    final char ctrlD = '\u0004';
    final char tab = '\t';

    boolean waitingForRead = false;

    private UsbService usbService;
    private TextView display;
    private EditText editText;
    private ProgressBar mainProgress;
    private MyHandler mHandler;

    // Get a handler that can be used to post to the main thread
    Handler mainHandler;

    private LineNumberEditText editorTxt;
    private TextView lineNumbersTxt;

    private StringBuilder mainPyStringBuilder;

    private boolean waitingForTabResult;

    private StringBuilder tabResult = new StringBuilder();

    private StringBuilder tempTabResult = new StringBuilder();

    private Runnable tabResultDoneRun;

    private boolean isLoading = false;

    private StringBuilder alreadySent = new StringBuilder();
    /*
     * Notifications from UsbService will be received here.
     */
    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case UsbService.ACTION_USB_PERMISSION_GRANTED: // USB PERMISSION GRANTED
                    Toast.makeText(context, "USB Ready", Toast.LENGTH_SHORT).show();
                    break;
                case UsbService.ACTION_USB_PERMISSION_NOT_GRANTED: // USB PERMISSION NOT GRANTED
                    Toast.makeText(context, "USB Permission not granted", Toast.LENGTH_SHORT).show();
                    break;
                case UsbService.ACTION_NO_USB: // NO USB CONNECTED
                    Toast.makeText(context, "No USB connected", Toast.LENGTH_SHORT).show();
                    break;
                case UsbService.ACTION_USB_DISCONNECTED: // USB DISCONNECTED
                    Toast.makeText(context, "USB disconnected", Toast.LENGTH_SHORT).show();
                    break;
                case UsbService.ACTION_USB_NOT_SUPPORTED: // USB NOT SUPPORTED
                    Toast.makeText(context, "USB device not supported", Toast.LENGTH_SHORT).show();
                    break;
            }
        }
    };

    private final ServiceConnection usbConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName arg0, IBinder arg1) {
            usbService = ((UsbService.UsbBinder) arg1).getService();
            usbService.setHandler(mHandler);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            usbService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mainHandler = new Handler(Looper.getMainLooper());

        mainPyStringBuilder = new StringBuilder();
        MainPagerAdapter mAdapter = new MainPagerAdapter();
        ViewPager mainPager = (ViewPager)findViewById(R.id.pager);
        mainPager.setAdapter(mAdapter);
        mainProgress = (ProgressBar)findViewById(R.id.mainProgress);



        mHandler = new MyHandler(this);

        tabResultDoneRun = new Runnable() {
            @Override
            public void run() {
                if (tempTabResult.toString().contains("Traceback") == false){
                    if(tempTabResult.toString().contains("       ") == false){
                        //if(editText.getText().toString().startsWith(tempTabResult.substring(0,2))) {

                        //}
                        editText.setText("");
                        String[] lines = display.getText().toString().split("\n");
                        String lastLine = lines[lines.length-1];
                        editText.append(lastLine.substring(4));

                        alreadySent = new StringBuilder(editText.getText().toString());
                        Log.i("CircuitPythonEditor", "tab result done run: " + tempTabResult.toString());
                    }
                }

                tempTabResult = new StringBuilder();
                waitingForTabResult = false;

            }
        };


    }


    @Override
    public void onResume() {
        super.onResume();
        setFilters();  // Start listening notifications from UsbService
        startService(UsbService.class, usbConnection, null); // Start UsbService(if it was not started before) and Bind it
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterReceiver(mUsbReceiver);
        unbindService(usbConnection);
    }

    private void startService(Class<?> service, ServiceConnection serviceConnection, Bundle extras) {
        if (!UsbService.SERVICE_CONNECTED) {
            Intent startService = new Intent(this, service);
            if (extras != null && !extras.isEmpty()) {
                Set<String> keys = extras.keySet();
                for (String key : keys) {
                    String extra = extras.getString(key);
                    startService.putExtra(key, extra);
                }
            }
            startService(startService);
        }
        Intent bindingIntent = new Intent(this, service);
        bindService(bindingIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void setFilters() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbService.ACTION_USB_PERMISSION_GRANTED);
        filter.addAction(UsbService.ACTION_NO_USB);
        filter.addAction(UsbService.ACTION_USB_DISCONNECTED);
        filter.addAction(UsbService.ACTION_USB_NOT_SUPPORTED);
        filter.addAction(UsbService.ACTION_USB_PERMISSION_NOT_GRANTED);
        registerReceiver(mUsbReceiver, filter);
    }

    public void sendCtrlC(View view) {

        sendCtrlC();
        editText.postDelayed(new Runnable(){
            @Override
            public void run() {
                usbService.write(("\n").getBytes());
            }
        }, 100);
    }

    private void sendCtrlC(){
        if (usbService != null) { // if UsbService was correctly binded, Send data
            usbService.write((""+ctrlC).getBytes());
        }
    }

    public void sendCtrlD(View view) {
        if (usbService != null) { // if UsbService was correctly binded, Send data
            usbService.write((""+ctrlD).getBytes());
        }
    }
    public void sendTab(View view) {
        if (usbService != null) { // if UsbService was correctly binded, Send data
            waitingForTabResult = true;
            String data = editText.getText().toString();
            if (alreadySent.toString().length() > 0){
                data = data.replaceFirst(alreadySent.toString(), "");
            }
            usbService.write((data+tab).getBytes());
            alreadySent.append(editText.getText().toString());

        }
    }

    private void send(String text){
        //Log.i("CircuitPythonEditor", "inside send");
        if (usbService != null) { // if UsbService was correctly binded, Send data
            usbService.write((text + "\r\n").getBytes());
            //Log.i("CircuitPythonEditor", "after write");
        }
    }
    int curIndex = 0;
    public boolean writeFile(String[] lines){
        Log.i("CircuitPythonEditor", "line: " + lines[curIndex]);
        if(lines[curIndex].length() > 0) {
            //Log.i("CircuitPythonEditor", "last char: " + lines[curIndex].charAt(lines[curIndex].length() - 1));
            //Log.i("CircuitPythonEditor", "newline: " + (lines[curIndex].charAt(lines[curIndex].length() - 1) == '\n'));
            send("f.write(\"\"\"" + lines[curIndex].substring(0, lines[curIndex].length()-1) + "\\r\\n\"\"\")");
        }else{
            send("f.write(\"\"\"" + lines[curIndex] + "\\r\\n\"\"\")");
        }

        if (curIndex >= lines.length-1){
            send("f.close()");
            mainProgress.setProgress(0);
            curIndex = 0;
            return true;
        }else {
            curIndex++;
            mainProgress.setProgress(curIndex);
            writeFile(lines);
        }
        return false;
    }

    public boolean writeFileBackground(String[] lines){
        Log.i("CircuitPythonEditor", "line: " + lines[curIndex]);

        Thread t = new Thread(){
            @Override
            public void run() {
                super.run();

                for (int i = 0; i < lines.length; i++){
                    if(lines[i].length() > 0) {

                        send("f.write(\"\"\"" + lines[i].substring(0, lines[i].length()-1) + "\\r\\n\"\"\")");
                    }else{
                        send("f.write(\"\"\"" + lines[i] + "\\r\\n\"\"\")");
                    }
                    final int tempI = i;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mainProgress.setProgress(tempI);
                        }
                    });

                }

                send("f.close()");
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mainProgress.setProgress(0);
                    }
                });

                curIndex = 0;
            }
        };

        t.start();

        //end("gc.collect()");
        //send("print(gc.mem_free())");


        return true;
    }

    public boolean writeFileDelayed(String[] lines){
        Log.i("CircuitPythonEditor", "line: " + lines[curIndex]);
        if(lines[curIndex].length() > 0) {
            //Log.i("CircuitPythonEditor", "last char: " + lines[curIndex].charAt(lines[curIndex].length() - 1));
            //Log.i("CircuitPythonEditor", "newline: " + (lines[curIndex].charAt(lines[curIndex].length() - 1) == '\n'));
            send("f.write(\"\"\"" + lines[curIndex].substring(0, lines[curIndex].length()-1) + "\\r\\n\"\"\")");
        }else{
            send("f.write(\"\"\"" + lines[curIndex] + "\\r\\n\"\"\")");
        }
        /*if (lines[curIndex].length() > 1) {
            Log.i("CircuitPythonEditor", "last two: " + lines[curIndex].substring(lines[curIndex].length()-2));
        }*/

        //send("gc.collect()");
        //send("print(gc.mem_free())");

        if (curIndex >= lines.length-1){
            send("f.close()");
            mainProgress.setProgress(0);
            curIndex = 0;
            return true;
        }else{
            editorTxt.postDelayed(new Runnable() {
                @Override
                public void run() {
                    curIndex++;
                    mainProgress.setProgress(curIndex);

                    writeFileDelayed(lines);
                }
            }, 100);
        }
        return false;
    }

    public void saveMainPy(View view) {
        /*
        import storage
        storage.remount("/", False)
         */

            Log.i("CircuitPythonEditor","begining write prep");
            send("import gc");
            Log.i("CircuitPythonEditor","after first send");
            send("f = open('main.py', 'w')");
            send("f.write('')");
            send("f.close()");
            //send("f = open('main.py', 'a')");
            send("f = open('/main.py', 'a')");

            Log.i("CircuitPythonEditor","opened file");
            String[] lines = editorTxt.getText().toString().split("\r\n");
            Log.i("CircuitPythonEditor","lines: " + lines.length);
            mainProgress.setMax(lines.length-1);
            writeFileDelayed(lines);

        }

    public void loadMainPy(View view) {
        mainProgress.setIndeterminate(true);
        isLoading = true;
        sendCtrlC();
    }

    /*
     * This handler will be passed to UsbService. Data received from serial port is displayed through this handler
     */
    private static class MyHandler extends Handler {
        private final WeakReference<MainActivity> mActivity;

        public MyHandler(MainActivity activity) {
            mActivity = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SyncUsbService.SYNC_READ:
                    //
                    // break;
                case UsbService.MESSAGE_FROM_SERIAL_PORT:
                    String data = (String) msg.obj;
                    //Log.i("CircuitPythonEditor", data);
                    if(mActivity.get().isLoading) {
                        if (data.contains("Press any key to enter the REPL. Use CTRL-D to reload.")) {
                            Log.i("CircuitPythonEditor", "Found REPL msg");
                            mActivity.get().send("a");
                            mActivity.get().send("f = open('main.py', 'r')");
                            postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    mActivity.get().waitingForRead = true;
                                    mActivity.get().send("print(f.read())");
                                    mActivity.get().send("f.close()");
                                    mActivity.get().isLoading = false;

                                }
                            }, 500);
                        }
                    }
                    if (mActivity.get().waitingForRead){
                        if(data.contains(">>>") == false) {
                            mActivity.get().mainPyStringBuilder.append(data);
                        }else{
                            String code = mActivity.get().mainPyStringBuilder.toString();
                            mActivity.get().mainPyStringBuilder = new StringBuilder();
                            mActivity.get().editorTxt.setText(code.substring(17));
                            mActivity.get().showLineNumbers();
                            mActivity.get().waitingForRead = false;
                            mActivity.get().mainProgress.setIndeterminate(false);
                        }
                    }
                    if (mActivity.get().waitingForTabResult){
                        if(data.contains(">>>") == false) {
                            mActivity.get().tabResult.append(data);
                            mActivity.get().tempTabResult.append(data);
                            Log.i("CircuitPythonEditor", "tab result:" + data);
                            //mActivity.get().editText.setText(mActivity.get().editText.getText().toString() + data);
                            mActivity.get().editText.removeCallbacks(mActivity.get().tabResultDoneRun);
                            mActivity.get().editText.postDelayed(mActivity.get().tabResultDoneRun, 100);
                        }else {
                            Log.i("CircuitPythonEditor", "tab final result:" + mActivity.get().tabResult.toString());
                            mActivity.get().waitingForTabResult = false;
                        }
                    }
                    mActivity.get().display.append(data);
                    break;
                case UsbService.CTS_CHANGE:
                    Log.i("CircuitPythonEditor", "CTS");
                    break;
                case UsbService.DSR_CHANGE:
                    Log.i("CircuitPythonEditor", "DSR");
                    break;
            }
        }
    }

    private void showLineNumbers(){
        String lineNumbersStr = "";
        int lines = editorTxt.getText().toString().split("\r\n").length;
        for (int i = 1; i < lines; i++){
            lineNumbersStr +=  i + "\n";
        }
        lineNumbersTxt.setText(lineNumbersStr);
    }



    public class MainPagerAdapter extends PagerAdapter {
        @Override
        public int getCount() {
            return 2;
        }


        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            LayoutInflater inflater = (LayoutInflater) container.getContext()
                    .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            View page = null;

            if (position == 0) {
                page = inflater.inflate(R.layout.terminal_layout, null);

                display = (TextView) page.findViewById(R.id.terminalTxt);
                /*display.setOnTouchListener(new View.OnTouchListener() {

                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        display.getParent().requestDisallowInterceptTouchEvent(true);
                        return false;
                    }
                });*/

                display.setMovementMethod(new ScrollingMovementMethod());
                editText = (EditText) page.findViewById(R.id.inputEdt);
                Button sendButton = (Button) page.findViewById(R.id.buttonSend);
                sendButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (!editText.getText().toString().equals("")) {
                            String data = editText.getText().toString();
                            if (alreadySent.toString().length() > 0){
                                data = data.replaceFirst(alreadySent.toString(), "");
                                alreadySent = new StringBuilder();
                            }
                            if (usbService != null) { // if UsbService was correctly binded, Send data
                                usbService.write((data+"\r\n").getBytes());
                                editText.setText("");
                            }
                        }
                    }
                });
            }else if (position == 1){
                page = inflater.inflate(R.layout.code_editor_layout, null);

                editorTxt = (LineNumberEditText) page.findViewById(R.id.mainEditor);

                editorTxt.setHorizontallyScrolling(true);
                lineNumbersTxt = (TextView)page.findViewById(R.id.editorLineNumbers);
                editorTxt.setLineNumbersText(lineNumbersTxt);

                /*editorTxt.setOptions(Options.Default.get(editorTxt.getContext())
                        .withLanguage("python")
                        .withCode("py")
                        .withTheme(ColorTheme.MONOKAI));*/
            }

            ((ViewPager) container).addView(page, 0);
            return page;
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
            return view == ((View) object);
        }
    }

    private class SendSerialTask extends AsyncTask<String, Void, Void>{
        @Override
        protected Void doInBackground(String... strings) {
            send(strings[0]);
            return null;
        }
    }

}