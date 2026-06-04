package com.example.myapp.extractor;

import android.util.Log;

import com.example.myapp.model.VideoItem;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * All YouTube extraction via NewPipe Extractor v0.22.7.
 *
 * NOTE: v0.22.7 uses getThumbnailUrl() (String) on StreamInfoItem.
 *       The Image / getThumbnails() API was added in v0.24+ and must NOT be used here.
 */
public class YouTubeExtractorService {

    private static final String TAG = "YTExtractor";
    private static volatile YouTubeExtractorService sInstance;
    private final StreamingService mYT;

    private YouTubeExtractorService() {
        mYT = ServiceList.YouTube;
    }

    public static YouTubeExtractorService getInstance() {
        if (sInstance == null) {
            synchronized (YouTubeExtractorService.class) {
                if (sInstance == null) sInstance = new YouTubeExtractorService();
            }
        }
        return sInstance;
    }

    // ── Search ────────────────────────────────────────────────────────────

    public Single<List<VideoItem>> search(String query) {
        return Single.fromCallable(() -> runSearch(query)).subscribeOn(Schedulers.io());
    }

    private List<VideoItem> runSearch(String q) throws Exception {
        SearchExtractor ex = mYT.getSearchExtractor(q);
        ex.fetchPage();
        return toItems(ex.getInitialPage().getItems());
    }

    // ── Trending ──────────────────────────────────────────────────────────

    public Single<List<VideoItem>> getTrending() {
        return Single.fromCallable(this::runTrending)
                     .subscribeOn(Schedulers.io())
                     .onErrorResumeNext(e -> {
                         Log.w(TAG, "Kiosk failed: " + safe(e) + " — fallback to search");
                         return search("trending videos today");
                     });
    }

    private List<VideoItem> runTrending() throws Exception {
        KioskExtractor kiosk = mYT.getKioskList().getDefaultKioskExtractor();
        kiosk.fetchPage();
        return toItems(kiosk.getInitialPage().getItems());
    }

    // ── Category ──────────────────────────────────────────────────────────

    public Single<List<VideoItem>> getByCategory(String query) {
        return search(query);
    }

    // ── Stream URL ────────────────────────────────────────────────────────

    public Single<String> getStreamUrl(String videoId) {
        return Single.fromCallable(() -> resolveStream(videoId)).subscribeOn(Schedulers.io());
    }

    private String resolveStream(String videoId) throws Exception {
        String watchUrl = "https://www.youtube.com/watch?v=" + videoId;
        StreamInfo info;
        try {
            info = StreamInfo.getInfo(mYT, watchUrl);
        } catch (Exception e) {
            throw new Exception("Extraction failed: " + safe(e), e);
        }

        // Pass 1: progressive (video+audio), best <= 720p
        List<VideoStream> progressive = info.getVideoStreams();
        if (progressive != null) {
            String best = null; int bestRes = 0;
            for (VideoStream vs : progressive) {
                String u = vs.getUrl(); if (u == null || u.isEmpty()) continue;
                int r = parseRes(vs.getResolution());
                if (r > bestRes && r <= 720) { bestRes = r; best = u; }
            }
            if (best != null) return best;
            for (VideoStream vs : progressive) {
                String u = vs.getUrl(); if (u != null && !u.isEmpty()) return u;
            }
        }

        // Pass 2: HLS
        String hls = info.getHlsUrl();
        if (hls != null && !hls.isEmpty()) return hls;

        // Pass 3: video-only adaptive
        List<VideoStream> adaptive = info.getVideoOnlyStreams();
        if (adaptive != null) {
            for (VideoStream vs : adaptive) {
                String u = vs.getUrl(); if (u != null && !u.isEmpty()) return u;
            }
        }

        throw new Exception("No playable stream for " + videoId);
    }

    // ── Item parsing ──────────────────────────────────────────────────────

    private List<VideoItem> toItems(List<InfoItem> items) {
        List<VideoItem> out = new ArrayList<>();
        if (items == null) return out;

        for (InfoItem item : items) {
            if (!(item instanceof StreamInfoItem)) continue;
            StreamInfoItem si = (StreamInfoItem) item;

            String videoId = videoId(si.getUrl());
            if (videoId == null || videoId.isEmpty()) continue;

            // v0.22.7 API: getThumbnailUrl() returns a String directly
            String thumb = "";
            try {
                String t = si.getThumbnailUrl();
                if (t != null) thumb = t;
            } catch (Exception ignored) {}

            out.add(new VideoItem(
                videoId,
                si.getName()              != null ? si.getName()              : "Unknown",
                si.getUploaderName()      != null ? si.getUploaderName()      : "",
                thumb,
                fmtDuration(si.getDuration()),
                si.getViewCount(),
                si.getTextualUploadDate() != null ? si.getTextualUploadDate() : ""
            ));
        }
        return out;
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    private static String videoId(String url) {
        if (url == null) return null;
        int i = url.indexOf("v=");
        if (i >= 0) {
            String id = url.substring(i + 2);
            int a = id.indexOf('&');
            return a < 0 ? id : id.substring(0, a);
        }
        if (url.contains("youtu.be/")) {
            String[] p = url.split("youtu.be/");
            if (p.length > 1) {
                String id = p[1];
                int q = id.indexOf('?');
                return q < 0 ? id : id.substring(0, q);
            }
        }
        return null;
    }

    private static int parseRes(String r) {
        if (r == null) return 0;
        try { return Integer.parseInt(r.replaceAll("[^0-9]", "")); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String fmtDuration(long sec) {
        if (sec <= 0) return "";
        long h = sec/3600, m = (sec%3600)/60, s = sec%60;
        return h > 0 ? String.format("%d:%02d:%02d",h,m,s) : String.format("%d:%02d",m,s);
    }

    private static String safe(Throwable e) {
        return e != null && e.getMessage() != null ? e.getMessage()
             : e != null ? e.getClass().getSimpleName() : "null";
    }
}
