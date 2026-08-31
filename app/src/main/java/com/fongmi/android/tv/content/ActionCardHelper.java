package com.fongmi.android.tv.content;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.event.ActionCardEvent;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.ui.web.GameWebActivity;
import com.github.catvod.net.OkHttp;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 实验室：影视+ VodPlus「动作卡片」执行器。
 *
 * 兼容上游 Vod.action 原生字段与 vod_tag:'action' + vod_id JSON 两种协议，
 * 由 TypeFragment.onItemClick 的 isAction 分支调用，替代上游只 Toast msg 的简陋处理。
 *
 * 支持的弹窗动作类型（type 字段）：
 *  - browser / webview → 内置 WebView 打开（GameWebActivity）
 *  - input             → 单项输入（链式输入，确认后把输入回传 spider）
 *  - msgbox            → 消息弹窗（标题 + 富文本 + 可选图片）
 *  - multiInput        → 多项输入（文本/下拉/快速选择/日期/文件夹/文件/多行/密码）
 *  - edit              → 多行编辑
 *  - menu              → 单选菜单
 *  - select            → 多选菜单
 *  - help              → 使用帮助（键值说明）
 *
 * 流程：点击动作卡 → 自包含 browser/webview 卡本地直接打开；
 *       其余弹窗类型本地直接渲染（卡片即规格），确认后把结果回传 spider.action；
 *       spider 也可直接返回上述 type 的响应，由 dispatch 渲染（嵌套在 action 字段里亦可）。
 */
public final class ActionCardHelper {

    private static final ExecutorService executor = Executors.newFixedThreadPool(2);
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** 点击动作卡片入口：siteKey 为站点 key，actionJson 为卡片 vod_id JSON 或原生 action 字段内容。 */
    public static void handleAction(Activity activity, String siteKey, String actionJson) {
        if (activity == null || TextUtils.isEmpty(actionJson)) return;
        JsonObject card = parse(actionJson);
        if (card != null) {
            // 自包含 browser/webview 卡：本地直接打开内置 WebView（JS 爬虫未实现 action()，
            // 回传只会得到 null，表现为「动作无响应」）。
            String web = webUrl(card);
            if (web != null) {
                openWeb(activity, card, web);
                return;
            }
            // 其余弹窗类型：卡片即规格，本地直接渲染。
            String type = stringOf(card, "type");
            if (isDialogType(type)) {
                renderDialog(activity, siteKey, card);
                return;
            }
        }
        // 非 JSON 卡片（如动态弹窗仅含 actionId 字符串）/ 未知类型 / 需 spider 决策的：
        // 异步调 spider.action 再分发响应。
        submit(activity, siteKey, actionJson);
    }

    /** 异步调 spider.action 并分发响应（回传专用，避免 input 回传被本地判定拦截成死循环）。 */
    private static void submit(Activity activity, String siteKey, String actionJson) {
        executor.execute(() -> {
            String resp = callSpider(siteKey, actionJson);
            main.post(() -> dispatch(activity, siteKey, resp));
        });
    }

