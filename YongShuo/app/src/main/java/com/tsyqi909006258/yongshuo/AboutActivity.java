package com.tsyqi909006258.yongshuo;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.support.v7.app.ActionBarActivity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.support.v7.widget.Toolbar;
import android.widget.ImageView;
import android.widget.Toast;

import com.TsyQi.MaterialMenu.MaterialMenuDrawable;
import com.TsyQi.MyClasses.ExitApplication;

public class AboutActivity extends ActionBarActivity {

	private static String url = "http://www.yongshuo99.com";
    Button btn1,btn2;
    Toolbar toolBar;
    ImageView img4,img5;
    String[] arrayFruit;

	@TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        ExitApplication.getInstance().addActivity(this);
        toolBar= (Toolbar) findViewById(R.id.about_tool);
        toolBar.setTitleTextColor(getResources().getColor(R.color.white));
        toolBar.setTitle(R.string.action_about);
        btn1=(Button)findViewById(R.id.gov);
        btn2=(Button)findViewById(R.id.release);
        img4=(ImageView)findViewById(R.id.imageView4);
        img5=(ImageView)findViewById(R.id.imageView5);
        MaterialMenuDrawable materialMenu = new MaterialMenuDrawable(this, Color.WHITE, MaterialMenuDrawable.Stroke.THIN);
        materialMenu.setIconState(MaterialMenuDrawable.IconState.ARROW);
        toolBar.setNavigationIcon(materialMenu);
        setSupportActionBar(toolBar);
        toolBar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        arrayFruit = new String[] { getString(R.string.phone1),getString(R.string.phone2) };
	}

    @Override
    protected void onResume() {
        super.onResume();
        btn1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(AboutActivity.this, BrowserActivty.class);
                Bundle bundle = new Bundle();
                bundle.putString("url", url);
                intent.putExtras(bundle);
                startActivity(intent);
            }
        });
        btn2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

            }
        });
        img4.setOnTouchListener(new View.OnTouchListener() {

            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_MOVE:
                        img4.setAlpha(50);
                        break;
                    case MotionEvent.ACTION_UP:
                        img4.setAlpha(255);
                        break;
                }
                return false;
            }
        });
        img5.setOnTouchListener(new View.OnTouchListener() {

            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_MOVE:
                        img5.setAlpha(50);
                        break;
                    case MotionEvent.ACTION_UP:
                        img5.setAlpha(255);
                        break;
                }
                return false;
            }
        });
        img4.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AlertDialog dialog = new AlertDialog.Builder(AboutActivity.this).setTitle(getString(R.string.choice))
                .setItems(arrayFruit, new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent intent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + arrayFruit[which]));
                        startActivity(intent);
                    }
                }).setNegativeButton(getString(R.string.cancel), new DialogInterface.OnClickListener() {

                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                // TODO Auto-generated method stub
                            }
                        }).create();
                dialog.show();
            }
        });
        img5.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick (View v){
                    String location = "http://3gimg.qq.com/lightmap/v1/wxmarker/index.html?marker=coord:30.59069,114.21985;title:%E6%B0%B8%E7%83%81%E4%B8%80%E5%81%A5%E5%BA%B7;addr:%E4%B8%AD%E5%9B%BD%E6%B9%96%E5%8C%97%E7%9C%81%E6%AD%A6%E6%B1%89%E5%B8%82%E7%A1%9A%E5%8F%A3%E5%8C%BA%E5%8F%A4%E7%94%B0%E4%BA%94%E8%B7%AF17%E5%8F%B7&referer=wexinmp_profile";
                    Intent intent = new Intent(AboutActivity.this, BrowserActivty.class);
                    Bundle bundle = new Bundle();
                    bundle.putString("url", location);
                    intent.putExtras(bundle);
                    startActivity(intent);
                     }
                });
              }
}
