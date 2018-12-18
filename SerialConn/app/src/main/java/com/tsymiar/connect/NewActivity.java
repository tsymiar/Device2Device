package com.tsymiar.connect;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.view.KeyEvent;
import android.widget.Toast;

public class NewActivity extends Activity{

	public void onCreate(Bundle savedInstanceState){
		{
			super.onCreate(savedInstanceState);

			Toast.makeText(getBaseContext(), R.string.developing, Toast.LENGTH_SHORT).show();
		}
	}

	@Override
	protected void onPause()
	{
		super.onPause();
		this.finish();
	}

	@Override
	public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {

		if (keyCode == KeyEvent.KEYCODE_BACK) {
			Intent intent = new Intent(this,MainActivity.class);
			startActivity(intent);
		}
		return super.onKeyDown(keyCode, event);
	}
}

