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
 * 손글씨 → 텍스트 (Google ML Kit Digital Ink).
 * 언어: 한국어(ko) / 영어(en) / 숫자·수학 기호(영어 모델 + 수식 보정).
 * 언어마다 처음 한 번 모델(약 20MB)을 인터넷으로 내려받고, 이후에는 기기 안에서 인식한다.
 * ML Kit 에는 수식 전용 모델이 없어 분수·지수·적분 같은 2차원 수식은 한 줄 글자로만 인식된다.
 * 여러 줄은 획의 세로 위치로 줄을 나눈 뒤 줄마다 인식한다.
 */
public class HandwritingRecognizer {
    public interface Cb {
        void ok(String text, float lineHeight);

        void fail(String msg);

        void progress(String msg);
    }

    public static final String KO = "ko", EN = "en", MATH = "math";
    public static final String[] LANGS = {KO, EN, MATH};
    public static final String[] LANG_NAMES = {"한국어", "English", "숫자·기호"};

    private static final java.util.HashMap<String, DigitalInkRecognizer> recognizers = new java.util.HashMap<>();
    private static final java.util.HashMap<String, DigitalInkRecognitionModel> models = new java.util.HashMap<>();

    private static String tag(String lang) {
        return MATH.equals(lang) ? EN : lang;
    }

    private static DigitalInkRecognitionModel model(String lang) throws MlKitException {
        String t = tag(lang);
        DigitalInkRecognitionModel m = models.get(t);
        if (m == null) {
            DigitalInkRecognitionModelIdentifier id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(t);
            if (id == null) throw new MlKitException("인식 모델을 찾을 수 없습니다", MlKitException.NOT_FOUND);
            m = DigitalInkRecognitionModel.builder(id).build();
            models.put(t, m);
        }
        return m;
    }

    public static void recognize(Activity a, List<StrokeElem> strokes, Cb cb) {
        recognize(a, strokes, KO, cb);
    }

    public static void recognize(Activity a, List<StrokeElem> strokes, String lang, Cb cb) {
        if (strokes.isEmpty()) {
            cb.fail("선택한 손글씨가 없습니다");
            return;
        }
        DigitalInkRecognitionModel m;
        try {
            m = model(lang);
        } catch (MlKitException e) {
            cb.fail(e.getMessage());
            return;
        }
        RemoteModelManager mgr = RemoteModelManager.getInstance();
        mgr.isModelDownloaded(m).addOnSuccessListener(done -> {
            if (done) {
                run(strokes, lang, cb);
            } else {
                String name = LANG_NAMES[java.util.Arrays.asList(LANGS).indexOf(lang)];
                cb.progress(name + " 손글씨 인식 모델을 내려받는 중… (처음 한 번만, 인터넷 필요)");
                mgr.download(m, new DownloadConditions.Builder().build())
                        .addOnSuccessListener(v -> run(strokes, lang, cb))
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

    private static void run(List<StrokeElem> strokes, String lang, Cb cb) {
        DigitalInkRecognizer recognizer = recognizers.get(tag(lang));
        if (recognizer == null) {
            try {
                recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model(lang)).build());
                recognizers.put(tag(lang), recognizer);
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
                        if (--remaining[0] == 0) finish(results, lineH, lang, cb);
                    })
                    .addOnFailureListener(e -> {
                        results[idx] = "";
                        if (--remaining[0] == 0) finish(results, lineH, lang, cb);
                    });
        }
    }

    /** 숫자·수식 보정: 숫자 옆의 비슷한 글자를 숫자·기호로 */
    static String mathFix(String s) {
        String t = s;
        for (int pass = 0; pass < 2; pass++) {
            t = t.replaceAll("(?<=[0-9)])\\s*[xX×]\\s*(?=[0-9(])", " × ")      // 3x4 → 3 × 4
                    .replaceAll("(?<=[0-9])[oO](?=[0-9.,]|$)|(?<=^|[^A-Za-z])[oO](?=[0-9])", "0") // 1o5 → 105
                    .replaceAll("(?<=[0-9])[lI|](?=[0-9])|(?<=^|[^A-Za-z])[lI|](?=[0-9])", "1")  // l2 → 12
                    .replaceAll("(?<=[0-9])[sS](?=[0-9])", "5")
                    .replaceAll("(?<=[0-9)])\\s*[÷:]\\s*(?=[0-9(])", " ÷ ")
                    .replaceAll("\\s*=\\s*", " = ")
                    .replaceAll("(?<=[0-9)])\\s*([+\\-])\\s*(?=[0-9(])", " $1 ");
        }
        return t.replaceAll(" {2,}", " ").trim();
    }

    private static void finish(String[] results, float lineH, String lang, Cb cb) {
        StringBuilder sb = new StringBuilder();
        for (String r : results) {
            if (r == null || r.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(MATH.equals(lang) ? mathFix(r) : r);
        }
        if (sb.length() == 0) cb.fail("글씨를 알아보지 못했습니다");
        else cb.ok(sb.toString(), lineH);
    }
}
