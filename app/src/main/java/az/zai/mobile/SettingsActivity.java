package az.zai.mobile;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Parametrlər: hesab, token, məlumatların təmizlənməsi. */
public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private TextView accountInfo;
    private EditText tokenInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("zai", MODE_PRIVATE);
        accountInfo = findViewById(R.id.account_info);
        tokenInput = findViewById(R.id.token_input);
        Button saveBtn = findViewById(R.id.btn_save_token);
        Button logoutBtn = findViewById(R.id.btn_logout);
        Button wipeBtn = findViewById(R.id.btn_wipe);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.settings_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        refreshInfo();

        saveBtn.setOnClickListener(v -> saveToken());
        logoutBtn.setOnClickListener(v -> {
            prefs.edit().remove("token").remove("user_name").remove("user_role").apply();
            Toast.makeText(this, R.string.logged_out, Toast.LENGTH_SHORT).show();
            finish();
        });

        wipeBtn.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.wipe_title)
                .setMessage(R.string.wipe_msg)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    new DbHelper(this).deleteAll();
                    Toast.makeText(this, R.string.wipe_done, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show());
    }

    private void refreshInfo() {
        String name = prefs.getString("user_name", null);
        String role = prefs.getString("user_role", "guest");
        String info;
        if (name == null) {
            info = getString(R.string.account_none);
        } else {
            String roleText = "guest".equals(role)
                    ? getString(R.string.role_guest)
                    : getString(R.string.role_user);
            info = name + " (" + roleText + ")";
        }
        accountInfo.setText(getString(R.string.account_fmt, info));
        tokenInput.setHint(R.string.token_hint);
    }

    private void saveToken() {
        String t = tokenInput.getText().toString().trim();
        if (t.isEmpty()) {
            Toast.makeText(this, R.string.token_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.checking, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                ApiClient.AuthResult a = ApiClient.obtainAuth(t);
                runOnUiThread(() -> {
                    prefs.edit()
                            .putString("token", a.token)
                            .putString("user_name", a.name)
                            .putString("user_role", a.role)
                            .apply();
                    tokenInput.setText("");
                    refreshInfo();
                    Toast.makeText(this, R.string.token_saved, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.token_invalid), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
