package az.zai.mobile;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Lokal söhbət tarixçəsi (SQLite). */
public class DbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "zai_chats.db";
    private static final int DB_VERSION = 1;

    public static final String T_CHATS = "chats";
    public static final String T_MSGS = "messages";

    public DbHelper(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T_CHATS + " ("
                + "id TEXT PRIMARY KEY, "
                + "title TEXT NOT NULL, "
                + "model TEXT, "
                + "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE " + T_MSGS + " ("
                + "rid INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "chat_id TEXT NOT NULL, "
                + "role TEXT NOT NULL, "
                + "content TEXT NOT NULL, "
                + "ts INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_msgs_chat ON " + T_MSGS + "(chat_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        db.execSQL("DROP TABLE IF EXISTS " + T_MSGS);
        db.execSQL("DROP TABLE IF EXISTS " + T_CHATS);
        onCreate(db);
    }

    public void createChat(String id, String title, String model) {
        ContentValues v = new ContentValues();
        v.put("id", id);
        v.put("title", title == null || title.isEmpty() ? "Yeni söhbət" : title);
        v.put("model", model);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(T_CHATS, null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void touchChat(String id, String title) {
        ContentValues v = new ContentValues();
        v.put("updated_at", System.currentTimeMillis());
        if (title != null && !title.isEmpty()) {
            v.put("title", title);
        }
        getWritableDatabase().update(T_CHATS, v, "id=?", new String[]{id});
    }

    public void deleteChat(String id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(T_MSGS, "chat_id=?", new String[]{id});
        db.delete(T_CHATS, "id=?", new String[]{id});
    }

    public void deleteAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(T_MSGS, null, null);
        db.delete(T_CHATS, null, null);
    }

    public static class ChatRow {
        public String id;
        public String title;
        public long updatedAt;
    }

    public List<ChatRow> getChats() {
        List<ChatRow> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query(T_CHATS, null, null, null,
                null, null, "updated_at DESC");
        while (c.moveToNext()) {
            ChatRow r = new ChatRow();
            r.id = c.getString(c.getColumnIndexOrThrow("id"));
            r.title = c.getString(c.getColumnIndexOrThrow("title"));
            r.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
            out.add(r);
        }
        c.close();
        return out;
    }

    public void insertMessage(String chatId, String role, String content) {
        ContentValues v = new ContentValues();
        v.put("chat_id", chatId);
        v.put("role", role);
        v.put("content", content);
        v.put("ts", System.currentTimeMillis());
        getWritableDatabase().insert(T_MSGS, null, v);
        touchChat(chatId, null);
    }

    public static class MsgRow {
        public String role;
        public String content;
    }

    public List<MsgRow> getMessages(String chatId) {
        List<MsgRow> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query(T_MSGS,
                new String[]{"role", "content"},
                "chat_id=?", new String[]{chatId}, null, null, "rid ASC");
        while (c.moveToNext()) {
            MsgRow m = new MsgRow();
            m.role = c.getString(0);
            m.content = c.getString(1);
            out.add(m);
        }
        c.close();
        return out;
    }
}
