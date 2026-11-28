package com.tsymiar.device2device.utils;

import android.content.Intent;

/**
 * 让Dialog 等没有生命周期的组件也能走宿主的 Activity Result API。
 * 宿主实现本接口后，Dialog 不再依赖已废弃的 startActivityForResult/onActivityResult。
 */
public interface ActivityResultRequester {
    void launchForResult(Intent intent, int requestCode);
}
