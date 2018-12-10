package com.foamyguy.circuitpythoneditor;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;

/**
 * Created by o_0 on 12/9/2018.
 */

public class Macro {

    public static File getMacroDirFile(Context ctx) {
        File macrosDir = new File(ctx.getFilesDir() + "/macros/");
        return macrosDir;
    }

    public static File[] getMacroFileList(Context ctx) {
        File macrosDir = new File(ctx.getFilesDir() + "/macros/");
        String[] fileNames = macrosDir.list();
        ArrayList<File> files = new ArrayList<File>();
        for (String file : fileNames) {
            files.add(new File(ctx.getFilesDir() + "/macros/" + file));
        }

        File[] fileArr = new File[files.size()];
        return files.toArray(fileArr);
    }

    public static String readMacroFile(Context ctx, String name) {
        File macroFile = new File(ctx.getFilesDir() + "/macros/" + name);
        BufferedReader in = null;
        try {
            
            StringBuilder buf = new StringBuilder();
            InputStream is = new FileInputStream(macroFile);
            in = new BufferedReader(new InputStreamReader(is));

            String str;
            boolean isFirst = true;
            while ((str = in.readLine()) != null) {
                if (isFirst) {
                    isFirst = false;
                } else {
                    //buf.append('\r');
                    buf.append('\n');
                }
                buf.append(str);
            }
            return buf.toString();
        } catch (IOException e) {
            Log.e(MainActivity.TAG, "Error opening asset " + name);
            e.printStackTrace();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    Log.e(MainActivity.TAG, "Error closing asset " + name);
                }
            }
        }
        return null;
    }
    
    public static void writeMacroFile(Context ctx, String fileName, String contents){
        File macroFile = new File(ctx.getFilesDir() + "/macros/" + fileName);
        try {
            BufferedWriter bw = new BufferedWriter(new FileWriter(macroFile));
            for(String line: contents.split("\n")){
                bw.write(line + "\n");
            }
            bw.flush();
            bw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
