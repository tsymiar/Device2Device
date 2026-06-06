package com.tsymiar.device2device.activity;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import com.tsymiar.device2device.R;

public class MyGitActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = new Intent();
        intent.setAction("android.intent.action.VIEW");
        Uri contentUrl = Uri.parse(getString(R.string.github));
        intent.setData(contentUrl);
        startActivity(intent);
        this.finish();
    }
}