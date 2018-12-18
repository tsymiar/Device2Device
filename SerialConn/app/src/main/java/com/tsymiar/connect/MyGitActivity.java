package com.tsymiar.connect;

import android.content.Intent;
import android.app.Activity;
import android.os.Bundle;
import android.net.Uri;

public class MyGitActivity extends Activity{

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = new Intent();
        intent.setAction("android.intent.action.VIEW");
        Uri content_url = Uri.parse(getString(R.string.github));
        intent.setData(content_url);
        startActivity(intent);
    }

    @Override
    protected void onStop()
    {
        super.onStop();
        this.finish();
    }
}