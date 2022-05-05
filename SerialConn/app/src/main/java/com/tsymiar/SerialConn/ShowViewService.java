package com.tsymiar.SerialConn;


import android.app.Activity;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

import com.tsymiar.SerialConn.Sensor.SensorService;

public class ShowViewService extends Service {

    @Override
    public IBinder onBind(Intent p1) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    public void stopService(Activity me) {
        Intent intent = new Intent(me, SensorService.class);
        Intent i_0 = new Intent(me, WindowService.class);
        Bundle bundle = new Bundle();
        bundle.putString("temp", "");
        intent.putExtras(bundle);
        me.sendBroadcast(intent);
        me.startService(intent);
        me.stopService(intent);
        i_0.putExtras(bundle);
        me.sendBroadcast(i_0);
        me.startService(i_0);
        me.stopService(i_0);
    }
}
