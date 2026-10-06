package com.fanqieshuku.loginprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class DownloadEngine {
    interface Listener { void onChanged(List<Task> tasks); }

    static final class Task {
        String id;
        String bookId;
        String title;
        String author;
        String coverUrl;
        String format;
        int start;
        int end;
        int downloaded;
        String status;
        String error;
        String outputUri;

        int total() { return end - start + 1; }
        int percent() { return total() == 0 ? 0 : downloaded * 100 / total(); }

        static Task from(DownloadTaskEntity entity) {
            Task task = new Task();
            task.id = entity.taskId; task.bookId = entity.bookId; task.title = entity.bookTitle;
            task.author = entity.author; task.coverUrl = entity.coverUrl; task.format = entity.format;
            task.start = entity.startChapter; task.end = entity.endChapter;
            task.downloaded = entity.downloadedChapters; task.status = entity.status;
            task.error = entity.errorMessage; task.outputUri = entity.outputUri;
            return task;
        }
    }

    private final Context context;
    private final DownloadDao dao;
    private final WorkManager workManager;
    private final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor();
    private volatile List<Task> tasks = Collections.emptyList();
    private volatile Listener listener;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };

    DownloadEngine(Context context) {
        this.context = context.getApplicationContext();
        dao = AppDatabase.get(context).downloads();
        workManager = WorkManager.getInstance(context);
        IntentFilter filter = new IntentFilter(DownloadWorker.ACTION_CHANGED);
        ContextCompat.registerReceiver(this.context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        databaseExecutor.execute(() -> { migrateLegacyTask(); refreshNow(); });
    }

    void setListener(Listener listener) { this.listener = listener; refresh(); }
    List<Task> getTasks() { return tasks; }

    void start(BiekApiClient.Book book, int start, int end, String format) {
        String taskId = UUID.randomUUID().toString();
        databaseExecutor.execute(() -> {
            if (dao.getBook(book.id) == null) dao.putBook(BookEntity.from(book));
            DownloadTaskEntity entity = new DownloadTaskEntity();
            entity.taskId = taskId; entity.bookId = book.id; entity.bookTitle = book.title;
            entity.author = book.author; entity.coverUrl = book.coverUrl; entity.format = format;
            entity.startChapter = start; entity.endChapter = end; entity.downloadedChapters = 0;
            entity.totalDownloadChapters = end - start + 1; entity.status = "WAITING"; entity.progress = 0;
            entity.createdAt = System.currentTimeMillis(); entity.updatedAt = entity.createdAt;
            entity.outputUri = ""; entity.errorMessage = "";
            dao.putTask(entity);
            refreshNow();
            enqueue(taskId);
        });
    }

    void pause(String taskId) {
        databaseExecutor.execute(() -> {
            dao.setStatus(taskId, "PAUSED", "", System.currentTimeMillis());
            workManager.cancelUniqueWork(workName(taskId));
            refreshNow();
        });
    }

    void resume(String taskId) {
        databaseExecutor.execute(() -> {
            DownloadTaskEntity task = dao.getTask(taskId);
            if (task == null || task.status.equals("COMPLETED") || task.status.equals("CANCELED")) return;
            try {
                workManager.cancelUniqueWork(workName(taskId)).getResult().get();
            } catch (Exception ignored) {
                // Re-enqueue below; WorkManager may already have finished cancellation.
            }
            dao.setStatus(taskId, "WAITING", "", System.currentTimeMillis());
            refreshNow();
            enqueue(taskId);
        });
    }

    void cancel(String taskId) {
        databaseExecutor.execute(() -> {
            dao.setStatus(taskId, "CANCELED", "", System.currentTimeMillis());
            workManager.cancelUniqueWork(workName(taskId));
            dao.deleteChapters(taskId);
            delete(new File(context.getFilesDir(), "chapters/" + taskId));
            refreshNow();
        });
    }

    void deleteTasks(List<String> taskIds) {
        databaseExecutor.execute(() -> {
            for (String taskId : taskIds) {
                dao.setStatus(taskId, "CANCELED", "", System.currentTimeMillis());
                workManager.cancelUniqueWork(workName(taskId));
                dao.deleteChapters(taskId);
                delete(new File(context.getFilesDir(), "chapters/" + taskId));
                dao.deleteTask(taskId);
            }
            refreshNow();
        });
    }

    void close() {
        try { context.unregisterReceiver(receiver); } catch (Exception ignored) { }
        databaseExecutor.shutdownNow();
    }

    private void enqueue(String taskId) {
        Data input = new Data.Builder().putString(DownloadWorker.KEY_TASK_ID, taskId).build();
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(DownloadWorker.class)
                .setInputData(input).setConstraints(constraints).addTag("book_downloads").build();
        workManager.enqueueUniqueWork(workName(taskId), ExistingWorkPolicy.REPLACE, request);
    }

    void refresh() { databaseExecutor.execute(this::refreshNow); }

    private void refreshNow() {
        List<Task> fresh = new ArrayList<>();
        for (DownloadTaskEntity entity : dao.getTasks()) fresh.add(Task.from(entity));
        tasks = fresh;
        Listener current = listener;
        if (current != null) current.onChanged(fresh);
    }

    private void migrateLegacyTask() {
        if (context.getSharedPreferences("downloads", Context.MODE_PRIVATE).getBoolean("roomMigrated", false)) return;
        String saved = context.getSharedPreferences("downloads", Context.MODE_PRIVATE).getString("task", "");
        if (!saved.isEmpty()) try {
            JSONObject json = new JSONObject(saved);
            DownloadTaskEntity entity = new DownloadTaskEntity();
            entity.taskId = json.optString("id", UUID.randomUUID().toString());
            entity.bookId = json.optString("bookId"); entity.bookTitle = json.optString("title");
            entity.author = json.optString("author"); entity.coverUrl = json.optString("coverUrl");
            entity.format = json.optString("format", "TXT"); entity.startChapter = json.optInt("start", 1);
            entity.endChapter = json.optInt("end", 1); entity.downloadedChapters = json.optInt("downloaded");
            entity.totalDownloadChapters = entity.endChapter - entity.startChapter + 1;
            entity.status = json.optString("status", "PAUSED");
            if (entity.status.equals("DOWNLOADING") || entity.status.equals("EXPORTING")) entity.status = "PAUSED";
            entity.progress = entity.totalDownloadChapters == 0 ? 0 : entity.downloadedChapters * 100 / entity.totalDownloadChapters;
            entity.outputUri = json.optString("outputUri"); entity.errorMessage = json.optString("error");
            entity.createdAt = System.currentTimeMillis(); entity.updatedAt = entity.createdAt;
            if (!entity.bookId.isEmpty()) dao.putTask(entity);
        } catch (Exception ignored) { }
        context.getSharedPreferences("downloads", Context.MODE_PRIVATE).edit().putBoolean("roomMigrated", true).apply();
    }

    private static String workName(String taskId) { return "download:" + taskId; }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        if (file.exists()) file.delete();
    }
}
