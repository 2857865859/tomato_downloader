package com.fanqieshuku.loginprobe;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;

@Entity(tableName = "chapters", primaryKeys = {"taskId", "chapterIndex"},
        indices = {@Index(value = {"bookId", "chapterId", "downloadStatus"})})
final class ChapterEntity {
    @NonNull String taskId;
    @NonNull String bookId;
    String chapterId;
    int chapterIndex;
    String title;
    String content;
    String downloadStatus;
    String errorMessage;
    long updatedAt;
}
