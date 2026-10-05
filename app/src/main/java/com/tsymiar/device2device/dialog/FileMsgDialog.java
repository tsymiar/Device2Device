package com.tsymiar.device2device.dialog;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.wrapper.NetworkWrapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Objects;

public class FileMsgDialog extends DialogFragment {
    private static final String TAG = "FileMsgDialog";
    private static final int REQUEST_PERMISSION = 1001;

    private EditText etIp, etPort;
    private TextView tvStatus, tvFilePath;
    private ProgressBar pbTransfer;
    private Button btnSelect, btnStartServer, btnConnect, btnSendFile, btnDisconnect;

    private boolean isServerStarted = false;
    private boolean isConnected = false;
    private String selectedFilePath = null;
    private OnFileSelectedListener listener = null;

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (getActivity() == null) return;
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        selectedFilePath = getPathFromUri(uri);
                        if (tvFilePath != null) {
                            tvFilePath.setText(selectedFilePath != null ? selectedFilePath : uri.getLastPathSegment());
                        }
                        if (btnSendFile != null) {
                            btnSendFile.setEnabled(selectedFilePath != null && isConnected);
                        }
                        if (listener != null && selectedFilePath != null) {
                            listener.onFileSelected(selectedFilePath);
                        }
                    }
                }
            }
    );

    private final ActivityResultLauncher<String> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (isGranted) {
                    openFilePicker();
                } else {
                    if (getContext() != null) {
                        Toast.makeText(getContext(), "Storage permission required", Toast.LENGTH_SHORT).show();
                    }
                }
            }
    );

    public interface OnFileSelectedListener {
        void onFileSelected(String filePath);
    }

    public static FileMsgDialog newInstance() {
        return new FileMsgDialog();
    }

    public void setOnFileSelectedListener(OnFileSelectedListener listener) {
        this.listener = listener;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setContentView(R.layout.dialog_file_msg);
        Objects.requireNonNull(dialog.getWindow()).setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT);

        initViews(dialog);
        initListeners(dialog);
        updateUI();
        return dialog;
    }

    private void initViews(Dialog dialog) {
        etIp = dialog.findViewById(R.id.et_file_trans_ip);
        etPort = dialog.findViewById(R.id.et_file_trans_port);
        tvFilePath = dialog.findViewById(R.id.tv_file_trans_path);
        tvStatus = dialog.findViewById(R.id.tv_file_trans_status);
        pbTransfer = dialog.findViewById(R.id.pb_file_trans);

        btnSelect = dialog.findViewById(R.id.btn_file_trans_select);
        btnStartServer = dialog.findViewById(R.id.btn_file_trans_server);
        btnConnect = dialog.findViewById(R.id.btn_file_trans_connect);
        btnSendFile = dialog.findViewById(R.id.btn_file_trans_send);
        btnDisconnect = dialog.findViewById(R.id.btn_file_trans_disconnect);
    }

    @SuppressLint({"SetTextI18n", "DefaultLocale"})
    private void initListeners(Dialog dialog) {
        btnSelect.setOnClickListener(v -> checkPermissionAndOpenPicker());

        btnStartServer.setOnClickListener(v -> {
            if (!isServerStarted) {
                int port = Integer.parseInt(etPort.getText().toString());
                applySavePath();
                int ret = NetworkWrapper.startFileMsgServer(port);
                if (ret >= 0) {
                    isServerStarted = true;
                    tvStatus.setText("Server started on port " + port);
                } else {
                    Toast.makeText(getContext(), "Server start failed: " + ret, Toast.LENGTH_SHORT).show();
                }
            } else {
                NetworkWrapper.stopFileMsgServer();
                isServerStarted = false;
                tvStatus.setText("FileMsgDialog Server Exit");
            }
            updateUI();
        });

        btnConnect.setOnClickListener(v -> {
            if (!isConnected) {
                String ip = etIp.getText().toString();
                int port = Integer.parseInt(etPort.getText().toString());
                int ret = NetworkWrapper.connectFileMsgServer(ip, port);
                if (ret >= 0) {
                    isConnected = true;
                    applySavePath();
                    tvStatus.setText(String.format("Connected to %s:%d", ip, port));
                } else {
                    Toast.makeText(getContext(), "Connection failed: " + ret, Toast.LENGTH_SHORT).show();
                }
            }
            updateUI();
        });

        btnSendFile.setOnClickListener(v -> {
            if (!isConnected) {
                Toast.makeText(getContext(), "Not connected", Toast.LENGTH_SHORT).show();
                return;
            }
            if (selectedFilePath == null) {
                Toast.makeText(getContext(), "Please select a file", Toast.LENGTH_SHORT).show();
                return;
            }
            File file = new File(selectedFilePath);
            if (!file.exists()) {
                Toast.makeText(getContext(), "File not found: " + selectedFilePath, Toast.LENGTH_SHORT).show();
                return;
            }
            new Thread(() -> {
                int ret = NetworkWrapper.sendLocalFile(selectedFilePath);
                if (ret >= 0) {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            tvStatus.setText(String.format("File sent: %s", file.getName()));
                            Toast.makeText(getContext(), "File sent successfully", Toast.LENGTH_SHORT).show();
                        });
                    }
                } else {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() ->
                                Toast.makeText(getContext(), "Send failed: " + ret, Toast.LENGTH_SHORT).show()
                        );
                    }
                }
            }).start();
        });

        btnDisconnect.setOnClickListener(v -> {
            if (isConnected) {
                NetworkWrapper.disconnectFileMsg();
                isConnected = false;
                tvStatus.setText("Disconnected");
            }
            updateUI();
        });
    }

    /**
     * 接收目录：用应用专属外部目录（Android 10+ 无需存储权限即可写），
     * 服务端与客户端都要设 —— 服务端收文件时同样靠它落盘。
     */
    private void applySavePath() {
        android.content.Context ctx = getContext();
        if (ctx == null) return;
        File dir = new File(ctx.getExternalFilesDir(null), "Device2Device");
        if (dir == null) dir = new File(ctx.getFilesDir(), "Device2Device");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create receive dir: " + dir.getAbsolutePath());
        }
        NetworkWrapper.setFileSavePath(dir.getAbsolutePath());
    }

    private void checkPermissionAndOpenPicker() {
        if (getContext() == null) return;
        /*
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED) {
                openFilePicker();
            } else {
                permissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES);
            }
        } else */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            openFilePicker();
        } else {
            if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                openFilePicker();
            } else {
                permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        filePickerLauncher.launch(intent);
    }

    private String getPathFromUri(Uri uri) {
        if (uri == null) return null;
        if (getContext() == null) return uri.getLastPathSegment();

        // 使用 ContentResolver 复制文件到 app 私有目录
        try {
            ContentResolver resolver = getContext().getContentResolver();
            String fileName = null;

            // 尝试获取文件名
            String[] projection = {"_display_name"};
            try (android.database.Cursor cursor = resolver.query(uri, projection, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex("_display_name");
                    if (nameIndex >= 0) {
                        fileName = cursor.getString(nameIndex);
                    }
                }
            }

            if (fileName == null) {
                fileName = "temp_file_" + System.currentTimeMillis();
            }

            // 复制到 app 私有目录
            File cacheDir = getContext().getCacheDir();
            File tempFile = new File(cacheDir, fileName);

            try (InputStream input = resolver.openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(tempFile)) {
                if (input == null) return uri.getLastPathSegment();
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                }
            }

            return tempFile.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
            return uri.getLastPathSegment();
        }
    }

    private void updateUI() {
        btnStartServer.setText(isServerStarted ? "Stop Server" : "Start Server");
        btnConnect.setEnabled(!isServerStarted);
        btnSendFile.setEnabled(selectedFilePath != null && isConnected);
        btnDisconnect.setEnabled(isConnected);
    }

    public void updateStatus(String status) {
        if (tvStatus != null) {
            tvStatus.setText(status);
        }
    }

    public void updateProgress(long current, long total, String status) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (tvStatus != null) {
                tvStatus.setText(status);
            }
            if (pbTransfer != null) {
                // total<=0 是纯状态消息（客户端上下线等），没有进度可言，直接收起进度条
                if (total <= 0 || current >= total) {
                    pbTransfer.setVisibility(android.view.View.GONE);
                } else {
                    pbTransfer.setVisibility(android.view.View.VISIBLE);
                    pbTransfer.setProgress((int) (current * 100 / total));
                }
            }
        });
    }

    @Override
    public void onDestroy() {
        if (isServerStarted) {
            NetworkWrapper.stopFileMsgServer();
            isServerStarted = false;
        }
        if (isConnected) {
            NetworkWrapper.disconnectFileMsg();
            isConnected = false;
        }
        super.onDestroy();
    }
}
