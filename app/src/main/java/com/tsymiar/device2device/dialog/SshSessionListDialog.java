package com.tsymiar.device2device.dialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.tsymiar.device2device.R;
import com.tsymiar.device2device.activity.SshClientActivity;
import com.tsymiar.device2device.entity.SshConnection;
import com.tsymiar.device2device.utils.SshKeyStore;
import com.tsymiar.device2device.utils.SshSessionStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 会话列表浮窗：点击主页面「SSH 客户端」从底部弹出，点会话进入对应连接。
 * 保留原会话页的新建/编辑/删除/私钥导入能力，但不再占用整个屏幕。
 */
public class SshSessionListDialog {
    /** 避开 SelectActivity 自己用的 10001~10005 */
    public static final int REQ_PICK_KEY = 20001;

    private final Activity mActivity;
    private BottomSheetDialog mSheet;
    private LinearLayout mContainer;
    private List<SshConnection> mSessions = new ArrayList<>();

    private View mEditorView;
    private SshConnection mEditing;
    private String mPendingKeyFile = "";

    public SshSessionListDialog(Activity activity) {
        mActivity = activity;
    }

    public void show() {
        View v = LayoutInflater.from(mActivity).inflate(R.layout.dialog_ssh_session_list, null);
        mContainer = v.findViewById(R.id.ssh_session_container);
        v.findViewById(R.id.btn_ssh_new).setOnClickListener(btn -> showEditor(new SshConnection()));
        v.findViewById(R.id.btn_ssh_temp).setOnClickListener(btn -> {
            mSheet.dismiss();
            mActivity.startActivity(new Intent(mActivity, SshClientActivity.class));
        });

        mSheet = new BottomSheetDialog(mActivity);
        mSheet.setContentView(v);
        mSheet.show();
        refresh();
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK_KEY || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            String old = mPendingKeyFile;
            mPendingKeyFile = SshKeyStore.importKey(mActivity, uri);
            if (!TextUtils.isEmpty(old) && !old.equals(mPendingKeyFile)) {
                SshKeyStore.remove(mActivity, old);
            }
            updateKeyName();
        } catch (Exception e) {
            Toast.makeText(mActivity, mActivity.getString(R.string.ssh_import_key_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void refresh() {
        mSessions = SshSessionStore.all(mActivity);
        Collections.sort(mSessions, Comparator
                .comparing((SshConnection c) -> c.group == null ? "" : c.group)
                .thenComparing(SshConnection::displayName));

        mContainer.removeAllViews();
        if (mSessions.isEmpty()) {
            TextView empty = new TextView(mActivity);
            empty.setText(R.string.ssh_session_empty);
            empty.setTextColor(androidx.core.content.ContextCompat.getColor(
                    mActivity, R.color.ui_text_dim));
            empty.setTextSize(12);
            empty.setPadding(4, 24, 4, 24);
            mContainer.addView(empty);
            return;
        }
        for (SshConnection c : mSessions) {
            View item = LayoutInflater.from(mActivity)
                    .inflate(R.layout.item_ssh_connection, mContainer, false);
            ((TextView) item.findViewById(R.id.tv_ssh_session_name)).setText(c.displayName());
            ((TextView) item.findViewById(R.id.tv_ssh_session_detail)).setText(detail(c));
            TextView group = item.findViewById(R.id.tv_ssh_session_group);
            if (TextUtils.isEmpty(c.group)) {
                group.setVisibility(View.GONE);
            } else {
                group.setText(c.group);
            }
            item.setOnClickListener(v -> openSession(c));
            item.setOnLongClickListener(v -> {
                showItemMenu(c);
                return true;
            });
            mContainer.addView(item);
        }
    }

    private String detail(SshConnection c) {
        String auth = c.authType == SshConnection.AUTH_KEY ? "密钥" : "密码";
        return c.endpoint() + "  ·  " + auth;
    }

    private void openSession(SshConnection c) {
        SshSessionStore.setLastId(mActivity, c.id);
        mSheet.dismiss();
        Intent i = new Intent(mActivity, SshClientActivity.class);
        i.putExtra("conn_id", c.id);
        mActivity.startActivity(i);
    }

    private void showItemMenu(SshConnection c) {
        String[] actions = {"连接", mActivity.getString(R.string.ssh_session_edit),
                mActivity.getString(R.string.ssh_session_delete)};
        new AlertDialog.Builder(mActivity)
                .setTitle(c.displayName())
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        openSession(c);
                    } else if (which == 1) {
                        showEditor(c);
                    } else {
                        confirmDelete(c);
                    }
                })
                .show();
    }

    private void confirmDelete(SshConnection c) {
        new AlertDialog.Builder(mActivity)
                .setTitle(R.string.ssh_session_delete)
                .setMessage(mActivity.getString(R.string.ssh_delete_session, c.displayName()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (c.authType == SshConnection.AUTH_KEY) {
                        SshKeyStore.remove(mActivity, c.keyFile);
                    }
                    SshSessionStore.remove(mActivity, c.id);
                    refresh();
                })
                .show();
    }

