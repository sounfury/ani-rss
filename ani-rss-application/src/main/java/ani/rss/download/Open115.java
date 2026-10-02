package ani.rss.download;

import ani.rss.commons.FileUtils;
import ani.rss.entity.*;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.service.ScrapeService;
import ani.rss.util.other.*;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static ani.rss.util.other.Open115Util.string;

/** 同步离线下载；任务和文件只在显式番剧根 cid 下创建。 */
@Slf4j
@Service
public class Open115 implements BaseDownload {
    @Lazy
    @Resource
    private ScrapeService scrapeService;
    @Override
    public Boolean login(Boolean test, Config config) {
        try {
            new Open115Util(ObjectUtil.clone(config), !test).validate();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            throw new IllegalStateException("115 网络连接失败，请稍后重试");
        }
    }

    @Override
    public Boolean download(Ani ani, Item item, String savePath, File torrentFile) {
        Config config = ObjectUtil.clone(ConfigUtil.CONFIG);
        Open115Util api = new Open115Util(config, true);
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(Math.max(1, config.getOpenListDownloadTimeout())).toMillis();
        api.deadline(deadline);
        try {
            savePath = Open115Util.relativePath(savePath, false);
            String magnet = TorrentUtil.getMagnet(torrentFile);
            String hash = Open115Util.infoHash(magnet);
            if (StrUtil.isNotBlank(item.getInfoHash()) && !hash.equals(Open115Util.normalizeHash(item.getInfoHash()))) {
                throw new IllegalArgumentException("种子 hash 与订阅记录不一致");
            }
            api.validate();
            var task = Open115TaskStore.load(config.getOpen115RootCid(), ani.getId(), hash);
            if (task == null) {
                // 检查已有任务后才创建本地归属；不能接管手工或 NSFW 任务。
                if (api.findTask(hash) != null) throw new IllegalStateException("相同 hash 已存在非 ASS 当前任务，请先人工核对目录");
                task = new Open115TaskStore.Task();
                task.root = config.getOpen115RootCid();
                task.aniId = ani.getId(); task.season = ani.getSeason(); task.episode = item.getEpisode();
                task.master = item.getMaster(); task.subgroup = item.getSubgroup(); task.hash = hash; task.savePath = savePath;
                String cid = task.root;
                for (String segment : savePath.split("/")) cid = api.ensureFolder(cid, segment);
                task.finalCid = cid;
                task.stagingCid = api.ensureFolder(cid, ".ass-" + hash);
                if (!api.files(task.stagingCid).isEmpty()) throw new IllegalStateException("未登记的任务目录已有文件，拒绝接管");
                Open115TaskStore.save(task);
            }
            if (!task.savePath.equals(savePath)) throw new IllegalStateException("未完成任务保存位置已改变，请先恢复原位置");
            if (!task.complete) {
                // 已持久化文件选择时直接恢复移动/重命名，不再次提交离线任务。
                if (task.files.isEmpty()) {
                    waitForTask(api, task, magnet, config);
                    selectFiles(api, task, ani, item, config);
                }
                finishFiles(api, task);
                task.complete = true;
                Open115TaskStore.save(task);
            }
            if (!task.postProcessed) {
                wash(api, task, config);
                cleanup(api, task, config);
                task.postProcessed = true;
                Open115TaskStore.save(task);
            }
            if (!task.notified) {
                scrapeNow(ani, config);
                NotificationUtil.send(config, ani, item.getReName() + " 下载完成", NotificationStatusEnum.DOWNLOAD_END);
                task.notified = true;
                Open115TaskStore.save(task);
                scrapeAfterStrm(ani, config, item);
            }
            log.info("115 离线下载完成 {}", item.getReName());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("115 等待已中断，保留任务供下次恢复 {}", item.getReName());
        } catch (Exception e) {
            // 不输出 HTTP 响应或请求；异常只包含本下载器构造的安全描述。
            log.error("115 下载未完成 {}：{}", item.getReName(), e instanceof IOException ? "网络请求失败" : safeMessage(e));
        }
        return false;
    }

