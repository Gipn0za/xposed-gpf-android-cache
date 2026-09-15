package com.frinsecta.gbfcache;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.webkit.CookieManager;
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
import java.util.concurrent.atomic.AtomicLong;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public class GBFCacheHook extends XposedModule {
    private static volatile GBFCacheHook INSTANCE;
    private static final AtomicBoolean HOOKS_INSTALLED = new AtomicBoolean(false);

    private static final String TAG = "GBFCache";

    private static final String CMD_HOST = "gbf-cache.local";
    private static final String PATH_CLEAR = "/clear";
    private static final String PATH_STATUS = "/status";
    private static final String PATH_FILES = "/files";

    private static final String MODIFIED_LIST_PATH = "/assets/resources/native/modified_list.txt";

    private static final String PREF_NAME = "gbfcache";
    private static final String KEY_LAST_VERSION = "last_version";
    private static final String KEY_LAST_PROPS = "last_props_json";
    private static final String KEY_LAST_PROPS_TIME = "last_props_time";

    private static final long PROPS_FETCH_MIN_INTERVAL = 60L * 60 * 1000;

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
        s.add("game.granbluefantasy.jp");
        GBF_HOSTS = Collections.unmodifiableSet(s);
    }

    private static final Set<String> PAGE_HOSTS;
    static {
        Set<String> s = new HashSet<String>();
        s.add("gbf.game.mbga.jp");
        s.add("game.granbluefantasy.jp");
        PAGE_HOSTS = Collections.unmodifiableSet(s);
    }

    private static volatile File CACHE_ROOT = null;
    private static volatile SharedPreferences PREFS = null;

    private static final AtomicLong LAST_PROPS_FETCH = new AtomicLong(0);

    private static final Set<String> DOWNLOADING =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static final ExecutorService DOWNLOAD_POOL = Executors.newFixedThreadPool(3);

    private static final Set<Class<?>> HOOKED_CLIENT_CLASSES =
            Collections.synchronizedSet(new HashSet<Class<?>>());

    private static final AtomicBoolean SYNCING = new AtomicBoolean(false);

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
            xlog("setupCacheRoot, SDK=" + Build.VERSION.SDK_INT);

            File internal = ctx.getFilesDir();
            if (internal == null) {
                xlog("getFilesDir null, abort");
                return;
            }

            File root = new File(internal, "gbf-cache");
            if (!root.exists()) {
                boolean ok = root.mkdirs();
                if (!ok && !root.exists()) {
                    xlog("cannot create cache root, abort");
                    return;
                }
            }

            CACHE_ROOT = root;
            PREFS = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            xlog("cache root ready = " + CACHE_ROOT.getAbsolutePath());
        } catch (Throwable t) {
            xlog("setupCacheRoot failed", t);
        }
    }

    private static String readVersion() {
        if (PREFS == null) return null;
        return PREFS.getString(KEY_LAST_VERSION, null);
    }

    private static void writeVersion(String version) {
        if (PREFS == null || version == null) return;
        PREFS.edit().putString(KEY_LAST_VERSION, version).apply();
    }

    private static String readLastProps() {
        if (PREFS == null) return null;
        return PREFS.getString(KEY_LAST_PROPS, null);
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

            if (isGamePageUrl(url)) {
                maybeFetchServerProps(url);
            }

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

    // ================== 游戏版本探测 ==================

    private static boolean isGamePageUrl(String url) {
        if (url == null) return false;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null || !PAGE_HOSTS.contains(host.toLowerCase())) return false;
            String path = uri.getPath();
            if (path == null || path.length() == 0) return true;
            if (path.equals("/")) return true;
            return !path.contains(".");
        } catch (Throwable t) {
            return false;
        }
    }

    private static void maybeFetchServerProps(String url) {
        long now = System.currentTimeMillis();
        long last = LAST_PROPS_FETCH.get();
        if (now - last < PROPS_FETCH_MIN_INTERVAL) return;
        if (!LAST_PROPS_FETCH.compareAndSet(last, now)) return;

        final String pageUrl = url;
        DOWNLOAD_POOL.execute(new Runnable() {
            @Override
            public void run() {
                fetchServerProps(pageUrl);
            }
        });
    }

    private static void fetchServerProps(String pageUrl) {
        HttpURLConnection conn = null;
        InputStream in = null;
        BufferedReader reader = null;
        try {
            URL u = new URL(pageUrl);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Referer", pageUrl);

            String cookie = CookieManager.getInstance().getCookie(pageUrl);
            if (cookie != null) conn.setRequestProperty("Cookie", cookie);

            conn.connect();
            if (conn.getResponseCode() != 200) {
                xlog("fetchServerProps HTTP " + conn.getResponseCode());
                return;
            }

            in = conn.getInputStream();
            reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));

            StringBuilder sb = new StringBuilder();
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null && lines++ < 2000) {
                sb.append(line).append('\n');
                if (sb.indexOf("server-props") >= 0 && sb.indexOf("\"version\"") >= 0) {
                    ServerProps p = extractServerProps(sb.toString());
                    if (p != null && p.version != null) {
                        onVersionDetected(p);
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            xlog("fetchServerProps failed", t);
        } finally {
            try { if (reader != null) reader.close(); } catch (Throwable ignored) {}
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (conn != null) conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static class ServerProps {
        String version;
        String jsUri;
        String cssUri;
        String imgUri;
    }

    private static ServerProps extractServerProps(String html) {
        int propsIdx = html.indexOf("server-props");
        if (propsIdx < 0) return null;

        ServerProps p = new ServerProps();
        p.version = extractJsonString(html, propsIdx, "\"version\"");
        p.jsUri = extractJsonString(html, propsIdx, "\"jsUri\"");
        p.cssUri = extractJsonString(html, propsIdx, "\"cssUri\"");
        p.imgUri = extractJsonString(html, propsIdx, "\"imgUri\"");

        if (p.version == null) return null;
        return p;
    }

    private static String extractJsonString(String html, int fromIdx, String key) {
        int keyIdx = html.indexOf(key, fromIdx);
        if (keyIdx < 0) return null;
        int colon = html.indexOf(':', keyIdx + key.length());
        if (colon < 0) return null;
        int q1 = html.indexOf('"', colon);
        if (q1 < 0) return null;
        int q2 = html.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return html.substring(q1 + 1, q2);
    }

    private static void onVersionDetected(ServerProps p) {
        if (PREFS != null) {
            PREFS.edit()
                    .putString(KEY_LAST_PROPS, "v=" + p.version + " js=" + p.jsUri
                            + " css=" + p.cssUri + " img=" + p.imgUri)
                    .putLong(KEY_LAST_PROPS_TIME, System.currentTimeMillis())
                    .apply();
        }

        String oldVersion = readVersion();
        if (p.version.equals(oldVersion)) return;

        xlog("version changed: " + oldVersion + " -> " + p.version);
        writeVersion(p.version);
        cleanupOldVersionDirs(p.version);
    }

    private static void cleanupOldVersionDirs(final String currentVersion) {
        if (CACHE_ROOT == null) return;
        File[] hosts = CACHE_ROOT.listFiles();
        if (hosts == null) return;

        for (File host : hosts) {
            if (!host.isDirectory()) continue;
            File assets = new File(host, "assets");
            if (!assets.isDirectory()) continue;

            File[] dirs = assets.listFiles();
            if (dirs == null) continue;

            for (File d : dirs) {
                if (!d.isDirectory()) continue;
                if (!d.getName().matches("\\d+")) continue;
                if (d.getName().equals(currentVersion)) continue;
                xlog("cleanup old version dir: " + d.getAbsolutePath());
                deleteRecursive(d);
            }
        }
        removeEmptyDirs(CACHE_ROOT);
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        safeDelete(f);
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
                    xlog("syncFromModifiedList failed: " + t);
                } finally {
                    SYNCING.set(false);
                }
            }
        }, "GBFCache-Sync").start();
    }

    /*
     * 下载 modified_list.txt，全量遍历并删除本地命中的资源。
     * 版本判断交给 server-props 探测，这里不再做版本对比。
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
                xlog("modified_list HTTP " + conn.getResponseCode());
                return;
            }

            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null || !isGBFHost(host)) return;

            in = conn.getInputStream();
            reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));

            String cacheRootCanonical = CACHE_ROOT.getCanonicalPath();
            int deleted = 0;
            int checked = 0;
            String line;
            boolean firstLine = true;

            while ((line = reader.readLine()) != null) {
                if (firstLine) { firstLine = false; continue; }
                line = line.trim();
                if (line.isEmpty()) continue;

                int comma = line.indexOf(',');
                if (comma <= 0) continue;

                String relPath = line.substring(0, comma);
                if (relPath.startsWith("assets/")) {
                    relPath = relPath.substring("assets/".length());
                }
                String path = "/assets/" + relPath;
                if (!isCacheableAsset(host, path)) continue;

                File file = buildLocalFileFast(host, path, cacheRootCanonical);
                if (file == null) continue;

                checked++;
                if (file.isFile() && safeDelete(file)) deleted++;
            }

            xlog("modified_list sync: checked=" + checked + " deleted=" + deleted);
        } catch (Throwable t) {
            xlog("syncFromModifiedList error", t);
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
            int beforeCount = countFiles();
            long beforeSize = getCacheSize();
            syncFromModifiedList(url);
            int afterCount = countFiles();
            long afterSize = getCacheSize();

            int deletedCount = beforeCount - afterCount;
            long freedSize = beforeSize - afterSize;

            if (deletedCount <= 0 && freedSize <= 0) {
                return "整理完成，本次无需删除";
            }
            return "整理完成：删除 " + deletedCount + " 个文件，释放 " + fmtMB(freedSize);

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
                    return pageSyncResult(manualSync());
                }

                String version = uri.getQueryParameter("version");
                String quality = uri.getQueryParameter("quality");
                String confirm = uri.getQueryParameter("confirm");

                if (version != null && !version.matches("\\d+")) version = null;
                if (quality != null && !isValidQuality(quality)) quality = null;

                if ("1".equals(confirm) && (version != null || quality != null)) {
                    long before = getCacheSize();
                    purgeByVersionQuality(version, quality);
                    long freed = before - getCacheSize();
                    return pageDone(freed, getCacheSize());
                }

                String host = uri.getQueryParameter("host");
                String type = uri.getQueryParameter("type");
                String dir = uri.getQueryParameter("dir");
                String older = uri.getQueryParameter("older");
                String all = uri.getQueryParameter("all");

                if ("1".equals(confirm)) {
                    long freed = purgeByFilter(host, type, dir, older, all);
                    return pageDone(freed, getCacheSize());
                }

                if (version != null || quality != null) {
                    return pageConfirmVersionPurge(version, quality);
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

            if (PATH_FILES.equals(path)) {
                String relPath = uri.getQueryParameter("path");
                String action = uri.getQueryParameter("action");
                String confirm = uri.getQueryParameter("confirm");

                if ("delete".equals(action) && "1".equals(confirm) && relPath != null) {
                    return handleFileDelete(relPath);
                }
                return pageFiles(relPath);
            }

            return pageHelp();
        } catch (Throwable t) {
            return pageSimple("错误", "处理失败", String.valueOf(t));
        }
    }

    private static boolean isValidQuality(String q) {
        return "img_low".equals(q) || "img_mid".equals(q) || "img".equals(q)
                || "css_low".equals(q) || "css_mid".equals(q) || "css".equals(q)
                || "js".equals(q);
    }

    private static long purgeByVersionQuality(String version, String quality) {
        if (CACHE_ROOT == null) return 0;

        String v = (version != null) ? version : readVersion();
        long freed = 0;

        File[] hosts = CACHE_ROOT.listFiles();
        if (hosts == null) return freed;

        for (File host : hosts) {
            if (!host.isDirectory()) continue;
            File assets = new File(host, "assets");
            if (!assets.isDirectory()) continue;

            if (quality == null || quality.startsWith("img")) {
                String[] imgDirs = quality == null
                        ? new String[]{"img_low", "img_mid", "img"}
                        : new String[]{quality};
                for (String q : imgDirs) {
                    File dir = new File(assets, q);
                    if (dir.isDirectory()) {
                        freed += dirSize(dir);
                        deleteRecursive(dir);
                    }
                }
            }

            if (quality == null || quality.startsWith("css") || "js".equals(quality)) {
                if (v != null) {
                    File vdir = new File(assets, v);
                    if (vdir.isDirectory()) {
                        String[] subDirs = quality == null
                                ? new String[]{"css_low", "css_mid", "css", "js"}
                                : new String[]{quality};
                        for (String q : subDirs) {
                            File dir = new File(vdir, q);
                            if (dir.isDirectory()) {
                                freed += dirSize(dir);
                                deleteRecursive(dir);
                            }
                        }
                    }
                }
            }
        }
        removeEmptyDirs(CACHE_ROOT);
        return freed;
    }

    private static long dirSize(File dir) {
        if (dir == null) return 0;
        if (dir.isFile()) return dir.length();
        if (!dir.isDirectory()) return 0;
        long total = 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        for (File f : children) total += dirSize(f);
        return total;
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
                .append("<a class=\"btn\" href=\"https://")
                .append(CMD_HOST).append(PATH_FILES).append("\">文件管理</a>")
                .append("</div>");

        // 游戏信息
        String lastProps = readLastProps();
        String currentVersion = readVersion();
        if (currentVersion != null || lastProps != null) {
            html.append("<div class=\"card\"><h2>游戏信息</h2>");
            if (currentVersion != null) {
                html.append("<div class=\"row\"><span class=\"key\">当前版本</span>")
                        .append("<span class=\"val\">").append(esc(currentVersion))
                        .append("</span></div>");
            }
            if (lastProps != null) {
                html.append("<div class=\"row\" style=\"display:block;\">")
                        .append("<span class=\"key\">资源路径</span>")
                        .append("<div style=\"color:#c0c0d0;font-size:11px;")
                        .append("word-break:break-all;margin-top:4px;\">")
                        .append(esc(lastProps)).append("</div></div>");
            }
            if (PREFS != null) {
                long t = PREFS.getLong(KEY_LAST_PROPS_TIME, 0);
                if (t > 0) {
                    html.append("<div class=\"row\"><span class=\"key\">探测时间</span>")
                            .append("<span class=\"val\">")
                            .append(android.text.format.DateFormat.format(
                                    "yyyy-MM-dd HH:mm", t))
                            .append("</span></div>");
                }
            }
            html.append("</div>");
        }

        // 设备信息
        html.append("<div class=\"card\"><h2>设备信息</h2>")
                .append("<div class=\"row\"><span class=\"key\">Android</span>")
                .append("<span class=\"val\">").append(esc(Build.VERSION.RELEASE))
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")</span></div>")
                .append("<div class=\"row\"><span class=\"key\">设备</span>")
                .append("<span class=\"val\">").append(esc(Build.MANUFACTURER))
                .append(" ").append(esc(Build.MODEL)).append("</span></div>");

        String wvPkg = "";
        try {
            PackageInfo pi = WebView.getCurrentWebViewPackage();
            if (pi != null) wvPkg = pi.versionName;
        } catch (Throwable ignored) {}
        if (!wvPkg.isEmpty()) {
            html.append("<div class=\"row\"><span class=\"key\">WebView</span>")
                    .append("<span class=\"val\">").append(esc(wvPkg)).append("</span></div>");
        }

        if (CACHE_ROOT != null) {
            long free = CACHE_ROOT.getUsableSpace();
            long total = CACHE_ROOT.getTotalSpace();
            html.append("<div class=\"row\"><span class=\"key\">可用空间</span>")
                    .append("<span class=\"val\">").append(fmtMB(free))
                    .append(" / ").append(fmtMB(total)).append("</span></div>");
        }
        html.append("</div>");

        // 原有统计
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
        String currentVersion = readVersion();
        Map<String, long[]> cur = computeCurrentVersionStats();
        Map<String, Long> old = computeOldVersionStats();

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>清理缓存</title><style>").append(css()).append("</style>")
                .append("</head><body>");

        html.append("<div class=\"card\">")
                .append("<h1>清理缓存</h1>")
                .append("<div style=\"color:#a0a0b0;font-size:13px;\">")
                .append("当前版本 ")
                .append(currentVersion == null
                        ? "(未知)"
                        : esc(formatVersionTimestamp(currentVersion)))
                .append(currentVersion == null
                        ? ""
                        : "<span style=\"color:#555;font-size:11px;margin-left:6px;\">("
                          + esc(currentVersion) + ")</span>")
                .append("</div></div>");

        html.append("<div class=\"card\"><h2>当前版本 · 按画质</h2>");
        appendQualityRow(html, "img_low", "图片-低", cur.get("img_low"), currentVersion);
        appendQualityRow(html, "img_mid", "图片-中", cur.get("img_mid"), currentVersion);
        appendQualityRow(html, "img", "图片-高", cur.get("img"), currentVersion);
        appendQualityRow(html, "css_low", "CSS-低", cur.get("css_low"), currentVersion);
        appendQualityRow(html, "css_mid", "CSS-中", cur.get("css_mid"), currentVersion);
        appendQualityRow(html, "css", "CSS-高", cur.get("css"), currentVersion);
        appendQualityRow(html, "js", "JS", cur.get("js"), currentVersion);
        html.append("</div>");

        // 版本列表（含当前版本），按时间戳降序，最新在最上面
        Map<String, Long> allVersions = computeAllVersionStats();
        if (!allVersions.isEmpty()) {
            List<Map.Entry<String, Long>> versionList =
                    new ArrayList<Map.Entry<String, Long>>(allVersions.entrySet());
            Collections.sort(versionList, new java.util.Comparator<Map.Entry<String, Long>>() {
                @Override
                public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                    return b.getKey().compareTo(a.getKey());
                }
            });

            html.append("<div class=\"card\"><h2>版本</h2>");
            boolean isFirst = true;
            for (Map.Entry<String, Long> e : versionList) {
                String vk = e.getKey();
                String readable = formatVersionTimestamp(vk);
                boolean isCurrent = vk.equals(currentVersion);

                if (isFirst) {
                    html.append("<div style=\"color:#4ade80;font-size:11px;")
                            .append("font-weight:600;letter-spacing:0.5px;")
                            .append("margin:12px 0 6px 2px;\">目前最新版本</div>");
                }

                String extraStyle = isCurrent
                        ? "background:#16281e;border:1px solid #2a4a35;"
                        : "";

                html.append("<a class=\"btn\" style=\"display:block;text-align:left;")
                        .append(extraStyle).append("\" href=\"https://")
                        .append(CMD_HOST).append(PATH_CLEAR)
                        .append("?version=").append(esc(vk)).append("\">")
                        .append("<span style=\"display:block;font-size:13px;")
                        .append(isCurrent ? "color:#4ade80;" : "color:#e8e8f0;")
                        .append("\">").append(esc(readable)).append("</span>")
                        .append("<span style=\"display:block;color:#888;font-size:11px;")
                        .append("font-weight:400;margin-top:3px;\">")
                        .append(fmtMB(e.getValue()))
                        .append("</span>")
                        .append("</a>");

                isFirst = false;
            }
            html.append("</div>");
        }

        html.append("<div class=\"card\"><h2>按时间</h2>")
                .append("<a class=\"btn\" href=\"https://").append(CMD_HOST).append(PATH_CLEAR)
                .append("?older=30\">清理 30 天前的</a>")
                .append("<a class=\"btn\" href=\"https://").append(CMD_HOST).append(PATH_CLEAR)
                .append("?older=90\">清理 90 天前的</a>")
                .append("</div>");

        html.append("<div class=\"card\"><h2>全部清空</h2>")
                .append("<a class=\"btn btn-danger\" href=\"https://")
                .append(CMD_HOST).append(PATH_CLEAR).append("?all=1\">清空全部缓存</a>")
                .append("</div>");

        // 不起眼的整理入口
        html.append("<div style=\"text-align:center;margin-top:20px;\">")
                .append("<a href=\"https://").append(CMD_HOST).append(PATH_CLEAR)
                .append("?action=sync\" style=\"color:#555;font-size:11px;")
                .append("text-decoration:none;\">整理缓存（修正老资源结构，一般不需要）</a>")
                .append("</div>");

        html.append("<div class=\"footer\">GBF Cache Hook</div>")
                .append("</body></html>");

        return htmlResponse(html.toString());
    }

    private static void appendQualityRow(StringBuilder html, String quality,
                                         String label, long[] stat, String version) {
        if (stat == null) stat = new long[]{0, 0};
        String href = "https://" + CMD_HOST + PATH_CLEAR
                + "?quality=" + quality
                + (version != null ? "&version=" + version : "");
        html.append("<a class=\"btn\" style=\"display:flex;justify-content:space-between;"
                        + "text-align:left;\" href=\"").append(href).append("\">")
                .append("<span>").append(esc(label)).append("</span>")
                .append("<span style=\"color:#a0a0b0;\">").append(fmtMB(stat[0]))
                .append(" · ").append(stat[1]).append(" 个</span>")
                .append("</a>");
    }

    private static Map<String, long[]> computeCurrentVersionStats() {
        Map<String, long[]> stats = new LinkedHashMap<String, long[]>();
        String[] qualities = {"img_low", "img_mid", "img", "css_low", "css_mid", "css", "js"};
        for (String q : qualities) stats.put(q, new long[]{0L, 0L});

        if (CACHE_ROOT == null) return stats;
        String currentVersion = readVersion();

        File[] hosts = CACHE_ROOT.listFiles();
        if (hosts == null) return stats;

        for (File host : hosts) {
            if (!host.isDirectory()) continue;
            File assets = new File(host, "assets");
            if (!assets.isDirectory()) continue;

            for (String q : new String[]{"img_low", "img_mid", "img"}) {
                File dir = new File(assets, q);
                if (dir.isDirectory()) addDirStats(dir, stats.get(q));
            }
            if (currentVersion != null) {
                File vdir = new File(assets, currentVersion);
                if (vdir.isDirectory()) {
                    for (String q : new String[]{"css_low", "css_mid", "css", "js"}) {
                        File dir = new File(vdir, q);
                        if (dir.isDirectory()) addDirStats(dir, stats.get(q));
                    }
                }
            }
        }
        return stats;
    }

    private static void addDirStats(File dir, long[] out) {
        if (dir == null || out == null || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) addDirStats(f, out);
            else if (f.isFile()) { out[0] += f.length(); out[1]++; }
        }
    }

    private static Map<String, Long> computeOldVersionStats() {
        Map<String, Long> out = new LinkedHashMap<String, Long>();
        if (CACHE_ROOT == null) return out;
        String currentVersion = readVersion();

        File[] hosts = CACHE_ROOT.listFiles();
        if (hosts == null) return out;

        for (File host : hosts) {
            if (!host.isDirectory()) continue;
            File assets = new File(host, "assets");
            if (!assets.isDirectory()) continue;
            File[] dirs = assets.listFiles();
            if (dirs == null) continue;
            for (File d : dirs) {
                if (!d.isDirectory()) continue;
                String name = d.getName();
                if (!name.matches("\\d+")) continue;
                if (name.equals(currentVersion)) continue;
                Long prev = out.get(name);
                long size = dirSize(d);
                out.put(name, (prev == null ? 0L : prev) + size);
            }
        }
        return out;
    }

    /*
     * 返回所有版本目录（含当前版本）的总大小。
     * key 为版本号字符串，value 为字节数。
     */
    private static Map<String, Long> computeAllVersionStats() {
        Map<String, Long> out = new LinkedHashMap<String, Long>();
        if (CACHE_ROOT == null) return out;

        File[] hosts = CACHE_ROOT.listFiles();
        if (hosts == null) return out;

        for (File host : hosts) {
            if (!host.isDirectory()) continue;
            File assets = new File(host, "assets");
            if (!assets.isDirectory()) continue;
            File[] dirs = assets.listFiles();
            if (dirs == null) continue;
            for (File d : dirs) {
                if (!d.isDirectory()) continue;
                String name = d.getName();
                if (!name.matches("\\d+")) continue;
                Long prev = out.get(name);
                long size = dirSize(d);
                out.put(name, (prev == null ? 0L : prev) + size);
            }
        }
        return out;
    }

    /*
     * 把版本号时间戳转成可读时间。
     * 支持三种常见格式：
     *   14 位 → yyyyMMddHHmmss
     *   13 位 → Unix 毫秒
     *   10 位 → Unix 秒
     * 解析失败时原样返回。
     */
    private static String formatVersionTimestamp(String version) {
        if (version == null || version.isEmpty()) return "(未知)";
        try {
            if (version.matches("\\d{14}")) {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                        "yyyyMMddHHmmss", java.util.Locale.US);
                java.util.Date d = sdf.parse(version);
                if (d != null) {
                    return android.text.format.DateFormat.format(
                            "yyyy-MM-dd HH:mm:ss", d).toString();
                }
            }
            if (version.matches("\\d{13}")) {
                long ms = Long.parseLong(version);
                return android.text.format.DateFormat.format(
                        "yyyy-MM-dd HH:mm:ss", ms).toString();
            }
            if (version.matches("\\d{10}")) {
                long s = Long.parseLong(version);
                return android.text.format.DateFormat.format(
                        "yyyy-MM-dd HH:mm:ss", s * 1000L).toString();
            }
        } catch (Throwable ignored) {}
        return version;
    }



    private static WebResourceResponse pageConfirmVersionPurge(String version, String quality) {
        String label;
        if (quality != null) {
            label = "画质 " + quality + (version != null ? "（版本 " + version + "）" : "");
        } else {
            label = "版本 " + version + " 的全部内容";
        }

        long estimate = 0;
        if (quality != null) {
            long[] v = computeCurrentVersionStats().get(quality);
            estimate = v == null ? 0 : v[0];
        } else {
            Map<String, Long> old = computeOldVersionStats();
            Long v = old.get(version);
            estimate = v == null ? 0 : v;
        }

        StringBuilder href = new StringBuilder();
        href.append("https://").append(CMD_HOST).append(PATH_CLEAR);
        boolean first = true;
        if (version != null) { href.append(first ? "?" : "&").append("version=").append(version); first = false; }
        if (quality != null) { href.append(first ? "?" : "&").append("quality=").append(quality); first = false; }
        href.append(first ? "?" : "&").append("confirm=1");

        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>确认清理</title><style>" + css() + "</style></head><body>"
                + "<div class=\"card\">"
                + "<h1 style=\"color:#f87171;\">确认清理？</h1>"
                + "<p style=\"color:#a0a0b0;font-size:13px;\">将清理：" + esc(label) + "</p>"
                + "<div style=\"text-align:center;margin:16px 0;\">"
                + "<div class=\"big\">" + fmtMB(estimate) + "</div></div>"
                + "<a class=\"btn btn-danger\" href=\"" + href + "\">确认清理</a>"
                + "<a class=\"btn\" href=\"https://" + CMD_HOST + PATH_CLEAR + "\">取消</a>"
                + "<div class=\"footer\">GBF Cache Hook</div>"
                + "</div></body></html>";
        return htmlResponse(html);
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
                + "<a class=\"btn\" href=\"https://" + CMD_HOST + PATH_FILES + "\">文件管理</a>"
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

    // ================== 文件管理器 ==================

    private static WebResourceResponse pageFiles(String relPath) {
        if (CACHE_ROOT == null) return pageSimple("错误", "缓存未初始化", "");
        if (relPath == null) relPath = "";

        File target;
        try {
            target = new File(CACHE_ROOT, relPath).getCanonicalFile();
            String root = CACHE_ROOT.getCanonicalPath();
            if (!target.getPath().equals(root)
                    && !target.getPath().startsWith(root + File.separator)) {
                return pageSimple("错误", "非法路径", esc(relPath));
            }
        } catch (Throwable t) {
            return pageSimple("错误", "路径解析失败", String.valueOf(t));
        }

        if (target.isFile()) return pageFileDetail(target, relPath);
        if (!target.isDirectory()) return pageSimple("错误", "路径不存在", esc(relPath));

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>文件管理</title><style>").append(css()).append("</style>")
                .append("</head><body>");

        html.append("<div class=\"card\">")
                .append("<h1>文件管理</h1>")
                .append("<div style=\"color:#a0a0b0;font-size:12px;word-break:break-all;\">")
                .append(renderBreadcrumb(relPath))
                .append("</div>")
                .append("<div style=\"color:#888;font-size:12px;margin-top:6px;\">")
                .append(fmtMB(dirSize(target))).append(" · ")
                .append(countFilesIn(target)).append(" 个文件</div>")
                .append("</div>");

        html.append("<div class=\"card\">");

        if (!relPath.isEmpty()) {
            String parent = parentPath(relPath);
            html.append("<a class=\"btn\" href=\"https://").append(CMD_HOST).append(PATH_FILES)
                    .append("?path=").append(Uri.encode(parent))
                    .append("\">⬆ 返回上级</a>");
        }

        File[] children = target.listFiles();
        if (children != null) {
            java.util.Arrays.sort(children, new java.util.Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) {
                        return a.isDirectory() ? -1 : 1;
                    }
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });

            for (File f : children) {
                String childRel = relPath.isEmpty() ? f.getName() : relPath + "/" + f.getName();
                String encoded = Uri.encode(childRel);

                if (f.isDirectory()) {
                    html.append("<a class=\"btn\" style=\"display:flex;justify-content:space-between;"
                                    + "text-align:left;\" href=\"https://").append(CMD_HOST)
                            .append(PATH_FILES).append("?path=").append(encoded).append("\">")
                            .append("<span>📁 ").append(esc(f.getName())).append("</span>")
                            .append("<span style=\"color:#a0a0b0;font-size:12px;\">")
                            .append(fmtMB(dirSize(f))).append("</span></a>");
                } else {
                    html.append("<div style=\"display:flex;justify-content:space-between;")
                            .append("align-items:center;padding:8px 0;border-bottom:1px solid #2a2a3e;\">")
                            .append("<a href=\"https://").append(CMD_HOST).append(PATH_FILES)
                            .append("?path=").append(encoded)
                            .append("\" style=\"color:#c0c0d0;text-decoration:none;flex:1;")
                            .append("word-break:break-all;font-size:12px;\">")
                            .append(esc(f.getName())).append("</a>")
                            .append("<span style=\"color:#888;font-size:11px;margin:0 8px;\">")
                            .append(fmtMB(f.length())).append("</span>")
                            .append("<a href=\"https://").append(CMD_HOST).append(PATH_FILES)
                            .append("?path=").append(encoded).append("&action=delete&confirm=1")
                            .append("\" style=\"color:#f87171;font-size:12px;text-decoration:none;\">")
                            .append("删</a>")
                            .append("</div>");
                }
            }
        }

        html.append("</div>");
        html.append("<div class=\"footer\">GBF Cache Hook</div>")
                .append("</body></html>");
        return htmlResponse(html.toString());
    }

    private static WebResourceResponse pageFileDetail(File file, String relPath) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>文件详情</title><style>").append(css()).append("</style>")
                .append("</head><body>");

        html.append("<div class=\"card\"><h1>文件详情</h1>")
                .append("<div style=\"word-break:break-all;font-size:12px;color:#c0c0d0;\">")
                .append(renderBreadcrumb(relPath)).append("</div>")
                .append("<div class=\"row\" style=\"margin-top:12px;\">")
                .append("<span class=\"key\">大小</span>")
                .append("<span class=\"val\">").append(fmtMB(file.length())).append("</span></div>")
                .append("<div class=\"row\"><span class=\"key\">修改时间</span>")
                .append("<span class=\"val\">")
                .append(android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", file.lastModified()))
                .append("</span></div>")
                .append("<a class=\"btn btn-danger\" href=\"https://").append(CMD_HOST)
                .append(PATH_FILES).append("?path=").append(Uri.encode(relPath))
                .append("&action=delete&confirm=1\">删除此文件</a>")
                .append("<a class=\"btn\" href=\"https://").append(CMD_HOST).append(PATH_FILES)
                .append("?path=").append(Uri.encode(parentPath(relPath)))
                .append("\">返回</a>")
                .append("</div>");

        html.append("<div class=\"footer\">GBF Cache Hook</div></body></html>");
        return htmlResponse(html.toString());
    }

    private static WebResourceResponse handleFileDelete(String relPath) {
        if (CACHE_ROOT == null) return pageSimple("错误", "缓存未初始化", "");
        File target;
        try {
            target = new File(CACHE_ROOT, relPath).getCanonicalFile();
            String root = CACHE_ROOT.getCanonicalPath();
            if (!target.getPath().startsWith(root + File.separator)) {
                return pageSimple("错误", "非法路径", esc(relPath));
            }
        } catch (Throwable t) {
            return pageSimple("错误", "路径解析失败", String.valueOf(t));
        }

        boolean ok = false;
        try {
            if (target.isDirectory()) {
                deleteRecursive(target);
                ok = !target.exists();
            } else if (target.isFile()) {
                ok = target.delete();
            }
        } catch (Throwable ignored) {}

        String parent = parentPath(relPath);
        String msg = ok ? "已删除：" + relPath : "删除失败：" + relPath;
        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>删除结果</title><style>" + css() + "</style></head><body>"
                + "<div class=\"card\"><h1>" + (ok ? "✓ 已删除" : "✗ 删除失败") + "</h1>"
                + "<p style=\"color:#a0a0b0;font-size:12px;word-break:break-all;\">"
                + esc(msg) + "</p>"
                + "<a class=\"btn\" href=\"https://" + CMD_HOST + PATH_FILES
                + "?path=" + Uri.encode(parent) + "\">返回上级</a>"
                + "</div></body></html>";
        return htmlResponse(html);
    }

    private static String parentPath(String relPath) {
        if (relPath == null || relPath.isEmpty()) return "";
        int idx = relPath.lastIndexOf('/');
        return idx <= 0 ? "" : relPath.substring(0, idx);
    }

    private static String renderBreadcrumb(String relPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("<a href=\"https://").append(CMD_HOST).append(PATH_FILES)
                .append("\" style=\"color:#60a5fa;text-decoration:none;\">根目录</a>");
        if (relPath == null || relPath.isEmpty()) return sb.toString();

        String[] parts = relPath.split("/");
        StringBuilder accum = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (accum.length() > 0) accum.append('/');
            accum.append(part);
            sb.append(" / <a href=\"https://").append(CMD_HOST).append(PATH_FILES)
                    .append("?path=").append(Uri.encode(accum.toString()))
                    .append("\" style=\"color:#60a5fa;text-decoration:none;\">")
                    .append(esc(part)).append("</a>");
        }
        return sb.toString();
    }

    private static int countFilesIn(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        int n = 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        for (File f : children) {
            if (f.isDirectory()) n += countFilesIn(f);
            else n++;
        }
        return n;
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
        if ("gbf.game.mbga.jp".equalsIgnoreCase(host)
                || "game.granbluefantasy.jp".equalsIgnoreCase(host)) {
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