    private void showEditor(SshConnection conn) {
        mEditing = conn;
        mPendingKeyFile = conn.keyFile;
        View v = LayoutInflater.from(mActivity).inflate(R.layout.dialog_ssh_connection, null);
        mEditorView = v;

        ((EditText) v.findViewById(R.id.et_ssh_name)).setText(conn.name);
        ((EditText) v.findViewById(R.id.et_ssh_group)).setText(conn.group);
        ((EditText) v.findViewById(R.id.et_ssh_host)).setText(conn.host);
        ((EditText) v.findViewById(R.id.et_ssh_port)).setText(String.valueOf(conn.port));
        ((EditText) v.findViewById(R.id.et_ssh_user)).setText(conn.user);
        ((EditText) v.findViewById(R.id.et_ssh_password)).setText(conn.password);
        ((EditText) v.findViewById(R.id.et_ssh_key_pass)).setText(conn.keyPassphrase);
        RadioGroup authGroup = v.findViewById(R.id.rg_ssh_auth);
        authGroup.check(conn.authType == SshConnection.AUTH_KEY
                ? R.id.rb_ssh_key : R.id.rb_ssh_password);
        updateAuthUi(authGroup.getCheckedRadioButtonId());
        updateKeyName();
        authGroup.setOnCheckedChangeListener((group, checkedId) -> updateAuthUi(checkedId));

        v.findViewById(R.id.btn_ssh_pick_key).setOnClickListener(btn -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (mActivity instanceof com.tsymiar.device2device.utils.ActivityResultRequester) {
                ((com.tsymiar.device2device.utils.ActivityResultRequester) mActivity)
                        .launchForResult(intent, REQ_PICK_KEY);
            } else {
                // 宿主未实现 ActivityResultRequester 时的兼容路径
                mActivity.startActivityForResult(intent, REQ_PICK_KEY);
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(mActivity)
                .setTitle(conn.id.isEmpty() ? R.string.ssh_session_new : R.string.ssh_session_edit)
                .setView(v)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(btn -> {
                    if (saveEditor()) {
                        dialog.dismiss();
                    }
                }));
        dialog.show();
    }

    private void updateAuthUi(int checkedId) {
        if (mEditorView == null) {
            return;
        }
        boolean keyAuth = checkedId == R.id.rb_ssh_key;
        mEditorView.findViewById(R.id.et_ssh_password).setVisibility(keyAuth ? View.GONE : View.VISIBLE);
        mEditorView.findViewById(R.id.ssh_key_area).setVisibility(keyAuth ? View.VISIBLE : View.GONE);
    }

    private void updateKeyName() {
        if (mEditorView == null) {
            return;
        }
        TextView label = mEditorView.findViewById(R.id.tv_ssh_key_name);
        label.setText(TextUtils.isEmpty(mPendingKeyFile)
                ? mActivity.getString(R.string.ssh_session_key_hint)
                : "已选择：" + mPendingKeyFile);
    }

    private boolean saveEditor() {
        View v = mEditorView;
        if (v == null || mEditing == null) {
            return false;
        }
        String host = ((EditText) v.findViewById(R.id.et_ssh_host)).getText().toString().trim();
        String user = ((EditText) v.findViewById(R.id.et_ssh_user)).getText().toString().trim();
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(user)) {
            Toast.makeText(mActivity, R.string.ssh_client_need_host_user, Toast.LENGTH_SHORT).show();
            return false;
        }
        int port;
        try {
            port = Integer.parseInt(((EditText) v.findViewById(R.id.et_ssh_port))
                    .getText().toString().trim());
        } catch (NumberFormatException e) {
            port = 22;
        }
        if (port <= 0 || port > 65535) {
            port = 22;
        }

        mEditing.name = ((EditText) v.findViewById(R.id.et_ssh_name)).getText().toString().trim();
        mEditing.group = ((EditText) v.findViewById(R.id.et_ssh_group)).getText().toString().trim();
        mEditing.host = host;
        mEditing.port = port;
        mEditing.user = user;
        mEditing.authType = ((RadioGroup) v.findViewById(R.id.rg_ssh_auth)).getCheckedRadioButtonId()
                == R.id.rb_ssh_key ? SshConnection.AUTH_KEY : SshConnection.AUTH_PASSWORD;
        mEditing.password = ((EditText) v.findViewById(R.id.et_ssh_password)).getText().toString();
        mEditing.keyFile = mPendingKeyFile;
        mEditing.keyPassphrase = ((EditText) v.findViewById(R.id.et_ssh_key_pass)).getText().toString();
        if (mEditing.authType == SshConnection.AUTH_KEY && TextUtils.isEmpty(mEditing.keyFile)) {
            Toast.makeText(mActivity, R.string.ssh_session_need_key, Toast.LENGTH_SHORT).show();
            return false;
        }
        if (TextUtils.isEmpty(mEditing.id)) {
            mEditing.id = SshConnection.create().id;
        }
        SshSessionStore.save(mActivity, mEditing);
        refresh();
        return true;
    }
}
