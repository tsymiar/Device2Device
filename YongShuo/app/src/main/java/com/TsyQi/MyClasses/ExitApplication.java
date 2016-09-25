package com.TsyQi.MyClasses;

import android.app.Activity;
import android.app.Application;
import android.app.Service;

import java.util.LinkedList;
import java.util.List;

@SuppressWarnings("ALL")
public class ExitApplication extends Application {
    //save every activity by List
    private List<Activity> mList = new LinkedList<>();
    private List<Service> vList = new LinkedList<>();
    private static ExitApplication instance;
    public ExitApplication(){}
    public synchronized static ExitApplication getInstance(){
        if (null == instance) {
            instance = new ExitApplication();
        }
        return instance;
    }
    // add Activity
    public void addActivity(Activity activity) {
        mList.add(activity);
    }
    // add Service
    public void addService(Service service) {
        vList.add(service);
    }
    //close every listed-activity/service
    public void Exit() {
        try {
            for (Activity activity:mList) {
                if (activity != null)
                    activity.finish();
            }
            for (Service service:vList) {
                if (service != null)
                    service.onDestroy();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            System.exit(0);
        }
    }
    //kill the process
    public void onLowMemory() {
        super.onLowMemory();
        System.gc();
    }
}