package com.tsyqi909006258.yongshuo;

import android.annotation.TargetApi;
import android.app.ActionBar;
import android.app.Activity;
import android.app.ListActivity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.widget.Toolbar;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.TsyQi.MyClasses.ExitApplication;
import com.TsyQi.MaterialMenu.MaterialMenuDrawable;

public class SettingActivity extends ActionBarActivity {

    private Toolbar toolBar;

    @TargetApi(Build.VERSION_CODES.HONEYCOMB|10)
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_setting);
		ExitApplication.getInstance().addActivity(this);

		toolBar= (Toolbar) findViewById(R.id.set_bar);
		toolBar.setTitleTextColor(getResources().getColor(R.color.white));
		toolBar.setTitle(R.string.action_settings);
        MaterialMenuDrawable materialMenu = new MaterialMenuDrawable(this, Color.WHITE, MaterialMenuDrawable.Stroke.THIN);
        materialMenu.setIconState(MaterialMenuDrawable.IconState.ARROW);
        toolBar.setNavigationIcon(materialMenu);
        setSupportActionBar(toolBar);
	}

	@Override
	protected void onResume() {
		super.onResume();

        toolBar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        Toast.makeText(this, getText(R.string.action_settings), Toast.LENGTH_SHORT).show();
	}

	@TargetApi(Build.VERSION_CODES.HONEYCOMB)
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			this.finish();
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		finish();
	}
}
