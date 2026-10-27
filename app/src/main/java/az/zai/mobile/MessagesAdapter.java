package az.zai.mobile;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Mesaj siyahısı adapteri: istifadəçi (sağ) + köməkçi (sol) qabarcıqları. */
public class MessagesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_USER = 0;
    private static final int TYPE_ASSISTANT = 1;

    public static class UiMessage {
        public String role;        // "user" | "assistant"
        public String content;     // tam mətn (reasoning daxil)
        public boolean streaming;
        public boolean error;
        public String errorText;
    }

    public interface Listener {
        void onCopy(String text);

        void onRetry();
    }

    private final List<UiMessage> items = new ArrayList<>();
    private final Listener listener;
    private boolean expandedThinking = false;

    public MessagesAdapter(Listener listener) {
        this.listener = listener;
    }

    public void resetExpanded() {
        expandedThinking = false;
    }

    public List<UiMessage> items() {
        return items;
    }

    public void add(UiMessage m) {
        items.add(m);
        notifyItemInserted(items.size() - 1);
    }

    public void updateLast(String content, boolean streaming) {
        if (items.isEmpty()) return;
        UiMessage m = items.get(items.size() - 1);
        m.content = content;
        m.streaming = streaming;
        notifyItemChanged(items.size() - 1);
    }

    public void markError(String message) {
        if (items.isEmpty()) return;
        UiMessage m = items.get(items.size() - 1);
        m.streaming = false;
        m.error = true;
        m.errorText = message;
        notifyItemChanged(items.size() - 1);
    }

    public void finish() {
        if (items.isEmpty()) return;
        UiMessage m = items.get(items.size() - 1);
        m.streaming = false;
        notifyItemChanged(items.size() - 1);
    }

    public void clear() {
        items.clear();
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return "user".equals(items.get(position).role) ? TYPE_USER : TYPE_ASSISTANT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (type == TYPE_USER) {
            return new UserVh(inf.inflate(R.layout.item_message_user, parent, false));
        }
        return new AssistantVh(inf.inflate(R.layout.item_message_assistant, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        UiMessage m = items.get(position);
        if (holder instanceof UserVh) {
            UserVh h = (UserVh) holder;
            h.text.setText(m.content);
            h.itemView.setOnLongClickListener(v -> {
                if (listener != null) listener.onCopy(m.content);
                return true;
            });
        } else if (holder instanceof AssistantVh) {
            AssistantVh h = (AssistantVh) holder;
            render(h, m);
        }
    }

    private void render(AssistantVh h, UiMessage m) {
        String[] parts = MarkdownUtil.split(m.content);
        String reasoning = parts[0];
        String visible = parts[1];

        // Düşünmə bölməsi
        if (!reasoning.isEmpty()) {
            h.thinkingRow.setVisibility(View.VISIBLE);
            boolean streamingThink = m.streaming && visible.isEmpty();
            h.thinkingLabel.setText(streamingThink
                    ? h.itemView.getContext().getString(R.string.thinking)
                    : h.itemView.getContext().getString(R.string.thought));
            h.thinkingToggle.setOnClickListener(v -> {
                expandedThinking = !expandedThinking;
                notifyItemChanged(h.getBindingAdapterPosition());
            });
            if (expandedThinking) {
                h.thinkingBody.setVisibility(View.VISIBLE);
                h.thinkingBody.setText(reasoning);
            } else {
                h.thinkingBody.setVisibility(View.GONE);
            }
        } else if (m.streaming) {
            h.thinkingRow.setVisibility(View.VISIBLE);
            h.thinkingBody.setVisibility(View.GONE);
            h.thinkingLabel.setText(R.string.thinking);
            h.thinkingToggle.setOnClickListener(null);
        } else {
            h.thinkingRow.setVisibility(View.GONE);
            h.thinkingBody.setVisibility(View.GONE);
        }

        // Əsas məzmun
        if (m.error) {
            h.content.setVisibility(View.GONE);
            h.errorBox.setVisibility(View.VISIBLE);
            h.errorText.setText(m.errorText != null ? m.errorText : "Xəta");
        } else {
            h.content.setVisibility(View.VISIBLE);
            h.errorBox.setVisibility(View.GONE);
            if (visible.isEmpty() && m.streaming) {
                h.content.setText(R.string.waiting);
            } else {
                MarkdownUtil.render(h.itemView.getContext(), h.content, visible);
            }
        }

        h.copyBtn.setOnClickListener(v -> {
            if (listener != null) {
                listener.onCopy(MarkdownUtil.stripReasoning(m.content));
            }
        });
        h.retryBtn.setOnClickListener(v -> {
            if (listener != null) listener.onRetry();
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class UserVh extends RecyclerView.ViewHolder {
        final TextView text;

        UserVh(@NonNull View v) {
            super(v);
            text = v.findViewById(R.id.msg_text);
        }
    }

    static class AssistantVh extends RecyclerView.ViewHolder {
        final TextView content;
        final View thinkingRow;
        final TextView thinkingLabel;
        final View thinkingToggle;
        final TextView thinkingBody;
        final View errorBox;
        final TextView errorText;
        final ImageButton copyBtn;
        final View retryBtn;

        AssistantVh(@NonNull View v) {
            super(v);
            content = v.findViewById(R.id.msg_text);
            thinkingRow = v.findViewById(R.id.thinking_row);
            thinkingLabel = v.findViewById(R.id.thinking_label);
            thinkingToggle = v.findViewById(R.id.thinking_toggle);
            thinkingBody = v.findViewById(R.id.thinking_body);
            errorBox = v.findViewById(R.id.error_box);
            errorText = v.findViewById(R.id.error_text);
            copyBtn = v.findViewById(R.id.btn_copy);
            retryBtn = v.findViewById(R.id.btn_retry);
        }
    }
}
