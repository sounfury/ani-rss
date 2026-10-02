package ani.rss.util.other;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import cn.hutool.crypto.SecureUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 仅持久化本下载器的任务及文件归属；不含凭据。 */
public class Open115TaskStore {
    public static class Task {
        public String root;
        public String aniId;
        public Integer season;
        public Double episode;
        public String subgroup;
        public Boolean master;
        public String hash;
        public String finalCid;
        public String stagingCid;
        public String savePath;
        public boolean complete;
        public boolean postProcessed;
        public boolean notified;
        public List<CloudFile> files = new ArrayList<>();
    }

    public static class CloudFile {
        public String id;
        public String parent;
        public String name;
        public String targetName;
    }

    private static Path directory() { return ConfigUtil.getConfigDir().toPath().resolve("open115-tasks"); }

    private static Path path(String root, String aniId, String hash) {
        return directory().resolve(SecureUtil.sha256(root + ":" + aniId + ":" + hash) + ".json");
    }

    public static synchronized Task load(String root, String aniId, String hash) {
        Path path = path(root, aniId, hash);
        if (!Files.exists(path)) return null;
        try {
            Task task = GsonStatic.GSON.fromJson(Files.readString(path), Task.class);
            if (task == null || !Objects.equals(root, task.root) || !Objects.equals(aniId, task.aniId) || !Objects.equals(hash, task.hash)) {
                throw new IllegalStateException("115 任务记录归属校验失败");
            }
            return task;
        } catch (IOException | com.google.gson.JsonParseException e) {
            throw new IllegalStateException("无法读取 115 任务记录，请检查本地配置目录");
        }
    }

    public static boolean completed(Config config, Ani ani, Item item) {
        Task task = find(config, ani, item);
        return task != null && task.complete && task.notified;
    }

    public static Task find(Config config, Ani ani, Item item) {
        String hash = item.getInfoHash();
        if (hash == null || hash.isBlank()) return null;
        try { hash = Open115Util.normalizeHash(hash); }
        catch (IllegalArgumentException e) { return null; }
        return load(config.getOpen115RootCid(), ani.getId(), hash);
    }

    public static synchronized void save(Task task) {
        try {
            Files.createDirectories(directory());
            Path target = path(task.root, task.aniId, task.hash);
            Path temp = Files.createTempFile(directory(), "task-", ".tmp");
            try {
                Files.writeString(temp, GsonStatic.GSON.toJson(task), StandardCharsets.UTF_8);
                try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temp); }
        } catch (IOException e) {
            throw new IllegalStateException("无法保存 115 任务记录，请检查本地配置目录");
        }
    }

    public static synchronized List<Task> tasks(String root, String aniId) {
        if (!Files.isDirectory(directory())) return List.of();
        try (var files = Files.list(directory())) {
            List<Task> tasks = new ArrayList<>();
            for (Path file : files.filter(f -> f.getFileName().toString().matches("[a-f0-9]{64}\\.json")).toList()) {
                Task task = GsonStatic.GSON.fromJson(Files.readString(file), Task.class);
                if (task != null && Objects.equals(root, task.root) && Objects.equals(aniId, task.aniId)) tasks.add(task);
            }
            return tasks;
        } catch (IOException | com.google.gson.JsonParseException e) {
            throw new IllegalStateException("无法读取 115 洗版记录");
        }
    }
}
