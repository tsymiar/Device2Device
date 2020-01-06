package com.tsymiar.devidroid.utils;

import android.util.Log;

public class MethodUtil {

    public static final String TAG = MethodUtil.class.getCanonicalName();

    public void hello(int a, String c) {
        Log.e(TAG, ("Hello, this is JAVA: " + a + ", " + c));
    }

    public static void welcome(int a, String c) {
        Log.e(TAG, ("Welcome to JAVA: " + a + ", " + c));
    }
}
