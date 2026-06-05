package com.tsymiar.device2device.utils;

import android.app.Activity;
import android.app.Service;

import java.util.LinkedList;
import java.util.List;

/**
 * Thread-safe activity/service tracker for app-wide exit.
 * Does NOT extend Application (no manifest registration needed).
 */
public final class ExitAll {

    private final List<Activity> activities = new LinkedList<>();
    private final List<Service> services = new LinkedList<>();

    private static volatile ExitAll instance;

    private ExitAll() {}

    public static ExitAll getInstance() {
        if (instance == null) {
            synchronized (ExitAll.class) {
                if (instance == null) {
                    instance = new ExitAll();
                }
            }
        }
        return instance;
    }

    public synchronized void addActivity(Activity activity) {
        activities.add(activity);
    }

    public synchronized void addService(Service service) {
        services.add(service);
    }

    public synchronized void exit() {
        for (Activity activity : activities) {
            if (activity != null && !activity.isFinishing()) {
                activity.finish();
            }
        }
        for (Service service : services) {
            if (service != null) {
                service.stopSelf();
            }
        }
        activities.clear();
        services.clear();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    public void onLowMemory() {
        System.gc();
    }
}
