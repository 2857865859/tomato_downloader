package com.fanqieshuku.loginprobe;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

final class CoverStore {
    interface Loader { byte[] load() throws Exception; }
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final long MAX_ENTRY_BYTES = 8L * 1024 * 1024;
    private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private final File directory;
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> loading = new ConcurrentHashMap<>();
    private long generation;

    CoverStore(File directory) { this.directory = directory; }
    synchronized long generation() { return generation; }

    byte[] get(String url, Loader loader) throws Exception {
        String key = key(url);
        long epoch = generation();
        byte[] cached = readIfAvailable(key);
        if (cached != null) return cached;
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        CompletableFuture<byte[]> existing = loading.putIfAbsent(key, future);
        if (existing != null) return existing.get();
        try {
            cached = readIfAvailable(key);
            byte[] bytes = cached == null ? loader.load() : cached;
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_ENTRY_BYTES) throw new IOException("封面数据无效");
            if (cached == null) {
                try { write(key, bytes, epoch); } catch (IOException ignored) { }
            }
            future.complete(bytes);
            return bytes;
        } catch (Exception error) {
            future.completeExceptionally(error);
            throw error;
        } finally { loading.remove(key, future); }
    }

    private byte[] readIfAvailable(String key) {
        try { return read(key); } catch (IOException ignored) { return null; }
    }

    private synchronized byte[] read(String key) throws IOException {
        File file = new File(directory, key);
        if (!file.isFile()) return null;
        if (file.length() == 0 || file.length() > MAX_ENTRY_BYTES || System.currentTimeMillis() - file.lastModified() > TTL_MS) {
            Files.deleteIfExists(file.toPath());
            return null;
        }
        byte[] bytes = Files.readAllBytes(file.toPath());
        file.setLastModified(System.currentTimeMillis());
        return bytes;
    }

    private synchronized void write(String key, byte[] bytes, long epoch) throws IOException {
        if (epoch != generation) return;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建封面缓存");
        File temporary = new File(directory, key + ".tmp");
        File target = new File(directory, key);
        try {
            Files.write(temporary.toPath(), bytes);
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary.toPath()); }
        File[] files = directory.listFiles(File::isFile);
        if (files == null) return;
        Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified));
        long total = 0;
        for (File file : files) total += file.length();
        for (File file : files) {
            if (total <= MAX_BYTES) break;
            long size = file.length();
            if (file.delete()) total -= size;
        }
    }

    synchronized long size() {
        long bytes = 0;
        File[] files = directory.listFiles(File::isFile);
        if (files != null) for (File file : files) bytes += file.length();
        return bytes;
    }

    synchronized void clear() throws IOException {
        generation++;
        loading.clear();
        File[] files = directory.listFiles(File::isFile);
        if (files != null) for (File file : files) Files.deleteIfExists(file.toPath());
    }

    static String key(String url) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
        StringBuilder key = new StringBuilder();
        for (byte b : digest) key.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return key.toString();
    }
}
