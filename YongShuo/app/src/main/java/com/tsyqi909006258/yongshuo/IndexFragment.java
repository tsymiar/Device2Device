package com.tsyqi909006258.yongshuo;

import android.annotation.TargetApi;
import android.app.Fragment;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

@TargetApi(Build.VERSION_CODES.HONEYCOMB)
public class IndexFragment extends Fragment {

	private static final String STATE_SELECTED_POSITION = "selected_navigation_drawer_position";
	int mCurrentSelectedPosition = 0;
	boolean mFromSavedInstanceState;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Toast.makeText(getActivity(),"Section1 Created!",Toast.LENGTH_SHORT).show();
		//SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getActivity());

		if (savedInstanceState != null) {
			mCurrentSelectedPosition = savedInstanceState.getInt(STATE_SELECTED_POSITION);
			mFromSavedInstanceState = true;
		}
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		// Indicate that this fragment would like to influence the set of actions in the action bar.
		setHasOptionsMenu(true);
	}

}
