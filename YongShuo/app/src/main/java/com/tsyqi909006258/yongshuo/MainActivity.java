package com.tsyqi909006258.yongshuo;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.view.MenuItemCompat;
import android.support.v4.view.PagerTabStrip;
import android.support.v4.view.ViewPager;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.widget.ShareActionProvider;
import android.support.v7.widget.Toolbar;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.TsyQi.MaterialMenu.MaterialMenuDrawable;
import com.TsyQi.MyClasses.ExitApplication;
import com.TsyQi.MyClasses.FirstFragment;
//import com.instabug.library.Instabug;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;

public class MainActivity extends ActionBarActivity
		implements NavigationDrawerFragment.NavigationDrawerCallbacks, Serializable/*,BluetoothAdapter.LeScanCallback*/ {
	/**
	 * Fragment managing the behaviors, interactions and presentation of the navigation drawer.
	 */
	//private NavigationDrawerFragment mNavigationDrawerFragment;

	/**
	 * Used to store the last screen title. For use in {@link #}.
	 */
	private long mCurTime;
	private DrawerLayout mDrawerLayout;
	private View mFragmentContainerView;
	private NavigationDrawerFragment mNavigationDrawerFragment;
	private NavigationDrawerFragment mPager;
	private ExitApplication exitApplication;
	private Toolbar mToolbar;
    private BluetoothAdapter mBluetoothAdapter;
    private static final int REQUEST_DISCOVER_DEVICE = 1;
    private static final int REQUEST_ENABLE_BT = 2;
    private boolean mScanning;
    private Handler mHandler;
    // Stops scanning after 10 seconds.
    private static final long SCAN_PERIOD = 10000;
    private MaterialMenuDrawable materialMenu;
    private FirstFragment firstFragment;
    ShareActionProvider mShareActionProvider;
    ViewPager pager = null;
    PagerTabStrip tabStrip = null;
    FragmentManager fragManager;
    ArrayList<View> viewContainter = new ArrayList<>();
    ArrayList<String> titleContainer = new ArrayList<>();
    Bundle savedInstanceState;
    public String TAG = "tag";

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

        exitApplication=new ExitApplication();
        ExitApplication.getInstance().addActivity(this);

        final LayoutInflater inflater = LayoutInflater.from(this);
        mBluetoothAdapter=BluetoothAdapter.getDefaultAdapter();
        mToolbar = (Toolbar) findViewById(R.id.toolbar);

		mNavigationDrawerFragment = (NavigationDrawerFragment)
				getFragmentManager().findFragmentById(R.id.navigation_drawer);

		mFragmentContainerView = this.findViewById(R.id.navigation_drawer);
		mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);

        //Instabug.initialize(getApplication(), "1535f9681695458b13244182fbe6ff8e");

        mNavigationDrawerFragment.setUp(
                R.id.navigation_drawer,
                (DrawerLayout) findViewById(R.id.drawer_layout));
        materialMenu = new MaterialMenuDrawable(this, Color.WHITE, MaterialMenuDrawable.Stroke.THIN);

        mToolbar.setNavigationIcon(materialMenu);
        mToolbar.setTitle(R.string.app_name);
        mToolbar.setSubtitle(getString(R.string.statement));
        mToolbar.setTitleTextColor(getResources().getColor(R.color.white));
        setSupportActionBar(mToolbar);
        /* 这些通过ActionBar来设置也是一样的，要在setSupportActionBar(toolbar);之后，不然就报错了 */
        // getSupportActionBar().setTitle("标题");
        // getSupportActionBar().setSubtitle("副标题");
        // getSupportActionBar().setLogo(R.drawable.ic_launcher);
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            AlertDialog.Builder dialog = new AlertDialog.Builder(this);
            dialog.setTitle(R.string.requestbt);
            dialog.setPositiveButton(getString(R.string.openbt),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                        }
                    });
            dialog.setNegativeButton(getString(R.string.ignore),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            //Applications.getInstance().exit();
                        }
                    });
            dialog.show();
        }
        }
    @Override
    protected void onResume() {
        super.onResume();

        mToolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                materialMenu.animateIconState(MaterialMenuDrawable.IconState.BURGER);
                // Handle your drawable state here
                mDrawerLayout.openDrawer(mFragmentContainerView);
            }
        });
        // Use this check to determine whether BLE is supported on the device. Then
        // you can selectively disable BLE-related features.
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            finish();
        }
    }
    //TODO：搜索BLE
    /*
            @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
            private void scanLeDevice(final boolean enable) {
                if (enable) {
                    // Stops scanning after a pre-defined scan period.
                    mHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mScanning = false;
                            mBluetoothAdapter.stopLeScan(mLeScanCallback);
                        }
                    }, SCAN_PERIOD);

                    mScanning = true;
                    mBluetoothAdapter.startLeScan(mLeScanCallback);
                } else {
                    mScanning = false;
                    mBluetoothAdapter.stopLeScan(mLeScanCallback);
                }
            }

            // Device scan callback.
            private BluetoothAdapter.LeScanCallback mLeScanCallback =
                    new BluetoothAdapter.LeScanCallback() {
                        @Override
                        public void onLeScan(final BluetoothDevice device, int rssi,
                                             byte[] scanRecord) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    mLeDeviceListAdapter.addDevice(device);
                                    mLeDeviceListAdapter.notifyDataSetChanged();
                                }
                            });
                        }
                    };
*/
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
	@Override
	public void onNavigationDrawerItemSelected(int position) {
		// update the main content by replacing fragments
		FragmentManager fragmentManager = getFragmentManager();
		fragmentManager.beginTransaction()
				.replace(R.id.container, PlaceholderFragment.newInstance(position + 1))
				.commit();
        fragmentManager.removeOnBackStackChangedListener(new FragmentManager.OnBackStackChangedListener() {
            @Override
            public void onBackStackChanged() {
                Toast.makeText(MainActivity.this, "BackStackChanged", Toast.LENGTH_SHORT).show();
            }
        });
	}

	@TargetApi(Build.VERSION_CODES.HONEYCOMB)
	public void onSectionAttached(int number) {
		switch (number) {
            case 1:
                mPerson();
                break;
            case 2:break;
			case 3:
                mChart();
				break;
            case 4:
				mViewPager();
				break;
			case 5:
                mMore();
				break;
		}
	}
    public static LayoutInflater from(Context context) {
        LayoutInflater LayoutInflater =
                (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        if (LayoutInflater == null) {
            throw new AssertionError("LayoutInflater not found.");
        }
        return LayoutInflater;
    }
        public void mPerson(){
        //TODO:侧滑菜单第一个item的点击事件
        }
        public void mChart(){
            //TODO：画图表
            mToolbar.setTitle(getString(R.string.title_section1));
            Toast.makeText(this, getText(R.string.title_section1), Toast.LENGTH_SHORT).show();
            /*
            FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
            ChartFragment fragment = new ChartFragment();
            transaction.replace(R.id.pager_fragment,fragment);
            transaction.commit();
            */
            FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
            ChartFragment fragment = new ChartFragment();
            transaction.replace(R.id.pager_fragment, fragment);
            transaction.commit();
        }

        public void mViewPager(){
            mToolbar.setTitle(getString(R.string.title_section2));
            FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
            ProductFragment fragment = new ProductFragment();
            transaction.replace(R.id.pager_fragment, fragment);
            transaction.commit();
            }

        public void mMore(){
            mToolbar.setTitle(getString(R.string.title_section3));
            FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
            SwipeRefreshFragment fragment = new SwipeRefreshFragment();
            transaction.replace(R.id.pager_fragment,fragment);
            transaction.commit();
        }
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		if (!mNavigationDrawerFragment.isDrawerOpen()) {
			// Only show items in the action bar relevant to this screen
			// if the drawer is not showing. Otherwise, let the drawer
			// decide what to show in the action bar.
			getMenuInflater().inflate(R.menu.main, menu);
            // Locate MenuItem with ShareActionProvider
            MenuItem menuItem = menu.findItem(R.id.action_share);
                // Fetch and store ShareActionProvider
            mShareActionProvider = (ShareActionProvider) MenuItemCompat.getActionProvider(menuItem);
            // Set history different from the default before getting the action
            // view since a call to MenuItemCompat.getActionView() calls
            // onCreateActionView() which uses the backing file name. Omit this
            // line if using the default share history file is desired.
            //mShareActionProvider.setShareHistoryFileName("custom_share_history.xml");
			return true;
		}
		return super.onCreateOptionsMenu(menu);
	}
    private void doShareIntent(Intent shareIntent) {
        if (mShareActionProvider != null) {
         mShareActionProvider.setShareIntent(shareIntent);
        }
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		switch (id) {
			case R.id.action_settings:
				startActivity(new Intent(this, SettingActivity.class));
				return true;
			case R.id.action_about:
				startActivity(new Intent(this, AboutActivity.class));
				return true;
			case R.id.action_exit:
				exitApplication.Exit();
				return true;
		}
        //TODO:分享按钮点击事件
        if(id==R.id.action_share){
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("image/*");
            Uri uri = Uri.fromFile(new File(getFilesDir(), "foo.png"));
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri.toString());
            doShareIntent(shareIntent);
        }
		return super.onOptionsItemSelected(item);
	}

    /**
	 * A placeholder fragment containing a simple view.
	 */
	@TargetApi(Build.VERSION_CODES.HONEYCOMB)
	public static class PlaceholderFragment extends Fragment {
		/**
		 * The fragment argument representing the section number for this
		 * fragment.
		 */
		private static final String ARG_SECTION_NUMBER = "section_number";

		/**
		 * Returns a new instance of this fragment for the given section
		 * number.
		 */
		public static PlaceholderFragment newInstance(int sectionNumber) {
			PlaceholderFragment fragment = new PlaceholderFragment();
			Bundle args = new Bundle();
			args.putInt(ARG_SECTION_NUMBER, sectionNumber);
			fragment.setArguments(args);
			return fragment;
		}

		public PlaceholderFragment() {
		}

		@Override
		public View onCreateView(LayoutInflater inflater, ViewGroup container,
								 Bundle savedInstanceState) {
			return inflater.inflate(R.layout.fragment_main, container, false);
		}

		@Override
		public void onAttach(Activity activity) {
			super.onAttach(activity);
			((MainActivity) activity).onSectionAttached(
					getArguments().getInt(ARG_SECTION_NUMBER));
		}
	}

	@TargetApi(Build.VERSION_CODES.HONEYCOMB)
	public static class ContentFragment extends Fragment {

		private static final String ARG_SECTION_TITLE = "section_title";

		public static ContentFragment newInstance(String title) {
			ContentFragment fragment = new ContentFragment();
			Bundle args = new Bundle();
			args.putString(ARG_SECTION_TITLE, title);
			fragment.setArguments(args);
			return fragment;
		}

        @Override
		public View onCreateView(LayoutInflater inflater, ViewGroup container,
								 Bundle savedInstanceState) {
			View rootView = inflater.inflate(R.layout.fragment_chart, container, false);
			TextView textView = (TextView) rootView.findViewById(R.id.section_label);
			textView.setText(getArguments().getString(ARG_SECTION_TITLE));
			return rootView;
		}
	}

	@Override
	public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
		long mLastTime = mCurTime;
		mCurTime = System.currentTimeMillis();
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			if (mDrawerLayout != null && mDrawerLayout.isDrawerOpen(mFragmentContainerView)) {
                materialMenu.animateIconState(MaterialMenuDrawable.IconState.ARROW);
				mDrawerLayout.closeDrawer(mFragmentContainerView);
			}
			else{
				assert mDrawerLayout != null;
                materialMenu.animateIconState(MaterialMenuDrawable.IconState.BURGER);
				mDrawerLayout.openDrawer(mFragmentContainerView);
			}
			if (mCurTime - mLastTime < 300) {
				this.finish();
				return true;
			} else if (mCurTime - mLastTime >= 300) {
				return true;
			}
			return true;
		}
		return super.onKeyDown(keyCode, event);
	}
}
