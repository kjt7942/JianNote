package com.kjt.jiannote;

import android.app.Activity;
import android.graphics.RectF;

import com.google.mlkit.common.MlKitException;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions;
import com.google.mlkit.vision.digitalink.recognition.Ink;

import com.kjt.jiannote.model.StrokeElem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 손글씨 → 텍스트 (Google ML Kit Digital Ink, 한국어 모델).
 * 처음 한 번 모델(약 20MB)을 인터넷으로 내려받고, 이후에는 기기 안에서 인식한다.
 * 여러 줄은 획의 세로 위치로 줄을 나눈 뒤 줄마다 인식한다.
 */
public class HandwritingRecognizer {
    public interface Cb {
        void ok(String text, float lineHeight);

        void fail(String msg);

        void progress(String msg);
    }

    private static DigitalInkRecognizer recognizer;
    private static DigitalInkRecognitionModel model;

    private static DigitalInkRecognitionModel model() throws MlKitException {
        if (model == null) {
            DigitalInkRecognitionModelIdentifier id = DigitalInkRecognitionModelIdentifier.fromLanguageTag("ko");
            if (id == null) throw new MlKitException("한국어 모델을 찾을 수 없습니다", MlKitException.NOT_FOUND);
            model = DigitalInkRecognitionModel.builder(id).build();
        }
        return model;
    }

    public static void recognize(Activity a, List<StrokeElem> strokes, Cb cb) {
        if (strokes.isEmpty()) {
            cb.fail("선택한 손글씨가 없습니다");
            return;
        }
        DigitalInkRecognitionModel m;
        try {
            m = model();
        } catch (MlKitException e) {
            cb.fail(e.getMessage());
            return;
        }
        RemoteModelManager mgr = RemoteModelManager.getInstance();
        mgr.isModelDownloaded(m).addOnSuccessListener(done -> {
            if (done) {
                run(strokes, cb);
            } else {
                cb.progress("한국어 손글씨 인식 모델을 내려받는 중… (처음 한 번만, 인터넷 필요)");
                mgr.download(m, new DownloadConditions.Builder().build())
                        .addOnSuccessListener(v -> run(strokes, cb))
                        .addOnFailureListener(e -> cb.fail("모델을 내려받지 못했습니다. 인터넷 연결을 확인하세요."));
            }
        }).addOnFailureListener(e -> cb.fail(String.valueOf(e.getMessage())));
    }

    /** 줄 나누기: 세로로 겹치는 획끼리 한 줄 */
    private static List<List<StrokeElem>> lines(List<StrokeElem> strokes) {
        ArrayList<StrokeElem> sorted = new ArrayList<>(strokes);
        Collections.sort(sorted, (x, y) -> Float.compare(x.bounds().centerY(), y.bounds().centerY()));
        List<List<StrokeElem>> out = new ArrayList<>();
        List<StrokeElem> cur = null;
        RectF band = null;
        for (StrokeElem s : sorted) {
            RectF b = s.bounds();
            if (cur == null || b.centerY() > band.bottom) {
                cur = new ArrayList<>();
                out.add(cur);
                band = new RectF(b);
            } else {
                band.union(b);
            }
            cur.add(s);
        }
        for (List<StrokeElem> l : out) Collections.sort(l, (x, y) -> Float.compare(x.bounds().left, y.bounds().left));
        return out;
    }

    private static void run(List<StrokeElem> strokes, Cb cb) {
        if (recognizer == null) {
            try {
                recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model()).build());
            } catch (MlKitException e) {
                cb.fail(e.getMessage());
                return;
            }
        }
        List<List<StrokeElem>> ls = lines(strokes);
        String[] results = new String[ls.size()];
        int[] remaining = {ls.size()};
        float totalH = 0;
        for (List<StrokeElem> l : ls) {
            RectF b = null;
            for (StrokeElem s : l) {
                if (b == null) b = new RectF(s.bounds());
                else b.union(s.bounds());
            }
            totalH += b == null ? 0 : b.height();
        }
        final float lineH = ls.isEmpty() ? 40 : totalH / ls.size();
        for (int i = 0; i < ls.size(); i++) {
            final int idx = i;
            Ink.Builder ib = Ink.builder();
            long t = 0;
            for (StrokeElem s : ls.get(i)) {
                Ink.Stroke.Builder sb = Ink.Stroke.builder();
                for (int k = 0; k < s.n; k++) {
                    sb.addPoint(Ink.Point.create(s.pts[3 * k], s.pts[3 * k + 1], t));
                    t += 8;
                }
                ib.addStroke(sb.build());
                t += 120;
            }
            recognizer.recognize(ib.build())
                    .addOnSuccessListener(r -> {
                        results[idx] = r.getCandidates().isEmpty() ? "" : r.getCandidates().get(0).getText();
                        if (--remaining[0] == 0) finish(results, lineH, cb);
                    })
                    .addOnFailureListener(e -> {
                        results[idx] = "";
                        if (--remaining[0] == 0) finish(results, lineH, cb);
                    });
        }
    }

    private static void finish(String[] results, float lineH, Cb cb) {
        StringBuilder sb = new StringBuilder();
        for (String r : results) {
            if (r == null || r.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(r);
        }
        if (sb.length() == 0) cb.fail("글씨를 알아보지 못했습니다");
        else cb.ok(sb.toString(), lineH);
    }
}
