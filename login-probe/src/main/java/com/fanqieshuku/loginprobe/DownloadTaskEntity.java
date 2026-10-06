package com.fanqieshuku.loginprobe;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "download_tasks")
final class DownloadTaskEntity {
    @PrimaryKey @NonNull String taskId;
    @NonNull String bookId;
    String bookTitle;
    String author;
    String coverUrl;
    String format;
    int startChapter;
    int endChapter;
    int downloadedChapters;
    int totalDownloadChapters;
    String status;
    int progress;
    long createdAt;
    long updatedAt;
    String outputUri;
    String errorMessage;
}
