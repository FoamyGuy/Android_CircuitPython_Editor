package com.foamyguy.circuitpythoneditor;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AppCompatActivity;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.util.Set;

public class MainActivity extends Activity {

    public static final String TAG = "CircuitPythonEditor";
    final char ctrlC = '\u0003';
    final char ctrlD = '\u0004';
    final String upArrow = "\u001b[A";
    final String downArrow = "\u001b[B";
    final String clearLine = "\u001b[K";
    final char tab = '\t';

    boolean waitingForRead = false;

    private UsbService usbService;
    private EditText display;
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

    private RelativeLayout macroLyt;

    private RelativeLayout macroEditorLyt;

    private RelativeLayout newMacroNameLyt;
            
    private LineNumberEditText macroEditorTxt;
    private TextView macroLineNumbersTxt;

    private MacroFileAdapter macroFileAdapter;

    private String currentlyEditingMacro = "";
    
    private boolean isInREPL = false;
    
    private boolean isLoadingCodePy = false;
    private boolean isSavingCodePy = false;
    private boolean isReadyForWrite = false;
    
    private boolean waitingOnHistoryResult = false;
    
    private boolean sentUp = false;

    ViewPager mainPager;
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
        mainPager = (ViewPager) findViewById(R.id.pager);
        mainPager.setAdapter(mAdapter);
        
        mainProgress = (ProgressBar) findViewById(R.id.mainProgress);


        mHandler = new MyHandler(this);

        tabResultDoneRun = new Runnable() {
            @Override
            public void run() {
                if (!tempTabResult.toString().contains("Traceback")) {
                    if (!tempTabResult.toString().contains("       ")) {
                        //if(editText.getText().toString().startsWith(tempTabResult.substring(0,2))) {

                        //}
                        editText.setText("");
                        String[] lines = display.getText().toString().split("\n");
                        String lastLine = lines[lines.length - 1];
                        editText.append(lastLine.substring(4));

                        alreadySent = new StringBuilder(editText.getText().toString());
                        Log.i("CircuitPythonEditor", "tab result done run: " + tempTabResult.toString());
                    }
                }

                tempTabResult = new StringBuilder();
                waitingForTabResult = false;

            }
        };

