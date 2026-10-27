package az.zai.mobile;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;

/** Əsas chat ekranı — tam nativ, WebView yoxdur. */
public class MainActivity extends AppCompatActivity implements MessagesAdapter.Listener {

    private RecyclerView recycler;
    private MessagesAdapter adapter;
    private EditText input;
    private ImageButton sendBtn;
    private TextView modelLabel;
    private View emptyState;

    private DbHelper db;
    private SharedPreferences prefs;

    private String token;
    private String userRole = "guest";
    private String model = ApiClient.DEFAULT_MODEL;
    private String modelName = "GLM-5.3-Flash";
    private String chatId;

    private final AtomicReference<Call> activeCall = new AtomicReference<>();
    private volatile boolean streaming = false;
    private String pendingQuestion;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = new DbHelper(this);
        prefs = getSharedPreferences("zai", MODE_PRIVATE);
        model = prefs.getString("model", ApiClient.DEFAULT_MODEL);
        modelName = prefs.getString("model_name", "GLM-5.3-Flash");
        token = prefs.getString("token", null);

        recycler = findViewById(R.id.recycler);
        input = findViewById(R.id.input);
        sendBtn = findViewById(R.id.btn_send);
        modelLabel = findViewById(R.id.model_label);
        emptyState = findViewById(R.id.empty_state);

        adapter = new MessagesAdapter(this);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        modelLabel.setText(modelName);
        modelLabel.setOnClickListener(v -> showModelPicker());

        findViewById(R.id.chip_write).setOnClickListener(v -> input.setText("Mənə bir mətn yaz: "));
        findViewById(R.id.chip_code).setOnClickListener(v -> input.setText("Bu üçün kod yaz: "));
        findViewById(R.id.chip_translate).setOnClickListener(v -> input.setText("İngiliscədən Azərbaycan dilinə tərcümə et: "));

        sendBtn.setOnClickListener(v -> {
            if (streaming) {
                stopStream();
            } else {
                send();
            }
        });

