package com.tsymiar.yongshuo;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.AbsoluteSizeSpan;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.tsyutil.MyClasses.ExitApplication;
import com.tsymiar.yongshuo.R;

public class BrowserActivty extends Activity {

    private float OldX1,OldX2,OldY1,OldY2,NewX1,NewX2,NewY1,NewY2;
	private boolean error=false;

	WebView webview;
	ActionBar actionBar;
	ProgressBar progressBar;
	Bundle bundle=new Bundle();

	@SuppressLint("SetJavaScriptEnabled")
	@TargetApi(Build.VERSION_CODES.HONEYCOMB)
	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		// TODO: Implement this method
		super.onCreate(savedInstanceState);
		actionBar = getActionBar();
		assert actionBar != null;
		actionBar.setDisplayShowHomeEnabled(false);
		setContentView(R.layout.activty_browser);
        ExitApplication.getInstance().addActivity(this);
		webview = new WebView(this);
		webview=(WebView)findViewById(R.id.html);
		WebSettings webSettings = webview.getSettings();
		webSettings.setDisplayZoomControls(true);
		webSettings.setJavaScriptEnabled(true);
		webSettings.setAppCacheEnabled(true);
		webSettings.setSupportZoom(true);
		webSettings.setBuiltInZoomControls(false);
		webSettings.setDatabaseEnabled(true);
		String dir = this.getApplicationContext().getDir("database", Context.MODE_PRIVATE).getPath();
		webSettings.setGeolocationDatabasePath(dir);
		webSettings.setGeolocationEnabled(true);
		webSettings.setDomStorageEnabled(true);

		progressBar= (ProgressBar) findViewById(R.id.progressBar);
		progressBar.setMax(100);
		try {
			int dividerID = this.getResources().getIdentifier("android:id/titleDivider", null, null);
			View divider = findViewById(dividerID);
			divider.setBackgroundColor(Color.TRANSPARENT);
		}catch(Exception e)
		{
			e.printStackTrace();
		}
		if(Build.VERSION.SDK_INT>=21){
			getActionBar().setElevation(0);
			getActionBar().setDisplayHomeAsUpEnabled(true);
		}

