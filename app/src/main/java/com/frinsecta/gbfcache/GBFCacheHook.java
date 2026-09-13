package com.frinsecta.gbfcache;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public class GBFCacheHook extends XposedModule {
    private static volatile GBFCacheHook INSTANCE;
    private static final AtomicBoolean HOOKS_INSTALLED = new AtomicBoolean(false);

    private static void xlog(String msg) {
        GBFCacheHook self = INSTANCE;
        if (self != null) {
            try {
                self.log(Log.INFO, TAG, msg);
                return;
            } catch (Throwable ignored) {
            }
        }
        Log.i(TAG, msg);
    }

    private static void xlog(String msg, Throwable tr) {
        GBFCacheHook self = INSTANCE;
        if (self != null) {
            try {
                self.log(Log.ERROR, TAG, msg, tr);
                return;
            } catch (Throwable ignored) {
            }
        }
        Log.e(TAG, msg, tr);
    }

    private static void hookExecutable(Method method, XposedInterface.Hooker hooker) {
        GBFCacheHook self = INSTANCE;
        if (self == null || method == null) return;
        self.hook(method)
                .setPriority(PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker);
    }
    private static final String TAG = "GBFCache";

    private static final String CMD_HOST = "gbf-cache.local";
    private static final String PATH_CLEAR = "/clear";
    private static final String PATH_STATUS = "/status";

    private static final String MODIFIED_LIST_PATH = "/assets/resources/native/modified_list.txt";
    private static final String VERSION_FILE_NAME = ".last_version";

    private static final Set<String> GBF_HOSTS;
    static {
        Set<String> s = new HashSet<String>();
        s.add("prd-game-a-granbluefantasy.akamaized.net");
        s.add("prd-game-a-gbf.akamaized.net");
        s.add("prd-game-a1-granbluefantasy.akamaized.net");
        s.add("prd-game-a2-granbluefantasy.akamaized.net");
        s.add("prd-game-a3-granbluefantasy.akamaized.net");
        s.add("prd-game-a4-granbluefantasy.akamaized.net");
        s.add("prd-game-a5-granbluefantasy.akamaized.net");
        s.add("gbf.game.mbga.jp");
        GBF_HOSTS = Collections.unmodifiableSet(s);
    }

    private static volatile File CACHE_ROOT = null;

    private static final Set<String> DOWNLOADING =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static final ExecutorService DOWNLOAD_POOL = Executors.newFixedThreadPool(3);

    private static final Set<Class<?>> HOOKED_CLIENT_CLASSES =
            Collections.synchronizedSet(new HashSet<Class<?>>());

    private static final AtomicBoolean SYNCING = new AtomicBoolean(false);

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        INSTANCE = this;
        if (!HOOKS_INSTALLED.compareAndSet(false, true)) {
            return;
        }

        xlog("========================================");
        xlog("target process loaded: " + param.getProcessName());
        xlog("mode = LibXposed API 102");
        xlog("========================================");

        try {
            final Method onCreate = Application.class.getDeclaredMethod("onCreate");
            hookExecutable(onCreate, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Object obj = chain.getThisObject();
                        if (obj instanceof Application) {
                            setupCacheRoot((Application) obj);
                        }
                    } catch (Throwable t) {
                        xlog("Application.onCreate post hook failed", t);
                    }
                    return result;
                }
            });
        } catch (Throwable t) {
            xlog("hook Application.onCreate failed", t);
        }

        hookBaseWebViewClient();

        try {
            Method setClient = WebView.class.getDeclaredMethod(
                    "setWebViewClient", WebViewClient.class);
            hookExecutable(setClient, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        if (chain.getArgs() != null && chain.getArgs().size() > 0) {
                            Object client = chain.getArgs().get(0);
                            if (client != null) hookClientClass(client.getClass());
                        }
                    } catch (Throwable t) {
                        xlog("setWebViewClient post hook failed", t);
                    }
                    return result;
                }
            });
        } catch (Throwable t) {
            xlog("hook WebView.setWebViewClient failed", t);
        }
    }

    private static void setupCacheRoot(Context ctx) {
        try {
            xlog(TAG + ": setupCacheRoot, SDK=" + android.os.Build.VERSION.SDK_INT);

            File internal = ctx.getFilesDir();
            xlog(TAG + ": getFilesDir = "
                    + (internal == null ? "null" : internal.getAbsolutePath()));

            if (internal == null) {
                xlog(TAG + ": getFilesDir null, abort");
                return;
            }

            File root = new File(internal, "gbf-cache");
            if (!root.exists()) {
                boolean ok = root.mkdirs();
                if (!ok && !root.exists()) {
                    xlog(TAG + ": cannot create cache root, abort");
                    return;
                }
            }

            CACHE_ROOT = root;
            xlog(TAG + ": cache root ready = " + CACHE_ROOT.getAbsolutePath());

        } catch (Throwable t) {
            xlog(TAG + ": setupCacheRoot failed: " + t);
        }
    }

    private static void hookBaseWebViewClient() {
        try {
            Method m = WebViewClient.class.getDeclaredMethod(
                    "shouldInterceptRequest", WebView.class, WebResourceRequest.class);
            hookExecutable(m, new InterceptHook());
            xlog("hooked WebViewClient.shouldInterceptRequest(WebResourceRequest)");
        } catch (Throwable t) {
            xlog("hook WebViewClient WebResourceRequest failed", t);
        }

        try {
            Method m = WebViewClient.class.getDeclaredMethod(
                    "shouldInterceptRequest", WebView.class, String.class);
            hookExecutable(m, new InterceptHook());
            xlog("hooked WebViewClient.shouldInterceptRequest(String)");
        } catch (Throwable t) {
            xlog("hook WebViewClient String failed", t);
        }
    }

    private static void hookClientClass(Class<?> cls) {
        if (cls == null || cls == WebViewClient.class) {
            return;
        }
        if (!HOOKED_CLIENT_CLASSES.add(cls)) {
            return;
        }
        for (Method method : cls.getDeclaredMethods()) {
            if (!"shouldInterceptRequest".equals(method.getName())) {
                continue;
            }
            Class<?>[] p = method.getParameterTypes();
            if (p.length != 2 || p[0] != WebView.class) {
                continue;
            }
            if (p[1] == WebResourceRequest.class || p[1] == String.class) {
                try {
                    hookExecutable(method, new InterceptHook());
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static class InterceptHook implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            List<Object> args = chain.getArgs();
            Object[] rawArgs = args == null ? null : args.toArray();
            String url = extractUrl(rawArgs);
            if (url == null) {
                return chain.proceed();
            }

            // modified_list.txt 必须让原 WebView 请求继续执行。
            if (isModifiedListUrl(url)) {
                handleModifiedListAsync(url);
                return chain.proceed();
            }

            if (isCommandHost(url)) {
                WebResourceResponse r = handleCommand(url);
                if (r != null) return r;
            }

            WebResourceResponse response = tryLocalResponse(url);
            if (response != null) {
                return response;
            }

            scheduleDownload(url);
            return chain.proceed();
        }
    }

    private static String extractUrl(Object[] args) {
        if (args == null || args.length < 2 || args[1] == null) {
            return null;
        }
        Object request = args[1];
        if (request instanceof String) {
            return (String) request;
        }
        if (request instanceof WebResourceRequest) {
            Uri uri = ((WebResourceRequest) request).getUrl();
            return uri == null ? null : uri.toString();
        }
        return null;
    }

    // ================== modified_list 同步 ==================

    private static boolean isModifiedListUrl(String url) {
        if (url == null) return false;
        return url.contains(MODIFIED_LIST_PATH);
    }

    /*
     * 拦截到 modified_list.txt 时异步处理。
     */
    private static void handleModifiedListAsync(final String url) {
        if (!SYNCING.compareAndSet(false, true)) {
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    syncFromModifiedList(url);
                } catch (Throwable t) {
                    xlog(TAG + ": syncFromModifiedList failed: " + t);
                } finally {
                    SYNCING.set(false);
                }
            }
        }, "GBFCache-Sync").start();
    }

    /*
     * 下载 modified_list.txt，对比版本号，删除本地对应文件。
     */
    private static void syncFromModifiedList(String url) {
        if (CACHE_ROOT == null) return;

        HttpURLConnection conn = null;
        InputStream in = null;
        BufferedReader reader = null;

        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Referer", "https://gbf.game.mbga.jp/");
            conn.setRequestProperty("Origin", "https://gbf.game.mbga.jp");
            conn.connect();

            if (conn.getResponseCode() != 200) {
                xlog(TAG + ": modified_list HTTP " + conn.getResponseCode());
                return;
            }

            in = conn.getInputStream();
            reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));

            String versionLine = reader.readLine();
            if (versionLine == null) return;

            String newVersion = versionLine.trim();
            String lastVersion = readVersionFile();

            if (newVersion.equals(lastVersion)) {
                return;
            }

            xlog(TAG + ": version changed: " + lastVersion + " -> " + newVersion);

            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null || !isGBFHost(host)) return;

            // 缓存 CACHE_ROOT 的 canonical path，避免循环里重复计算
            String cacheRootCanonical = CACHE_ROOT.getCanonicalPath();

            int deleted = 0;
            int checked = 0;

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                int comma = line.indexOf(',');
                if (comma <= 0) continue;

                String relPath = line.substring(0, comma);

                if (relPath.startsWith("assets/")) {
                    relPath = relPath.substring("assets/".length());
                }

                String path = "/assets/" + relPath;

                // 只处理图片/音频。JS/CSS 路径带版本号，会自动失效，不用管。
                if (!isCacheableAsset(host, path)) {
                    continue;
                }

                File file = buildLocalFileFast(host, path, cacheRootCanonical);
                if (file == null) continue;

                checked++;
                if (file.isFile()) {
                    if (safeDelete(file)) {
                        deleted++;
                    }
                }
            }

            writeVersionFile(newVersion);

            xlog(TAG + ": sync done, checked=" + checked + " deleted=" + deleted);

        } catch (Throwable t) {
            xlog(TAG + ": syncFromModifiedList error: " + t);
        } finally {
            try { if (reader != null) reader.close(); } catch (Throwable ignored) {}
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (conn != null) conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    /*
     * 不调 getCanonicalPath 的快速版本。
     * 用于 modified_list 批量删除，避免几千次 canonical 计算。
     * modified_list 的路径是可信的，不做穿越检查。
     */
    private static File buildLocalFileFast(String host, String path, String cacheRootCanonical) {
        if (CACHE_ROOT == null) return null;
        try {
            String rel = stripLeadingSlash(path);
            return new File(new File(cacheRootCanonical, safeHost(host)), rel);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readVersionFile() {
        if (CACHE_ROOT == null) return null;
        File f = new File(CACHE_ROOT, VERSION_FILE_NAME);
        if (!f.isFile()) return null;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(f);
            long len = f.length();
            if (len <= 0 || len > 1024) return null;
            byte[] buf = new byte[(int) len];
            int n = fis.read(buf);
            if (n <= 0) return null;
            return new String(buf, 0, n, "UTF-8").trim();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (fis != null) fis.close(); } catch (Throwable ignored) {}
        }
    }

    private static void writeVersionFile(String version) {
        if (CACHE_ROOT == null) return;
        File f = new File(CACHE_ROOT, VERSION_FILE_NAME);
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
            fos.write(version.getBytes("UTF-8"));
            fos.flush();
        } catch (Throwable t) {
            xlog(TAG + ": writeVersionFile failed: " + t);
        } finally {
            try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
        }
    }

    /*
     * 手动整理。从 /clear?action=sync 触发。
     * 返回处理结果描述字符串。
     */
    private static String manualSync() {
        if (CACHE_ROOT == null) return "缓存目录未初始化";

        // 用 CDN host 直接请求 modified_list.txt，不带 mtime
        String url = "https://prd-game-a-granbluefantasy.akamaized.net"
                + MODIFIED_LIST_PATH;

        try {
            int beforeDeleted = countFiles();
            long beforeSize = getCacheSize();
            syncFromModifiedList(url);
            int afterDeleted = countFiles();
            long afterSize = getCacheSize();

            int diffCount = beforeDeleted - afterDeleted;
            long diffSize = beforeSize - afterSize;

            if (diffCount <= 0 && diffSize <= 0) {
                return "已是最新版本，无需整理";
            }

            return "整理完成：删除 " + diffCount + " 个文件，释放 " + fmtMB(diffSize);

        } catch (Throwable t) {
            return "整理失败: " + t;
        }
    }

    // ================== 虚拟指令 ==================

    private static boolean isCommandHost(String url) {
        try {
            Uri uri = Uri.parse(url);
            return CMD_HOST.equalsIgnoreCase(uri.getHost());
        } catch (Throwable t) {
            return false;
        }
    }

    private static WebResourceResponse handleCommand(String url) {
        try {
            Uri uri = Uri.parse(url);
            String path = uri.getPath();

            if (PATH_CLEAR.equals(path)) {
                String action = uri.getQueryParameter("action");
                if ("sync".equals(action)) {
                    String result = manualSync();
                    return pageSyncResult(result);
                }

                String host = uri.getQueryParameter("host");
                String type = uri.getQueryParameter("type");
                String dir = uri.getQueryParameter("dir");
                String older = uri.getQueryParameter("older");
                String all = uri.getQueryParameter("all");
                String confirm = uri.getQueryParameter("confirm");

                if ("1".equals(confirm)) {
                    long freed = purgeByFilter(host, type, dir, older, all);
                    return pageDone(freed, getCacheSize());
                }

                if (host != null || type != null || dir != null
                        || older != null || all != null) {
                    return pageConfirmFilter(host, type, dir, older, all);
                }

                return pageClearMenu();
            }

            if (PATH_STATUS.equals(path)) {
                return pageStatus();
            }

            return pageHelp();

        } catch (Throwable t) {
            return pageSimple("错误", "处理失败", String.valueOf(t));
        }
    }

    // ================== 数据统计 ==================

    private static class Stats {
        long totalSize;
        int totalFiles;
        Map<String, long[]> byHost = new LinkedHashMap<String, long[]>();
        Map<String, long[]> byType = new LinkedHashMap<String, long[]>();
        Map<String, long[]> byDir = new LinkedHashMap<String, long[]>();
        long recent1d, recent7d, recent30d, older30d;
        int recent1dN, recent7dN, recent30dN, older30dN;
    }

    private static Stats computeStats() {
        Stats s = new Stats();
        if (CACHE_ROOT == null) return s;

        long now = System.currentTimeMillis();
        long d1 = now - 24L * 3600 * 1000;
        long d7 = now - 7L * 24 * 3600 * 1000;
        long d30 = now - 30L * 24 * 3600 * 1000;

        List<File> all = new ArrayList<File>();
        collectFiles(CACHE_ROOT, all);

        for (File f : all) {
            if (!f.isFile()) continue;

            long size = f.length();
            s.totalSize += size;
            s.totalFiles++;

            String relPath = relativePath(f);
            if (relPath == null) continue;

            int slash = relPath.indexOf('/');
            if (slash > 0) {
                String host = relPath.substring(0, slash);
                addTo(s.byHost, host, size);
            }

            String dirKey = topDir(relPath);
            if (dirKey != null) {
                addTo(s.byDir, dirKey, size);
            }

            String type = classifyByName(f.getName());
            addTo(s.byType, type, size);

            long lm = f.lastModified();
            if (lm >= d1) {
                s.recent1d += size; s.recent1dN++;
            } else if (lm >= d7) {
                s.recent7d += size; s.recent7dN++;
            } else if (lm >= d30) {
                s.recent30d += size; s.recent30dN++;
            } else {
                s.older30d += size; s.older30dN++;
            }
        }

        return s;
    }

    private static void addTo(Map<String, long[]> map, String key, long size) {
        if (key == null) return;
        long[] v = map.get(key);
        if (v == null) {
            v = new long[]{0L, 0L};
            map.put(key, v);
        }
        v[0] += size;
        v[1]++;
    }

    private static String relativePath(File f) {
        if (CACHE_ROOT == null) return null;
        try {
            String root = CACHE_ROOT.getCanonicalPath();
            String path = f.getCanonicalPath();
            if (!path.startsWith(root + File.separator)) return null;
            return path.substring(root.length() + 1).replace(File.separatorChar, '/');
        } catch (Throwable t) {
            return null;
        }
    }

    private static String topDir(String relPath) {
        if (relPath == null) return null;
        int slash = relPath.indexOf('/');
        if (slash <= 0) return null;
        String after = relPath.substring(slash + 1);

        String[] parts = after.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            String p = parts[i];
            if (p.equals("assets")) continue;
            if (p.matches("\\d+")) continue;
            return p;
        }
        return null;
    }

    private static String classifyByName(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".gif") || n.endsWith(".webp")
                || n.endsWith(".avif") || n.endsWith(".bmp")
                || n.endsWith(".svg")) return "图片";
        if (n.endsWith(".mp3") || n.endsWith(".ogg") || n.endsWith(".oga")
                || n.endsWith(".wav") || n.endsWith(".m4a")
                || n.endsWith(".aac")) return "音频";
        if (n.endsWith(".mp4") || n.endsWith(".webm")
                || n.endsWith(".m4v")) return "视频";
        if (n.endsWith(".js")) return "JS";
        if (n.endsWith(".css")) return "CSS";
        return "其他";
    }

    private static String fmtMB(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)
            return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        return String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    // ================== 页面 ==================

    private static String css() {
        return "*{box-sizing:border-box;}"
                + "body{background:#0f0f1a;color:#e8e8f0;"
                + "font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;"
                + "margin:0;padding:16px;font-size:14px;line-height:1.5;}"
                + ".card{max-width:560px;margin:0 auto 16px;"
                + "background:#1a1a2e;border-radius:14px;padding:18px;}"
                + "h1{font-size:18px;margin:0 0 16px 0;color:#4ade80;font-weight:600;}"
                + "h2{font-size:15px;margin:18px 0 10px 0;color:#60a5fa;font-weight:600;}"
                + ".big{font-size:28px;color:#facc15;font-weight:bold;}"
                + ".row{display:flex;justify-content:space-between;"
                + "padding:6px 0;border-bottom:1px solid #2a2a3e;}"
                + ".row:last-child{border-bottom:none;}"
                + ".key{color:#a0a0b0;}"
                + ".val{color:#e8e8f0;font-weight:500;}"
                + ".btn{display:block;width:100%;padding:12px;margin:8px 0 0 0;"
                + "border:none;border-radius:10px;font-size:14px;font-weight:600;"
                + "cursor:pointer;text-decoration:none;text-align:center;"
                + "font-family:inherit;background:#2a2a3e;color:#c0c0d0;}"
                + ".btn:active{background:#3a3a4e;}"
                + ".btn-danger{background:#dc2626;color:#fff;}"
                + ".btn-danger:active{background:#b91c1c;}"
                + ".btn-warn{background:#ea580c;color:#fff;}"
                + ".btn-warn:active{background:#c2410c;}"
                + ".footer{text-align:center;color:#555;font-size:11px;margin-top:20px;}";
    }

    private static WebResourceResponse pageStatus() {
        Stats s = computeStats();

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>缓存状态</title><style>").append(css()).append("</style>")
                .append("</head><body>");

        html.append("<div class=\"card\">")
                .append("<h1>缓存状态</h1>")
                .append("<div style=\"text-align:center;margin:8px 0 16px;\">")
                .append("<div class=\"big\">").append(fmtMB(s.totalSize)).append("</div>")
                .append("<div style=\"color:#888;font-size:12px;\">")
                .append(s.totalFiles).append(" 个文件</div></div>")
                .append("<a class=\"btn btn-warn\" href=\"https://")
                .append(CMD_HOST).append(PATH_CLEAR).append("?action=sync\">整理缓存</a>")
                .append("<a class=\"btn btn-danger\" href=\"https://")
                .append(CMD_HOST).append(PATH_CLEAR).append("\">清理缓存</a>")
                .append("</div>");

        if (!s.byHost.isEmpty()) {
            html.append("<div class=\"card\"><h2>按 CDN 来源</h2>");
            for (Map.Entry<String, long[]> e : s.byHost.entrySet()) {
                html.append("<div class=\"row\"><span class=\"key\">")
                        .append(esc(e.getKey())).append("</span>")
                        .append("<span class=\"val\">").append(fmtMB(e.getValue()[0]))
                        .append(" · ").append(e.getValue()[1]).append(" 个</span></div>");
            }
            html.append("</div>");
        }

        if (!s.byType.isEmpty()) {
            html.append("<div class=\"card\"><h2>按类型</h2>");
            for (Map.Entry<String, long[]> e : s.byType.entrySet()) {
                html.append("<div class=\"row\"><span class=\"key\">")
                        .append(esc(e.getKey())).append("</span>")
                        .append("<span class=\"val\">").append(fmtMB(e.getValue()[0]))
                        .append(" · ").append(e.getValue()[1]).append(" 个</span></div>");
            }
            html.append("</div>");
        }

        if (!s.byDir.isEmpty()) {
            html.append("<div class=\"card\"><h2>按目录</h2>");
            for (Map.Entry<String, long[]> e : s.byDir.entrySet()) {
                html.append("<div class=\"row\"><span class=\"key\">/")
                        .append(esc(e.getKey())).append("</span>")
                        .append("<span class=\"val\">").append(fmtMB(e.getValue()[0]))
                        .append(" · ").append(e.getValue()[1]).append(" 个</span></div>");
            }
            html.append("</div>");
        }

        html.append("<div class=\"card\"><h2>按时间</h2>")
                .append("<div class=\"row\"><span class=\"key\">24 小时内</span>")
                .append("<span class=\"val\">").append(fmtMB(s.recent1d))
                .append(" · ").append(s.recent1dN).append(" 个</span></div>")
                .append("<div class=\"row\"><span class=\"key\">7 天内</span>")
                .append("<span class=\"val\">").append(fmtMB(s.recent7d))
                .append(" · ").append(s.recent7dN).append(" 个</span></div>")
                .append("<div class=\"row\"><span class=\"key\">30 天内</span>")
                .append("<span class=\"val\">").append(fmtMB(s.recent30d))
                .append(" · ").append(s.recent30dN).append(" 个</span></div>")
                .append("<div class=\"row\"><span class=\"key\">30 天以上</span>")
                .append("<span class=\"val\">").append(fmtMB(s.older30d))
                .append(" · ").append(s.older30dN).append(" 个</span></div>")
                .append("</div>");

        html.append("<div class=\"footer\">GBF Cache Hook</div>")
                .append("</body></html>");

        return htmlResponse(html.toString());
    }

    private static WebResourceResponse pageClearMenu() {
        Stats s = computeStats();

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>清理缓存</title><style>").append(css()).append("</style>")
                .append("</head><body>");

        html.append("<div class=\"card\">")
                .append("<h1>清理缓存</h1>")
                .append("<div style=\"color:#a0a0b0;font-size:13px;\">")
                .append("当前 ").append(fmtMB(s.totalSize))
                .append(" · ").append(s.totalFiles).append(" 个文件")
                .append("</div></div>");

        // 智能整理
        html.append("<div class=\"card\"><h2>智能整理</h2>")
                .append("<p style=\"color:#a0a0b0;font-size:13px;line-height:1.6;margin:0 0 12px 0;\">")
                .append("下载最新的 modified_list.txt，只删除游戏热更新后变化的资源。")
                .append("比全量清空更快、更省流量。</p>")
                .append("<a class=\"btn btn-warn\" href=\"https://")
                .append(CMD_HOST).append(PATH_CLEAR).append("?action=sync\">")
                .append("整理缓存</a>")
                .append("</div>");

        // 按时间清理
        html.append("<div class=\"card\"><h2>按时间</h2>")
                .append("<a class=\"btn\" href=\"https://").append(CMD_HOST).append(PATH_CLEAR)
                .append("?older=7\">清理 7 天前的（")
                .append(fmtMB(s.recent30d + s.older30d)).append("）</a>")
                .append("<a class=\"btn\" href=\"https://").append(CMD_HOST).append(PATH_CLEAR)
                .append("?older=30\">清理 30 天前的（")
                .append(fmtMB(s.older30d)).append("）</a>")
                .append("<a class=\"btn\" href=\"https://").append(CMD_HOST).append(PATH_CLEAR)
                .append("?older=90\">清理 90 天前的</a>")
                .append("</div>");

        // 按类型清理
        html.append("<div class=\"card\"><h2>按类型</h2>");
        for (Map.Entry<String, long[]> e : s.byType.entrySet()) {
            html.append("<a class=\"btn\" href=\"https://").append(CMD_HOST).append(PATH_CLEAR)
                    .append("?type=").append(esc(e.getKey()))
                    .append("\">清理 ").append(esc(e.getKey()))
                    .append("（").append(fmtMB(e.getValue()[0])).append("）</a>");
        }
        html.append("</div>");

        // 按目录清理
        if (!s.byDir.isEmpty()) {
            html.append("<div class=\"card\"><h2>按目录</h2>");
            for (Map.Entry<String, long[]> e : s.byDir.entrySet()) {
                html.append("<a class=\"btn\" href=\"https://").append(CMD_HOST).append(PATH_CLEAR)
                        .append("?dir=").append(esc(e.getKey()))
                        .append("\">清理 /").append(esc(e.getKey()))
                        .append("（").append(fmtMB(e.getValue()[0])).append("）</a>");
            }
            html.append("</div>");
        }

        // 全部清空
        html.append("<div class=\"card\"><h2>全部清空</h2>")
                .append("<a class=\"btn btn-danger\" href=\"https://")
                .append(CMD_HOST).append(PATH_CLEAR)
                .append("?all=1\">清空全部缓存</a>")
                .append("</div>");

        html.append("<div class=\"footer\">GBF Cache Hook</div>")
                .append("</body></html>");

        return htmlResponse(html.toString());
    }

    private static WebResourceResponse pageConfirmFilter(
            String host, String type, String dir, String older, String all) {

        String label = describeFilter(host, type, dir, older, all);
        long estimate = estimateFilter(host, type, dir, older, all);
        int estimateN = estimateFilterCount(host, type, dir, older, all);

        StringBuilder href = new StringBuilder();
        href.append("https://").append(CMD_HOST).append(PATH_CLEAR);
        boolean first = true;
        if (host != null) { href.append(first ? "?" : "&").append("host=").append(host); first = false; }
        if (type != null) { href.append(first ? "?" : "&").append("type=").append(type); first = false; }
        if (dir != null)  { href.append(first ? "?" : "&").append("dir=").append(dir);   first = false; }
        if (older != null){ href.append(first ? "?" : "&").append("older=").append(older); first = false; }
        if (all != null)  { href.append(first ? "?" : "&").append("all=").append(all);   first = false; }
        href.append(first ? "?" : "&").append("confirm=1");

        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>确认清理</title><style>" + css() + "</style></head><body>"
                + "<div class=\"card\">"
                + "<h1 style=\"color:#f87171;\">确认清理？</h1>"
                + "<p style=\"color:#a0a0b0;font-size:13px;\">将清理："
                + esc(label) + "</p>"
                + "<div style=\"text-align:center;margin:16px 0;\">"
                + "<div class=\"big\">" + fmtMB(estimate) + "</div>"
                + "<div style=\"color:#888;font-size:12px;\">" + estimateN + " 个文件</div>"
                + "</div>"
                + "<a class=\"btn btn-danger\" href=\"" + href + "\">确认清理</a>"
                + "<a class=\"btn\" href=\"https://" + CMD_HOST + PATH_CLEAR + "\">取消</a>"
                + "<div class=\"footer\">GBF Cache Hook</div>"
                + "</div></body></html>";

        return htmlResponse(html);
    }

    private static WebResourceResponse pageDone(long freed, long remaining) {
        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>已清理</title><style>" + css() + "</style></head><body>"
                + "<div class=\"card\">"
                + "<h1>✓ 清理完成</h1>"
                + "<div style=\"text-align:center;margin:16px 0;\">"
                + "<div class=\"big\">" + fmtMB(freed) + "</div>"
                + "<div style=\"color:#888;font-size:12px;\">释放空间</div>"
                + "</div>"
                + "<div class=\"row\"><span class=\"key\">当前缓存</span>"
                + "<span class=\"val\">" + fmtMB(remaining) + "</span></div>"
                + "<a class=\"btn\" href=\"https://" + CMD_HOST + PATH_STATUS + "\">查看状态</a>"
                + "<a class=\"btn\" href=\"https://" + CMD_HOST + PATH_CLEAR + "\">继续清理</a>"
                + "<div class=\"footer\">GBF Cache Hook</div>"
                + "</div></body></html>";
        return htmlResponse(html);
    }

    private static WebResourceResponse pageSyncResult(String message) {
        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>整理缓存</title><style>" + css() + "</style></head><body>"
                + "<div class=\"card\">"
                + "<h1>整理缓存</h1>"
                + "<p style=\"color:#a0a0b0;line-height:1.6;\">"
                + esc(message) + "</p>"
                + "<a class=\"btn\" href=\"https://" + CMD_HOST + PATH_STATUS + "\">查看状态</a>"
                + "<a class=\"btn\" href=\"https://" + CMD_HOST + PATH_CLEAR + "\">返回</a>"
                + "<div class=\"footer\">GBF Cache Hook</div>"
                + "</div></body></html>";
        return htmlResponse(html);
    }

    private static WebResourceResponse pageHelp() {
        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>GBF Cache</title><style>" + css() + "</style></head><body>"
                + "<div class=\"card\">"
                + "<h1>GBF Cache Hook</h1>"
                + "<a class=\"btn\" href=\"https://" + CMD_HOST + PATH_STATUS + "\">查看缓存状态</a>"
                + "<a class=\"btn\" href=\"https://" + CMD_HOST + PATH_CLEAR + "\">清理缓存</a>"
                + "<a class=\"btn btn-warn\" href=\"https://" + CMD_HOST + PATH_CLEAR + "?action=sync\">整理缓存</a>"
                + "<div class=\"footer\">GBF Cache Hook</div>"
                + "</div></body></html>";
        return htmlResponse(html);
    }

    private static WebResourceResponse pageSimple(String title, String line1, String line2) {
        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<title>" + esc(title) + "</title></head><body style=\""
                + "background:#0f0f1a;color:#e8e8f0;font-family:sans-serif;"
                + "text-align:center;padding:40px;\">"
                + "<h1>" + esc(title) + "</h1>"
                + "<p>" + esc(line1) + "</p>"
                + "<p style=\"color:#888;font-size:13px;\">" + esc(line2) + "</p>"
                + "</body></html>";
        return htmlResponse(html);
    }

    private static WebResourceResponse htmlResponse(String html) {
        try {
            byte[] bytes = html.getBytes("UTF-8");
            ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Content-Type", "text/html; charset=utf-8");
            headers.put("Content-Length", String.valueOf(bytes.length));
            headers.put("Cache-Control", "no-store, no-cache, must-revalidate");
            headers.put("Pragma", "no-cache");
            return new WebResourceResponse("text/html", "utf-8", 200, "OK", headers, stream);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String describeFilter(String host, String type, String dir,
                                         String older, String all) {
        if (all != null) return "全部缓存";
        if (host != null) return "CDN: " + host;
        if (type != null) return "类型: " + type;
        if (dir != null) return "目录: /" + dir;
        if (older != null) return older + " 天前的文件";
        return "未知条件";
    }

    private static long estimateFilter(String host, String type, String dir,
                                       String older, String all) {
        if (CACHE_ROOT == null) return 0;
        long now = System.currentTimeMillis();
        long cutoff = 0;
        if (older != null) {
            try {
                cutoff = now - Long.parseLong(older) * 24L * 3600 * 1000;
            } catch (Throwable ignored) {}
        }

        long sum = 0;
        List<File> all2 = new ArrayList<File>();
        collectFiles(CACHE_ROOT, all2);
        for (File f : all2) {
            if (matchFilter(f, host, type, dir, cutoff, all != null)) {
                sum += f.length();
            }
        }
        return sum;
    }

    private static int estimateFilterCount(String host, String type, String dir,
                                           String older, String all) {
        if (CACHE_ROOT == null) return 0;
        long now = System.currentTimeMillis();
        long cutoff = 0;
        if (older != null) {
            try {
                cutoff = now - Long.parseLong(older) * 24L * 3600 * 1000;
            } catch (Throwable ignored) {}
        }

        int count = 0;
        List<File> all2 = new ArrayList<File>();
        collectFiles(CACHE_ROOT, all2);
        for (File f : all2) {
            if (matchFilter(f, host, type, dir, cutoff, all != null)) {
                count++;
            }
        }
        return count;
    }

    private static boolean matchFilter(File f, String host, String type, String dir,
                                       long cutoff, boolean all) {
        if (!f.isFile()) return false;

        if (all) return true;

        if (cutoff > 0 && f.lastModified() >= cutoff) return false;

        String rel = relativePath(f);
        if (rel == null) return false;

        if (host != null) {
            int slash = rel.indexOf('/');
            if (slash <= 0) return false;
            if (!host.equals(rel.substring(0, slash))) return false;
        }

        if (dir != null) {
            String top = topDir(rel);
            if (top == null || !top.equals(dir)) return false;
        }

        if (type != null) {
            String t = classifyByName(f.getName());
            if (!type.equals(t)) return false;
        }

        return true;
    }

    private static long purgeByFilter(String host, String type, String dir,
                                      String older, String all) {
        if (CACHE_ROOT == null) return 0;

        long now = System.currentTimeMillis();
        long cutoff = 0;
        if (older != null) {
            try {
                cutoff = now - Long.parseLong(older) * 24L * 3600 * 1000;
            } catch (Throwable ignored) {}
        }
        boolean allFlag = all != null;

        long freed = 0;
        List<File> files = new ArrayList<File>();
        collectFiles(CACHE_ROOT, files);

        for (File f : files) {
            if (matchFilter(f, host, type, dir, cutoff, allFlag)) {
                long len = f.length();
                if (safeDelete(f)) {
                    freed += len;
                }
            }
        }

        removeEmptyDirs(CACHE_ROOT);
        return freed;
    }

    // ================== 缓存读写 ==================

    private static WebResourceResponse tryLocalResponse(String url) {
        try {
            if (CACHE_ROOT == null) return null;

            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();

            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return null;
            }
            if (host == null || !isGBFHost(host)) return null;

            String path = uri.getEncodedPath();
            if (path == null || path.length() == 0) path = "/";
            if (!isCacheableAsset(host, path)) return null;

            File file = buildLocalFile(host, path);
            if (file == null) return null;

            if (!file.isFile() || !file.canRead() || file.length() <= 0) {
                return null;
            }

            try {
                file.setLastModified(System.currentTimeMillis());
            } catch (Throwable ignored) {}

            String mime = guessMime(file.getName());

            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Content-Type", mime);
            headers.put("Content-Length", String.valueOf(file.length()));
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Cache-Control", "no-store");
            headers.put("Pragma", "no-cache");
            headers.put("Expires", "0");

            return new WebResourceResponse(
                    mime, null, 200, "OK", headers, new FileInputStream(file));

        } catch (Throwable t) {
            return null;
        }
    }

    private static File buildLocalFile(String host, String path) {
        if (CACHE_ROOT == null) return null;
        try {
            File file = new File(
                    new File(CACHE_ROOT, safeHost(host)),
                    stripLeadingSlash(path)
            );
            String root = CACHE_ROOT.getCanonicalPath();
            String target = file.getCanonicalPath();
            if (!target.startsWith(root + File.separator)) return null;
            return file;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void scheduleDownload(final String url) {
        if (CACHE_ROOT == null) return;
        if (!DOWNLOADING.add(url)) return;

        DOWNLOAD_POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    downloadAndCache(url);
                } catch (Throwable ignored) {
                } finally {
                    DOWNLOADING.remove(url);
                }
            }
        });
    }

    private static void downloadAndCache(String url) {
        if (CACHE_ROOT == null) return;

        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        String path = uri.getEncodedPath();

        if (host == null || path == null) return;
        if (!isGBFHost(host) || !isCacheableAsset(host, path)) return;

        File finalFile = buildLocalFile(host, path);
        if (finalFile == null) return;

        if (finalFile.isFile() && finalFile.canRead() && finalFile.length() > 0) {
            return;
        }

        File parent = finalFile.getParentFile();
        if (parent != null && !parent.exists()) {
            boolean ok = parent.mkdirs();
            if (!ok && !parent.exists()) return;
        }

        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;

        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Referer", "https://gbf.game.mbga.jp/");
            conn.setRequestProperty("Origin", "https://gbf.game.mbga.jp");
            conn.connect();

            int code = conn.getResponseCode();
            if (code != 200) return;

            in = conn.getInputStream();

            File tmpFile = new File(parent, finalFile.getName() + "." + System.nanoTime() + ".tmp");
            safeDelete(tmpFile);

            out = new FileOutputStream(tmpFile);
            byte[] buf = new byte[32768];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
            out.close();
            out = null;

            if (total <= 0 || tmpFile.length() <= 0) {
                safeDelete(tmpFile);
                return;
            }

            if (finalFile.exists()) safeDelete(finalFile);
            boolean renamed = tmpFile.renameTo(finalFile);
            if (!renamed) {
                try { Thread.sleep(50); } catch (Throwable ignored) {}
                if (finalFile.exists()) safeDelete(finalFile);
                renamed = tmpFile.renameTo(finalFile);
                if (!renamed) {
                    safeDelete(tmpFile);
                    return;
                }
            }

            try {
                finalFile.setLastModified(System.currentTimeMillis());
            } catch (Throwable ignored) {}

        } catch (Throwable ignored) {
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (conn != null) conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    // ================== 工具 ==================

    private static long getCacheSize() {
        if (CACHE_ROOT == null) return 0;
        long total = 0;
        List<File> all = new ArrayList<File>();
        collectFiles(CACHE_ROOT, all);
        for (File f : all) {
            if (f.isFile()) total += f.length();
        }
        return total;
    }

    private static int countFiles() {
        if (CACHE_ROOT == null) return 0;
        List<File> all = new ArrayList<File>();
        collectFiles(CACHE_ROOT, all);
        return all.size();
    }

    private static void collectFiles(File dir, List<File> out) {
        if (dir == null || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                collectFiles(f, out);
            } else if (f.isFile()) {
                out.add(f);
            }
        }
    }

    private static boolean removeEmptyDirs(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File f : children) {
                if (f.isDirectory()) removeEmptyDirs(f);
            }
        }
        if (!dir.equals(CACHE_ROOT)) {
            File[] left = dir.listFiles();
            if (left != null && left.length == 0) return dir.delete();
        }
        return false;
    }

    private static boolean safeDelete(File file) {
        try {
            if (file != null && file.exists()) return file.delete();
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isCacheableAsset(String host, String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();

        boolean isImage = lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".gif")
                || lower.endsWith(".webp") || lower.endsWith(".avif")
                || lower.endsWith(".bmp") || lower.endsWith(".svg");
        boolean isMedia = lower.endsWith(".mp3") || lower.endsWith(".ogg")
                || lower.endsWith(".oga") || lower.endsWith(".wav")
                || lower.endsWith(".m4a") || lower.endsWith(".aac")
                || lower.endsWith(".mp4") || lower.endsWith(".webm")
                || lower.endsWith(".m4v");
        boolean isScript = lower.endsWith(".js");
        boolean isStyle = lower.endsWith(".css");

        if (!isImage && !isMedia && !isScript && !isStyle) return false;

        if (host != null && host.toLowerCase().endsWith("akamaized.net")) {
            return true;
        }
        if ("gbf.game.mbga.jp".equalsIgnoreCase(host)) {
            return lower.startsWith("/assets/");
        }
        return false;
    }

    private static boolean isGBFHost(String host) {
        if (host == null) return false;
        for (String allowed : GBF_HOSTS) {
            if (allowed.equalsIgnoreCase(host)) return true;
        }
        return false;
    }

    private static String safeHost(String host) {
        if (host == null) return "unknown";
        return host.replace(":", "_");
    }

    private static String stripLeadingSlash(String path) {
        if (path == null) return "";
        while (path.startsWith("/")) path = path.substring(1);
        return path;
    }

    private static String guessMime(String name) {
        if (name == null) return "application/octet-stream";
        String n = name.toLowerCase();
        if (n.endsWith(".js")) return "application/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".xml")) return "application/xml";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".avif")) return "image/avif";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".ico")) return "image/x-icon";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".ogg") || n.endsWith(".oga")) return "audio/ogg";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".aac")) return "audio/aac";
        if (n.endsWith(".mp4") || n.endsWith(".m4v")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        return "application/octet-stream";
    }
}