    /** 补全协议头：裸域名（如 baidu.com）按 https:// 处理。 */
    private static String normalizeUrl(String url) {
        String u = url == null ? "" : url.trim();
        if (!u.isEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        return u;
    }

    private static String webUrl(JsonObject obj) {
        String type = stringOf(obj, "type");
        if (("browser".equals(type) || "webview".equals(type)) && hasUrl(obj)) {
            return normalizeUrl(stringOf(obj, "url"));
        }
        return null;
    }

    /* ---------------- spider 调用 ---------------- */

    private static String callSpider(String siteKey, String actionJson) {
        try {
            Site site = VodConfig.get().getSite(siteKey);
            if (site == null) return "";
            if (site.getType() == 3) return site.recent().spider().action(actionJson);
            if (site.getType() == 4) return OkHttp.string(actionJson);
        } catch (Throwable e) {
            return error(e.getMessage());
        }
        return "";
    }

    private static String error(String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("msg", TextUtils.isEmpty(message) ? "动作执行失败" : message);
        return obj.toString();
    }

    /* ---------------- 响应分发 ---------------- */

    private static void dispatch(Activity activity, String siteKey, String resp) {
        if (activity == null || activity.isFinishing()) return;
        JsonObject obj = parse(resp);
        if (obj == null) {
            // 纯文本响应（脚本直接返回字符串）：直接提示，而非「动作无响应」。
            if (!TextUtils.isEmpty(resp)) {
                Toast.makeText(activity, resp, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(activity, "动作无响应", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        // 嵌套变体：{"action":{...}}（py 端 toast/弹窗响应）→ 取内层对象分发。
        JsonElement nested = obj.get("action");
        if (nested != null && nested.isJsonObject()) obj = nested.getAsJsonObject();
        // 1. browser / webview
        String web = webUrl(obj);
        if (web != null) {
            openWeb(activity, obj, web);
            return;
        }
        // 2. 其它弹窗类型（input / msgbox / multiInput / edit / menu / select / help）
        String type = stringOf(obj, "type");
        if (isDialogType(type)) {
            renderDialog(activity, siteKey, obj);
            return;
        }
        // 3. 内置指令：__copy__ / __refresh_list__ / __self_search__ / __detail__
        String aid = stringOf(obj, "actionId");
        if (aid != null) {
            if ("__copy__".equals(aid)) { handleCopy(activity, obj); return; }
            if ("__refresh_list__".equals(aid)) {
                ActionCardEvent.refresh();
                toast(activity, obj, "列表已刷新");
                return;
            }
            if ("__self_search__".equals(aid)) {
                String kw = emptyTo(stringOf(obj, "tid"), stringOf(obj, "keyword"));
                if (TextUtils.isEmpty(kw)) { Toast.makeText(activity, "搜索内容为空", Toast.LENGTH_SHORT).show(); return; }
                ActionCardEvent.search(kw);
                toast(activity, obj, "正在全局搜索：" + kw);
                return;
            }
            if ("__detail__".equals(aid)) {
                String ids = emptyTo(stringOf(obj, "ids"), stringOf(obj, "url"));
                if (TextUtils.isEmpty(ids)) { Toast.makeText(activity, "跳转内容为空", Toast.LENGTH_SHORT).show(); return; }
                ActionCardEvent.play(ids);
                return;
            }
        }
        // 3. 结果列表：对话框显示
        JsonElement list = obj.get("list");
        if (list != null && list.isJsonArray() && list.getAsJsonArray().size() > 0) {
            showResult(activity, list);
            return;
        }
        // 4. msg 提示
        String msg = stringOf(obj, "msg");
        Toast.makeText(activity, TextUtils.isEmpty(msg) ? "操作完成" : msg, Toast.LENGTH_LONG).show();
    }

    /* ---------------- 内置指令处理 ---------------- */

    /** 复制到剪贴板。 */
    private static void handleCopy(Activity activity, JsonObject obj) {
        String content = emptyTo(stringOf(obj, "content"), stringOf(obj, "text"));
        if (!TextUtils.isEmpty(content)) {
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("action", content));
        }
        toast(activity, obj, "已复制到剪贴板");
    }

    /** 优先用响应里的 toast 字段提示，否则用 fallback。 */
    private static void toast(Activity activity, JsonObject obj, String fallback) {
        String t = stringOf(obj, "toast");
        Toast.makeText(activity, TextUtils.isEmpty(t) ? fallback : t, Toast.LENGTH_SHORT).show();
    }

    /** 统一渲染入口：按 type 分派到具体弹窗。 */
    private static void renderDialog(Activity activity, String siteKey, JsonObject spec) {
        String type = stringOf(spec, "type");
        switch (type == null ? "" : type) {
            case "input":      showInput(activity, siteKey, spec); break;
            case "msgbox":     showMsgBox(activity, spec); break;
            case "multiInput": showMultiInput(activity, siteKey, spec); break;
            case "edit":       showEdit(activity, siteKey, spec); break;
            case "menu":       showMenu(activity, siteKey, spec); break;
            case "select":     showSelect(activity, siteKey, spec); break;
            case "help":       showHelp(activity, spec); break;
            default: {
                String msg = stringOf(spec, "msg");
                Toast.makeText(activity, TextUtils.isEmpty(msg) ? "操作完成" : msg, Toast.LENGTH_LONG).show();
            }
        }
    }

    private static boolean isDialogType(String type) {
        if (type == null) return false;
        switch (type) {
            case "browser":
            case "webview":
            case "input":
            case "msgbox":
            case "multiInput":
            case "edit":
            case "menu":
            case "select":
            case "help":
                return true;
            default:
                return false;
        }
    }

    private static void openWeb(Activity activity, JsonObject spec, String url) {
        String title = emptyTo(stringOf(spec, "title"), "");
        GameWebActivity.start(activity, url, title, null, null);
    }

    /* ---------------- 回传 spider ---------------- */

    /** 把弹窗结果回传给 spider.action：包装 {action, actionId, value}，兼容读 action / actionId 两种脚本。 */
    private static void callback(Activity activity, String siteKey, JsonObject spec, JsonElement value) {
        String id = actionIdOf(spec);
        JsonObject payload = new JsonObject();
        for (Map.Entry<String, JsonElement> e : spec.entrySet()) {
            if ("value".equals(e.getKey())) continue; // 由下方覆盖
            payload.add(e.getKey(), e.getValue());
        }
        payload.addProperty("action", id);
        payload.addProperty("actionId", id);
        payload.add("value", value);
        submit(activity, siteKey, payload.toString());
    }

    private static String actionIdOf(JsonObject spec) {
        String a = stringOf(spec, "actionId");
        if (a == null) a = stringOf(spec, "action");
        return a == null ? "" : a;
    }

    /* ---------------- 单项输入 ---------------- */

    private static void showInput(Activity activity, String siteKey, JsonObject input) {
        String title = emptyTo(stringOf(input, "title"), "请输入");
        String tip = stringOf(input, "tip");
        String value = stringOf(input, "value");
        String selectData = stringOf(input, "selectData");

        ScrollView scroll = scrollView(activity);
        LinearLayout body = scrollBody(scroll);
        final ValueGetter[] getter = {null};

        // 带图输入（如「单项输入带图」）：渲染顶部图片。
        String imageUrl = stringOf(input, "imageUrl");
        int imageHeight = intOf(input, "imageHeight", 0);
        if (!TextUtils.isEmpty(imageUrl)) {
            ImageView img = new ImageView(activity);
            int h = imageHeight > 0
                    ? (int) (imageHeight * activity.getResources().getDisplayMetrics().density)
                    : ViewGroup.LayoutParams.WRAP_CONTENT;
            img.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
            img.setAdjustViewBounds(true);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setPadding(0, 0, 0, 16);
            body.addView(img);
            Glide.with(activity).load(imageUrl).into(img);
        }

        if (!TextUtils.isEmpty(selectData)) {
            // 带 selectData 的输入（如下拉/快速选择/文件夹/文件/日期）：复用 multiInput 字段构造。
            JsonObject f = new JsonObject();
            f.addProperty("id", emptyTo(stringOf(input, "id"), "value"));
            f.addProperty("name", title);
            f.addProperty("tip", tip);
            f.addProperty("value", value);
            f.addProperty("selectData", selectData);
            f.addProperty("inputType", intOf(input, "inputType", 0));
            getter[0] = buildField(activity, body, f);
        } else {
            EditText edit = editText(activity, value, tip, false, 1);
            body.addView(edit, fillWidth());
            final EditText fet = edit;
            getter[0] = () -> fet.getText().toString().trim();
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = getter[0].get().trim();
            if (TextUtils.isEmpty(text)) {
                Toast.makeText(activity, "内容不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            callback(activity, siteKey, input, new JsonPrimitive(text));
        });
    }

    /* ---------------- 消息弹窗 ---------------- */

    private static void showMsgBox(Activity activity, JsonObject spec) {
        String title = emptyTo(stringOf(spec, "title"), "消息");
        String html = stringOf(spec, "htmlMsg");
        if (TextUtils.isEmpty(html)) html = stringOf(spec, "msg");
        if (TextUtils.isEmpty(html)) html = stringOf(spec, "text");
        String imageUrl = stringOf(spec, "imageUrl");

        ScrollView scroll = scrollView(activity);
        LinearLayout body = scrollBody(scroll);

        if (!TextUtils.isEmpty(html)) {
            TextView msg = new TextView(activity);
            msg.setTextSize(15);
            msg.setText(Html.fromHtml(html));
            body.addView(msg, fillWidth());
        }
        if (!TextUtils.isEmpty(imageUrl)) {
            ImageView img = new ImageView(activity);
            img.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            img.setAdjustViewBounds(true);
            img.setPadding(0, 16, 0, 0);
            body.addView(img);
            Glide.with(activity).load(imageUrl).into(img);
        }

        new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /* ---------------- 多项输入 ---------------- */

    private static void showMultiInput(Activity activity, String siteKey, JsonObject spec) {
        String title = emptyTo(stringOf(spec, "title"), "多项输入");
        String msg = stringOf(spec, "msg");

        ScrollView scroll = scrollView(activity);
        LinearLayout body = scrollBody(scroll);
        if (!TextUtils.isEmpty(msg)) {
            TextView hint = new TextView(activity);
            hint.setTextSize(13);
            hint.setTextColor(0xFF9E9E9E);
            hint.setText(msg.replace("\\n", "\n"));
            body.addView(hint, fillWidth());
        }

        JsonElement inputs = spec.get("input");
        // id -> 取值器，保持顺序
        Map<String, ValueGetter> getters = new LinkedHashMap<>();
        if (inputs != null && inputs.isJsonArray()) {
            for (JsonElement el : inputs.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject f = el.getAsJsonObject();
                String id = stringOf(f, "id");
                if (TextUtils.isEmpty(id)) continue;
                ValueGetter g = buildField(activity, body, f);
                getters.put(id, g);
            }
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            JsonObject result = new JsonObject();
            for (Map.Entry<String, ValueGetter> e : getters.entrySet()) {
                result.addProperty(e.getKey(), e.getValue().get());
            }
            dialog.dismiss();
            callback(activity, siteKey, spec, result);
        });
    }

    /** 构建单个 multiInput 字段，返回取值器。 */
    private static ValueGetter buildField(Activity activity, LinearLayout body, JsonObject f) {
        String name = stringOf(f, "name");
        String tip = stringOf(f, "tip");
        String value = stringOf(f, "value");
        String selectData = stringOf(f, "selectData");
        int multiLine = intOf(f, "multiLine", 0);
        int inputType = intOf(f, "inputType", 0);
        int imageHeight = intOf(f, "imageHeight", 0);

        String title = name == null ? "" : name;
        String desc = "";
        int nl = title.indexOf('\n');
        if (nl >= 0) { desc = title.substring(nl + 1); title = title.substring(0, nl); }

        // 字段容器
        LinearLayout field = new LinearLayout(activity);
        field.setOrientation(LinearLayout.VERTICAL);
        field.setLayoutParams(fillWidth());
        field.setPadding(0, 0, 0, 16);

        if (!TextUtils.isEmpty(title)) {
            TextView tv = new TextView(activity);
            tv.setTextSize(14);
            tv.setTypeface(null, Typeface.BOLD);
            tv.setText(title.replace("\\n", " "));
            field.addView(tv, fillWidth());
        }
        if (!TextUtils.isEmpty(desc)) {
            TextView dv = new TextView(activity);
            dv.setTextSize(12);
            dv.setTextColor(0xFF9E9E9E);
            dv.setText(desc);
            field.addView(dv, fillWidth());
        }

        final ValueGetter[] getter = {null};

        if (!TextUtils.isEmpty(selectData)) {
            if (selectData.startsWith("[folder]") || selectData.startsWith("[file]")) {
                boolean isFolder = selectData.startsWith("[folder]");
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setLayoutParams(fillWidth());
                EditText et = editText(activity, value, tip, false, 1);
                et.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                Button pick = new Button(activity);
                pick.setText("浏览");
                pick.setOnClickListener(v -> showPathPicker(activity, isFolder, et.getText().toString(), et::setText));
                row.addView(et);
                row.addView(pick);
                field.addView(row, fillWidth());
                final EditText fet = et;
                getter[0] = () -> fet.getText().toString().trim();
            } else if (selectData.startsWith("[calendar]")) {
                EditText et = editText(activity, value, tip, false, 1);
                bindDatePicker(activity, et, value);
                field.addView(et, fillWidth());
                final EditText fet = et;
                getter[0] = () -> fet.getText().toString().trim();
            } else if (selectData.contains(":=")) {
                // 快速选择：label:=value,...
                List<String> labels = new ArrayList<>();
                Map<String, String> map = new LinkedHashMap<>();
                for (String part : selectData.split(",")) {
                    int idx = part.indexOf(":=");
                    if (idx < 0) { labels.add(part); map.put(part, part); }
                    else { String l = part.substring(0, idx); String v = part.substring(idx + 2); labels.add(l); map.put(l, v); }
                }
                Spinner sp = spinner(activity, labels, value);
                field.addView(sp, fillWidth());
                getter[0] = () -> { Object sel = sp.getSelectedItem(); return sel == null ? "" : map.get(sel.toString()); };
            } else if (selectData.startsWith("[")) {
                // 下拉：[占位]选项A,选项B,...
                int end = selectData.indexOf(']');
                String opts = end >= 0 ? selectData.substring(end + 1) : selectData;
                List<String> labels = new ArrayList<>(Arrays.asList(opts.split(",")));
                Spinner sp = spinner(activity, labels, value);
                field.addView(sp, fillWidth());
                getter[0] = () -> { Object sel = sp.getSelectedItem(); return sel == null ? "" : sel.toString(); };
            } else {
                EditText et = editText(activity, value, tip, false, 1);
                field.addView(et, fillWidth());
                final EditText fet = et;
                getter[0] = () -> fet.getText().toString().trim();
            }
        } else {
            boolean password = (inputType == 129);
            EditText et = editText(activity, value, tip, password, multiLine > 1 ? multiLine : 1);
            if (password) et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            field.addView(et, fillWidth());
            final EditText fet = et;
            getter[0] = () -> fet.getText().toString().trim();
        }

        body.addView(field);
        return getter[0] == null ? () -> "" : getter[0];
    }

    /* ---------------- 多行编辑 ---------------- */

    private static void showEdit(Activity activity, String siteKey, JsonObject spec) {
        String title = emptyTo(stringOf(spec, "title"), "编辑");
        String tip = stringOf(spec, "tip");
        String value = stringOf(spec, "value");
        int height = intOf(spec, "height", 0);

        ScrollView scroll = scrollView(activity);
        LinearLayout body = scrollBody(scroll);
        if (!TextUtils.isEmpty(tip)) {
            TextView hint = new TextView(activity);
            hint.setTextSize(13);
            hint.setTextColor(0xFF9E9E9E);
            hint.setText(tip.replace("\\n", "\n"));
            body.addView(hint, fillWidth());
        }
        EditText edit = new EditText(activity);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        edit.setGravity(Gravity.TOP);
        edit.setMinLines(height > 0 ? Math.max(3, height / 40) : 6);
        if (!TextUtils.isEmpty(value)) edit.setText(value.replace("\\n", "\n"));
        body.addView(edit, fillWidth());

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            dialog.dismiss();
            callback(activity, siteKey, spec, new JsonPrimitive(edit.getText().toString()));
        });
    }

    /* ---------------- 单选菜单 ---------------- */

    private static void showMenu(Activity activity, String siteKey, JsonObject spec) {
        String title = emptyTo(stringOf(spec, "title"), "请选择");
        int selected = intOf(spec, "selectedIndex", 0);

        ScrollView scroll = scrollView(activity);
        LinearLayout body = scrollBody(scroll);
        RadioGroup group = new RadioGroup(activity);
        group.setLayoutParams(fillWidth());
        List<String[]> options = parseOptions(spec.get("option"));
        List<String> actions = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            String[] o = options.get(i);
            actions.add(o[1]);
            RadioButton rb = new RadioButton(activity);
            rb.setText(o[0]);
            rb.setId(i);
            group.addView(rb, fillWidth());
        }
        body.addView(group, fillWidth());
        if (selected >= 0 && selected < options.size()) group.check(selected);

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int checked = group.getCheckedRadioButtonId();
            if (checked < 0) { Toast.makeText(activity, "请选择一项", Toast.LENGTH_SHORT).show(); return; }
            String action = actions.get(checked);
            dialog.dismiss();
            callback(activity, siteKey, spec, new JsonPrimitive(action));
        });
    }

