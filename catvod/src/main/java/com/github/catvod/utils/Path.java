package com.github.catvod.utils;

import android.os.Environment;

import com.github.catvod.Init;
import com.orhanobut.logger.Logger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Path {

    private static final String TAG = Path.class.getSimpleName();

    private static File mkdir(File file) {
        if (file == null || file.exists()) return file;
        if (file.mkdirs()) Logger.t(TAG).d("Created dir:" + file);
        return file;
    }

    public static boolean exists(String path) {
        return new File(path.replace("file://", "")).exists();
    }

    public static boolean exists(File file) {
        return file != null && file.exists() && file.length() > 0;
    }

    public static File root() {
        return Environment.getExternalStorageDirectory();
    }

    public static File cache() {
        return Init.context().getCacheDir();
    }

    public static File files() {
        return Init.context().getFilesDir();
    }

    public static String rootPath() {
        return root().getAbsolutePath();
    }

    public static File tv() {
        return mkdir(new File(root() + File.separator + "TV"));
    }

    public static File so() {
        return mkdir(new File(files() + File.separator + "so"));
    }

    public static File js() {
        return mkdir(new File(cache() + File.separator + "js"));
    }

    public static File py() {
        return mkdir(new File(cache() + File.separator + "py"));
    }

    public static File jar() {
        return mkdir(new File(cache() + File.separator + "jar"));
    }

    public static File exo() {
        return mkdir(new File(cache() + File.separator + "exo"));
    }

    public static File exoCache() {
        return exo();
    }

    public static File epg() {
        return mkdir(new File(cache() + File.separator + "epg"));
    }

    public static File jpa() {
        return mkdir(new File(cache() + File.separator + "jpa"));
    }

    public static File thunder() {
        return mkdir(new File(cache() + File.separator + "thunder"));
    }

    public static File root(String name) {
        return new File(root(), name);
    }

    public static File root(String child, String name) {
        return new File(mkdir(new File(root(), child)), name);
    }

    public static File cache(String name) {
        return new File(cache(), name);
    }

    public static File files(String name) {
        return new File(files(), name);
    }

    public static File epg(String name) {
        return new File(epg(), name);
    }

    public static File js(String name) {
        return new File(js(), name);
    }

    public static File py(String name) {
        return new File(py(), name);
    }

    public static File jar(String name) {
        return new File(jar(), Util.md5(name).concat(".jar"));
    }

    public static File thunder(String name) {
        return mkdir(new File(thunder(), name));
    }

    public static File local(String path) {
        path = path.replace("file:/", "");
        File file = new File(root(), path);
        return file.exists() ? file : new File(path);
    }

    public static String read(File file) {
        try {
            return new String(readToByte(file), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public static String read(InputStream is) {
        try {
            return new String(readToByte(is), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public static byte[] readToByte(File file) {
        try (FileInputStream is = new FileInputStream(file)) {
            return readToByte(is);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static byte[] readToByte(InputStream is) throws IOException {
        try (InputStream input = is; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            int read;
            byte[] buffer = new byte[16384];
            while ((read = input.read(buffer)) != -1) bos.write(buffer, 0, read);
            return bos.toByteArray();
        }
    }

    public static File write(File file, InputStream is) {
        try (InputStream input = is; FileOutputStream output = new FileOutputStream(create(file))) {
            int read;
            byte[] buffer = new byte[16384];
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return file;
        } catch (IOException e) {
            return file;
        }
    }

    public static File write(File file, byte[] data) {
        try (FileOutputStream fos = new FileOutputStream(create(file))) {
            fos.write(data);
            fos.flush();
            return file;
        } catch (IOException e) {
            return file;
        }
    }

    public static void move(File in, File out) {
        if (in.renameTo(out)) return;
        copy(in, out);
        clear(in);
    }

    public static void copy(File in, File out) {
        try {
            copy(new FileInputStream(in), out);
        } catch (IOException ignored) {
        }
    }

    public static void copy(InputStream in, File out) {
        try (InputStream input = in; FileOutputStream output = new FileOutputStream(create(out))) {
            int read;
            byte[] buffer = new byte[16384];
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        } catch (IOException ignored) {
        }
    }

    public static void sort(File[] files) {
        Arrays.sort(files, (o1, o2) -> {
            if (o1.isDirectory() && o2.isFile()) return -1;
            if (o1.isFile() && o2.isDirectory()) return 1;
            return naturalCompare(o1.getName(), o2.getName());
        });
    }

    /** 收尾内容标记：命中则排到目录/章节列表末尾（最终章、番外、外传、后日谈、后记、附录、特典等）。 */
    private static final String[] TAIL_MARKERS = {
            "最终章", "最终话", "最终回", "最终", "番外", "番外篇", "外传", "外傳", "外篇",
            "别传", "后日谈", "后日譚", "后记", "後記", "后纪", "附录", "特典", "完结篇",
            "完结", "完結", "总集篇", "总集编", "设定集"
    };

    /** 是否为收尾内容（终章/番外/外传等），是则排到最后。 */
    public static boolean isTailChapter(String name) {
        if (name == null) return false;
        for (String m : TAIL_MARKERS) if (name.contains(m)) return true;
        return false;
    }

    /** 自然排序：数字段按数值、非数字段按字符（忽略大小写），与本地小说/漫画阅读器章节目录排序一致。
     *  收尾内容（最终章/番外/外传…）始终排在末尾，修复「第1章/第2章/第10章」字典序错乱。 */
    public static int naturalCompare(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        boolean ta = isTailChapter(a), tb = isTailChapter(b);
        if (ta != tb) return ta ? 1 : -1;
        int ia = 0, ib = 0, la = a.length(), lb = b.length();
        while (ia < la && ib < lb) {
            char ca = a.charAt(ia), cb = b.charAt(ib);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int na = 0, nb = 0;
                while (ia < la && Character.isDigit(a.charAt(ia))) { na = na * 10 + (a.charAt(ia) - '0'); ia++; }
                while (ib < lb && Character.isDigit(b.charAt(ib))) { nb = nb * 10 + (b.charAt(ib) - '0'); ib++; }
                if (na != nb) return na < nb ? -1 : 1;
            } else {
                char lca = Character.toLowerCase(ca), lcb = Character.toLowerCase(cb);
                if (lca != lcb) return lca < lcb ? -1 : 1;
                ia++; ib++;
            }
        }
        return Integer.compare(la - ia, lb - ib);
    }

    public static List<File> list(File dir) {
        File[] files = dir.listFiles();
        if (files != null) sort(files);
        return files == null ? new ArrayList<>() : Arrays.asList(files);
    }

    public static void clear(File dir) {
        if (dir == null) return;
        if (dir.isDirectory()) for (File file : list(dir)) clear(file);
        if (dir.delete()) Logger.t(TAG).d("Deleted:" + dir);
    }

    public static File create(File file) {
        try {
            File parent = file.getParentFile();
            if (parent != null) mkdir(parent);
            if (file.exists()) clear(file);
            if (file.createNewFile()) Logger.t(TAG).d("Create:" + file);
            file.setReadable(true);
            file.setWritable(true);
            file.setExecutable(true);
            Shell.exec("chmod 777 " + file);
            return file;
        } catch (IOException e) {
            return file;
        }
    }
}
