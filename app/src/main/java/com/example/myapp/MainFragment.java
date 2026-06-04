package com.example.myapp;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.leanback.app.BrowseSupportFragment;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.HeaderItem;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.OnItemViewClickedListener;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.Row;
import androidx.leanback.widget.RowPresenter;

import com.example.myapp.extractor.YouTubeExtractorService;
import com.example.myapp.model.UserPreferences;
import com.example.myapp.model.VideoItem;
import com.example.myapp.presenter.VideoCardPresenter;

import java.util.List;
import java.util.Set;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;

/**
 * Main browse screen — 8 tabs: Home, Trending, Music, Gaming, News, Sports, Comedy, Education.
 *
 * ANDROID 9 CRASH FIXES:
 * FIX 1: Uses onViewCreated() not deprecated onActivityCreated().
 *         onActivityCreated() was removed in API 28 strict fragment lifecycle enforcement.
 * FIX 2: isAdded() guard in fillRow() — fragment can detach between IO callback and UI update.
 * FIX 3: mRowsAdapter null check before every access.
 * FIX 4: CompositeDisposable cleared in onDestroyView() (not onDestroy()) to match fragment lifecycle.
 * FIX 5: All network calls go through RxJava IO scheduler — no NetworkOnMainThreadException.
 */
public class MainFragment extends BrowseSupportFragment {

    private static final String TAG = "MainFragment";

    private static final String[] TAB_NAMES = {
        "Home", "Trending", "Music", "Gaming", "News", "Sports", "Comedy", "Education"
    };
    private static final String[] TAB_QUERIES = {
        null,                        // Home: built from prefs
        null,                        // Trending: kiosk
        "best music videos 2024",
        "gaming highlights 2024",
        "world news today",
        "sports highlights 2024",
        "best comedy videos",
        "educational videos"
    };

    private ArrayObjectAdapter       mRowsAdapter;
    private final CompositeDisposable mDisposables = new CompositeDisposable();
    private UserPreferences           mPrefs;

    // FIX 1: onViewCreated, NOT onActivityCreated
    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mPrefs = new UserPreferences(requireContext());
        setupUI();
        setupListeners();
        buildSkeletonRows();
        loadAllRows();
    }

    // FIX 4: clear disposables in onDestroyView
    @Override
    public void onDestroyView() {
        mDisposables.clear();
        super.onDestroyView();
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private void setupUI() {
        setTitle("AYouTube TV");
        setHeadersState(HEADERS_ENABLED);
        setHeadersTransitionOnBackEnabled(true);
        setBrandColor(Color.parseColor("#E53935"));
        setSearchAffordanceColor(Color.parseColor("#FF6D00"));
        mRowsAdapter = new ArrayObjectAdapter(new ListRowPresenter());
        setAdapter(mRowsAdapter);
    }

    private void setupListeners() {
        setOnItemViewClickedListener(new OnItemViewClickedListener() {
            @Override
            public void onItemClicked(Presenter.ViewHolder iVH, Object item,
                                      RowPresenter.ViewHolder rVH, Row row) {
                if (item instanceof VideoItem) openPlayer((VideoItem) item);
            }
        });
        setOnSearchClickedListener(v ->
            startActivity(new Intent(requireActivity(), SearchActivity.class))
        );
    }

    /** Create empty rows immediately so headers appear before data loads */
    private void buildSkeletonRows() {
        mRowsAdapter.clear();
        for (int i = 0; i < TAB_NAMES.length; i++) {
            ArrayObjectAdapter row = new ArrayObjectAdapter(new VideoCardPresenter());
            mRowsAdapter.add(new ListRow(new HeaderItem(i, TAB_NAMES[i]), row));
        }
    }

    // ── Data loading (all on IO thread) ───────────────────────────────────

    private void loadAllRows() {
        // Home — personalised
        Set<String> interests = mPrefs.getInterests();
        String interest = interests.isEmpty() ? "Trending" : interests.iterator().next();
        fetchRow(0, UserPreferences.interestToQuery(interest));

        // Trending — kiosk with search fallback built into service
        mDisposables.add(
            YouTubeExtractorService.getInstance().getTrending()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    vids -> fillRow(1, vids),
                    err  -> Log.e(TAG, "Trending: " + err.getMessage())
                )
        );

        // Category tabs 2-7
        for (int i = 2; i < TAB_QUERIES.length; i++) {
            fetchRow(i, TAB_QUERIES[i]);
        }
    }

    private void fetchRow(int idx, String query) {
        mDisposables.add(
            YouTubeExtractorService.getInstance().getByCategory(query)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    vids -> fillRow(idx, vids),
                    err  -> Log.e(TAG, TAB_NAMES[idx] + ": " + err.getMessage())
                )
        );
    }

    /** FIX 2 + 3: null and isAdded guards before touching adapter */
    private void fillRow(int idx, List<VideoItem> videos) {
        if (!isAdded() || mRowsAdapter == null) return;
        if (idx < 0 || idx >= mRowsAdapter.size()) return;
        Object obj = mRowsAdapter.get(idx);
        if (!(obj instanceof ListRow)) return;
        ArrayObjectAdapter rowAdapter = (ArrayObjectAdapter) ((ListRow) obj).getAdapter();
        rowAdapter.clear();
        rowAdapter.addAll(0, videos);
    }

    private void openPlayer(VideoItem video) {
        mPrefs.recordWatched(video.getVideoId());
        Intent i = new Intent(requireActivity(), PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_VIDEO, video);
        startActivity(i);
    }
}