    /* ---------------- 多选菜单 ---------------- */

    private static void showSelect(Activity activity, String siteKey, JsonObject spec) {
        String title = emptyTo(stringOf(spec, "title"), "多选");

        ScrollView scroll = scrollView(activity);
        LinearLayout body = scrollBody(scroll);
        List<CheckBox> boxes = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        JsonElement opts = spec.get("option");
        if (opts != null && opts.isJsonArray()) {
            for (JsonElement el : opts.getAsJsonArray()) {
                String name = "";
                String action = "";
                boolean sel = false;
                if (el.isJsonObject()) {
                    JsonObject o = el.getAsJsonObject();
                    name = stringOf(o, "name");
                    action = stringOf(o, "action");
                    sel = boolOf(o, "selected", false);
                } else {
                    String[] p = parseOption(el);
                    name = p[0]; action = p[1];
                }
                if (TextUtils.isEmpty(action)) action = name;
                CheckBox cb = new CheckBox(activity);
                cb.setText(name);
                cb.setChecked(sel);
                body.addView(cb, fillWidth());
                boxes.add(cb);
                actions.add(action);
            }
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            JsonArray result = new JsonArray();
            for (int i = 0; i < boxes.size(); i++) {
                if (boxes.get(i).isChecked()) result.add(actions.get(i));
            }
            dialog.dismiss();
            callback(activity, siteKey, spec, result);
        });
    }

    /* ---------------- 使用帮助 ---------------- */

    private static void showHelp(Activity activity, JsonObject spec) {
        String title = emptyTo(stringOf(spec, "title"), "使用帮助");
        JsonElement data = spec.get("data");

        ScrollView scroll = scrollView(activity);
        LinearLayout body = scrollBody(scroll);
        if (data != null && data.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : data.getAsJsonObject().entrySet()) {
                String key = e.getKey();
                String val = stringOf(data.getAsJsonObject(), key);
                if (val == null && e.getValue().isJsonPrimitive()) val = e.getValue().getAsString();
                TextView k = new TextView(activity);
                k.setTextSize(14);
                k.setTypeface(null, Typeface.BOLD);
                k.setText(key);
                body.addView(k, fillWidth());
                TextView v = new TextView(activity);
                v.setTextSize(14);
                v.setText(TextUtils.isEmpty(val) ? "" : val.replace("\\n", "\n"));
                v.setPadding(0, 4, 0, 16);
                body.addView(v, fillWidth());
            }
        } else {
            TextView t = new TextView(activity);
            t.setText("暂无帮助内容");
            body.addView(t, fillWidth());
        }

        new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /* ---------------- 结果展示 ---------------- */

    private static void showResult(Activity activity, JsonElement list) {
        StringBuilder sb = new StringBuilder();
        for (JsonElement el : list.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject vod = el.getAsJsonObject();
            String name = stringOf(vod, "vod_name");
            if (TextUtils.isEmpty(name)) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(name);
            String remarks = stringOf(vod, "vod_remarks");
            if (!TextUtils.isEmpty(remarks)) sb.append("\n").append(remarks);
        }
        if (sb.length() == 0) sb.append("操作完成");
        new MaterialAlertDialogBuilder(activity)
                .setTitle("执行结果")
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /* ---------------- 日期 / 路径选择 ---------------- */

    private static void bindDatePicker(Activity activity, EditText edit, String current) {
        if (TextUtils.isEmpty(current)) {
            Calendar c = Calendar.getInstance();
            current = String.format(Locale.getDefault(), "%04d-%02d-%02d",
                    c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        }
        final String def = current;
        edit.setFocusable(false);
        edit.setOnClickListener(v -> {
            Calendar c = Calendar.getInstance();
            try {
                String[] p = def.split("-");
                c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
            } catch (Throwable ignore) { }
            new DatePickerDialog(activity, (view, y, m, d) -> {
                String s = String.format(Locale.getDefault(), "%04d-%02d-%02d", y, m + 1, d);
                edit.setText(s);
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
        });
        if (TextUtils.isEmpty(edit.getText())) edit.setText(def);
    }

    /** 轻量文件/文件夹选择器（基于 java.io.File，从 /storage/emulated/0 起）。 */
    private static void showPathPicker(Activity activity, boolean isFolder, String start, final PathPick onPick) {
        final File[] cur = {resolveStart(start, isFolder)};
        ScrollView scroll = scrollView(activity);
        LinearLayout body = scrollBody(scroll);

        TextView pathView = new TextView(activity);
        pathView.setTextSize(13);
        pathView.setTextColor(0xFF9E9E9E);
        body.addView(pathView, fillWidth());

        ListView list = new ListView(activity);
        list.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 420));
        body.addView(list, fillWidth());

        final ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1);
        list.setAdapter(adapter);

        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                pathView.setText(cur[0].getAbsolutePath());
                adapter.clear();
                adapter.add(isFolder ? "✓ 选择此目录" : "（点击文件选择）");
                File[] files = cur[0].listFiles();
                if (files != null) {
                    Arrays.sort(files, (a, b) -> {
                        if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                        return a.getName().compareToIgnoreCase(b.getName());
                    });
                    for (File f : files) {
                        if (f.isDirectory()) adapter.add("📁 " + f.getName());
                        else if (!isFolder) adapter.add("📄 " + f.getName());
                    }
                }
                adapter.notifyDataSetChanged();
            }
        };

        list.setOnItemClickListener((parent, view, position, id) -> {
            String item = adapter.getItem(position);
            if (position == 0) { // 选择当前目录 / 提示
                if (isFolder) onPick.pick(cur[0].getAbsolutePath());
                return;
            }
            File target = new File(cur[0], item.substring(2).trim());
            if (target.isDirectory()) { cur[0] = target; refresh.run(); }
            else if (!isFolder) onPick.pick(target.getAbsolutePath());
        });

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle(isFolder ? "选择文件夹" : "选择文件")
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null);
        if (isFolder) builder.setPositiveButton("选择当前目录", (d, w) -> onPick.pick(cur[0].getAbsolutePath()));
        builder.show();
        refresh.run();
    }

    private static File resolveStart(String start, boolean isFolder) {
        if (!TextUtils.isEmpty(start)) {
            File f = new File(start);
            if (f.exists()) return f.isDirectory() ? f : f.getParentFile();
        }
        File root = new File("/storage/emulated/0");
        return root.exists() ? root : new File("/sdcard");
    }

    private interface PathPick { void pick(String path); }


    /* ---------------- 视图构造工具 ---------------- */

    private static ScrollView scrollView(Activity activity) {
        ScrollView sv = new ScrollView(activity);
        sv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private static LinearLayout scrollBody(ScrollView sv) {
        LinearLayout body = new LinearLayout(sv.getContext());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.setPadding(24, 12, 24, 12);
        sv.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return body;
    }

    private static EditText editText(Activity activity, String value, String hint, boolean password, int minLines) {
        EditText et = new EditText(activity);
        et.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : 0));
        if (minLines > 1) {
            et.setMinLines(minLines);
            et.setGravity(Gravity.TOP);
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : 0));
        }
        if (!TextUtils.isEmpty(value)) et.setText(value);
        if (!TextUtils.isEmpty(hint)) et.setHint(hint.replace("\\n", "\n"));
        return et;
    }

    private static Spinner spinner(Activity activity, List<String> labels, String selected) {
        Spinner sp = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(adapter);
        if (!TextUtils.isEmpty(selected)) {
            int idx = labels.indexOf(selected);
            if (idx >= 0) sp.setSelection(idx);
        }
        return sp;
    }

    private static LinearLayout.LayoutParams fillWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 解析 option 数组元素：[name, action]；支持 {name,action} 或 "name$action" 字符串。 */
    private static List<String[]> parseOptions(JsonElement opts) {
        List<String[]> result = new ArrayList<>();
        if (opts != null && opts.isJsonArray()) {
            for (JsonElement el : opts.getAsJsonArray()) result.add(parseOption(el));
        }
        return result;
    }

    private static String[] parseOption(JsonElement el) {
        if (el != null && el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            return new String[]{ stringOf(o, "name"), stringOf(o, "action") };
        }
        String s = el == null ? "" : el.getAsString();
        int idx = s.lastIndexOf('$');
        if (idx >= 0) return new String[]{ s.substring(0, idx), s.substring(idx + 1) };
        return new String[]{ s, s };
    }

    private interface ValueGetter { String get(); }

    /* ---------------- JSON 工具 ---------------- */

    private static String stringOf(JsonObject obj, String key) {
        if (obj == null) return null;
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) return null;
        String value = el.getAsString();
        return value == null ? null : value.trim();
    }

    private static int intOf(JsonObject obj, String key, int fallback) {
        if (obj == null) return fallback;
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) return fallback;
        try { return el.getAsInt(); } catch (Throwable e) { return fallback; }
    }

    private static boolean boolOf(JsonObject obj, String key, boolean fallback) {
        if (obj == null) return fallback;
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) return fallback;
        try { return el.getAsBoolean(); } catch (Throwable e) { return fallback; }
    }

    private static boolean hasUrl(JsonObject obj) {
        return !TextUtils.isEmpty(stringOf(obj, "url"));
    }

    private static String emptyTo(String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    private static JsonObject parse(String json) {
        if (TextUtils.isEmpty(json)) return null;
        String s = json.trim();
        if (!s.startsWith("{")) return null;
        try {
            JsonElement el = JsonParser.parseString(s);
            if (el != null && el.isJsonObject()) return el.getAsJsonObject();
        } catch (Throwable ignore) {
        }
        return null;
    }
}