		webview.setWebChromeClient(new WebChromeClient() {

			@Override
			public void onReceivedIcon(WebView view, Bitmap icon) {
				super.onReceivedIcon(view, icon);
			}
			@Override
			public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback callback) {
				callback.invoke(origin, true, false);
				super.onGeolocationPermissionsShowPrompt(origin, callback);
			}
		});
	}

	@TargetApi(Build.VERSION_CODES.LOLLIPOP)
	@Override
	protected void onResume() {
		super.onResume();
		Intent intent=this.getIntent();
		bundle = intent.getExtras();
		String url = bundle.getString("url");
		if (Build.VERSION.SDK_INT >= 19) {
			webview.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
		}
		if(url !=null)webview.loadUrl(url);
		webview.setWebViewClient(new WebViewClient() {
			@Override
			public boolean shouldOverrideUrlLoading(WebView view, String url) {
				view.loadUrl(url);
				if(url.startsWith("tel:")|| url.contains("tel:")){
					Intent intent=new Intent(Intent.ACTION_VIEW, Uri.parse(url));
					startActivity(intent);
				}
				return true;
			}
		});

		webview.setWebChromeClient(new WebChromeClient() {
			@Override
			public void onProgressChanged(WebView view, int newProgress) {
				progressBar.setProgress(newProgress);
				if (newProgress == 100) {
					progressBar.setVisibility(View.GONE);
				}
				super.onProgressChanged(view, newProgress);
			}

			@TargetApi(Build.VERSION_CODES.HONEYCOMB)
			@Override
			public void onReceivedTitle(WebView view, String title) {
				super.onReceivedTitle(view, title);
				SpannableString spannableString=new SpannableString(title);
				spannableString.setSpan(new AbsoluteSizeSpan(16, true), 0, spannableString.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
				actionBar.setTitle(spannableString);
			}
		});
		webview.setWebViewClient(new WebViewClient() {
			@Override
			public void onReceivedError(WebView view, int errorCode,
										String description, String failingUrl) {
				String warning = "file:///android_asset/doc/network_warning.htm";//getString(R.string.net_err);
				view.loadUrl(/*"javascript:document.body.innerHTML=\"" +*/ warning /*+ "\""*/);
				error=true;
			}
		});
		webview.setOnTouchListener(new View.OnTouchListener() {

			public boolean onTouch(View v, MotionEvent event) {

				switch (event.getAction()) {
					case MotionEvent.ACTION_POINTER_2_DOWN:
						if (event.getPointerCount() == 2) {
							for (int i = 0; i < event.getPointerCount(); i++) {
								if (i == 0) {
									OldX1 = event.getX(i);
									OldY1 = event.getY(i);
								} else if (i == 1) {
									OldX2 = event.getX(i);
									OldY2 = event.getY(i);
								}
							}
						}
						break;
					case MotionEvent.ACTION_MOVE:
						if (event.getPointerCount() == 2) {
							for (int i = 0; i < event.getPointerCount(); i++) {
								if (i == 0) {
									NewX1 = event.getX(i);
									NewY1 = event.getY(i);
								} else if (i == 1) {
									NewX2 = event.getX(i);
									NewY2 = event.getY(i);
								}
							}
							float disOld = (float) Math.sqrt((Math.pow(OldX2 - OldX1, 2) + Math.pow(
									OldY2 - OldY1, 2)));
							float disNew = (float) Math.sqrt((Math.pow(NewX2 - NewX1, 2) + Math.pow(
									NewY2 - NewY1, 2)));
							//Log.d("onTouch","disOld="+disOld+"|disNew="+disNew);
							if (disOld - disNew >= 25) {
								webview.zoomOut();
							} else if (disNew - disOld >= 25) {
								webview.zoomIn();
							}
							OldX1 = NewX1;
							OldX2 = NewX2;
							OldY1 = NewY1;
							OldY2 = NewY2;
						}
				}
				return false;
			}
		});
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.browser, menu);
		return super.onCreateOptionsMenu(menu);
	}
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		switch (id) {
			case android.R.id.home:
				finish();
				return true;
			case R.id.browser_quit:
				if(webview.canGoForward())
				webview.goForward();
				else{
					Toast.makeText(this,getString(R.string.last_page),Toast.LENGTH_SHORT).show();
				}
				return true;
			case R.id.browser_reload:
				if(!error)
				webview.reload();
				else{
					Toast.makeText(this,getString(R.string.none_page),Toast.LENGTH_SHORT).show();
				}
				return true;
			case R.id.browser_cache:
				webview.stopLoading();
				webview.clearHistory();
				webview.clearFormData();
				webview.clearMatches();
				webview.clearSslPreferences();
				webview.clearCache(true);
				Toast.makeText(this,getString(R.string.cleaned),Toast.LENGTH_SHORT).show();
				return true;
			}
		return super.onOptionsItemSelected(item);
	}

	@TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @Override
	public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK) {

			if(!webview.canGoBack()){
			AlertDialog.Builder dialog = new AlertDialog.Builder(this);
			dialog.setTitle(getString(R.string.nonsupport));
			dialog.setPositiveButton(getString(R.string.close),
					new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							finish();
						}
					});
			dialog.setNeutralButton(getString(R.string.ignore), new DialogInterface.OnClickListener() {

				@Override
				public void onClick(DialogInterface arg0, int arg1) {
					// TODO Auto-generated method stub
				}
			});
			dialog.setNegativeButton(getString(R.string.back),
					new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							webview.clearHistory();
							webview.clearCache(true);
							webview.goForward();
						}
					});
			dialog.show();}
			else{
                webview.clearCache(true);
				webview.goBack();
                webview.setWebViewClient(new WebViewClient() {
                   @Override
                    public void onPageFinished(WebView view, String url) {
                        String title = view.getTitle();
                        if (title == null) {
                            title = "";
                        }
                        BrowserActivty.this.setTitle(title);
                        super.onPageFinished(view, url);
                    }
                });
            }
			return true;
		}
		return super.onKeyDown(keyCode, event);
	}

	@Override
	protected void onPause()
	{
		// TODO: Implement this method
		super.onPause();
	}
}
