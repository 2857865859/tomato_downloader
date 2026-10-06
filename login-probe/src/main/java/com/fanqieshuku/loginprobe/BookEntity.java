package com.fanqieshuku.loginprobe;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "books")
final class BookEntity {
    @PrimaryKey @NonNull String bookId;
    String title;
    String author;
    String coverUrl;
    String description;
    int status;
    int totalChapters;
    long updatedAt;

    static BookEntity from(BiekApiClient.Book book) {
        BookEntity entity = new BookEntity();
        entity.bookId = book.id; entity.title = book.title; entity.author = book.author;
        entity.coverUrl = book.coverUrl; entity.description = book.description;
        entity.status = book.status; entity.totalChapters = book.chapterCount;
        entity.updatedAt = book.chapterCount == 0 ? 0 : System.currentTimeMillis();
        return entity;
    }

    BiekApiClient.Book toBook() {
        return new BiekApiClient.Book(
                bookId, title, author, description, coverUrl, status, totalChapters
        );
    }
}
