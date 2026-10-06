package com.fanqieshuku.loginprobe;

import androidx.annotation.NonNull;
import androidx.room.Entity;

@Entity(tableName = "catalog_chapters", primaryKeys = {"bookId", "chapterIndex"})
final class CatalogChapterEntity {
    @NonNull String bookId;
    int chapterIndex;
    String chapterId;
    String title;
    long updatedAt;

    static CatalogChapterEntity from(String bookId, BiekApiClient.Chapter chapter, long now) {
        CatalogChapterEntity entity = new CatalogChapterEntity();
        entity.bookId = bookId;
        entity.chapterIndex = chapter.index;
        entity.chapterId = chapter.id;
        entity.title = chapter.title;
        entity.updatedAt = now;
        return entity;
    }

    BiekApiClient.Chapter toChapter() {
        return new BiekApiClient.Chapter(chapterIndex, chapterId, title);
    }
}
