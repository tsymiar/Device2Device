package com.tsymiar.device2device.utils;

import android.util.Log;
import android.widget.TextView;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.activity.MainActivity;

public class JvmMethods {

    public static final String TAG = JvmMethods.class.getCanonicalName();

    public void hello(int a, String c) {
        Log.e(TAG, ("Hello, this is JAVA: " + a + ", " + c));
    }

    public static void welcome(int a, String c) {
        Log.e(TAG, ("Welcome to JAVA: " + a + ", " + c));
    }

    public static void SetTextView(String string)
    {
        ((TextView) MainActivity.getInstance().findViewById(R.id.txt_status)).setText(string);
    }
}
