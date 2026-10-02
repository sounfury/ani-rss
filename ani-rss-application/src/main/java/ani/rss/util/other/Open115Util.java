package ani.rss.util.other;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import cn.hutool.core.codec.Base32;
import cn.hutool.core.util.HexUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.io.IORuntimeException;
import cn.hutool.http.HttpException;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.Method;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 115 Open 请求与令牌。响应原文和凭据均不进入日志或异常。 */
public class Open115Util {
    public static final Object CREDENTIAL_LOCK = new Object();
    private static final Object RATE_LOCK = new Object();
    private static long lastRequest;
    private final Config config;
    private final boolean persist;
    private String accessToken;
    private long deadline = Long.MAX_VALUE;

    public Open115Util(Config config, boolean persist) {
        this.config = config;
        this.persist = persist;
    }

    public void deadline(long deadline) {
        this.deadline = deadline;
    }

    public static boolean isEnabled(Config config) {
        return "Open115".equals(config.getDownloadToolType());
    }

    public static String relativePath(String path, boolean template) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("115 保存位置不能为空，请填写相对根 cid 的模板");
        }
        String value = path.trim().replace('\\', '/');
        if (value.startsWith("/") || value.contains(":") || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("115 保存位置必须是相对路径，不能使用盘符或绝对路径");
        }
        for (String part : value.split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..") || part.getBytes(StandardCharsets.UTF_8).length > 255) {
                throw new IllegalArgumentException("115 路径包含空目录、越级路径或过长目录名");
            }
        }
        if (!template && value.contains("${")) {
            throw new IllegalArgumentException("115 路径仍包含未替换的模板变量");
        }
        return value;
    }

    public static String infoHash(String magnet) {
        var matcher = Pattern.compile("(?i)(?:[?&])xt=urn:btih:([a-z0-9]+)(?:&|$)").matcher(magnet);
        if (!matcher.find()) {
            throw new IllegalArgumentException("115 仅支持包含 BTIH 的磁力资源");
        }
        return normalizeHash(matcher.group(1));
    }

    public static String normalizeHash(String hash) {
        if (hash != null && hash.matches("(?i)[0-9a-f]{40}")) {
            return hash.toLowerCase(Locale.ROOT);
        }
        if (hash != null && hash.matches("(?i)[a-z2-7]{32}")) {
            return HexUtil.encodeHexStr(Base32.decode(hash.toUpperCase(Locale.ROOT)));
        }
        throw new IllegalArgumentException("无效的 BTIH");
    }

    public static boolean usesOpenList(Config config) {
        return isEnabled(config) && Boolean.TRUE.equals(config.getOpen115UseOpenList());
    }

    /** 115 相对保存路径映射到本机 strm 刮削目录；未配置则空字符串。 */
    public static String localScrapeDir(Config config, String relativeDownloadPath) {
        String root = StrUtil.trim(config.getOpen115ScrapePath());
        if (StrUtil.isBlank(root)) {
            return "";
        }
        File rootFile = new File(root);
        if (!rootFile.isAbsolute()) {
            throw new IllegalArgumentException("115 本地刮削目录必须是绝对路径");
        }
        return new File(rootFile, relativePath(relativeDownloadPath, false)).getAbsolutePath();
    }

    /** 调用方持有 CREDENTIAL_LOCK；空输入始终保留后台当前凭据。 */
    public static void mergeCredentials(Config incoming, Config saved) {
        if (Boolean.TRUE.equals(incoming.getOpen115ClearCredentials())) {
            incoming.setOpen115AccessToken("").setOpen115RefreshToken("").setOpen115OpenListToken("");
        } else {
            if (StrUtil.isBlank(incoming.getOpen115AccessToken())) incoming.setOpen115AccessToken(saved.getOpen115AccessToken());
            if (StrUtil.isBlank(incoming.getOpen115RefreshToken())) incoming.setOpen115RefreshToken(saved.getOpen115RefreshToken());
            if (StrUtil.isBlank(incoming.getOpen115OpenListToken())) incoming.setOpen115OpenListToken(saved.getOpen115OpenListToken());
        }
        incoming.setOpen115ClearCredentials(false);
    }

    public static void hideCredentials(Config config) {
        config.setOpen115AccessTokenConfigured(StrUtil.isNotBlank(config.getOpen115AccessToken()))
                .setOpen115RefreshTokenConfigured(StrUtil.isNotBlank(config.getOpen115RefreshToken()))
                .setOpen115OpenListTokenConfigured(StrUtil.isNotBlank(config.getOpen115OpenListToken()))
                .setOpen115AccessToken("").setOpen115RefreshToken("").setOpen115OpenListToken("")
                .setOpen115ClearCredentials(false);
    }

    public void validate() throws IOException, InterruptedException {
        String root = config.getOpen115RootCid();
        if (root == null || !root.matches("[1-9][0-9]*")) throw new IllegalArgumentException("请填写独立番剧根 cid，不能使用网盘根目录 0");
        relativePath(config.getDownloadPathTemplate(), true);
        relativePath(config.getOvaDownloadPathTemplate(), true);
        api("/open/user/info", Map.of(), false);
        JsonObject quota = api("/open/offline/get_quota_info", Map.of(), false).getAsJsonObject("data");
        if (quota == null || quota.get("surplus").getAsLong() <= 0) throw new IllegalArgumentException("115 离线额度不足");
        JsonObject folder = folderInfo(root);
        if (!"0".equals(string(folder, "file_category"))) throw new IllegalArgumentException("番剧根 cid 必须是文件夹");
    }

    public JsonObject folderInfo(String id) throws IOException, InterruptedException {
        JsonElement data = api("/open/folder/get_info", Map.of("file_id", id), false).get("data");
        if (data.isJsonArray()) data = data.getAsJsonArray().get(0);
        return data.getAsJsonObject();
    }

    public List<JsonObject> files(String cid) throws IOException, InterruptedException {
        List<JsonObject> files = new ArrayList<>();
        for (int offset = 0; offset < 100_000; offset += 200) {
            JsonObject body = api("/open/ufile/files", Map.of("cid", cid, "limit", "200", "offset", String.valueOf(offset), "show_dir", "1"), false);
            JsonArray data = body.getAsJsonArray("data");
            for (JsonElement element : data) files.add(element.getAsJsonObject());
            if (files.size() >= body.get("count").getAsLong() || data.isEmpty()) return files;
        }
        throw new IllegalStateException("115 目录分页超出安全上限");
    }

    public String ensureFolder(String parent, String name) throws IOException, InterruptedException {
        relativePath(name, false);
        if (name.contains("/")) throw new IllegalArgumentException("目录名不能含路径分隔符");
        for (JsonObject f : files(parent)) {
            if ("0".equals(string(f, "fc")) && name.equals(string(f, "fn"))) return string(f, "fid");
        }
        try {
            return string(api("/open/folder/add", Map.of("pid", parent, "file_name", name), true).getAsJsonObject("data"), "file_id");
        } catch (ApiException | IOException e) {
            // 创建超时或另一线程已创建时，只接管同名目录，不重复创建。
            for (JsonObject f : files(parent)) {
                if ("0".equals(string(f, "fc")) && name.equals(string(f, "fn"))) return string(f, "fid");
            }
            throw e;
        }
    }

    public JsonObject findTask(String hash) throws IOException, InterruptedException {
        for (int page = 1; page <= 1000; page++) {
            JsonObject data = api("/open/offline/get_task_list", Map.of("page", String.valueOf(page)), false).getAsJsonObject("data");
            for (JsonElement task : data.getAsJsonArray("tasks")) {
                JsonObject t = task.getAsJsonObject();
                if (hash.equalsIgnoreCase(string(t, "info_hash"))) return t;
            }
            if (page >= data.get("page_count").getAsInt()) return null;
        }
        throw new IllegalStateException("115 任务分页超出安全上限");
    }

    public void submit(String magnet, String hash, String cid) throws IOException, InterruptedException {
        JsonObject response = api("/open/offline/add_task_urls", Map.of("urls", magnet, "wp_path_id", cid), true);
        JsonArray data = response.getAsJsonArray("data");
        if (data == null || data.size() != 1) throw new IllegalStateException("115 提交结果缺少逐链接状态");
        JsonObject result = data.get(0).getAsJsonObject();
        if (!truth(result, "state")) throw new ApiException("115 单链接提交失败", number(result, "code"));
        if (!hash.equalsIgnoreCase(string(result, "info_hash"))) throw new IllegalStateException("115 返回任务 hash 与磁力不一致");
    }

    public void deleteTask(String hash) throws IOException, InterruptedException {
        api("/open/offline/del_task", Map.of("info_hash", hash, "del_source_file", "0"), true);
    }

    public void move(String id, String cid) throws IOException, InterruptedException {
        api("/open/ufile/move", Map.of("file_ids", id, "to_cid", cid), true);
    }

    public void rename(String id, String name) throws IOException, InterruptedException {
        relativePath(name, false);
        if (name.contains("/")) throw new IllegalArgumentException("文件名不能含路径分隔符");
        api("/open/ufile/update", Map.of("file_id", id, "file_name", name), true);
    }

    public void deleteFile(String id, String parent) throws IOException, InterruptedException {
        api("/open/ufile/delete", Map.of("file_ids", id, "parent_id", parent), true);
    }

    private JsonObject api(String endpoint, Map<String, String> parameters, boolean post) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (accessToken == null) accessToken = readAccess();
            try {
                JsonObject result = request("https://proapi.115.com" + endpoint, parameters, post, accessToken);
                if (truth(result, "state")) return result;
                throw new ApiException("115 " + endpoint + " 调用失败", number(result, "code"));
            } catch (ApiException e) {
                if (attempt == 0 && (e.code == 99 || e.code == 401 || String.valueOf(e.code).startsWith("401"))) {
                    refresh();
                    continue;
                }
                throw e;
            }
        }
        throw new IllegalStateException("115 鉴权失败，请重新授权");
    }

    private String readAccess() throws IOException, InterruptedException {
        if (usesOpenList(config)) {
            return readOpenListAccess();
        }
        if (StrUtil.isBlank(config.getOpen115AccessToken()) && StrUtil.isBlank(config.getOpen115RefreshToken())) {
            throw new IllegalArgumentException("请填写自行申请的 115 应用凭据，或改用 OpenList 令牌");
        }
        return StrUtil.blankToDefault(config.getOpen115AccessToken(), "");
    }

    private void refresh() throws IOException, InterruptedException {
        synchronized (CREDENTIAL_LOCK) {
            checkDeadline();
            if (usesOpenList(config)) {
                refreshOpenList();
                accessToken = readOpenListAccess();
                return;
            }
            String oldRefresh = config.getOpen115RefreshToken();
            if (StrUtil.isBlank(oldRefresh)) throw new IllegalArgumentException("独立应用未配置 refresh_token");
            if (persist && !Objects.equals(oldRefresh, ConfigUtil.CONFIG.getOpen115RefreshToken())) {
                config.setOpen115AccessToken(ConfigUtil.CONFIG.getOpen115AccessToken()).setOpen115RefreshToken(ConfigUtil.CONFIG.getOpen115RefreshToken());
                accessToken = config.getOpen115AccessToken();
                return;
            }
            // 不轮换尚未保存的新凭据，避免关闭设置或重启后丢失新 refresh_token。
            if (!persist && !Objects.equals(oldRefresh, ConfigUtil.CONFIG.getOpen115RefreshToken())) {
                throw new IllegalArgumentException("独立应用凭据已过期，请先保存再测试；测试不会轮换未保存的 refresh_token");
            }
            JsonObject result = request("https://passportapi.115.com/open/refreshToken", Map.of("refresh_token", oldRefresh), true, null);
            if (number(result, "code") != 0) throw new IllegalStateException("独立 115 应用刷新失败，请重新授权");
            JsonObject data = result.getAsJsonObject("data");
            String access = string(data, "access_token"), refresh = string(data, "refresh_token");
            if (access.isBlank() || refresh.isBlank()) throw new IllegalStateException("115 刷新响应缺少凭据");
            config.setOpen115AccessToken(access).setOpen115RefreshToken(refresh);
            accessToken = access;
            if (Objects.equals(oldRefresh, ConfigUtil.CONFIG.getOpen115RefreshToken())) {
                ConfigUtil.CONFIG.setOpen115AccessToken(access).setOpen115RefreshToken(refresh);
                ConfigUtil.sync();
            }
        }
    }

    private String openListHost() {
        String host = StrUtil.trim(config.getOpen115OpenListHost());
        if (StrUtil.isBlank(host)) throw new IllegalArgumentException("请填写 OpenList 地址");
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            throw new IllegalArgumentException("OpenList 地址必须是 http 或 https");
        }
        return host;
    }

    private String openListMount() {
        String mount = StrUtil.trim(config.getOpen115OpenListMountPath());
        if (StrUtil.isBlank(mount) || !mount.startsWith("/")) {
            throw new IllegalArgumentException("请填写 OpenList 存储挂载路径，例如 /115");
        }
        if (mount.length() > 1 && mount.endsWith("/")) mount = mount.substring(0, mount.length() - 1);
        for (String part : mount.substring(1).split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("OpenList 挂载路径不能包含空目录或越级路径");
            }
        }
        return mount;
    }

    private String readOpenListAccess() throws IOException, InterruptedException {
        if (StrUtil.isBlank(config.getOpen115OpenListToken())) {
            throw new IllegalArgumentException("请填写 OpenList Token");
        }
        String mount = openListMount();
        JsonObject result = openList("GET", "/api/admin/storage/list", null);
        JsonElement data = result.get("data");
        JsonArray content = new JsonArray();
        if (data != null && data.isJsonObject() && data.getAsJsonObject().has("content")) {
            content = data.getAsJsonObject().getAsJsonArray("content");
        } else if (data != null && data.isJsonArray()) {
            content = data.getAsJsonArray();
        }
        for (JsonElement element : content) {
            JsonObject storage = element.getAsJsonObject();
            if (!mount.equals(string(storage, "mount_path"))) continue;
            if (storage.has("disabled") && storage.get("disabled").getAsBoolean()) {
                throw new IllegalStateException("OpenList 存储已禁用");
            }
            if (!"115 Open".equals(string(storage, "driver"))) {
                throw new IllegalStateException("该挂载不是 115 Open 存储");
            }
            JsonObject addition = addition(storage);
            String access = string(addition, "access_token");
            if (access.isBlank()) throw new IllegalStateException("OpenList 该存储没有可用的 access_token，请先重新授权");
            return access;
        }
        throw new IllegalArgumentException("OpenList 未找到指定挂载路径");
    }

    private void refreshOpenList() throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("path", openListMount());
        body.addProperty("refresh", true);
        body.addProperty("page", 1);
        body.addProperty("per_page", 1);
        openList("POST", "/api/fs/list", GsonStatic.GSON.toJson(body));
    }

    private JsonObject addition(JsonObject storage) {
        JsonElement raw = storage.get("addition");
        if (raw == null || raw.isJsonNull()) return new JsonObject();
        if (raw.isJsonObject()) return raw.getAsJsonObject();
        try {
            return GsonStatic.GSON.fromJson(raw.getAsString(), JsonObject.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("OpenList 存储配置无法解析");
        }
    }

    private JsonObject openList(String method, String path, String jsonBody) throws IOException, InterruptedException {
        checkDeadline();
        int timeout = (int) Math.max(1, Math.min(20_000, deadline - System.currentTimeMillis()));
        HttpRequest request = HttpRequest.of(openListHost() + path)
                .method("POST".equals(method) ? Method.POST : Method.GET)
                .timeout(timeout).setConnectionTimeout(Math.min(15_000, timeout))
                .setFollowRedirects(false).disableCookie()
                .header("Authorization", config.getOpen115OpenListToken());
        if (jsonBody != null) {
            request.body(jsonBody).header("Content-Type", "application/json");
        }
        int status;
        String responseBody;
        try (var response = request.execute()) {
            status = response.getStatus();
            responseBody = response.body();
        } catch (IORuntimeException | HttpException e) {
            throw new IOException("OpenList 网络请求失败（" + e.getClass().getSimpleName() + "）");
        }
        checkDeadline();
        if (status < 200 || status >= 300) throw new ApiException("OpenList HTTP 请求失败", status);
        JsonObject result;
        try {
            result = GsonStatic.GSON.fromJson(responseBody, JsonObject.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("OpenList 响应不是有效 JSON");
        }
        if (number(result, "code") != 200) {
            throw new IllegalStateException("OpenList 请求失败，请检查地址、Token、挂载路径或 115 授权");
        }
        return result;
    }

    private JsonObject request(String url, Map<String, String> parameters, boolean post, String token) throws IOException, InterruptedException {
        checkDeadline();
        synchronized (RATE_LOCK) {
            long remaining = 600 - (System.nanoTime() - lastRequest) / 1_000_000;
            if (remaining > 0) pause(remaining);
            lastRequest = System.nanoTime();
        }
        checkDeadline();
        String encoded = parameters.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(Collectors.joining("&"));
        int timeout = (int) Math.max(1, Math.min(20_000, deadline - System.currentTimeMillis()));
        // 沿用项目 Hutool 请求层；Oracle 实测 JDK HttpClient 对 115 会反复握手超时。
        HttpRequest request = HttpRequest.of(url + (!post && !encoded.isEmpty() ? "?" + encoded : ""))
                .method(post ? Method.POST : Method.GET).timeout(timeout)
                .setConnectionTimeout(Math.min(15_000, timeout)).setFollowRedirects(false).disableCookie();
        if (token != null && !token.isBlank()) request.header("Authorization", "Bearer " + token);
        if (post) {
            request.body(encoded).header("Content-Type", "application/x-www-form-urlencoded");
        }
        int status;
        String responseBody;
        try (var response = request.execute()) {
            status = response.getStatus();
            responseBody = response.body();
        } catch (IORuntimeException | HttpException e) {
            throw new IOException("115 网络请求失败（" + e.getClass().getSimpleName() + "）");
        }
        checkDeadline();
        if (status < 200 || status >= 300) throw new ApiException("115 HTTP 请求失败", status);
        try {
            return GsonStatic.GSON.fromJson(responseBody, JsonObject.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("115 响应不是有效 JSON");
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    public static String string(JsonObject object, String name) {
        return object != null && object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
    }
    private static long number(JsonObject object, String name) {
        String value = string(object, name);
        return value.isBlank() ? -1 : Long.parseLong(value);
    }
    private static boolean truth(JsonObject object, String name) {
        return object.has(name) && (object.get(name).getAsString().equals("true") || object.get(name).getAsString().equals("1"));
    }
    private void checkDeadline() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (persist && (!isEnabled(ConfigUtil.CONFIG)
                || !Objects.equals(config.getOpen115RootCid(), ConfigUtil.CONFIG.getOpen115RootCid()))) {
            throw new InterruptedException("115 下载器或根目录已切换");
        }
        if (System.currentTimeMillis() >= deadline) throw new IllegalStateException("115 离线处理超时，下一轮 RSS 将恢复任务");
    }
    public void pause(long millis) throws InterruptedException {
        checkDeadline();
        Thread.sleep(Math.min(millis, Math.max(1, deadline - System.currentTimeMillis())));
        checkDeadline();
    }
    public static class ApiException extends IllegalStateException {
        public final long code;
        public ApiException(String operation, long code) { super(operation + "，错误码 " + code); this.code = code; }
    }
}
