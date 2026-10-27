package az.zai.mobile;

import android.content.Context;
import android.text.method.LinkMovementMethod;
import android.widget.TextView;

import io.noties.markwon.Markwon;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown render + reasoning bloklarının ayrışdırılması.
 * Model cavabında düşünmə <details type="reasoning">...</details> içində gəlir.
 */
public final class MarkdownUtil {

    private static final Pattern REASONING_CLOSED =
            Pattern.compile("<details[^>]*type=\"reasoning\"[^>]*>.*?</details>",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern REASONING_OPEN =
            Pattern.compile("<details[^>]*type=\"reasoning\"[^>]*>(.*)$",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern SUMMARY =
            Pattern.compile("<summary>.*?</summary>", Pattern.DOTALL);

    private static Markwon markwon;

    private MarkdownUtil() {
    }

    public static synchronized Markwon get(Context ctx) {
        if (markwon == null) {
            markwon = Markwon.create(ctx.getApplicationContext());
        }
        return markwon;
    }

    public static void render(Context ctx, TextView tv, String markdown) {
        get(ctx).setMarkdown(tv, markdown == null ? "" : markdown);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
    }

    /** Sətir-sətir görüntüləmə üçün: {reasoning, visible}. */
    public static String[] split(String content) {
        if (content == null) {
            return new String[]{"", ""};
        }
        String reasoning = "";
        String visible = content;

        Matcher mOpen = REASONING_OPEN.matcher(content);
        Matcher mClosed = REASONING_CLOSED.matcher(content);
        if (mClosed.find()) {
            reasoning = stripTags(mClosed.group());
            visible = mClosed.reset().replaceAll("");
        } else if (mOpen.find()) {
            reasoning = stripTags(mOpen.group(1));
            visible = content.substring(0, mOpen.start());
        }
        return new String[]{reasoning.trim(), visible.trim()};
    }

    private static String stripTags(String html) {
        String s = html.replaceAll("<summary>.*?</summary>", " ")
                .replaceAll("</?details[^>]*>", " ");
        return s.trim();
    }

    /** API-yə göndərilən tarixçədən reasoning bloklarını təmizləyir. */
    public static String stripReasoning(String content) {
        if (content == null) return "";
        String s = REASONING_CLOSED.matcher(content).replaceAll("");
        Matcher m = REASONING_OPEN.matcher(s);
        if (m.find()) {
            s = s.substring(0, m.start());
        }
        return s.trim();
    }
}