    private void scrapeNow(Ani ani, Config config) {
        if (!Boolean.TRUE.equals(config.getScrape()) || StrUtil.isBlank(config.getOpen115ScrapePath())) {
            return;
        }
        try {
            scrapeService.scrape(ani, false);
        } catch (Exception e) {
            log.error("刮削失败: {}", ani.getTitle());
            log.error(e.getMessage(), e);
        }
    }

    private void scrapeAfterStrm(Ani ani, Config config, Item item) {
        if (!Boolean.TRUE.equals(config.getScrape()) || StrUtil.isBlank(config.getOpen115ScrapePath())) {
            return;
        }
        int delay = ObjectUtil.defaultIfNull(config.getOpen115ScrapeDelay(), 20);
        if (delay <= 0) {
            return;
        }
        log.info("等待 {} 秒后刮削 strm {}", delay, item.getReName());
        ThreadUtil.sleep(delay, TimeUnit.SECONDS);
        scrapeNow(ani, config);
    }

    private String safeMessage(Exception e) {
        if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) return e.getMessage();
        return e.getClass().getSimpleName();
    }

    private void assertOwnership(JsonObject remote, Open115TaskStore.Task task) {
        if (!task.stagingCid.equals(string(remote, "wp_path_id"))) {
            throw new IllegalStateException("相同 hash 的离线任务位于其他目录，拒绝接管");
        }
    }

    private void waitForTask(Open115Util api, Open115TaskStore.Task task, String magnet, Config config) throws IOException, InterruptedException {
        long retry = 0;
        boolean submitted = false;
        long missingSince = System.currentTimeMillis();
        while (true) {
            JsonObject remote;
            try { remote = api.findTask(task.hash); }
            catch (IOException e) { api.pause(10_000); continue; }
            if (remote == null) {
                if (!submitted) {
                    // 提交失败/超时也先继续查状态，避免立即重复提交。
                    submitted = true;
                    missingSince = System.currentTimeMillis();
                    try { api.submit(magnet, task.hash, task.stagingCid); }
                    catch (Open115Util.ApiException | IOException e) {
                        log.warn("115 提交结果待确认 {}", task.hash);
                    }
                } else if (System.currentTimeMillis() - missingSince > 60_000) {
                    throw new IllegalStateException("115 提交后未找到任务，保留记录供下轮查询");
                }
                api.pause(10_000);
                continue;
            }
            assertOwnership(remote, task);
            int status = remote.get("status").getAsInt();
            if (status == 2) return;
            if (status == -1) {
                long limit = config.getOpenListDownloadRetryNumber();
                if (limit >= 0 && retry >= limit) throw new IllegalStateException("115 离线任务失败，已达到重试次数");
                retry++;
                api.deleteTask(task.hash); // 保留源文件，仅删除已确认归属的失败任务。
                api.pause(10_000);
                submitted = false;
            } else if (status != 0 && status != 1) {
                throw new IllegalStateException("115 返回未知离线任务状态 " + status);
            }
            api.pause(10_000);
        }
    }

    private void collect(Open115Util api, String cid, int depth, List<JsonObject> result, Set<String> visited) throws IOException, InterruptedException {
        if (depth > 8 || visited.size() >= 100 || result.size() >= 2000 || !visited.add(cid)) {
            throw new IllegalStateException("115 任务子目录超出安全遍历上限");
        }
        for (JsonObject file : api.files(cid)) {
            if ("0".equals(string(file, "fc"))) collect(api, string(file, "fid"), depth + 1, result, visited);
            else { file.addProperty("parent_cid", cid); result.add(file); }
        }
    }

    private void selectFiles(Open115Util api, Open115TaskStore.Task task, Ani ani, Item item, Config config) throws IOException, InterruptedException {
        List<JsonObject> all = new ArrayList<>();
        List<JsonObject> videos;
        // status=2 与目录内容可见之间允许短暂延迟。
        long visibilityDeadline = System.currentTimeMillis() + 60_000;
        do {
            all.clear();
            collect(api, task.stagingCid, 0, all, new HashSet<>());
            videos = all.stream().filter(f -> FileUtils.isVideoFormat(string(f, "fn"))).toList();
            if (!videos.isEmpty()) break;
            api.pause(10_000);
        } while (System.currentTimeMillis() < visibilityDeadline);
        if (videos.isEmpty()) throw new IllegalStateException("115 离线成功但未找到视频");
        List<String> keywords = Boolean.TRUE.equals(ani.getCustomPriorityKeywordsEnable()) ? ani.getCustomPriorityKeywords()
                : Boolean.TRUE.equals(config.getPriorityKeywordsEnable()) ? config.getPriorityKeywords() : List.of();
        for (String keyword : keywords) {
            if (StrUtil.isBlank(keyword)) continue;
            List<JsonObject> preferred = videos.stream().filter(f -> string(f, "fn").contains(keyword)).toList();
            if (!preferred.isEmpty()) { videos = preferred; break; }
        }
        // 不把多集种子的最大文件错误映射为单集。
        if (videos.size() != 1) throw new IllegalStateException("任务包含多个视频，保留云文件，请人工确认单集资源");
        JsonObject video = videos.getFirst();
        String base = cn.hutool.core.io.FileUtil.mainName(string(video, "fn"));
        String videoParent = string(video, "parent_cid");
        List<JsonObject> selected = new ArrayList<>(); selected.add(video);
        for (JsonObject f : all) {
            if (!FileUtils.isSubtitleFormat(string(f, "fn"))) continue;
            String name = cn.hutool.core.io.FileUtil.mainName(string(f, "fn"));
            if (name.equals(base) || name.startsWith(base + ".") || string(f, "parent_cid").equals(videoParent)) selected.add(f);
        }
        Set<String> targetNames = new HashSet<>();
        for (JsonObject f : selected) {
            var entry = new Open115TaskStore.CloudFile();
            entry.id = string(f, "fid"); entry.parent = string(f, "parent_cid"); entry.name = string(f, "fn");
            entry.targetName = Boolean.TRUE.equals(config.getOpen115CloudRename()) ? getFileReName(entry.name, item.getReName()) : entry.name;
            if (!targetNames.add(entry.targetName)) throw new IllegalStateException("视频或字幕目标文件名重复，保留云文件请人工确认");
            task.files.add(entry);
        }
        Open115TaskStore.save(task);
    }

    private void finishFiles(Open115Util api, Open115TaskStore.Task task) throws IOException, InterruptedException {
        for (var file : task.files) {
            List<JsonObject> target = api.files(task.finalCid);
            JsonObject existing = target.stream().filter(f -> file.id.equals(string(f, "fid"))).findFirst().orElse(null);
            if (existing == null) {
                List<JsonObject> source = api.files(file.parent);
                if (source.stream().noneMatch(f -> file.id.equals(string(f, "fid")))) throw new IllegalStateException("待移动文件已离开本任务目录");
                if (target.stream().anyMatch(f -> file.targetName.equals(string(f, "fn")) || file.name.equals(string(f, "fn")))) {
                    throw new IllegalStateException("最终目录已有同名文件，拒绝覆盖");
                }
                try { api.move(file.id, task.finalCid); }
                catch (IOException e) { /* 不确定结果以目标 fid 核验，仍失败则保留恢复记录。 */ }
            }
            JsonObject moved = null;
            for (int attempt = 0; attempt < 5; attempt++) {
                moved = api.files(task.finalCid).stream().filter(f -> file.id.equals(string(f, "fid"))).findFirst().orElse(null);
                if (moved != null) break;
                api.pause(2000);
            }
            if (moved == null) throw new IllegalStateException("115 文件移动未确认，下轮恢复");
            if (!file.targetName.equals(string(moved, "fn"))) {
                if (api.files(task.finalCid).stream().anyMatch(f -> !file.id.equals(string(f, "fid")) && file.targetName.equals(string(f, "fn")))) {
                    throw new IllegalStateException("重命名目标已存在，拒绝覆盖");
                }
                api.rename(file.id, file.targetName);
                if (api.files(task.finalCid).stream().noneMatch(f -> file.id.equals(string(f, "fid")) && file.targetName.equals(string(f, "fn")))) {
                    throw new IllegalStateException("115 文件重命名未确认，下轮恢复");
                }
            }
        }
    }

    private void wash(Open115Util api, Open115TaskStore.Task current, Config config) throws InterruptedException {
        if (!Boolean.TRUE.equals(config.getDelete()) || !Boolean.TRUE.equals(config.getStandbyRss())
                || Boolean.TRUE.equals(config.getCoexist()) || !Boolean.TRUE.equals(current.master)) return;
        try {
            Set<String> currentIds = new HashSet<>(); current.files.forEach(f -> currentIds.add(f.id));
            for (var old : Open115TaskStore.tasks(current.root, current.aniId)) {
                if (!old.complete || Boolean.TRUE.equals(old.master) || old.hash.equals(current.hash)
                        || !Objects.equals(old.season, current.season) || !Objects.equals(old.episode, current.episode)
                        || !Objects.equals(old.finalCid, current.finalCid)) continue;
                Set<String> present = new HashSet<>(); api.files(old.finalCid).forEach(f -> present.add(string(f, "fid")));
                if (!present.containsAll(currentIds)) throw new IllegalStateException("洗版前新视频或字幕已不在目标目录");
                for (var file : old.files) {
                    if (present.contains(file.id) && !currentIds.contains(file.id)) api.deleteFile(file.id, old.finalCid);
                }
                if (Boolean.TRUE.equals(config.getDeleteStandbyRSSOnly())) {
                    JsonObject remote = api.findTask(old.hash);
                    if (remote != null) { assertOwnership(remote, old); api.deleteTask(old.hash); }
                }
            }
        } catch (InterruptedException e) { throw e; }
        catch (Exception e) { log.warn("115 洗版未完成，已保留新资源：{}", safeMessage(e)); }
    }

    private boolean removeEmpty(Open115Util api, String cid, String parent, int depth) throws IOException, InterruptedException {
        if (depth > 8) return false;
        boolean empty = true;
        for (JsonObject file : api.files(cid)) {
            if (!"0".equals(string(file, "fc")) || !removeEmpty(api, string(file, "fid"), cid, depth + 1)) empty = false;
        }
        if (empty && api.files(cid).isEmpty()) { api.deleteFile(cid, parent); return true; }
        return false;
    }

    private void cleanup(Open115Util api, Open115TaskStore.Task task, Config config) throws InterruptedException {
        try {
            if (!removeEmpty(api, task.stagingCid, task.finalCid, 0)) log.info("115 保留含残留文件的任务目录 {}", task.hash);
            if (Boolean.TRUE.equals(config.getDelete()) && !Boolean.TRUE.equals(config.getDeleteStandbyRSSOnly())) {
                JsonObject remote = api.findTask(task.hash);
                if (remote != null) { assertOwnership(remote, task); api.deleteTask(task.hash); }
            }
        } catch (InterruptedException e) { throw e; }
        catch (Exception e) { log.warn("115 后处理清理未完成：{}", safeMessage(e)); }
    }

    @Override public List<TorrentsInfo> getTorrentsInfos() { return List.of(); }
    @Override public Boolean delete(TorrentsInfo info, Boolean deleteFiles) { return false; }
    @Override public Boolean rename(TorrentsInfo info) { return false; }
    @Override public Boolean addTags(TorrentsInfo info, String tags) { return false; }
    @Override public void updateTrackers(Set<String> trackers) { }
    @Override public void setSavePath(TorrentsInfo info, String path) { }
}
