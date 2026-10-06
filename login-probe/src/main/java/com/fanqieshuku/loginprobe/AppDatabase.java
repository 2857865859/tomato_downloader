package com.fanqieshuku.loginprobe;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {BookEntity.class, ChapterEntity.class, DownloadTaskEntity.class, CatalogChapterEntity.class}, version = 3, exportSchema = false)
abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase instance;
    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `catalog_chapters` (`bookId` TEXT NOT NULL, `chapterIndex` INTEGER NOT NULL, `chapterId` TEXT, `title` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`bookId`, `chapterIndex`))");
        }
    };
    private static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_chapters_bookId_chapterId_downloadStatus` ON `chapters` (`bookId`, `chapterId`, `downloadStatus`)");
        }
    };
    abstract DownloadDao downloads();

    static AppDatabase get(Context context) {
        if (instance == null) synchronized (AppDatabase.class) {
            if (instance == null) instance = Room.databaseBuilder(
                    context.getApplicationContext(), AppDatabase.class, "fanqie-library.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build();
        }
        return instance;
    }
}
