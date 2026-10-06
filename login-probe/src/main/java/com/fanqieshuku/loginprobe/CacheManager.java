package com.fanqieshuku.loginprobe;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

final class CacheManager {
    static final class Usage {
        long clearable;
        long retained;
    }
    private final File chapters;
    private final CoverStore covers;
    private final DownloadDao dao;

    CacheManager(File chapters, CoverStore covers, DownloadDao dao) {
        this.chapters = chapters;
        this.covers = covers;
        this.dao = dao;
    }

    Usage usage() {
        Usage usage = new Usage();
        usage.clearable = covers.size();
        File[] directories = chapters.listFiles();
        if (directories != null) for (File directory : directories) {
            DownloadTaskEntity task = dao.getTask(directory.getName());
            long bytes = size(directory);
            if (canClear(task == null ? null : task.status)) usage.clearable += bytes;
            else usage.retained += bytes;
        }
        return usage;
    }

    void clear() throws IOException {
        covers.clear();
        File[] directories = chapters.listFiles();
        if (directories != null) for (File directory : directories) {
            DownloadTaskEntity task = dao.getTask(directory.getName());
            if (!canClear(task == null ? null : task.status)) continue;
            dao.deleteChapters(directory.getName());
            delete(directory);
        }
        dao.deleteClearableChapters();
    }

    static boolean canClear(String status) {
        return status == null || status.equals("COMPLETED") || status.equals("CANCELED");
    }

    private static long size(File file) {
        if (file.isFile()) return file.length();
        long bytes = 0;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) bytes += size(child);
        return bytes;
    }

    private static void delete(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        Files.deleteIfExists(file.toPath());
    }
}
