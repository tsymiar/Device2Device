package com.tsymiar.SerialConn;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;

public class BuggerActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent data = new Intent(Intent.ACTION_SENDTO);
        data.setData(Uri.parse(getString(R.string.email)));
        data.putExtra(Intent.EXTRA_SUBJECT, R.string.feed);
        data.putExtra(Intent.EXTRA_TEXT, R.string.illreply);
        startActivity(data);
        // 附件
        // File file = new File(Environment.getExternalStorageDirectory().getPath()+ File.separator + "simplenote"+ File.separator+"note.xml");
    }

    @Override
    protected void onPause() {
        super.onPause();
        this.finish();
    }
}
