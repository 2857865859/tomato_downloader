package com.fanqieshuku.loginprobe;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.content.pm.PackageManager;
import android.text.InputType;
import android.text.TextUtils;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.RelativeSizeSpan;
import android.text.style.ImageSpan;
import android.util.LruCache;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private static final String INSTALL_REPORTED_KEY = "softwareHubInstallReported";

    private static final class OffsetImageSpan extends ImageSpan {
        private final int offsetY;

        OffsetImageSpan(Drawable drawable, int offsetY) {
            super(drawable, ALIGN_BASELINE);
            this.offsetY = offsetY;
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                                   float x, int top, int y, int bottom, Paint paint) {
            canvas.save();
            canvas.translate(0, offsetY);
            super.draw(canvas, text, start, end, x, top, y, bottom, paint);
            canvas.restore();
        }
    }

    private static final long DETAIL_CACHE_MS = 6L * 60 * 60 * 1000;
    private static final long SERIAL_CATALOG_CACHE_MS = 15L * 60 * 1000;
    private static final long COMPLETED_CACHE_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int RED = Color.rgb(242, 75, 58);
    private static final int RED_LIGHT = Color.rgb(255, 241, 239);
    private static final int DARK = Color.rgb(23, 32, 51);
    private static final int MUTED = Color.rgb(119, 131, 153);
    private static final int TERTIARY = Color.rgb(166, 175, 189);
    private static final int LIGHT = Color.rgb(247, 248, 250);
    private static final int BORDER = Color.rgb(231, 235, 240);
    private static final int PICK_DIRECTORY = 41;
    private static final int REQUEST_STORAGE_PERMISSION = 42;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 43;
    private static final int MAX_COVER_BYTES = 8 * 1024 * 1024;

    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService detailExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService detailCoordinator = Executors.newSingleThreadExecutor();
    private BiekApiClient searchApi;
    private java.util.concurrent.Future<?> searchJob;
    private BiekApiClient detailApi;
    private java.util.concurrent.Future<?> detailJob;
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(3);
    private CoverStore coverStore;
    private CacheManager cacheManager;
    private final LruCache<String, Bitmap> coverCache = new LruCache<String, Bitmap>(16 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getAllocationByteCount() / 1024;
        }
    };
    private DownloadEngine downloadEngine;
    private DownloadDao downloadDao;
    private SharedPreferences settingsPreferences;
    private final SoftwareHubClient softwareHub = new SoftwareHubClient();
    private String page = "search";
    private boolean showCompletedDownloads;
    private boolean manageDownloads;
    private final java.util.Map<String, DownloadRow> downloadRows = new java.util.LinkedHashMap<>();
    private TextView activeDownloadTabTitle;
    private TextView completedDownloadTabTitle;

    private static final class DownloadRow {
        DownloadEngine.Task task;
        View card;
        TextView count;
        TextView state;
        ProgressBar progress;
    }
    private final Set<String> selectedDownloadIds = new HashSet<>();
    private LinearLayout searchResults;
    private LinearLayout searchLoading;
    private LinearLayout searchEmptyState;
    private TextView searchEmptyTitle;
    private TextView searchEmptyDescription;
    private TextView searchCaption;
    private TextView searchMore;
    private EditText searchInput;
    private String detailInputsBookId = "";
    private EditText detailStartInput;
    private EditText detailEndInput;
    private RadioButton detailEpubInput;
    private ScrollView currentScroll;
    private View bottomNavArea;
    private final List<BiekApiClient.Book> cachedSearchResults = new ArrayList<>();
    private String cachedSearchQuery = "";
    private String cachedSearchCaption = "";
    private int cachedSearchScrollY;
    private int cachedSearchPage;
    private boolean cachedSearchHasMore;
    private boolean loadingNextSearchPage;
    private String currentDetailBookId = "";
    private boolean searchTabShowingDetail;
    private long searchRequestId;
    private long detailRequestId;
    private long authRequestId;
    private AuthPrecheckState authPrecheckState = AuthPrecheckState.UNKNOWN;
    private SoftwareHubClient.AuthStatus precheckedAuthStatus;
    private Runnable pendingAuthVerified;
    private Runnable pendingAuthFinished;
    private String pendingAuthBookId = "";
    private String authVerifiedDay = "";

    private enum AuthPrecheckState { UNKNOWN, CHECKING, VERIFIED, UNVERIFIED }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settingsPreferences = getSharedPreferences("settings", MODE_PRIVATE);
        reportStartupUsage();
        downloadDao = AppDatabase.get(this).downloads();
        coverStore = new CoverStore(new java.io.File(getCacheDir(), "covers"));
        cacheManager = new CacheManager(new java.io.File(getFilesDir(), "chapters"), coverStore, downloadDao);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE_PERMISSION);
        }
        downloadEngine = new DownloadEngine(this);
        downloadEngine.setListener(tasks -> runOnUiThreadIfAlive(() -> {
            if (page.equals("downloads")) refreshDownloadCards();
        }));
        showSearch();
        checkForUpdate();
    }

    private void reportStartupUsage() {
        String clientId = clientId();
        boolean installReported = settingsPreferences.getBoolean(INSTALL_REPORTED_KEY, false);
        executor.execute(() -> {
            String version;
            try {
                version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception ignored) {
                return;
            }
            if (!installReported) {
                try {
                    softwareHub.reportEvent(clientId, version, "install");
                    settingsPreferences.edit().putBoolean(INSTALL_REPORTED_KEY, true).apply();
                } catch (Exception ignored) {
                    // 网络恢复后在下次启动时重试安装上报。
                }
            }
            try {
                softwareHub.reportEvent(clientId, version, "start");
            } catch (Exception ignored) {
                // 统计上报失败不应影响应用启动。
            }
        });
    }

    private void showSearch() {
        cancelSearch();
        cancelDetail();
        searchRequestId++;
        detailRequestId++;
        authRequestId++;
        searchTabShowingDetail = false;
        page = "search";
        LinearLayout content = column(20);
        content.addView(brandTitle(false));
        content.addView(text("免费、简洁、好用的小说下载工具", 14, MUTED, false), matchWrap(6, 16));

        LinearLayout search = row();
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(dp(12), dp(3), dp(3), dp(3));
        search.setBackground(round(Color.WHITE, 16, Color.rgb(238, 240, 243), 1));
        search.setElevation(dp(1));
        TextView searchIcon = text("⌕", 22, MUTED, false);
        searchIcon.setGravity(Gravity.CENTER);
        search.addView(searchIcon, new LinearLayout.LayoutParams(dp(32), dp(48)));
        EditText input = new EditText(this);
        input.setHint("请输入书名 / 链接 / 书籍ID");
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setPadding(dp(4), 0, dp(4), 0);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setText(cachedSearchQuery);
        searchInput = input;
        Button searchButton = primaryButton("搜索");
        searchButton.setBackground(round(RED, 14, RED, 0));
        searchButton.setGravity(Gravity.CENTER);
        searchButton.setPadding(0, 0, 0, 0);
        search.addView(input, new LinearLayout.LayoutParams(0, dp(48), 1));
        search.addView(searchButton, new LinearLayout.LayoutParams(dp(70), dp(48)));
        content.addView(search, matchWrap(0, 14));

        searchCaption = text("", 14, MUTED, false);
        searchCaption.setVisibility(View.GONE);
        content.addView(searchCaption, matchWrap(0, 12));
        searchLoading = row();
        searchLoading.setGravity(Gravity.CENTER);
        searchLoading.setVisibility(View.GONE);
        ProgressBar searchProgress = new ProgressBar(this);
        searchProgress.setIndeterminate(true);
        searchLoading.addView(searchProgress, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView loadingText = text("搜索中", 15, MUTED, false);
        LinearLayout.LayoutParams loadingTextParams = new LinearLayout.LayoutParams(-2, -2);
        loadingTextParams.leftMargin = dp(10);
        searchLoading.addView(loadingText, loadingTextParams);
        int loadingHeight = Math.max(dp(300), getResources().getDisplayMetrics().heightPixels - dp(500));
        content.addView(searchLoading, new LinearLayout.LayoutParams(-1, loadingHeight));
        searchResults = column(0);
        content.addView(searchResults, matchWrap(0, 0));
        searchMore = text("加载更多", 13, RED, false);
        searchMore.setGravity(Gravity.CENTER);
        searchMore.setPadding(0, dp(14), 0, dp(14));
        searchMore.setVisibility(View.GONE);
        searchMore.setOnClickListener(view -> loadNextSearchPage());
        content.addView(searchMore, matchWrap(0, 0));
        searchEmptyState = column(0);
        searchEmptyState.setGravity(Gravity.CENTER);
        TextView emptyIcon = text("⌕", 62, Color.rgb(255, 170, 160), false);
        emptyIcon.setGravity(Gravity.CENTER);
        searchEmptyState.addView(emptyIcon, new LinearLayout.LayoutParams(-1, dp(100)));
        searchEmptyTitle = text("搜索你想下载的小说", 21, DARK, true);
        searchEmptyTitle.setGravity(Gravity.CENTER);
        searchEmptyState.addView(searchEmptyTitle, matchWrap(10, 20));
        searchEmptyDescription = text("一键下载番茄小说，支持下载 TXT/EPUB 格式", 14, MUTED, false);
        searchEmptyDescription.setGravity(Gravity.CENTER);
        searchEmptyDescription.setLineSpacing(dp(14), 1f);
        searchEmptyState.addView(searchEmptyDescription);
        content.addView(searchEmptyState, new LinearLayout.LayoutParams(-1, dp(330)));

        View.OnClickListener action = view -> performSearch(input.getText().toString().trim());
        searchButton.setOnClickListener(action);
        input.setOnEditorActionListener((view, id, event) -> { action.onClick(view); return true; });
        setScreen(content, "search");
        if (!cachedSearchResults.isEmpty()) {
            searchEmptyState.setVisibility(View.GONE);
            for (BiekApiClient.Book book : cachedSearchResults) searchResults.addView(resultCard(book), matchWrap(0, 14));
            updateSearchMore();
        }
        ScrollView searchScroll = currentScroll;
        searchScroll.post(() -> searchScroll.scrollTo(0, cachedSearchScrollY));
    }

    private void performSearch(String query) {
        if (query.isEmpty()) { toast("请输入书名、链接或书籍 ID"); return; }
        cancelSearch();
        if (searchInput != null) {
            searchInput.clearFocus();
            InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (inputMethodManager != null) {
                inputMethodManager.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
            }
        }
        searchCaption.setVisibility(View.VISIBLE);
        searchEmptyState.setVisibility(View.GONE);
        searchLoading.setVisibility(View.VISIBLE);
        searchResults.removeAllViews();
        cachedSearchQuery = query;
        cachedSearchCaption = "";
        cachedSearchResults.clear();
        cachedSearchPage = 0;
        cachedSearchHasMore = false;
        loadingNextSearchPage = false;
        long requestId = ++searchRequestId;
        String bookId = extractBookId(query);
        if (bookId != null) { loadDetail(bookId); return; }
        BiekApiClient api = new BiekApiClient();
        searchApi = api;
        searchJob = searchExecutor.submit(() -> {
            try {
                BiekApiClient.SearchPage result = api.searchPage(query, 1);
                runOnUiThreadIfAlive(() -> {
                    if (searchRequestId == requestId && page.equals("search")
                            && !searchTabShowingDetail && cachedSearchQuery.equals(query)) {
                        renderResults(result, false, 1);
                    }
                });
            } catch (Exception error) {
                runOnUiThreadIfAlive(() -> {
                    if (searchRequestId != requestId || !page.equals("search")
                            || searchTabShowingDetail || !cachedSearchQuery.equals(query)) return;
                    searchLoading.setVisibility(View.GONE);
                    searchCaption.setVisibility(View.VISIBLE);
                    cachedSearchCaption = "搜索失败：" + message(error);
                    searchCaption.setText(cachedSearchCaption);
                });
            }
        });
    }

    private void renderResults(BiekApiClient.SearchPage result, boolean append, int pageNumber) {
        searchLoading.setVisibility(View.GONE);
        searchCaption.setVisibility(View.GONE);
        if (!append) {
            cachedSearchResults.clear();
            searchResults.removeAllViews();
        }
        Set<String> existingIds = new HashSet<>();
        for (BiekApiClient.Book cached : cachedSearchResults) existingIds.add(cached.id);
        for (BiekApiClient.Book book : result.books) {
            if (existingIds.add(book.id)) {
                cachedSearchResults.add(book);
                searchResults.addView(resultCard(book), matchWrap(0, 14));
            }
        }
        cachedSearchPage = pageNumber;
        cachedSearchHasMore = result.hasMore;
        loadingNextSearchPage = false;
        cachedSearchCaption = "";
        searchCaption.setText("");
        updateSearchMore();
        boolean empty = cachedSearchResults.isEmpty();
        searchEmptyTitle.setText(empty ? "未找到相关小说" : "搜索你想下载的小说");
        searchEmptyDescription.setText(empty
                ? "换个书名、链接或书籍 ID 再试试"
                : "一键下载番茄小说，支持下载 TXT/EPUB 格式");
        searchEmptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void loadNextSearchPage() {
        if (!page.equals("search") || loadingNextSearchPage || !cachedSearchHasMore || cachedSearchQuery.isEmpty()) return;
        cancelSearch();
        loadingNextSearchPage = true;
        int nextPage = cachedSearchPage + 1;
        String query = cachedSearchQuery;
        long requestId = searchRequestId;
        searchCaption.setVisibility(View.VISIBLE);
        searchCaption.setText("正在加载更多……");
        searchMore.setText("正在加载…");
        searchMore.setTextColor(MUTED);
        BiekApiClient api = new BiekApiClient();
        searchApi = api;
        searchJob = searchExecutor.submit(() -> {
            try {
                BiekApiClient.SearchPage result = api.searchPage(query, nextPage);
                runOnUiThreadIfAlive(() -> {
                    if (searchRequestId == requestId && page.equals("search")
                            && !searchTabShowingDetail && cachedSearchQuery.equals(query)) {
                        renderResults(result, true, nextPage);
                    }
                });
            } catch (Exception error) {
                runOnUiThreadIfAlive(() -> {
                    if (searchRequestId != requestId || !page.equals("search")
                            || searchTabShowingDetail || !cachedSearchQuery.equals(query)) return;
                    loadingNextSearchPage = false;
                    searchCaption.setText("加载下一页失败，请点击“加载更多”重试：" + message(error));
                    searchMore.setText("加载更多");
                    searchMore.setTextColor(RED);
                });
            }
        });
    }

    private void updateSearchMore() {
        if (searchMore == null || cachedSearchResults.isEmpty()) return;
        searchMore.setVisibility(View.VISIBLE);
        searchMore.setText(cachedSearchHasMore ? "加载更多" : "到底了");
        searchMore.setTextColor(cachedSearchHasMore ? RED : MUTED);
        searchMore.setClickable(cachedSearchHasMore);
    }

    private View resultCard(BiekApiClient.Book book) {
        LinearLayout card = row();
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(round(Color.WHITE, 18, BORDER, 1));
        card.setElevation(dp(2));
        ImageView cover = coverView(book.coverUrl, 72, 100);
        card.addView(cover, new LinearLayout.LayoutParams(dp(72), dp(100)));
        LinearLayout info = column(0);
        info.setPadding(dp(12), 0, 0, 0);
        info.addView(text(book.title, 17, DARK, true));
        info.addView(text("作者：" + book.author, 13, MUTED, false), matchWrap(0, 4));
        TextView description = text(book.description, 13, MUTED, false);
        description.setMaxLines(2);
        info.addView(description, matchWrap(0, 6));
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = text("›", 28, MUTED, false);
        arrow.setGravity(Gravity.CENTER);
        card.addView(arrow, new LinearLayout.LayoutParams(dp(28), -1));
        card.setOnClickListener(view -> { rememberSearchState(); loadDetail(book.id); });
        return card;
    }

    private void rememberSearchState() {
        if (searchInput != null) cachedSearchQuery = searchInput.getText().toString();
        if (currentScroll != null && page.equals("search")) cachedSearchScrollY = currentScroll.getScrollY();
    }

    private void loadDetail(String bookId) {
        loadDetail(bookId, false);
    }

    private void loadDetail(String bookId, boolean forceRefresh) {
        boolean keepScreen = forceRefresh && page.equals("detail") && bookId.equals(currentDetailBookId);
        cancelSearch();
        cancelDetail();
        searchRequestId++;
        authRequestId++;
        long requestId = ++detailRequestId;
        searchTabShowingDetail = true;
        page = "detail";
        currentDetailBookId = bookId;
        precheckDownloadAuth();
        BiekApiClient.Book preview = null;
        for (BiekApiClient.Book result : cachedSearchResults) {
            if (result.id.equals(bookId)) { preview = result; break; }
        }
        final BiekApiClient.Book initialBook = preview;
        LinearLayout loading = column(16);
        loading.addView(brandTitle(true));
        LinearLayout loadingCenter = row();
        loadingCenter.setGravity(Gravity.CENTER);
        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        loadingCenter.addView(progress, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView loadingText = text("正在加载书籍详情", 15, MUTED, false);
        LinearLayout.LayoutParams loadingTextParams = new LinearLayout.LayoutParams(-2, -2);
        loadingTextParams.leftMargin = dp(10);
        loadingCenter.addView(loadingText, loadingTextParams);
        int loadingHeight = Math.max(dp(300), getResources().getDisplayMetrics().heightPixels - dp(170));
        loading.addView(loadingCenter, new LinearLayout.LayoutParams(-1, loadingHeight));
        if (!keepScreen) {
            setScreen(loading, "search");
            if (initialBook != null) showDetail(initialBook, null);
        }
        BiekApiClient api = new BiekApiClient();
        detailApi = api;
        detailJob = detailCoordinator.submit(() -> {
            java.util.concurrent.Future<?> bookJob = null;
            java.util.concurrent.Future<?> catalogJob = null;
            try {
                BookEntity cachedBook = downloadDao.getBook(bookId);
                List<CatalogChapterEntity> cachedCatalog = downloadDao.getCatalog(bookId);
                List<BiekApiClient.Chapter> cachedChapters = new ArrayList<>();
                for (CatalogChapterEntity cached : cachedCatalog) cachedChapters.add(cached.toChapter());
                class DetailState {
                    BiekApiClient.Book book = cachedBook == null ? initialBook : cachedBook.toBook();
                    List<BiekApiClient.Chapter> chapters = cachedChapters.isEmpty() ? null : cachedChapters;
                    String catalogError;
                }
                DetailState state = new DetailState();
                long now = System.currentTimeMillis();
                boolean completed = cachedBook != null && cachedBook.toBook().completed();
                boolean bookFresh = !forceRefresh && cachedBook != null && now - cachedBook.updatedAt < (completed ? COMPLETED_CACHE_MS : DETAIL_CACHE_MS);
                boolean catalogFresh = !forceRefresh && !cachedCatalog.isEmpty()
                        && now - cachedCatalog.get(0).updatedAt < (completed ? COMPLETED_CACHE_MS : SERIAL_CATALOG_CACHE_MS);
                runOnUiThreadIfAlive(() -> {
                    if (detailRequestId == requestId && page.equals("detail") && state.book != null) {
                        showDetail(state.book, state.chapters);
                    }
                });
                if (!bookFresh) bookJob = detailExecutor.submit(() -> {
                    try {
                        BiekApiClient.Book book = api.getBook(bookId);
                        downloadDao.putBook(BookEntity.from(book));
                        runOnUiThreadIfAlive(() -> {
                            if (detailRequestId != requestId || !page.equals("detail")) return;
                            state.book = book;
                            showDetail(book, state.chapters, state.catalogError);
                        });
                    } catch (Exception error) {
                        runOnUiThreadIfAlive(() -> {
                            if (detailRequestId != requestId || !page.equals("detail")) return;
                            toast("详情更新失败：" + message(error));
                            if (state.book == null) showSearch();
                        });
                    }
                });
                if (!catalogFresh) catalogJob = detailExecutor.submit(() -> {
                    try {
                        List<BiekApiClient.Chapter> chapters = api.getChapters(bookId);
                        saveCatalogCache(bookId, chapters);
                        runOnUiThreadIfAlive(() -> {
                            if (detailRequestId != requestId || !page.equals("detail")) return;
                            state.chapters = chapters;
                            if (state.book != null) showDetail(state.book, chapters);
                        });
                    } catch (Exception error) {
                        runOnUiThreadIfAlive(() -> {
                            if (detailRequestId != requestId || !page.equals("detail")) return;
                            state.catalogError = message(error);
                            if (state.book != null && state.chapters == null) showDetail(state.book, null, state.catalogError);
                            else toast("目录更新失败：" + state.catalogError);
                        });
                    }
                });
                if (bookJob != null) awaitResult(bookJob);
                if (catalogJob != null) awaitResult(catalogJob);
            } catch (Exception error) {
                runOnUiThreadIfAlive(() -> {
                    if (detailRequestId != requestId || !page.equals("detail") || !currentDetailBookId.equals(bookId)) return;
                    toast("详情加载失败：" + message(error));
                });
            } finally {
                api.cancel();
                if (bookJob != null) bookJob.cancel(true);
                if (catalogJob != null) catalogJob.cancel(true);
            }
        });
    }

    private static <T> T awaitResult(java.util.concurrent.Future<T> job) throws Exception {
        try {
            return job.get();
        } catch (java.util.concurrent.ExecutionException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            throw error;
        }
    }

    private void cancelSearch() {
        if (searchApi != null) searchApi.cancel();
        if (searchJob != null) searchJob.cancel(true);
        searchApi = null;
        searchJob = null;
        loadingNextSearchPage = false;
    }

    private void cancelDetail() {
        if (detailApi != null) detailApi.cancel();
        if (detailJob != null) detailJob.cancel(true);
        detailApi = null;
        detailJob = null;
    }

    private void saveCatalogCache(String bookId, List<BiekApiClient.Chapter> chapters) {
        long now = System.currentTimeMillis();
        List<CatalogChapterEntity> entities = new java.util.ArrayList<>();
        for (BiekApiClient.Chapter chapter : chapters) {
            entities.add(CatalogChapterEntity.from(bookId, chapter, now));
        }
        downloadDao.replaceCatalog(bookId, entities);
    }

    private void showDetail(BiekApiClient.Book book, List<BiekApiClient.Chapter> chapters) {
        showDetail(book, chapters, null);
    }

    private void showDetail(BiekApiClient.Book book, List<BiekApiClient.Chapter> chapters, String catalogError) {
        boolean preserveInputs = book.id.equals(detailInputsBookId) && detailStartInput != null;
        String previousStart = preserveInputs ? detailStartInput.getText().toString() : "1";
        String previousEnd = preserveInputs ? detailEndInput.getText().toString() : "";
        String previousFormat = preserveInputs ? (detailEpubInput.isChecked() ? "EPUB" : "TXT") : null;
        boolean startFocused = preserveInputs && detailStartInput.isFocused();
        boolean endFocused = preserveInputs && detailEndInput.isFocused();
        int previousSelection = startFocused ? detailStartInput.getSelectionStart() : endFocused ? detailEndInput.getSelectionStart() : 0;
        int previousScroll = preserveInputs && page.equals("detail") && currentScroll != null ? currentScroll.getScrollY() : 0;
        page = "detail";
        boolean catalogReady = chapters != null && !chapters.isEmpty();
        LinearLayout content = column(14);
        content.addView(detailBrandTitle());

        LinearLayout bookCard = row();
        bookCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        bookCard.setBackground(round(Color.WHITE, 20, BORDER, 1));
        bookCard.setElevation(dp(2));
        bookCard.addView(coverView(book.coverUrl, 64, 86), new LinearLayout.LayoutParams(dp(64), dp(86)));
        LinearLayout info = column(0);
        info.setPadding(dp(12), 0, 0, 0);
        TextView bookTitle = text(book.title, 16, DARK, true);
        bookTitle.setMaxLines(1);
        bookTitle.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(bookTitle);
        info.addView(text("作者：" + book.author, 12, MUTED, false), matchWrap(2, 2));
        String chapterStatus = catalogReady ? " · 共 " + chapters.size() + " 章"
                : catalogError == null ? " · 目录加载中……" : " · 目录加载失败";
        LinearLayout statusRow = row();
        statusRow.addView(text(book.statusLabel() + chapterStatus, 12, MUTED, false), new LinearLayout.LayoutParams(0, -2, 1));
        TextView refresh = text("刷新目录", 12, RED, false);
        refresh.setPadding(dp(6), 0, 0, 0);
        refresh.setOnClickListener(view -> loadDetail(book.id, true));
        statusRow.addView(refresh);
        info.addView(statusRow, matchWrap(0, 3));
        TextView description = text(book.description, 12, MUTED, false);
        description.setMaxLines(1);
        description.setEllipsize(TextUtils.TruncateAt.END);
        description.setPadding(dp(8), dp(4), dp(8), dp(4));
        description.setBackground(round(LIGHT, 11, Color.TRANSPARENT, 0));
        info.addView(description, matchWrap(0, 0));
        bookCard.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(bookCard, matchWrap(4, 10));

        LinearLayout formatCard = section("下载设置");
        formatCard.setPadding(dp(16), dp(22), dp(16), dp(20));
        formatCard.addView(text("选择下载格式和章节范围，开始下载小说", 13, MUTED, false), matchWrap(4, 20));
        formatCard.addView(text("下载格式", 17, DARK, true), matchWrap(0, 12));
        RadioGroup formats = new RadioGroup(this);
        formats.setOrientation(RadioGroup.HORIZONTAL);
        formats.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        RadioButton txt = radio("TXT");
        RadioButton epub = radio("EPUB");
        RadioGroup.LayoutParams txtParams = new RadioGroup.LayoutParams(0, dp(38), 1);
        txtParams.rightMargin = dp(8);
        formats.addView(txt, txtParams);
        formats.addView(epub, new RadioGroup.LayoutParams(0, dp(38), 1));
        String defaultFormat = previousFormat == null ? settingsPreferences.getString("format", "TXT") : previousFormat;
        (defaultFormat.equals("EPUB") ? epub : txt).setChecked(true);
        formats.setOnCheckedChangeListener((group, checkedId) -> {
            txt.setBackground(round(txt.isChecked() ? RED_LIGHT : Color.WHITE, 16, txt.isChecked() ? RED : BORDER, 1));
            epub.setBackground(round(epub.isChecked() ? RED_LIGHT : Color.WHITE, 16, epub.isChecked() ? RED : BORDER, 1));
        });
        txt.setBackground(round(txt.isChecked() ? RED_LIGHT : Color.WHITE, 16, txt.isChecked() ? RED : BORDER, 1));
        epub.setBackground(round(epub.isChecked() ? RED_LIGHT : Color.WHITE, 16, epub.isChecked() ? RED : BORDER, 1));
        formatCard.addView(formats, matchWrap(0, 18));

        View sectionDivider = new View(this);
        sectionDivider.setBackgroundColor(BORDER);
        formatCard.addView(sectionDivider, new LinearLayout.LayoutParams(-1, dp(1)));
        formatCard.addView(text("下载章节范围", 17, DARK, true), matchWrap(22, 6));
        formatCard.addView(text("输入开始章和结束章，支持下载指定章节范围", 13, MUTED, false), matchWrap(0, 14));
        LinearLayout range = row();
        EditText start = numberInput("起始章节", previousStart);
        EditText end = numberInput("结束章节", !previousEnd.isEmpty() ? previousEnd : catalogReady ? String.valueOf(chapters.size()) : "");
        detailInputsBookId = book.id;
        detailStartInput = start;
        detailEndInput = end;
        detailEpubInput = epub;
        range.addView(numberInputFrame(start), new LinearLayout.LayoutParams(0, dp(38), 1));
        TextView arrow = text("→", 22, MUTED, false);
        arrow.setGravity(Gravity.CENTER);
        range.addView(arrow, new LinearLayout.LayoutParams(dp(36), dp(38)));
        range.addView(numberInputFrame(end), new LinearLayout.LayoutParams(0, dp(38), 1));
        formatCard.addView(range, matchWrap(0, 16));
        Button startDownload = primaryButton(catalogReady ? "开始下载" : "正在加载章节目录……");
        startDownload.setTextSize(15);
        startDownload.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        startDownload.setIncludeFontPadding(false);
        startDownload.setGravity(Gravity.CENTER);
        startDownload.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        startDownload.setPadding(0, 0, 0, 0);
        startDownload.setText(catalogReady ? "开始下载" : catalogError == null ? "正在加载章节目录……" : "目录加载失败，点击重试");
        start.setEnabled(catalogReady);
        end.setEnabled(catalogReady);
        startDownload.setEnabled(catalogReady || catalogError != null);
        if (!catalogReady && catalogError != null) formatCard.addView(text(catalogError, 12, MUTED, false), matchWrap(8, 0));
        FrameLayout startDownloadFrame = new FrameLayout(this);
        startDownloadFrame.addView(startDownload, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout startDownloadLoading = row();
        startDownloadLoading.setGravity(Gravity.CENTER);
        startDownloadLoading.setVisibility(View.GONE);
        ProgressBar startDownloadProgress = new ProgressBar(this);
        startDownloadProgress.setIndeterminate(true);
        startDownloadProgress.getIndeterminateDrawable().setTint(Color.WHITE);
        startDownloadLoading.addView(startDownloadProgress, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView startDownloadLoadingText = text("获取中", 15, Color.WHITE, true);
        LinearLayout.LayoutParams startLoadingTextParams = new LinearLayout.LayoutParams(-2, -2);
        startLoadingTextParams.leftMargin = dp(10);
        startDownloadLoading.addView(startDownloadLoadingText, startLoadingTextParams);
        startDownloadFrame.addView(startDownloadLoading, new FrameLayout.LayoutParams(-1, -1));
        formatCard.addView(startDownloadFrame, new LinearLayout.LayoutParams(-1, dp(46)));
        LinearLayout pathRow = row();
        pathRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView pathIcon = new ImageView(this);
        pathIcon.setImageResource(R.drawable.ic_folder_outline);
        pathIcon.setColorFilter(MUTED);
        pathRow.addView(pathIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView path = text("保存路径：" + directoryLabel(), 13, MUTED, false);
        path.setSingleLine(true);
        path.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams pathTextParams = new LinearLayout.LayoutParams(0, -2, 1);
        pathTextParams.leftMargin = dp(8);
        pathRow.addView(path, pathTextParams);
        formatCard.addView(pathRow, matchWrap(18, 2));
        content.addView(formatCard, matchWrap(0, 10));

        startDownload.setOnClickListener(view -> {
            if (!catalogReady && catalogError != null) { loadDetail(book.id); return; }
            if (!catalogReady) return;
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && settingsPreferences.getString("treeUri", "").isEmpty()) {
                toast("Android 8/9 请先在设置中授权下载目录");
                showSettings();
                return;
            }
            int first;
            int last;
            try { first = Integer.parseInt(start.getText().toString()); last = Integer.parseInt(end.getText().toString()); }
            catch (Exception error) { toast("请输入正确的章节数字"); return; }
            String rangeError = DownloadValidation.validateRange(first, last, chapters.size());
            if (rangeError != null) { toast(rangeError); return; }
            String selectedFormat = epub.isChecked() ? "EPUB" : "TXT";
            requestNotificationPermissionIfNeeded();
            startDownload.setEnabled(false);
            startDownload.setText("");
            startDownloadLoading.setVisibility(View.VISIBLE);
            requireDownloadAuth(() -> {
                downloadEngine.start(book, first, last, selectedFormat);
                toast("已加入下载任务");
                showCompletedDownloads = false;
                showDownloads();
            }, () -> {
                startDownloadLoading.setVisibility(View.GONE);
                startDownload.setText("开始下载");
                startDownload.setEnabled(true);
            });
        });
        setScreen(content, "search");
        currentScroll.setVerticalScrollBarEnabled(false);
        EditText focusedInput = startFocused ? start : endFocused ? end : null;
        if (focusedInput != null && focusedInput.isEnabled()) {
            focusedInput.requestFocus();
            focusedInput.setSelection(Math.max(0, Math.min(previousSelection, focusedInput.length())));
        }
        ScrollView detailScroll = currentScroll;
        detailScroll.post(() -> detailScroll.scrollTo(0, previousScroll));
    }

    private void checkForUpdate() {
        executor.execute(() -> {
            SoftwareHubClient.UpdateInfo availableUpdate = null;
            try {
                String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                SoftwareHubClient.UpdateInfo update = softwareHub.checkUpdate(version);
                if (update.hasUpdate) availableUpdate = update;
            } catch (Exception ignored) {
                // 更新服务暂时不可用时不阻止用户使用现有版本。
            }
            SoftwareHubClient.UpdateInfo result = availableUpdate;
            runOnUiThreadIfAlive(() -> {
                if (result != null) showUpdateDialog(result);
            });
        });
    }

    private void showUpdateDialog(SoftwareHubClient.UpdateInfo update) {
        LinearLayout panel = column(0);
        panel.setPadding(dp(16), dp(16), dp(16), dp(12));
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setBackground(round(Color.WHITE, 24, Color.WHITE, 0));

        FrameLayout iconBackground = new FrameLayout(this);
        View outerGlow = new View(this);
        outerGlow.setBackground(round(Color.rgb(255, 246, 245), 40, Color.TRANSPARENT, 0));
        FrameLayout.LayoutParams outerGlowParams = new FrameLayout.LayoutParams(dp(104), dp(104), Gravity.CENTER);
        iconBackground.addView(outerGlow, outerGlowParams);
        View innerGlow = new View(this);
        innerGlow.setBackground(round(Color.rgb(255, 237, 235), 32, Color.TRANSPARENT, 0));
        FrameLayout.LayoutParams innerGlowParams = new FrameLayout.LayoutParams(dp(82), dp(82), Gravity.CENTER);
        iconBackground.addView(innerGlow, innerGlowParams);
        FrameLayout arrowTile = new FrameLayout(this);
        arrowTile.setBackground(round(Color.rgb(255, 249, 248), 18, Color.WHITE, 1));
        arrowTile.setElevation(dp(8));
        ImageView updateIcon = new ImageView(this);
        updateIcon.setImageResource(R.drawable.ic_update_arrow);
        updateIcon.setPadding(dp(13), dp(13), dp(13), dp(13));
        arrowTile.addView(updateIcon, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams arrowTileParams = new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER);
        iconBackground.addView(arrowTile, arrowTileParams);
        View leftDot = new View(this);
        leftDot.setBackground(round(Color.rgb(249, 158, 151), 5, Color.TRANSPARENT, 0));
        FrameLayout.LayoutParams leftDotParams = new FrameLayout.LayoutParams(dp(9), dp(9));
        leftDotParams.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
        leftDotParams.leftMargin = dp(2);
        iconBackground.addView(leftDot, leftDotParams);
        View topDot = new View(this);
        topDot.setBackground(round(Color.rgb(249, 158, 151), 5, Color.TRANSPARENT, 0));
        FrameLayout.LayoutParams topDotParams = new FrameLayout.LayoutParams(dp(8), dp(8));
        topDotParams.gravity = Gravity.RIGHT | Gravity.TOP;
        topDotParams.rightMargin = dp(9);
        topDotParams.topMargin = dp(5);
        iconBackground.addView(topDot, topDotParams);
        View bottomDot = new View(this);
        bottomDot.setBackground(round(Color.rgb(249, 158, 151), 4, Color.TRANSPARENT, 0));
        FrameLayout.LayoutParams bottomDotParams = new FrameLayout.LayoutParams(dp(7), dp(7));
        bottomDotParams.gravity = Gravity.RIGHT | Gravity.BOTTOM;
        bottomDotParams.rightMargin = dp(3);
        bottomDotParams.bottomMargin = dp(15);
        iconBackground.addView(bottomDot, bottomDotParams);
        panel.addView(iconBackground, new LinearLayout.LayoutParams(dp(96), dp(80)));

        TextView title = text("软件更新", 21, DARK, true);
        title.setGravity(Gravity.CENTER);
        panel.addView(title, matchWrap(6, 7));

        TextView version = text("发现新版本  " + update.version, 14, RED, true);
        version.setGravity(Gravity.CENTER);
        version.setPadding(dp(14), dp(5), dp(14), dp(5));
        version.setBackground(round(RED_LIGHT, 20, RED_LIGHT, 0));
        panel.addView(version, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout contentCard = column(14);
        contentCard.setBackground(round(Color.rgb(249, 248, 250), 18, Color.rgb(249, 248, 250), 0));
        LinearLayout contentHeading = row();
        contentHeading.setGravity(Gravity.CENTER_VERTICAL);
        ImageView noteIcon = new ImageView(this);
        noteIcon.setImageResource(R.drawable.ic_update_note);
        contentHeading.addView(noteIcon, new LinearLayout.LayoutParams(dp(23), dp(23)));
        TextView contentTitle = text("更新内容", 16, DARK, true);
        LinearLayout.LayoutParams contentTitleParams = new LinearLayout.LayoutParams(-2, -2);
        contentTitleParams.leftMargin = dp(10);
        contentHeading.addView(contentTitle, contentTitleParams);
        contentCard.addView(contentHeading);
        TextView content = text(update.content.isEmpty() ? "优化使用体验" : update.content, 14, MUTED, false);
        content.setLineSpacing(dp(4), 1f);
        content.setMaxLines(5);
        content.setEllipsize(TextUtils.TruncateAt.END);
        contentCard.addView(content, matchWrap(8, 0));
        panel.addView(contentCard, matchWrap(12, 12));

        LinearLayout actions = row();
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button later = secondaryButton("稍后");
        later.setTextColor(MUTED);
        later.setBackground(round(Color.WHITE, 14, BORDER, 1));
        Button install = primaryButton("");
        GradientDrawable installBackground = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(247, 91, 80), Color.rgb(215, 53, 45)}
        );
        installBackground.setCornerRadius(dp(15));
        install.setBackground(installBackground);
        install.setElevation(dp(5));
        install.setTextSize(16);
        install.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        Drawable downloadIcon = getDrawable(R.drawable.ic_update_download).mutate();
        downloadIcon.setBounds(0, dp(4), dp(24), dp(28));
        SpannableString installContent = new SpannableString("  立即更新");
        installContent.setSpan(new OffsetImageSpan(downloadIcon, dp(2)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        install.setText(installContent);
        install.setGravity(Gravity.CENTER);
        install.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        install.setPadding(0, 0, 0, dp(4));
        if (update.forceUpdate) {
            actions.addView(install, new LinearLayout.LayoutParams(-1, dp(52)));
        } else {
            LinearLayout.LayoutParams laterParams = new LinearLayout.LayoutParams(0, dp(52), 1);
            laterParams.rightMargin = dp(8);
            actions.addView(later, laterParams);
            LinearLayout.LayoutParams installParams = new LinearLayout.LayoutParams(0, dp(52), 1.25f);
            installParams.leftMargin = dp(2);
            actions.addView(install, installParams);
        }
        panel.addView(actions, new LinearLayout.LayoutParams(-1, dp(52)));

        AlertDialog dialog = new AlertDialog.Builder(this).setView(panel).create();
        dialog.setCancelable(!update.forceUpdate);
        dialog.setCanceledOnTouchOutside(!update.forceUpdate);
        dialog.setOnShowListener(ignored -> {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            int width = Math.min(dp(330), (int) (getResources().getDisplayMetrics().widthPixels * 0.80f));
            dialog.getWindow().setLayout(width, -2);
            later.setOnClickListener(view -> dialog.dismiss());
            install.setOnClickListener(view -> {
                if (update.downloadUrl.isEmpty()) { toast("后台暂未配置下载地址"); return; }
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl)));
            });
        });
        dialog.show();
    }

    private void requireDownloadAuth(Runnable onVerified, Runnable onCheckFinished) {
        if (authPrecheckState == AuthPrecheckState.VERIFIED && !authVerifiedDay.equals(todayKey())) {
            authPrecheckState = AuthPrecheckState.UNKNOWN;
        }
        if (authPrecheckState == AuthPrecheckState.VERIFIED) {
            onCheckFinished.run();
            onVerified.run();
            return;
        }
        if (authPrecheckState == AuthPrecheckState.UNVERIFIED && precheckedAuthStatus != null) {
            onCheckFinished.run();
            showVerificationDialog(precheckedAuthStatus, onVerified);
            return;
        }

        pendingAuthVerified = onVerified;
        pendingAuthFinished = onCheckFinished;
        pendingAuthBookId = currentDetailBookId;
        if (authPrecheckState == AuthPrecheckState.UNKNOWN) precheckDownloadAuth();
    }

    private void precheckDownloadAuth() {
        if (authPrecheckState == AuthPrecheckState.VERIFIED && !authVerifiedDay.equals(todayKey())) {
            authPrecheckState = AuthPrecheckState.UNKNOWN;
        }
        if (authPrecheckState != AuthPrecheckState.UNKNOWN) return;
        authPrecheckState = AuthPrecheckState.CHECKING;
        String clientId = clientId();
        String token = settingsPreferences.getString("softwareHubToken", "");
        executor.execute(() -> {
            try {
                SoftwareHubClient.AuthStatus status = softwareHub.authStatus(clientId);
                boolean verified = !status.required || softwareHub.tokenValid(clientId, token);
                runOnUiThreadIfAlive(() -> completeAuthPrecheck(verified, status, null));
            } catch (Exception error) {
                runOnUiThreadIfAlive(() -> completeAuthPrecheck(false, null, error));
            }
        });
    }

    private void completeAuthPrecheck(boolean verified, SoftwareHubClient.AuthStatus status, Exception error) {
        authPrecheckState = error != null ? AuthPrecheckState.UNKNOWN
                : verified ? AuthPrecheckState.VERIFIED : AuthPrecheckState.UNVERIFIED;
        authVerifiedDay = verified ? todayKey() : "";
        precheckedAuthStatus = error == null && !verified ? status : null;

        Runnable onVerified = pendingAuthVerified;
        Runnable onFinished = pendingAuthFinished;
        String requestedBookId = pendingAuthBookId;
        pendingAuthVerified = null;
        pendingAuthFinished = null;
        pendingAuthBookId = "";
        if (onFinished == null || !page.equals("detail") || !currentDetailBookId.equals(requestedBookId)) return;

        onFinished.run();
        if (error != null) {
            toast("验证服务连接失败，请稍后重试");
        } else if (verified) {
            onVerified.run();
        } else {
            showVerificationDialog(status, onVerified);
        }
    }

    private void showVerificationDialog(SoftwareHubClient.AuthStatus status, Runnable onVerified) {
        LinearLayout panel = column(0);
        panel.setPadding(dp(16), dp(16), dp(16), dp(12));
        panel.setBackground(round(Color.WHITE, 20, Color.WHITE, 0));
        panel.addView(text("验证后即可开始下载", 21, DARK, true));
        panel.addView(text("验证码免费获取，每天仅需验证一次", 13, MUTED, false), matchWrap(7, 18));

        Button getCode = primaryButton("免费获取验证码  →");
        getCode.setTextSize(15);
        panel.addView(getCode, new LinearLayout.LayoutParams(-1, dp(46)));

        EditText code = new EditText(this);
        code.setHint("请输入今日验证码");
        code.setSingleLine(true);
        code.setTextSize(16);
        code.setPadding(dp(14), 0, dp(14), 0);
        code.setBackground(round(Color.WHITE, 12, Color.rgb(211, 216, 224), 1));
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(-1, dp(50));
        codeParams.topMargin = dp(18);
        panel.addView(code, codeParams);
        TextView codeError = text("", 12, RED, false);
        codeError.setVisibility(View.GONE);
        codeError.setPadding(dp(4), 0, dp(4), 0);
        panel.addView(codeError, matchWrap(5, 0));

        LinearLayout actions = row();
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button cancel = secondaryButton("取消");
        Button verify = primaryButton("验证并下载");
        verify.setEnabled(false);
        verify.setAlpha(0.45f);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(dp(84), dp(44));
        cancelParams.rightMargin = dp(10);
        actions.addView(cancel, cancelParams);
        actions.addView(verify, new LinearLayout.LayoutParams(dp(136), dp(44)));
        panel.addView(actions, matchWrap(20, 0));

        AlertDialog dialog = new AlertDialog.Builder(this).setView(panel).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(Math.min(dp(330), (int) (getResources().getDisplayMetrics().widthPixels * 0.80f)), -2);
            getCode.setOnClickListener(view -> {
                if (status.codeUrl.isEmpty()) { toast("后台暂未配置验证码获取链接"); return; }
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(status.codeUrl)));
            });
            cancel.setOnClickListener(view -> dialog.dismiss());
            verify.setOnClickListener(view -> {
                String value = code.getText().toString().trim();
                if (value.length() != 4) {
                    codeError.setText("请输入 4 位验证码");
                    codeError.setVisibility(View.VISIBLE);
                    return;
                }
                verify.setEnabled(false);
                verify.setAlpha(0.45f);
                executor.execute(() -> {
                    try {
                        String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                        String newToken = softwareHub.verify(clientId(), version, value);
                        settingsPreferences.edit().putString("softwareHubToken", newToken).apply();
                        runOnUiThreadIfAlive(() -> {
                            authPrecheckState = AuthPrecheckState.VERIFIED;
                            authVerifiedDay = todayKey();
                            precheckedAuthStatus = null;
                            dialog.dismiss();
                            onVerified.run();
                        });
                    } catch (Exception error) {
                        runOnUiThreadIfAlive(() -> {
                            verify.setEnabled(true);
                            verify.setAlpha(1f);
                            String errorMessage = message(error);
                            codeError.setText(errorMessage.contains("验证码")
                                    ? "验证码错误或已过期" : "网络异常，请稍后重试");
                            codeError.setVisibility(View.VISIBLE);
                        });
                    }
                });
            });
        });
        code.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                codeError.setVisibility(View.GONE);
                boolean ready = value.toString().trim().length() == 4;
                verify.setEnabled(ready);
                verify.setAlpha(ready ? 1f : 0.45f);
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        dialog.show();
    }

    private String todayKey() {
        java.util.Calendar today = java.util.Calendar.getInstance();
        return today.get(java.util.Calendar.YEAR) + "-" + today.get(java.util.Calendar.DAY_OF_YEAR);
    }

    private String clientId() {
        String id = settingsPreferences.getString("softwareHubClientId", "");
        if (!id.isEmpty()) return id;
        id = UUID.randomUUID().toString();
        settingsPreferences.edit().putString("softwareHubClientId", id).apply();
        return id;
    }

    private void showDownloads() {
        cancelSearch();
        cancelDetail();
        downloadRows.clear();
        searchRequestId++;
        detailRequestId++;
        authRequestId++;
        page = "downloads";
        LinearLayout content = column(20);
        LinearLayout heading = row();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        if (manageDownloads) {
            Button cancelSelection = secondaryButton("取消");
            cancelSelection.setOnClickListener(view -> exitSelectionMode());
            heading.addView(cancelSelection, new LinearLayout.LayoutParams(dp(68), dp(40)));
            TextView selectionTitle = text("已选择 " + selectedDownloadIds.size() + " 项", 18, DARK, true);
            selectionTitle.setGravity(Gravity.CENTER);
            heading.addView(selectionTitle, new LinearLayout.LayoutParams(0, dp(40), 1));
            heading.addView(new View(this), new LinearLayout.LayoutParams(dp(68), dp(40)));
        } else {
            heading.addView(text("下载任务", 28, DARK, true), new LinearLayout.LayoutParams(0, -2, 1));
        }
        content.addView(heading, matchWrap(8, 16));
        List<DownloadEngine.Task> tasks = downloadEngine.getTasks();
        int completed = 0;
        for (DownloadEngine.Task task : tasks) if (task.status.equals("COMPLETED")) completed++;
        int active = tasks.size() - completed;
        LinearLayout tabs = row();
        tabs.addView(taskTab("进行中  " + active, !showCompletedDownloads, view -> switchDownloadTab(false)), new LinearLayout.LayoutParams(0, dp(48), 1));
        tabs.addView(taskTab("已完成  " + completed, showCompletedDownloads, view -> switchDownloadTab(true)), new LinearLayout.LayoutParams(0, dp(48), 1));
        activeDownloadTabTitle = (TextView) ((LinearLayout) tabs.getChildAt(0)).getChildAt(0);
        completedDownloadTabTitle = (TextView) ((LinearLayout) tabs.getChildAt(1)).getChildAt(0);
        content.addView(tabs, matchWrap(0, 18));

        int visible = 0;
        for (DownloadEngine.Task task : tasks) {
            boolean isCompleted = task.status.equals("COMPLETED");
            if (isCompleted == showCompletedDownloads) {
                content.addView(downloadCard(task), matchWrap(0, 12));
                visible++;
            }
        }
        if (visible == 0) {
            content.addView(emptyDownloadsState(showCompletedDownloads), new LinearLayout.LayoutParams(-1, dp(330)));
        }
        setScreen(content, "downloads");
    }

    private void refreshDownloadCards() {
        List<DownloadEngine.Task> visible = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        int completed = 0;
        for (DownloadEngine.Task task : downloadEngine.getTasks()) {
            if (task.status.equals("COMPLETED")) completed++;
            if (task.status.equals("COMPLETED") == showCompletedDownloads) {
                visible.add(task);
                ids.add(task.id);
            }
        }
        if (!ids.equals(new ArrayList<>(downloadRows.keySet()))) {
            int scrollY = currentScroll == null ? 0 : currentScroll.getScrollY();
            showDownloads();
            ScrollView scroll = currentScroll;
            scroll.post(() -> scroll.scrollTo(0, scrollY));
            return;
        }
        String activeLabel = "进行中  " + (downloadEngine.getTasks().size() - completed);
        String completedLabel = "已完成  " + completed;
        if (!TextUtils.equals(activeDownloadTabTitle.getText(), activeLabel)) activeDownloadTabTitle.setText(activeLabel);
        if (!TextUtils.equals(completedDownloadTabTitle.getText(), completedLabel)) completedDownloadTabTitle.setText(completedLabel);
        for (DownloadEngine.Task task : visible) {
            DownloadRow binding = downloadRows.get(task.id);
            if (!binding.task.status.equals(task.status)) {
                LinearLayout parent = (LinearLayout) binding.card.getParent();
                int index = parent.indexOfChild(binding.card);
                android.view.ViewGroup.LayoutParams params = binding.card.getLayoutParams();
                parent.removeView(binding.card);
                parent.addView(downloadCard(task), index, params);
            } else {
                if (binding.task.downloaded != task.downloaded) {
                    binding.count.setText(task.downloaded + " / " + task.total() + " 章");
                    if (binding.progress != null) binding.progress.setProgress(task.percent());
                }
                if (binding.state != null && (binding.task.downloaded != task.downloaded
                        || !java.util.Objects.equals(binding.task.error, task.error))) {
                    binding.state.setText(downloadStateText(task));
                }
                binding.task = task;
            }
        }
    }

    private String downloadStateText(DownloadEngine.Task task) {
        if (task.status.equals("WAITING")) return "加载中";
        if (task.status.equals("FAILED")) return "失败：" + task.error;
        if (task.status.equals("DOWNLOADING") && task.error != null) {
            String reason = task.error.split("，", 2)[0];
            if (reason.equals("等待重试") || reason.equals("本书请求异常") || reason.equals("接口暂时异常")) {
                return reason + "  " + task.percent() + "%";
            }
            if (task.error.contains("待补 ")) return "下载中，部分章节待重试  " + task.percent() + "%";
        }
        return stateName(task.status) + "  " + task.percent() + "%";
    }

    private View downloadCard(DownloadEngine.Task task) {
        DownloadRow binding = new DownloadRow();
        binding.task = task;
        LinearLayout card = column(10);
        binding.card = card;
        downloadRows.put(task.id, binding);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        boolean loading = task.status.equals("WAITING");
        boolean downloading = task.status.equals("DOWNLOADING") || task.status.equals("EXPORTING");
        card.setBackground(round(Color.WHITE, 20, BORDER, 1));
        card.setElevation(dp(2));
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        if (manageDownloads) {
            CheckBox selected = new CheckBox(this);
            selected.setContentDescription("选择《" + task.title + "》");
            selected.setButtonTintList(android.content.res.ColorStateList.valueOf(RED));
            selected.setChecked(selectedDownloadIds.contains(task.id));
            selected.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selectedDownloadIds.add(task.id);
                else selectedDownloadIds.remove(task.id);
                showDownloads();
            });
            top.addView(selected, new LinearLayout.LayoutParams(dp(42), dp(48)));
        }
        top.addView(coverView(task.coverUrl, 68, 92), new LinearLayout.LayoutParams(dp(68), dp(92)));
        LinearLayout right = column(0);
        right.setPadding(dp(12), 0, 0, 0);
        TextView taskTitle = text(task.title, 15, DARK, true);
        taskTitle.setSingleLine(true);
        taskTitle.setEllipsize(TextUtils.TruncateAt.END);
        right.addView(taskTitle, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout detailsRow = row();
        detailsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout info = column(0);
        info.addView(text(task.format + " · " + task.start + "–" + task.end + " 章", 12, RED, false), matchWrap(5, 3));
        binding.count = text(task.downloaded + " / " + task.total() + " 章", 12, MUTED, false);
        info.addView(binding.count, matchWrap(3, 4));
        if (!task.status.equals("COMPLETED")) {
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            binding.progress = bar;
            bar.setIndeterminate(loading);
            if (!loading) { bar.setMax(100); bar.setProgress(task.percent()); }
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(RED));
            bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(RED));
            bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(BORDER));
            info.addView(bar, new LinearLayout.LayoutParams(-1, dp(4)));
            String stateText = downloadStateText(task);
            TextView state = text(stateText, 12, task.status.equals("FAILED") ? RED : MUTED, false);
            binding.state = state;
            state.setPadding(dp(10), dp(4), dp(10), dp(4));
            boolean warningState = task.status.equals("FAILED") || downloading;
            state.setBackground(round(warningState ? RED_LIGHT : LIGHT, 16,
                    warningState ? RED_LIGHT : LIGHT, 0));
            LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(-2, -2);
            stateParams.topMargin = dp(4);
            info.addView(state, stateParams);
        }
        detailsRow.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        if (!manageDownloads) {
            LinearLayout actions = row();
            actions.setGravity(Gravity.CENTER_VERTICAL);
            if (task.status.equals("COMPLETED")) {
                ImageButton delete = taskIconButton(R.drawable.ic_task_delete, "删除任务记录");
                delete.setOnClickListener(view -> confirmDeleteTask(task, false));
                actions.addView(delete, new LinearLayout.LayoutParams(dp(32), dp(32)));
                ImageButton share = taskIconButton(R.drawable.ic_task_share, "分享文件");
                share.setOnClickListener(view -> openFile(task));
                LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(dp(32), dp(32));
                shareParams.leftMargin = dp(4);
                shareParams.rightMargin = dp(6);
                actions.addView(share, shareParams);
            } else {
                boolean resumable = task.status.equals("PAUSED") || task.status.equals("FAILED") || task.status.equals("CANCELED");
                ImageButton pauseOrResume = taskIconButton(
                        resumable ? R.drawable.ic_task_play : R.drawable.ic_task_pause,
                        resumable ? "继续下载" : "暂停下载"
                );
                pauseOrResume.setOnClickListener(view -> {
                    if (resumable) downloadEngine.resume(task.id);
                    else downloadEngine.pause(task.id);
                });
                actions.addView(pauseOrResume, new LinearLayout.LayoutParams(dp(32), dp(32)));

                ImageButton delete = taskIconButton(R.drawable.ic_task_delete, "删除下载任务");
                delete.setOnClickListener(view -> confirmDeleteTask(task, true));
                LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(32), dp(32));
                deleteParams.leftMargin = dp(4);
                actions.addView(delete, deleteParams);
            }
            detailsRow.addView(actions, new LinearLayout.LayoutParams(-2, -1));
        }
        right.addView(detailsRow, new LinearLayout.LayoutParams(-1, -2));
        top.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(top);
        card.setOnLongClickListener(view -> {
            if (!manageDownloads) enterSelectionMode(task.id);
            return true;
        });
        card.setOnClickListener(view -> {
            if (manageDownloads) toggleTaskSelection(task.id);
        });
        return card;
    }

    private ImageButton taskIconButton(int iconResource, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconResource);
        button.setColorFilter(RED);
        button.setPadding(dp(7), dp(7), dp(7), dp(7));
        int paleRed = Color.rgb(255, 246, 244);
        button.setBackground(round(paleRed, 11, paleRed, 0));
        button.setContentDescription(description);
        return button;
    }

    private View taskTab(String label, boolean selected, View.OnClickListener click) {
        LinearLayout tab = column(0);
        tab.setGravity(Gravity.CENTER);
        TextView title = text(label, 15, selected ? RED : MUTED, selected);
        title.setGravity(Gravity.CENTER);
        tab.addView(title, new LinearLayout.LayoutParams(-1, 0, 1));
        View indicator = new View(this);
        indicator.setBackground(round(selected ? RED : Color.TRANSPARENT, 2, Color.TRANSPARENT, 0));
        tab.addView(indicator, new LinearLayout.LayoutParams(dp(42), dp(3)));
        tab.setOnClickListener(click);
        return tab;
    }

    private View emptyDownloadsState(boolean completed) {
        LinearLayout empty = column(0);
        empty.setGravity(Gravity.CENTER);
        TextView icon = text(completed ? "✓" : "⇩", 38, Color.rgb(255, 157, 147), true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(RED_LIGHT, 34, Color.TRANSPARENT, 0));
        empty.addView(icon, new LinearLayout.LayoutParams(dp(68), dp(68)));
        TextView title = text(completed ? "暂无已完成任务" : "暂无下载任务", 18, DARK, true);
        title.setGravity(Gravity.CENTER);
        empty.addView(title, matchWrap(32, 16));
        TextView description = text(completed ? "下载完成后的小说会显示在这里" : "搜索小说并开始下载后，任务会显示在这里", 14, MUTED, false);
        description.setGravity(Gravity.CENTER);
        empty.addView(description, matchWrap(0, 30));
        if (!completed) {
            Button search = secondaryButton("去搜索小说");
            search.setOnClickListener(view -> showSearch());
            empty.addView(search, new LinearLayout.LayoutParams(dp(112), dp(40)));
        }
        return empty;
    }

    private void switchDownloadTab(boolean completed) {
        showCompletedDownloads = completed;
        selectedDownloadIds.clear();
        showDownloads();
    }

    private void confirmDeleteTask(DownloadEngine.Task task, boolean activeTask) {
        LinearLayout panel = column(0);
        panel.setPadding(dp(20), dp(22), dp(20), dp(20));
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setBackground(round(Color.WHITE, 24, Color.WHITE, 0));

        FrameLayout iconArea = new FrameLayout(this);
        View outerGlow = new View(this);
        outerGlow.setBackground(round(Color.rgb(255, 247, 246), 38, Color.TRANSPARENT, 0));
        iconArea.addView(outerGlow, new FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER));
        View innerGlow = new View(this);
        innerGlow.setBackground(round(Color.rgb(255, 237, 235), 30, Color.TRANSPARENT, 0));
        iconArea.addView(innerGlow, new FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER));
        ImageView trash = new ImageView(this);
        trash.setImageResource(R.drawable.ic_task_delete);
        trash.setColorFilter(Color.rgb(205, 62, 52));
        trash.setPadding(dp(10), dp(10), dp(10), dp(10));
        iconArea.addView(trash, new FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER));
        View leftDot = new View(this);
        leftDot.setBackground(round(Color.rgb(250, 223, 220), 4, Color.TRANSPARENT, 0));
        FrameLayout.LayoutParams leftDotParams = new FrameLayout.LayoutParams(dp(8), dp(8));
        leftDotParams.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
        leftDotParams.leftMargin = dp(7);
        iconArea.addView(leftDot, leftDotParams);
        View rightDot = new View(this);
        rightDot.setBackground(round(Color.rgb(250, 223, 220), 5, Color.TRANSPARENT, 0));
        FrameLayout.LayoutParams rightDotParams = new FrameLayout.LayoutParams(dp(9), dp(9));
        rightDotParams.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        rightDotParams.rightMargin = dp(5);
        iconArea.addView(rightDot, rightDotParams);
        panel.addView(iconArea, new LinearLayout.LayoutParams(dp(92), dp(68)));

        TextView title = text(activeTask ? "删除下载任务" : "删除任务记录", 21, DARK, true);
        title.setGravity(Gravity.CENTER);
        panel.addView(title, matchWrap(6, 7));
        TextView prompt = text(activeTask ? "确定删除此下载任务？" : "确定删除这条任务记录？", 15, DARK, false);
        prompt.setGravity(Gravity.CENTER);
        panel.addView(prompt, matchWrap(0, 12));

        LinearLayout warning = row();
        warning.setGravity(Gravity.CENTER_VERTICAL);
        warning.setPadding(dp(12), dp(9), dp(12), dp(9));
        warning.setBackground(round(Color.rgb(255, 246, 244), 14, Color.rgb(255, 246, 244), 0));
        TextView warningIcon = text("!", 14, RED, true);
        warningIcon.setGravity(Gravity.CENTER);
        warningIcon.setBackground(round(Color.TRANSPARENT, 12, RED, 1));
        warning.addView(warningIcon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView warningText = text(activeTask ? "未完成的章节缓存将被删除。" : "已经下载到本地的 TXT/EPUB 文件会保留", 13, MUTED, false);
        warningText.setIncludeFontPadding(true);
        warningText.setLineSpacing(0, 1.05f);
        LinearLayout.LayoutParams warningTextParams = new LinearLayout.LayoutParams(0, -2, 1);
        warningTextParams.leftMargin = dp(12);
        warning.addView(warningText, warningTextParams);
        panel.addView(warning, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = row();
        Button back = secondaryButton("取消");
        back.setTextColor(MUTED);
        back.setTextSize(16);
        back.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        back.setBackground(round(Color.WHITE, 15, Color.rgb(211, 213, 218), 1));
        Button confirm = primaryButton("删除");
        confirm.setTextSize(16);
        confirm.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable deleteBackground = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(241, 86, 75), Color.rgb(207, 53, 45)}
        );
        deleteBackground.setCornerRadius(dp(15));
        confirm.setBackground(deleteBackground);
        confirm.setElevation(dp(5));
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        backParams.rightMargin = dp(8);
        actions.addView(back, backParams);
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(0, dp(46), 1.12f);
        confirmParams.leftMargin = dp(2);
        actions.addView(confirm, confirmParams);
        panel.addView(actions, matchWrap(14, 0));

        AlertDialog dialog = new AlertDialog.Builder(this).setView(panel).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            int width = Math.min(dp(330), (int) (getResources().getDisplayMetrics().widthPixels * 0.80f));
            dialog.getWindow().setLayout(width, -2);
            back.setOnClickListener(view -> dialog.dismiss());
            confirm.setOnClickListener(view -> {
                dialog.dismiss();
                downloadEngine.deleteTasks(java.util.Collections.singletonList(task.id));
            });
        });
        dialog.show();
    }

    private List<DownloadEngine.Task> currentTabTasks() {
        List<DownloadEngine.Task> visible = new ArrayList<>();
        for (DownloadEngine.Task task : downloadEngine.getTasks()) {
            if (task.status.equals("COMPLETED") == showCompletedDownloads) visible.add(task);
        }
        return visible;
    }

    private void enterSelectionMode(String selectedTaskId) {
        manageDownloads = true;
        selectedDownloadIds.clear();
        if (selectedTaskId != null) selectedDownloadIds.add(selectedTaskId);
        showDownloads();
    }

    private void exitSelectionMode() {
        manageDownloads = false;
        selectedDownloadIds.clear();
        showDownloads();
    }

    private void toggleTaskSelection(String taskId) {
        if (!selectedDownloadIds.add(taskId)) selectedDownloadIds.remove(taskId);
        showDownloads();
    }

    private View selectionActionBar() {
        List<DownloadEngine.Task> visibleTasks = currentTabTasks();
        List<String> visibleIds = new ArrayList<>();
        for (DownloadEngine.Task task : visibleTasks) visibleIds.add(task.id);
        boolean allSelected = !visibleIds.isEmpty() && selectedDownloadIds.containsAll(visibleIds);
        LinearLayout bar = row();
        bar.setPadding(dp(20), dp(8), dp(20), dp(8));
        bar.setBackgroundColor(Color.WHITE);
        Button selectAll = secondaryButton(allSelected ? "取消全选" : "全选");
        selectAll.setOnClickListener(view -> {
            if (allSelected) selectedDownloadIds.removeAll(visibleIds);
            else selectedDownloadIds.addAll(visibleIds);
            showDownloads();
        });
        Button delete = primaryButton("删除（" + selectedDownloadIds.size() + "）");
        delete.setEnabled(!selectedDownloadIds.isEmpty());
        delete.setAlpha(selectedDownloadIds.isEmpty() ? 0.4f : 1f);
        delete.setOnClickListener(view -> deleteSelectedTasks());
        bar.addView(selectAll, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        deleteParams.leftMargin = dp(12);
        bar.addView(delete, deleteParams);
        return bar;
    }

    private void deleteSelectedTasks() {
        if (selectedDownloadIds.isEmpty()) {
            toast("请先选择要删除的任务");
            return;
        }
        int count = selectedDownloadIds.size();
        AlertDialog bulkDialog = new AlertDialog.Builder(this)
                .setTitle("删除任务记录")
                .setMessage("确定删除选中的 " + count + " 个任务？\n\n进行中的下载会停止，已经下载到本地的TXT/EPUB文件会保留")
                .setNegativeButton("返回", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    downloadEngine.deleteTasks(new java.util.ArrayList<>(selectedDownloadIds));
                    selectedDownloadIds.clear();
                    manageDownloads = false;
                })
                .create();
        bulkDialog.setOnShowListener(ignored -> bulkDialog.getWindow().setLayout(
                Math.min(dp(330), (int) (getResources().getDisplayMetrics().widthPixels * 0.80f)), -2));
        bulkDialog.show();
    }

    private void showSettings() {
        cancelSearch();
        cancelDetail();
        searchRequestId++;
        detailRequestId++;
        authRequestId++;
        page = "settings";
        LinearLayout content = column(20);
        content.addView(text("设置", 28, DARK, true), matchWrap(8, 4));
        content.addView(text("个性化设置，让阅读下载更顺心", 14, MUTED, false), matchWrap(0, 20));
        LinearLayout directory = section("保存目录");
        TextView current = text(directoryLabel(), 14, MUTED, false);
        directory.addView(current, matchWrap(6, 18));
        Button choose = primaryButton("选择下载目录");
        choose.setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, PICK_DIRECTORY);
        });
        directory.addView(choose, new LinearLayout.LayoutParams(-1, dp(54)));
        Button reset = secondaryButton("恢复默认目录");
        reset.setOnClickListener(view -> { settingsPreferences.edit().remove("treeUri").apply(); showSettings(); });
        LinearLayout.LayoutParams resetParams = matchWrap(12, 4);
        resetParams.height = dp(52);
        directory.addView(reset, resetParams);
        content.addView(directory, matchWrap(0, 8));
        LinearLayout cache = section("缓存管理");
        TextView usage = text("正在计算缓存占用……", 14, MUTED, false);
        cache.addView(usage, matchWrap(8, 12));
        cache.addView(text("清理封面和已完成任务的正文缓存，保留未完成任务续传数据和已导出的文件。", 12, MUTED, false), matchWrap(0, 14));
        Button clear = secondaryButton("清理可清理缓存");
        clear.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("清理缓存")
                .setMessage("清理封面和已完成任务的正文缓存？未完成任务和已导出的文件会保留，之后重复下载可能需要重新获取章节。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清理", (dialog, which) -> {
                    ((java.util.concurrent.ThreadPoolExecutor) imageExecutor).getQueue().clear();
                    clear.setEnabled(false);
                    clear.setText("清理中……");
                    executor.execute(() -> {
                        try {
                            cacheManager.clear();
                            coverCache.evictAll();
                            CacheManager.Usage fresh = cacheManager.usage();
                            runOnUiThreadIfAlive(() -> {
                                toast("缓存已清理");
                                if (usage.isAttachedToWindow()) {
                                    usage.setText(cacheUsageLabel(fresh));
                                    clear.setEnabled(true);
                                    clear.setText("清理可清理缓存");
                                }
                            });
                        } catch (Exception error) {
                            runOnUiThreadIfAlive(() -> {
                                toast("缓存清理失败：" + message(error));
                                clear.setEnabled(true);
                                clear.setText("清理可清理缓存");
                            });
                        }
                    });
                }).show());
        cache.addView(clear, new LinearLayout.LayoutParams(-1, dp(52)));
        content.addView(cache, matchWrap(10, 8));
        setScreen(content, "settings");
        executor.execute(() -> {
            CacheManager.Usage fresh = cacheManager.usage();
            runOnUiThreadIfAlive(() -> {
                if (usage.isAttachedToWindow()) usage.setText(cacheUsageLabel(fresh));
            });
        });
    }

    private static String cacheUsageLabel(CacheManager.Usage usage) {
        return String.format(java.util.Locale.CHINA, "可清理缓存文件 %.2f MB\n续传保留 %.2f MB",
                usage.clearable / 1048576.0, usage.retained / 1048576.0);
    }

    @SuppressLint("WrongConstant")
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_DIRECTORY && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            getContentResolver().takePersistableUriPermission(uri, data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            settingsPreferences.edit().putString("treeUri", uri.toString()).apply();
            showSettings();
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATION_PERMISSION);
        }
    }

    @Override public void onBackPressed() {
        if (manageDownloads) {
            exitSelectionMode();
        } else if (page.equals("detail") || page.equals("downloads") || page.equals("settings")) {
            showSearch();
        } else {
            super.onBackPressed();
        }
    }

    private void setScreen(LinearLayout content, String active) {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(LIGHT);
        applySystemBarInsets(root);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(content);
        currentScroll = scroll;
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (page.equals("downloads") && manageDownloads) {
            View divider = new View(this);
            divider.setBackgroundColor(BORDER);
            root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
            root.addView(selectionActionBar(), new LinearLayout.LayoutParams(-1, dp(64)));
        }
        LinearLayout navArea = new LinearLayout(this);
        navArea.setOrientation(LinearLayout.VERTICAL);
        navArea.setBackgroundColor(Color.WHITE);
        navArea.setElevation(dp(8));
        View divider = new View(this);
        divider.setBackgroundColor(BORDER);
        navArea.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        navArea.addView(bottomNav(active), new LinearLayout.LayoutParams(-1, dp(71)));
        bottomNavArea = navArea;
        root.addView(navArea, new LinearLayout.LayoutParams(-1, dp(72)));
        setContentView(root);
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (bottomNavArea == null) return;
            Rect visibleFrame = new Rect();
            root.getWindowVisibleDisplayFrame(visibleFrame);
            int coveredHeight = root.getRootView().getHeight() - visibleFrame.bottom;
            bottomNavArea.setVisibility(coveredHeight > dp(160) ? View.GONE : View.VISIBLE);
        });
    }

    private void applySystemBarInsets(View root) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            WindowInsets systemInsets = insets;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(view.getPaddingLeft(), bars.top, view.getPaddingRight(), bars.bottom);
                if (bottomNavArea != null) {
                    bottomNavArea.setVisibility(insets.isVisible(WindowInsets.Type.ime()) ? View.GONE : View.VISIBLE);
                }
            } else {
                view.setPadding(view.getPaddingLeft(), systemInsets.getSystemWindowInsetTop(),
                        view.getPaddingRight(), systemInsets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        root.requestApplyInsets();
    }

    private View bottomNav(String active) {
        LinearLayout nav = row(); nav.setGravity(Gravity.CENTER); nav.setPadding(dp(8), dp(5), dp(8), dp(3)); nav.setBackgroundColor(Color.WHITE);
        nav.addView(navButton(R.drawable.ic_nav_search, "搜索", active.equals("search"), view -> {
            manageDownloads = false;
            selectedDownloadIds.clear();
            if (searchTabShowingDetail && !currentDetailBookId.isEmpty()) {
                if (!page.equals("detail")) loadDetail(currentDetailBookId);
            } else {
                showSearch();
            }
        }), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navButton(R.drawable.ic_nav_download, "下载", active.equals("downloads"), view -> {
            rememberSearchState();
            if (!page.equals("downloads")) showCompletedDownloads = false;
            showDownloads();
        }), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navButton(R.drawable.ic_nav_settings, "设置", active.equals("settings"), view -> {
            manageDownloads = false;
            selectedDownloadIds.clear();
            rememberSearchState();
            showSettings();
        }), new LinearLayout.LayoutParams(0, -1, 1));
        return nav;
    }

    private View navButton(int iconResource, String label, boolean active, View.OnClickListener click) {
        LinearLayout item = column(0);
        item.setGravity(Gravity.CENTER);
        int color = active ? RED : MUTED;
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResource);
        icon.setColorFilter(color);
        item.addView(icon, new LinearLayout.LayoutParams(dp(27), dp(27)));
        TextView title = text(label, 12, color, active);
        title.setGravity(Gravity.CENTER);
        item.addView(title, new LinearLayout.LayoutParams(-1, dp(22)));
        item.setOnClickListener(click);
        return item;
    }

    private View brandTitle(boolean back) {
        LinearLayout row = row(); row.setGravity(Gravity.CENTER_VERTICAL);
        int titleSize = back ? 24 : 28;
        int titleHeight = back ? 44 : 48;
        if (back) {
            ImageButton arrow = new ImageButton(this);
            arrow.setImageResource(com.fanqieshuku.loginprobe.R.drawable.ic_arrow_back);
            arrow.setBackgroundColor(Color.TRANSPARENT);
            arrow.setPadding(dp(3), dp(9), dp(3), dp(9));
            arrow.setContentDescription("返回");
            arrow.setOnClickListener(view -> showSearch());
            row.addView(arrow, new LinearLayout.LayoutParams(dp(36), dp(titleHeight)));
        }
        TextView brand = text("西红柿", titleSize, RED, true);
        brand.setIncludeFontPadding(false);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(brand, new LinearLayout.LayoutParams(-2, dp(titleHeight)));
        TextView title = text("下载器", titleSize, DARK, true);
        title.setIncludeFontPadding(false);
        title.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(title, new LinearLayout.LayoutParams(-2, dp(titleHeight)));
        return row;
    }

    private View detailBrandTitle() {
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton arrow = new ImageButton(this);
        arrow.setImageResource(R.drawable.ic_arrow_back);
        arrow.setBackgroundColor(Color.TRANSPARENT);
        arrow.setPadding(dp(5), dp(9), dp(5), dp(9));
        arrow.setContentDescription("返回");
        arrow.setOnClickListener(view -> showSearch());
        header.addView(arrow, new LinearLayout.LayoutParams(dp(36), dp(44)));
        TextView brand = text("西红柿", 24, RED, true);
        brand.setIncludeFontPadding(false);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(brand, new LinearLayout.LayoutParams(-2, dp(44)));
        TextView title = text("下载器", 24, DARK, true);
        title.setIncludeFontPadding(false);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(-2, dp(44)));
        return header;
    }

    private LinearLayout section(String title) {
        LinearLayout section = column(16); section.setBackground(round(Color.WHITE, 20, BORDER, 1)); section.setElevation(dp(2)); section.addView(text(title, 20, DARK, true)); return section;
    }
    private LinearLayout column(int padding) { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); view.setPadding(dp(padding), dp(padding), dp(padding), dp(padding)); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); return view; }
    private TextView text(String value, int size, int color, boolean bold) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view; }
    private Button primaryButton(String label) { Button button = new Button(this); button.setText(label); button.setTextSize(15); button.setTextColor(Color.WHITE); button.setAllCaps(false); button.setMinHeight(0); button.setMinimumHeight(0); button.setBackground(round(RED, 14, RED, 0)); return button; }
    private void setDownloadButtonIcon(Button button) {
        Drawable icon = getDrawable(R.drawable.ic_nav_download).mutate();
        icon.setTint(Color.WHITE);
        icon.setBounds(0, 0, dp(22), dp(22));
        button.setGravity(Gravity.CENTER);
        button.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        button.setCompoundDrawables(null, null, null, null);
        SpannableString content = new SpannableString("  开始下载");
        content.setSpan(new OffsetImageSpan(icon, dp(2)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        button.setText(content);
    }
    private Button secondaryButton(String label) { Button button = new Button(this); button.setText(label); button.setTextSize(14); button.setTextColor(RED); button.setAllCaps(false); button.setMinHeight(0); button.setMinimumHeight(0); button.setBackground(round(RED_LIGHT, 14, RED_LIGHT, 0)); return button; }
    private RadioButton radio(String label) { RadioButton radio = new RadioButton(this); radio.setId(View.generateViewId()); radio.setText(label); radio.setTextSize(14); radio.setTextColor(DARK); radio.setGravity(Gravity.CENTER_VERTICAL); radio.setPadding(dp(8), 0, dp(6), 0); radio.setButtonTintList(android.content.res.ColorStateList.valueOf(RED)); return radio; }
    private EditText numberInput(String hint, String value) { EditText input = new EditText(this); input.setHint(hint); input.setText(value); input.setTextSize(14); input.setInputType(InputType.TYPE_CLASS_NUMBER); input.setMinHeight(0); input.setMinimumHeight(0); input.setPadding(dp(10), 0, dp(10), 0); input.setBackgroundColor(Color.TRANSPARENT); return input; }
    private FrameLayout numberInputFrame(EditText input) { FrameLayout frame = new FrameLayout(this); frame.setBackground(round(Color.WHITE, 12, Color.rgb(216, 221, 229), 1)); frame.addView(input, new FrameLayout.LayoutParams(-1, -1)); return frame; }
    private GradientDrawable round(int color, int radius, int strokeColor, int strokeWidth) { GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); if (strokeWidth > 0) shape.setStroke(dp(strokeWidth), strokeColor); return shape; }
    private LinearLayout.LayoutParams matchWrap(int top, int bottom) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(top); params.bottomMargin = dp(bottom); return params; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private String message(Throwable error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    private String directoryLabel() {
        String storedUri = settingsPreferences.getString("treeUri", "");
        if (storedUri.isEmpty()) {
            return new java.io.File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "西红柿下载器"
            ).getAbsolutePath();
        }
        try {
            Uri treeUri = Uri.parse(storedUri);
            String documentId = DocumentsContract.getTreeDocumentId(treeUri);
            if (documentId.startsWith("raw:")) return documentId.substring(4);
            if ("com.android.providers.downloads.documents".equals(treeUri.getAuthority())
                    && documentId.equalsIgnoreCase("downloads")) {
                return Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                ).getAbsolutePath();
            }
            String[] parts = documentId.split(":", 2);
            if (parts.length == 2) {
                String root = parts[0].equalsIgnoreCase("primary")
                        ? Environment.getExternalStorageDirectory().getAbsolutePath()
                        : "/storage/" + parts[0];
                return parts[1].isEmpty() ? root : root + "/" + parts[1];
            }
            return Uri.decode(documentId);
        } catch (Exception ignored) {
            return Uri.decode(storedUri);
        }
    }

    private String extractBookId(String input) {
        if (input.matches("\\d{10,}")) return input;
        if (input.startsWith("http://") || input.startsWith("https://")) {
            Matcher matcher = Pattern.compile("(\\d{10,})").matcher(input);
            if (matcher.find()) return matcher.group(1);
        }
        return null;
    }

    private ImageView coverView(String url, int width, int height) {
        ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER); image.setBackground(round(LIGHT, 10, LIGHT, 0));
        image.setImageResource(R.drawable.ic_book_placeholder);
        image.setColorFilter(Color.rgb(199, 205, 214));
        image.setTag(url);
        Bitmap cached = url == null ? null : coverCache.get(url);
        if (cached != null) { image.clearColorFilter(); image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setImageBitmap(cached); }
        else if (url != null && !url.isEmpty()) imageExecutor.execute(() -> {
            long epoch = coverStore.generation();
            try {
                byte[] bytes = coverStore.get(url, () -> downloadCover(url));
                Bitmap bitmap = decodeCover(bytes, dp(width), dp(height));
                if (bitmap != null && epoch == coverStore.generation()) coverCache.put(url, bitmap);
                runOnUiThreadIfAlive(() -> {
                    if (bitmap != null && url.equals(image.getTag())) {
                        image.clearColorFilter();
                        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        image.setImageBitmap(bitmap);
                    }
                });
            } catch (Exception ignored) { }
        });
        return image;
    }

    private byte[] downloadCover(String url) throws Exception {
        String secure = url.startsWith("http://") ? "https://" + url.substring(7) : url;
        HttpURLConnection connection = (HttpURLConnection) new URL(secure).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        try {
            int contentLength = connection.getContentLength();
            if (contentLength > MAX_COVER_BYTES) return null;
            byte[] bytes;
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int count;
                while ((count = stream.read(buffer)) >= 0) {
                    total += count;
                    if (total > MAX_COVER_BYTES) return null;
                    output.write(buffer, 0, count);
                }
                bytes = output.toByteArray();
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new java.io.IOException("封面不是有效图片");
            return bytes;
        } finally {
            connection.disconnect();
        }
    }

    private String stateName(String state) {
        switch (state) { case "WAITING": return "等待中"; case "DOWNLOADING": return "下载中"; case "PAUSED": return "已暂停"; case "EXPORTING": return "正在生成文件"; case "COMPLETED": return "下载完成"; case "FAILED": return "下载失败"; default: return state; }
    }

    private void openFile(DownloadEngine.Task task) {
        Uri uri;
        try {
            uri = Uri.parse(task.outputUri);
            if (!canReadFile(uri)) {
                toast("文件已被删除或移动，请重新下载");
                return;
            }
            String mimeType = task.format.equals("EPUB") ? "application/epub+zip" : "text/plain";
            Intent intent = new Intent(Intent.ACTION_SEND)
                    .setType(mimeType)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(android.content.ClipData.newRawUri("下载文件", uri));
            startActivity(Intent.createChooser(intent, "打开文件"));
        } catch (Exception error) {
            toast("文件已被删除或移动，请重新下载");
        }
    }

    private boolean canReadFile(Uri uri) {
        if (uri == null || uri.toString().isEmpty()) return false;
        if ("file".equals(uri.getScheme())) {
            return uri.getPath() != null && new java.io.File(uri.getPath()).isFile();
        }
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            return stream != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void runOnUiThreadIfAlive(Runnable action) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) action.run();
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (downloadEngine != null) downloadEngine.refresh();
    }

    private static Bitmap decodeCover(byte[] bytes, int targetWidth, int targetHeight) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / (options.inSampleSize * 2) >= targetWidth
                && bounds.outHeight / (options.inSampleSize * 2) >= targetHeight) options.inSampleSize *= 2;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    @Override protected void onDestroy() {
        cancelSearch();
        cancelDetail();
        downloadEngine.close();
        executor.shutdownNow();
        searchExecutor.shutdownNow();
        detailCoordinator.shutdownNow();
        detailExecutor.shutdownNow();
        imageExecutor.shutdownNow();
        super.onDestroy();
    }
}
