package az.zai.mobile;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Söhbət tarixçəsi ekranı. */
public class HistoryActivity extends AppCompatActivity {

    private DbHelper db;
    private RecyclerView recycler;
    private TextView empty;
    private HistoryAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        db = new DbHelper(this);
        recycler = findViewById(R.id.recycler_history);
        empty = findViewById(R.id.history_empty);

        adapter = new HistoryAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.history_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        adapter.reload();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.Vh> {

        private List<DbHelper.ChatRow> rows;

        void reload() {
            rows = db.getChats();
            notifyDataSetChanged();
            empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        }

        @NonNull
        @Override
        public Vh onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Vh(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_chat_history, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Vh h, int position) {
            DbHelper.ChatRow row = rows.get(position);
            h.title.setText(row.title);
            SimpleDateFormat f = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());
            h.date.setText(f.format(new Date(row.updatedAt)));

            h.itemView.setOnClickListener(v -> {
                Intent data = new Intent();
                data.putExtra("chat_id", row.id);
                setResult(RESULT_OK, data);
                finish();
            });

            h.itemView.setOnLongClickListener(v -> {
                new MaterialAlertDialogBuilder(HistoryActivity.this)
                        .setTitle(R.string.delete_chat_title)
                        .setMessage(R.string.delete_chat_msg)
                        .setPositiveButton(R.string.delete, (d, w) -> {
                            db.deleteChat(row.id);
                            reload();
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return rows == null ? 0 : rows.size();
        }

        class Vh extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView date;

            Vh(@NonNull View v) {
                super(v);
                title = v.findViewById(R.id.chat_title);
                date = v.findViewById(R.id.chat_date);
            }
        }
    }
}
