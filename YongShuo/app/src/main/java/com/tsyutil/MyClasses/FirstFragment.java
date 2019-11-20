package com.tsyutil.MyClasses;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.tsymiar.yongshuo.MainActivity;
import com.tsymiar.yongshuo.R;

import java.io.Serializable;

public class FirstFragment extends Fragment {

    private static final String TAG = "FirstFragment";
    private int tmpValue;
    private MainActivity parent;

    public static FirstFragment newInstance(Serializable parent)
    {
        FirstFragment fragment=new FirstFragment();
        Bundle bundle=new Bundle();
        bundle.putSerializable("parent", parent);
        fragment.setArguments(bundle);
        return fragment;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        // TODO Auto-generated method stub
        super.onSaveInstanceState(outState);
        Log.d("my", "FirstFragment-->onSaveInstanceState ");
        outState.putSerializable("parent", this.parent);
        outState.putSerializable("tmp",tmpValue);
    }


    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        Log.d("my","FirstFragment-->onCreate and saveDInstancesState is null->"+(savedInstanceState==null));
        super.onCreate(savedInstanceState);
        Bundle bundle=this.getArguments();
        Log.d("my", "bundle-->" + bundle);
        Log.d("my", "getArguments is null-->"+(bundle==null));
        this.parent=(MainActivity) bundle.getSerializable("parent");

        if(savedInstanceState!=null)
        {
            Log.d("my", "parent is null and get from savedInstanceState");
            this.tmpValue= (int) savedInstanceState.getSerializable("tmp");
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
    {
        View view=inflater.inflate(R.layout.activity_main, container,false);
        return view;
    }
}
