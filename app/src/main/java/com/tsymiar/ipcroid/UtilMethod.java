package com.tsymiar.ipcroid;

import android.util.Log;

public class UtilMethod {

    static final String TAG = UtilMethod.class.getCanonicalName();

    public void hello(int a, String c) {
        Log.e(TAG, ("Hello, this is JAVA: " + a + ", " + c));
    }

    public static void welcome(int a, String c) {
        Log.e(TAG, ("Welcome to JAVA: " + a + ", " + c));
    }
}
