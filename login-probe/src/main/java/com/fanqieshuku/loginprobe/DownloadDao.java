package com.fanqieshuku.loginprobe;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download_tasks WHERE status != 'CANCELED' ORDER BY createdAt DESC")
    List<DownloadTaskEntity> getTasks();

    @Query("SELECT * FROM download_tasks WHERE taskId = :taskId LIMIT 1")
    DownloadTaskEntity getTask(String taskId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putTask(DownloadTaskEntity task);

    @Query("UPDATE download_tasks SET status=:status, errorMessage=:error, updatedAt=:now WHERE taskId=:taskId")
    void setStatus(String taskId, String status, String error, long now);

    @Query("UPDATE download_tasks SET status='DOWNLOADING', errorMessage='', updatedAt=:now WHERE taskId=:taskId AND status IN ('WAITING','DOWNLOADING')")
    int startDownloading(String taskId, long now);

    @Query("UPDATE download_tasks SET errorMessage=:message, updatedAt=:now WHERE taskId=:taskId AND status='DOWNLOADING'")
    int updateDownloading(String taskId, String message, long now);

    @Query("UPDATE download_tasks SET status='EXPORTING', errorMessage='', updatedAt=:now WHERE taskId=:taskId AND status='DOWNLOADING'")
    int startExporting(String taskId, long now);

    @Query("UPDATE download_tasks SET downloadedChapters=:downloaded, progress=:progress, updatedAt=:now WHERE taskId=:taskId")
    void setProgress(String taskId, int downloaded, int progress, long now);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putBook(BookEntity book);

    @Query("SELECT * FROM books WHERE bookId=:bookId LIMIT 1")
    BookEntity getBook(String bookId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putCatalog(List<CatalogChapterEntity> chapters);

    @Query("SELECT * FROM catalog_chapters WHERE bookId=:bookId ORDER BY chapterIndex")
    List<CatalogChapterEntity> getCatalog(String bookId);

    @Query("DELETE FROM catalog_chapters WHERE bookId=:bookId")
    void deleteCatalog(String bookId);

    @Transaction
    default void replaceCatalog(String bookId, List<CatalogChapterEntity> chapters) {
        deleteCatalog(bookId);
        putCatalog(chapters);
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putChapter(ChapterEntity chapter);

    @Query("SELECT * FROM chapters WHERE taskId=:taskId AND chapterIndex=:index LIMIT 1")
    ChapterEntity getChapter(String taskId, int index);

    @Query("SELECT * FROM chapters WHERE bookId=:bookId AND chapterId=:chapterId AND downloadStatus='SUCCESS' AND length(content)>0 ORDER BY updatedAt DESC LIMIT 1")
    ChapterEntity getReusableChapter(String bookId, String chapterId);

    @Transaction
    default boolean putChapterIfActive(ChapterEntity chapter) {
        DownloadTaskEntity task = getTask(chapter.taskId);
        if (task == null || !task.status.equals("DOWNLOADING")) return false;
        putChapter(chapter);
        return true;
    }

    @Query("UPDATE download_tasks SET status='COMPLETED', progress=100, outputUri=:outputUri, errorMessage='', updatedAt=:now WHERE taskId=:taskId AND status='EXPORTING'")
    int completeIfExporting(String taskId, String outputUri, long now);

    @Query("SELECT COUNT(*) FROM chapters WHERE taskId=:taskId AND downloadStatus='SUCCESS' AND length(content)>0")
    int countSuccessfulChapters(String taskId);

    @Query("DELETE FROM chapters WHERE taskId=:taskId")
    void deleteChapters(String taskId);

    @Query("DELETE FROM chapters WHERE taskId NOT IN (SELECT taskId FROM download_tasks) OR taskId IN (SELECT taskId FROM download_tasks WHERE status IN ('COMPLETED','CANCELED'))")
    void deleteClearableChapters();

    @Query("DELETE FROM download_tasks WHERE taskId=:taskId")
    void deleteTask(String taskId);
}
