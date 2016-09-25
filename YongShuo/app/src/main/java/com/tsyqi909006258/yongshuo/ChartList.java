package com.tsyqi909006258.yongshuo;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.SimpleAdapter;

import com.TsyQi.MyClasses.SaxService;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;

public class ChartList extends Fragment {

    List<HashMap<String, String>> list = null;
    InputStream inputStream = null;
    SimpleAdapter simpleAdapter;
    ListView listView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.item_list, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {

                inputStream = getResources().openRawResource(R.raw.test);
                list = SaxService.readXML(inputStream, "user");
                listView=new ListView(getActivity());
                simpleAdapter = new SimpleAdapter(getActivity().getApplicationContext(), SaxService.readXML(getResources().openRawResource(R.raw.test), "user"),
                        R.layout.item_list, new String[] { "id", "name", "age" },
                        new int[] { R.id.textView1, R.id.textView2, R.id.textView3 });
                listView.setAdapter(simpleAdapter);
            }
        }

