package com.fanqieshuku.loginprobe;

final class DownloadValidation {
    private DownloadValidation() { }

    static String validateRange(int first, int last, int total) {
        if (first < 1) return "起始章节不能小于 1";
        if (last > total) return "章节范围不能超过总章节数";
        if (first > last) return "起始章节不能大于结束章节";
        return null;
    }
}