        // Başlıq düymələri
        findViewById(R.id.btn_new_chat).setOnClickListener(v -> {
            if (streaming) stopStream();
            openChat(null);
        });
        findViewById(R.id.btn_history).setOnClickListener(v ->
                startActivityForResult(new Intent(this, HistoryActivity.class), 101));
        findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                sendBtn.setImageResource(streaming
                        ? R.drawable.ic_stop
                        : (s.length() > 0 ? R.drawable.ic_send : R.drawable.ic_send));
                sendBtn.setAlpha(s.length() > 0 || streaming ? 1f : 0.45f);
            }
        });

        // İlk girişdə auth (qonaq tokeni)
        if (token == null || token.isEmpty()) {
            authAsync();
        }
        openChat(null);
    }

    private void authAsync() {
        new Thread(() -> {
            try {
                ApiClient.AuthResult a = ApiClient.obtainAuth(token);
                runOnUiThread(() -> {
                    token = a.token;
                    userRole = a.role;
                    prefs.edit()
                            .putString("token", a.token)
                            .putString("user_name", a.name)
                            .putString("user_role", a.role)
                            .apply();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.auth_failed), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void openChat(String id) {
        chatId = id;
        adapter.clear();
        adapter.resetExpanded();
        if (id != null) {
            List<DbHelper.MsgRow> msgs = db.getMessages(id);
            for (DbHelper.MsgRow r : msgs) {
                MessagesAdapter.UiMessage m = new MessagesAdapter.UiMessage();
                m.role = r.role;
                m.content = r.content;
                adapter.add(m);
            }
        }
        updateEmptyState();
    }

    private void updateEmptyState() {
        boolean empty = adapter.items().isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (!empty) {
            recycler.scrollToPosition(adapter.items().size() - 1);
        }
    }

    private void send() {
        send(true);
    }

    private void send(boolean persistUser) {
        String text = input.getText().toString().trim();
        if (text.isEmpty() || streaming) return;
        if (token == null || token.isEmpty()) {
            authAsync();
            Toast.makeText(this, R.string.preparing, Toast.LENGTH_SHORT).show();
            return;
        }

        input.setText("");

        // Yeni chat lazımdırsa yarat
        if (chatId == null) {
            chatId = UUID.randomUUID().toString();
            String title = text.length() > 42 ? text.substring(0, 42) + "…" : text;
            db.createChat(chatId, title, model);
        }

        // İstifadəçi mesajı
        MessagesAdapter.UiMessage um = new MessagesAdapter.UiMessage();
        um.role = "user";
        um.content = text;
        adapter.add(um);
        if (persistUser) {
            db.insertMessage(chatId, "user", text);
        }
        updateEmptyState();

        // Boş köməkçi mesajı
        MessagesAdapter.UiMessage am = new MessagesAdapter.UiMessage();
        am.role = "assistant";
        am.content = "";
        am.streaming = true;
        adapter.add(am);
        recycler.scrollToPosition(adapter.items().size() - 1);

        setStreaming(true);
        pendingQuestion = text;

        // Tarixçəni topla (reasoning təmizlənmiş)
        List<String[]> history = new ArrayList<>();
        for (MessagesAdapter.UiMessage m : adapter.items()) {
            if (m == am) break;
            if (m.error || m.content == null || m.content.isEmpty()) continue;
            String content = "assistant".equals(m.role)
                    ? MarkdownUtil.stripReasoning(m.content) : m.content;
            if (content.isEmpty()) continue;
            history.add(new String[]{m.role, content});
        }

        ApiClient.streamChat(token, model, chatId, history, new ApiClient.StreamListener() {
            @Override
            public void onDelta(String fullContent) {
                adapter.updateLast(fullContent, true);
                scrollToBottom();
            }

            @Override
            public void onPhase(String phase) {
            }

            @Override
            public void onDone() {
                runOnUiThread(() -> {
                    MessagesAdapter.UiMessage last = lastAssistant();
                    adapter.finish();
                    setStreaming(false);
                    if (last != null && !last.error
                            && MarkdownUtil.stripReasoning(last.content).length() > 0) {
                        db.insertMessage(chatId, "assistant", last.content);
                    }
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    adapter.markError(message);
                    setStreaming(false);
                });
            }
        }, activeCall);
    }

    private void stopStream() {
        Call c = activeCall.get();
        if (c != null) {
            c.cancel();
        }
        runOnUiThread(() -> {
            adapter.finish();
            setStreaming(false);
            MessagesAdapter.UiMessage last = lastAssistant();
            if (last != null && !last.error
                    && MarkdownUtil.stripReasoning(last.content).length() > 0
                    && chatId != null) {
                db.insertMessage(chatId, "assistant", last.content);
            }
        });
    }

    private MessagesAdapter.UiMessage lastAssistant() {
        List<MessagesAdapter.UiMessage> items = adapter.items();
        if (items.isEmpty()) return null;
        MessagesAdapter.UiMessage last = items.get(items.size() - 1);
        return "assistant".equals(last.role) ? last : null;
    }

    @SuppressLint("NotifyDataSetChanged")
    private void setStreaming(boolean s) {
        streaming = s;
        sendBtn.setImageResource(s ? R.drawable.ic_stop : R.drawable.ic_send);
        sendBtn.setAlpha(s ? 1f : (input.length() > 0 ? 1f : 0.45f));
    }

    private void scrollToBottom() {
        RecyclerView.LayoutManager lm = recycler.getLayoutManager();
        if (lm instanceof LinearLayoutManager) {
            int last = ((LinearLayoutManager) lm).findLastVisibleItemPosition();
            if (last >= adapter.items().size() - 3) {
                recycler.scrollToPosition(adapter.items().size() - 1);
            }
        }
    }

    private void showModelPicker() {
        new Thread(() -> {
            try {
                List<ApiClient.ModelInfo> models = ApiClient.getModels(token);
                runOnUiThread(() -> {
                    final List<ApiClient.ModelInfo> list = models;
                    String[] names = new String[list.size()];
                    final boolean[] locked = new boolean[list.size()];
                    boolean guest = "guest".equals(userRole);
                    for (int i = 0; i < list.size(); i++) {
                        ApiClient.ModelInfo m = list.get(i);
                        locked[i] = guest && !ApiClient.DEFAULT_MODEL.equals(m.id);
                        names[i] = (locked[i] ? "🔒 " : "● ") + m.name;
                    }
                    int selected = -1;
                    for (int i = 0; i < list.size(); i++) {
                        if (list.get(i).id.equals(model)) selected = i;
                    }
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.select_model)
                            .setSingleChoiceItems(names, selected, (d, which) -> {
                                if (locked[which]) {
                                    Toast.makeText(this, R.string.model_locked,
                                            Toast.LENGTH_LONG).show();
                                    return;
                                }
                                model = list.get(which).id;
                                modelName = list.get(which).name;
                                modelLabel.setText(modelName);
                                prefs.edit()
                                        .putString("model", model)
                                        .putString("model_name", modelName)
                                        .apply();
                                d.dismiss();
                            })
                            .setPositiveButton(R.string.close, null)
                            .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.models_failed) + e.getMessage(),
                        Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    @Override
    public void onCopy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Z.ai", text));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRetry() {
        if (pendingQuestion == null || streaming) return;
        // Xətalı son mesajı silib yenidən göndər
        List<MessagesAdapter.UiMessage> items = adapter.items();
        if (!items.isEmpty()) {
            items.remove(items.size() - 1);
            adapter.notifyDataSetChanged();
        }
        String q = pendingQuestion;
        // istifadəçi mesajı da silinməlidirsə: saxla
        if (!items.isEmpty()) {
            MessagesAdapter.UiMessage lastUser = items.get(items.size() - 1);
            if ("user".equals(lastUser.role) && q.equals(lastUser.content)) {
                items.remove(items.size() - 1);
                adapter.notifyDataSetChanged();
            }
        }
        input.setText(q);
        send(false);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 101 && resultCode == RESULT_OK && data != null) {
            String id = data.getStringExtra("chat_id");
            if (id != null) {
                openChat(id);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Settings-də token dəyişə bilər
        String t = prefs.getString("token", null);
        if (t != null && !t.equals(token)) {
            token = t;
            userRole = prefs.getString("user_role", "guest");
        } else if (t == null && token != null) {
            token = null;
            userRole = "guest";
        }
    }

    @Override
    protected void onDestroy() {
        Call c = activeCall.get();
        if (c != null) c.cancel();
        super.onDestroy();
    }
}
