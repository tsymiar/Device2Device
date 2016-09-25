package com.TsyQi.MyClasses;

import android.graphics.drawable.Drawable;

public class ListItem {

	private Drawable icon;
	private String title;

	public ListItem() {
	}

	public ListItem(Drawable icon, String title) {
		this.icon = icon;
		this.title = title;
	}

	public Drawable getIcon() {
		return icon;
	}
	public String getTitle() {
		return title;
	}
	public void setIcon(Drawable icon) {
		this.icon = icon;
	}
	public void setTitle(String title) {
		this.title = title;
	}
}