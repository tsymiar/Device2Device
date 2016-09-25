package com.TsyQi.MyClasses;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.tsyqi909006258.yongshuo.R;

import java.util.List;

public class ListAdapter extends BaseAdapter {

	private LayoutInflater mInflater;
	private List<ListItem> mItems;

	public ListAdapter(Context context, List<ListItem> data) {
		this.mInflater = LayoutInflater.from(context);
		this.mItems = data;
	}

	@Override
	public int getCount() {
		return mItems.size();
	}

	@Override
	public ListItem getItem(int position) {
		return mItems.get(position);
	}

	@Override
	public long getItemId(int position) {
		return position;
	}

	@SuppressLint("InflateParams")
	@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		ListItem item = getItem(position);
		TextView itemTitle = null;
		ImageView itemIcon = null;
		if(convertView == null){
			convertView = mInflater.inflate(R.layout.drawer_list_item, null);
		}
		itemTitle = (TextView) convertView.findViewById(R.id.item_title);
		itemIcon = (ImageView) convertView.findViewById(R.id.item_icon);
		itemTitle.setText(item.getTitle());
		itemIcon.setBackground(item.getIcon());
		return convertView;
	}
}