        File macrosDir = new File(getFilesDir() + "/macros/");
        Log.i(TAG, macrosDir.getAbsolutePath());
        if (!macrosDir.exists()) {
            macrosDir.mkdir();
        }
        Log.i(TAG, "macroFileAdapter dir exist? " + macrosDir.exists());

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
        //stopService(new Intent(this, UsbService.class));
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
        editText.postDelayed(new Runnable() {
            @Override
            public void run() {
                usbService.write(("\n").getBytes());
            }
        }, 100);
    }

    private void sendCtrlC() {
        if (usbService != null) { // if UsbService was correctly binded, Send data
            usbService.write(("" + ctrlC).getBytes());
            isInREPL = true;
        }
    }

    public void sendCtrlD(View view) {
        if (usbService != null) { // if UsbService was correctly binded, Send data
            usbService.write(("" + ctrlD).getBytes());
            isInREPL = false;
        }
    }
    public void sendUpArrow(View view) {
        if (usbService != null) { // if UsbService was correctly binded, Send data
            usbService.write(("" + upArrow).getBytes());
            sentUp = true;
            waitingOnHistoryResult = true;
        }
    }

    private void clearSavedTerminal(int cols) {
        
        if (usbService != null) { // if UsbService was correctly binded, Send data
            //usbService.write(("\u001b[" + cols + "D").getBytes());
            //usbService.write(clearLine.getBytes());
            for (int i = 0; i < cols; i++){
                usbService.write("\b".getBytes());    
            }
            //display.setText(display.getText().toString().substring(0, display.getText().toString().length() - cols));
        }
    }

    public void sendDownArrow(View view) {
        if (usbService != null) { // if UsbService was correctly binded, Send data
            usbService.write(("" + downArrow).getBytes());
            waitingOnHistoryResult = true;
        }
    }

    public void sendTab(View view) {
        if (usbService != null) { // if UsbService was correctly binded, Send data
            waitingForTabResult = true;
            String data = editText.getText().toString();
            if (alreadySent.toString().length() > 0) {
                data = data.replaceFirst(alreadySent.toString(), "");
            }
            usbService.write((data + tab).getBytes());
            alreadySent.append(editText.getText().toString());

        }
    }

    private void send(String text) {
        //Log.i("CircuitPythonEditor", "inside send");
        if (usbService != null) { // if UsbService was correctly binded, Send data
            usbService.write((text + "\r\n").getBytes());
            //Log.i("CircuitPythonEditor", "after write");
        }
    }

    int curIndex = 0;

    public boolean writeFile(String[] lines) {
        Log.i("CircuitPythonEditor", "line: " + lines[curIndex]);
        if (lines[curIndex].length() > 0) {
            //Log.i("CircuitPythonEditor", "last char: " + lines[curIndex].charAt(lines[curIndex].length() - 1));
            //Log.i("CircuitPythonEditor", "newline: " + (lines[curIndex].charAt(lines[curIndex].length() - 1) == '\n'));
            send("f.write(\"\"\"" + lines[curIndex].substring(0, lines[curIndex].length() - 1) + "\\r\\n\"\"\")");
        } else {
            send("f.write(\"\"\"" + lines[curIndex] + "\\r\\n\"\"\")");
        }

        if (curIndex >= lines.length - 1) {
            send("f.close()");
            mainProgress.setProgress(0);
            curIndex = 0;
            return true;
        } else {
            curIndex++;
            mainProgress.setProgress(curIndex);
            writeFile(lines);
        }
        return false;
    }

    public boolean writeFileBackground(String[] lines) {
        Log.i("CircuitPythonEditor", "line: " + lines[curIndex]);

        Thread t = new Thread() {
            @Override
            public void run() {
                super.run();

                for (int i = 0; i < lines.length; i++) {
                    if (lines[i].length() > 0) {

                        send("f.write(\"\"\"" + lines[i].substring(0, lines[i].length() - 1) + "\\r\\n\"\"\")");
                    } else {
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

    public boolean writeFileDelayed(String[] lines) {
        if(isReadyForWrite){
            Log.i("CircuitPythonEditor", "line: " + lines[curIndex]);
            //Log.i("CircuitPythonEditor", "setting RTS high");
            //usbService.setRTS(true);
            //usbService.setRTS(false);
            isReadyForWrite = false;
            if (lines[curIndex].length() > 0) {
                //Log.i("CircuitPythonEditor", "last char: " + lines[curIndex].charAt(lines[curIndex].length() - 1));
                //Log.i("CircuitPythonEditor", "newline: " + (lines[curIndex].charAt(lines[curIndex].length() - 1) == '\n'));
                send("f.write(\"\"\"" + lines[curIndex].substring(0, lines[curIndex].length() - 1) + "\\r\\n\"\"\")");
            } else {
                send("f.write(\"\"\"" + lines[curIndex] + "\\r\\n\"\"\")");
            }
        /*if (lines[curIndex].length() > 1) {
            Log.i("CircuitPythonEditor", "last two: " + lines[curIndex].substring(lines[curIndex].length()-2));
        }*/

            //send("gc.collect()");
            //send("print(gc.mem_free())");

            if (curIndex >= lines.length - 1) {
                send("f.close()");
                isSavingCodePy = false;
                mainProgress.setProgress(0);
                curIndex = 0;
                return true;
            } else {
                editorTxt.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        curIndex++;
                        mainProgress.setProgress(curIndex);

                        writeFileDelayed(lines);
                    }
                }, 50);
            }
        } else{ // not ready for write
            Log.i(TAG, "wasn't ready for write, posting next try");
            editorTxt.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mainProgress.setProgress(curIndex);
                    writeFileDelayed(lines);
                }
            }, 50);
        }
        
        return false;
    }

    public void executeMacro(String[] macroLines) {
        Log.i("CircuitPythonEditor", "line: " + macroLines[curIndex]);
        if (macroLines[curIndex].length() > 0) {
            //Log.i("CircuitPythonEditor", "last char: " + lines[curIndex].charAt(lines[curIndex].length() - 1));
            //Log.i("CircuitPythonEditor", "newline: " + (lines[curIndex].charAt(lines[curIndex].length() - 1) == '\n'));
            send( macroLines[curIndex] );
        } else {
            send("\r\n");
        }
        
        if (curIndex >= macroLines.length - 1) {
            curIndex = 0;
        } else {
            editorTxt.postDelayed(new Runnable() {
                @Override
                public void run() {
                    curIndex++;
                    //mainProgress.setProgress(curIndex);
                    
                    executeMacro(macroLines);
                }
            }, 100);
        }


    }

    public void saveMainPy(View view) {
        if (isInREPL) {
            if (!isLoading && !isSavingCodePy) {
                isSavingCodePy = true;
                Log.i("CircuitPythonEditor", "begining write prep");
                send("import gc");
                Log.i("CircuitPythonEditor", "after first send");
                send("f = open('code.py', 'w')");
                send("f.write('')");
                send("f.close()");
                //send("f = open('code.py', 'a')");
                send("f = open('/code.py', 'a')");

                Log.i("CircuitPythonEditor", "opened file");
                String[] lines = editorTxt.getText().toString().split("\r\n");
                Log.i("CircuitPythonEditor", "lines: " + lines.length);
                mainProgress.setMax(lines.length - 1);
                writeFileDelayed(lines);
            }else{
                Toast.makeText(view.getContext(), "Please wait for current operation to complete", Toast.LENGTH_SHORT).show();
            }
        }else{
            //Toast.makeText(view.getContext(), "Please enter REPL before saving", Toast.LENGTH_SHORT).show();
            AlertDialog.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
            } else {
                builder = new AlertDialog.Builder(this);
            }
            builder.setTitle("Warning")
                    .setMessage("Must enter REPL before saving. Please send CTRL-C, then try again.")
                    .setPositiveButton("Send CTRL-C", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            sendCtrlC(null);
                        }
                    })
                    .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            // do nothing
                        }
                    })
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .show();
        }

    }

    public void loadCodePy(View view) {
        if(!isInREPL) {
            if(!isLoading && !isSavingCodePy) {
                mainProgress.setIndeterminate(true);
                isLoading = true;
                sendCtrlC();
            }else{
                Toast.makeText(view.getContext(), "Please wait for current operation to complete", Toast.LENGTH_SHORT).show();
            }
        }else{
            //Toast.makeText(view.getContext(), "Please exit REPL before loading", Toast.LENGTH_SHORT).show();
            AlertDialog.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
            } else {
                builder = new AlertDialog.Builder(this);
            }
            builder.setTitle("Warning")
                    .setMessage("Must exit REPL before loading. Please send CTRL-D, then try again.")
                    .setPositiveButton("Send CTRL-D", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            sendCtrlD(null);
                        }
                    })
                    .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            // do nothing
                        }
                    })
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .show();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();


    }

    public void loadSamplePy(View view) {

        String sampleCodeStr = loadAssetTextAsString(view.getContext(), "sample_code.py");
        editorTxt.setText(sampleCodeStr);
        showLineNumbers();
        sendCtrlC();

    }

    public void saveMacro(View view) {
        String macroContent = macroEditorTxt.getText().toString();
        Macro.writeMacroFile(view.getContext(), currentlyEditingMacro, macroContent);
        macroEditorLyt.setVisibility(View.GONE);
    }

    public void showMacroLyt(View view) {
        mainPager.setCurrentItem(0);
        macroLyt.setVisibility(View.VISIBLE);
        hideKeyboard();
    }

    public void showTerminalLyt(View view) {
        if(macroLyt.getVisibility() == View.VISIBLE) {
            macroLyt.setVisibility(View.GONE);
        }
        mainPager.setCurrentItem(0);
    }

    public void showEditorLyt(View view) {
        mainPager.setCurrentItem(1);
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
            boolean dontShow = false;
            switch (msg.what) {
                case SyncUsbService.SYNC_READ:
                    //
                    // break;
                case UsbService.MESSAGE_FROM_SERIAL_PORT:
                    String data = (String) msg.obj;
                    Log.i("CircuitPythonEditor", data);
                    if (mActivity.get().isLoading) {
                        if (data.contains("Press any key to enter the REPL. Use CTRL-D to reload.")) {
                            Log.i("CircuitPythonEditor", "Found REPL msg");
                            mActivity.get().send("a");
                            mActivity.get().send("f = open('code.py', 'r')");
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
                    if (mActivity.get().waitingForRead) {
                        if (!data.contains(">>>")) {
                            mActivity.get().mainPyStringBuilder.append(data);
                        } else {
                            String code = mActivity.get().mainPyStringBuilder.toString();
                            mActivity.get().mainPyStringBuilder = new StringBuilder();
                            mActivity.get().editorTxt.setText(code.substring(17));
                            mActivity.get().showLineNumbers();
                            mActivity.get().waitingForRead = false;
                            mActivity.get().mainProgress.setIndeterminate(false);
                        }
                    }
                    if (mActivity.get().waitingForTabResult) {
                        if (!data.contains(">>>")) {
                            mActivity.get().tabResult.append(data);
                            mActivity.get().tempTabResult.append(data);
                            Log.i("CircuitPythonEditor", "tab result:" + data);
                            //mActivity.get().editText.setText(mActivity.get().editText.getText().toString() + data);
                            mActivity.get().editText.removeCallbacks(mActivity.get().tabResultDoneRun);
                            mActivity.get().editText.postDelayed(mActivity.get().tabResultDoneRun, 100);
                        } else {
                            Log.i("CircuitPythonEditor", "tab final result:" + mActivity.get().tabResult.toString());
                            mActivity.get().waitingForTabResult = false;
                        }
                    }
                    if(mActivity.get().isSavingCodePy){
                        if(data.contains(">>>")){
                            mActivity.get().isReadyForWrite = true;
                        }
                    }
                    if (data.startsWith("\u001b[") && data.endsWith("D")){
                        dontShow = true;
                        int number = Integer.valueOf(data.replace("\u001b[", "").replace("D", ""));
                        
                        String curDisplay = mActivity.get().display.getText().toString();
                        String editDisplay = curDisplay.substring(0, curDisplay.length() - number);
                        mActivity.get().display.setText(editDisplay);
                        
                    }
                    if (data.equals("\u001b[K")){
                        dontShow = true;
                    }
                    if (data.equals("\b")){
                        dontShow = true;
                    }
                    if(mActivity.get().waitingOnHistoryResult){
                        mActivity.get().waitingOnHistoryResult = false;
                        mActivity.get().editText.setText(data);
                        mActivity.get().editText.setSelection(data.length());
                        mActivity.get().clearSavedTerminal(data.length());
                        dontShow = true;
                    }
                    if (!dontShow) {
                        mActivity.get().display.append(data);
                        mActivity.get().display.setSelection(mActivity.get().display.getText().toString().length()-1);
                    }
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



    private void showLineNumbers() {
        String lineNumbersStr = "";
        int lines = editorTxt.getText().toString().split("\r\n").length;
        Log.i(TAG, "found lines: " + lines);
        for (int i = 1; i <= lines; i++) {
            lineNumbersStr += i + "\n";
        }
        lineNumbersTxt.setText(lineNumbersStr);
    }

    private void showMacroLineNumbers() {
        String lineNumbersStr = "";
        int lines = macroEditorTxt.getText().toString().split("\n").length;
        Log.i(TAG, "found lines: " + lines);
        for (int i = 1; i <= lines; i++) {
            lineNumbersStr += i + "\n";
        }
        macroLineNumbersTxt.setText(lineNumbersStr);
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
                macroLyt = page.findViewById(R.id.macroLyt);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    macroLyt.setElevation(1000f);
                }
                display = (EditText)page.findViewById(R.id.terminalTxt);
                display.setTextIsSelectable(true);
                mainPager.setOnTouchListener(new View.OnTouchListener()
                {
                    public boolean onTouch(View p_v, MotionEvent p_event)
                    {
                        display.getParent().requestDisallowInterceptTouchEvent(false);
                        //  We will have to follow above for all scrollable contents
                        return false;
                    }
                });
                display.setOnTouchListener(new View.OnTouchListener()
                {
                    public boolean onTouch(View p_v, MotionEvent p_event)
                    {
                        // this will disallow the touch request for parent scroll on touch of child view
                        p_v.getParent().requestDisallowInterceptTouchEvent(false);
                        return false;
                    }
                });
                //display.setHorizontallyScrolling(true);
                Log.i(TAG, display.toString());
                /*display.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        macroLyt.setVisibility(View.VISIBLE);
                        hideKeyboard();
                        
                    }
                });*/


                ListView macroList = page.findViewById(R.id.macroFilesLst);
                File macrosDir = new File(getFilesDir() + "/macros/");
                if (macroFileAdapter == null) {
                    macroFileAdapter = new MacroFileAdapter(macroList.getContext(), Macro.getMacroFileList(macroList.getContext()));
                    macroList.setAdapter(macroFileAdapter);
                } else {
                    macroFileAdapter.removeAll();
                    macroFileAdapter.addAll(Macro.getMacroFileList(macroList.getContext()));
                    macroFileAdapter.notifyDataSetChanged();
                }
                EditText newMacroNameEdt = page.findViewById(R.id.newMacroNameEdt);
                newMacroNameLyt = page.findViewById(R.id.newMacroNameLyt);
                ImageView newMacroBtn = page.findViewById(R.id.newMacroBtn);

                macroEditorLyt = page.findViewById(R.id.macroEditorLyt);
                macroEditorTxt = page.findViewById(R.id.macroEditor);
                macroLineNumbersTxt = page.findViewById(R.id.macroEditorLineNumbers);
                macroEditorTxt.setLineNumbersText(macroLineNumbersTxt);
                macroEditorTxt.setHorizontallyScrolling(true);

                ImageView createBtn = page.findViewById(R.id.createMacroBtn);

                createBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        newMacroNameLyt.setVisibility(View.GONE);
                        hideKeyboard();
                        File newMacroFile = new File(getFilesDir() + "/macros/" + newMacroNameEdt.getText().toString());
                        if (!newMacroFile.exists()) {
                            try {
                                newMacroFile.createNewFile();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }

                        macroFileAdapter.removeAll();
                        macroFileAdapter.addAll(Macro.getMacroFileList(macroList.getContext()));
                        macroFileAdapter.notifyDataSetChanged();
                    }
                });

                newMacroBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        newMacroNameLyt.setVisibility(View.VISIBLE);
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            newMacroNameLyt.setElevation(1001);
                        }
                        newMacroNameEdt.requestFocus();
                        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                        imm.showSoftInput(newMacroNameEdt, InputMethodManager.SHOW_IMPLICIT);
                    }
                });

                //display.setMovementMethod(new ScrollingMovementMethod());
                editText = (EditText) page.findViewById(R.id.inputEdt);
                Button sendButton = (Button) page.findViewById(R.id.buttonSend);
                sendButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (!editText.getText().toString().equals("")) {
                            String data = editText.getText().toString();
                            if (alreadySent.toString().length() > 0) {
                                /* Use substring instead of replaceFirst
                                 * replaceFirst() is using regex and having
                                 * escape chars like ( in the string break it
                                  * */
                                //data = data.replaceFirst(alreadySent.toString(), "");
                                data = data.substring(alreadySent.length());
                                alreadySent = new StringBuilder();
                            }
                            if (usbService != null) { // if UsbService was correctly binded, Send data
                                usbService.write((data + "\r\n").getBytes());
                                
                                editText.setText("");
                            }
                        }else{
                            if(sentUp){
                                usbService.write(("\r\n").getBytes());
                            }
                        }
                    }
                });
            } else if (position == 1) {
                page = inflater.inflate(R.layout.code_editor_layout, null);

                editorTxt = (LineNumberEditText) page.findViewById(R.id.mainEditor);

                editorTxt.setHorizontallyScrolling(true);
                lineNumbersTxt = (TextView) page.findViewById(R.id.editorLineNumbers);
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

    @Override
    public void onBackPressed() {
        
        if(macroEditorLyt.getVisibility() == View.VISIBLE){
            macroEditorLyt.setVisibility(View.GONE);
            return;
        }
        
        if(newMacroNameLyt.getVisibility() == View.VISIBLE){
            newMacroNameLyt.setVisibility(View.GONE);
            return;
        }

        if(macroLyt.getVisibility() == View.VISIBLE){
            macroLyt.setVisibility(View.GONE);
            return;
        }
        
        super.onBackPressed();
    }

    public void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Activity.INPUT_METHOD_SERVICE);
        //Find the currently focused view, so we can grab the correct window token from it.
        View view = getCurrentFocus();
        //If no view currently has focus, create a new one, just so we can grab a window token from it
        if (view == null) {
            view = new View(this);
        }
        imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private class SendSerialTask extends AsyncTask<String, Void, Void> {
        @Override
        protected Void doInBackground(String... strings) {
            send(strings[0]);
            return null;
        }
    }

    private String loadAssetTextAsString(Context context, String name) {
        BufferedReader in = null;
        try {
            StringBuilder buf = new StringBuilder();
            InputStream is = context.getAssets().open(name);
            in = new BufferedReader(new InputStreamReader(is));

            String str;
            boolean isFirst = true;
            while ((str = in.readLine()) != null) {
                if (isFirst) {
                    isFirst = false;
                } else {
                    buf.append('\r');
                    buf.append('\n');
                }
                buf.append(str);
            }
            return buf.toString();
        } catch (IOException e) {
            Log.e(TAG, "Error opening asset " + name);
            e.printStackTrace();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing asset " + name);
                }
            }
        }

        return null;
    }


    public class MacroFileAdapter extends ArrayAdapter<File> {
        private LayoutInflater inflater;

        public MacroFileAdapter(@NonNull Context context, File[] files) {
            super(context, 0);
            this.addAll(files);
            inflater = (LayoutInflater.from(context));

        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            RelativeLayout row = (RelativeLayout) convertView;
            if (row == null) {
                row = (RelativeLayout) inflater.inflate(R.layout.row_macro_file, parent, false);
            }
            TextView fileNameTxt = row.findViewById(R.id.nameTxt);
            ImageView runBtn = row.findViewById(R.id.runBtn);
            ImageView editBtn = row.findViewById(R.id.editBtn);

            editBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Log.i(TAG, "edit btn");
                    macroEditorTxt.setText(Macro.readMacroFile(view.getContext(), getItem(position).getName()));
                    macroEditorLyt.setVisibility(View.VISIBLE);
                    macroEditorTxt.addTextChangedListener(new TextWatcher() {
                        @Override
                        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                            Log.i(TAG, "beforeChange");
                        }

                        @Override
                        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                            Log.i(TAG, "onChange");
                            showMacroLineNumbers();
                        }

                        @Override
                        public void afterTextChanged(Editable editable) {
                            Log.i(TAG, "afterChange");
                        }
                    });
                    showMacroLineNumbers();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        macroEditorLyt.setElevation(1002);
                    }
                    currentlyEditingMacro = getItem(position).getName();
                }
            });

            runBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if(isInREPL) {
                        String macroStr = Macro.readMacroFile(view.getContext(), getItem(position).getName());
                        String[] lines = macroStr.split("\n");
                        executeMacro(lines);
                        macroLyt.setVisibility(View.GONE);
                    }else{
                        //Toast.makeText(view.getContext(), "Please enter REPL before using macro", Toast.LENGTH_SHORT).show();
                        AlertDialog.Builder builder;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            builder = new AlertDialog.Builder(fileNameTxt.getContext(), android.R.style.Theme_Material_Dialog_Alert);
                        } else {
                            builder = new AlertDialog.Builder(fileNameTxt.getContext());
                        }
                        builder.setTitle("Warning")
                                .setMessage("Must enter REPL before saving. Please send CTRL-C, then try again.")
                                .setPositiveButton("Send CTRL-C", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                        sendCtrlC(null);
                                    }
                                })
                                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                        // do nothing
                                    }
                                })
                                .setIcon(android.R.drawable.ic_dialog_alert)
                                .show();
                    }

                }
            });
            
            fileNameTxt.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View view) {
                    AlertDialog.Builder builder;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        builder = new AlertDialog.Builder(fileNameTxt.getContext(), android.R.style.Theme_Material_Dialog_Alert);
                    } else {
                        builder = new AlertDialog.Builder(fileNameTxt.getContext());
                    }
                    builder.setTitle("Delete entry")
                            .setMessage("Are you sure you want to delete this entry?")
                            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    // continue with delete
                                    Macro.delete(fileNameTxt.getContext(), getItem(position).getName());
                                    macroFileAdapter.removeAll();
                                    macroFileAdapter.addAll(Macro.getMacroFileList(fileNameTxt.getContext()));
                                    macroFileAdapter.notifyDataSetChanged();
                                }
                            })
                            .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    // do nothing
                                }
                            })
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .show();
                    return false;
                }
            });

            fileNameTxt.setText(getItem(position).getName());


            return row;
        }

        public void removeAll() {
            int startingCount = getCount();
            for (int i = 0; i < startingCount; i++) {
                remove(getItem(0));
            }
        }
    }

}