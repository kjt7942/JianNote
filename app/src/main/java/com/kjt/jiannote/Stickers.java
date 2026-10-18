package com.kjt.jiannote;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.kjt.jiannote.model.Document;
import com.kjt.jiannote.model.Element;
import com.kjt.jiannote.model.ImageElem;
import com.kjt.jiannote.model.MemoElem;
import com.kjt.jiannote.model.TapeElem;
import com.kjt.jiannote.render.RenderContext;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 스티커: 기본 세트(테이프·메모지·꾸미기), 내 이미지 스티커, 필기를 저장한 스티커.
 * 내 스티커는 files/stickers/ 에 저장 (img_*.png = 이미지, clip_*.bin + clip_*.png = 필기 묶음)
 */
public class Stickers {

    public interface InsertCb {
        void insert(List<Element> elems);
    }

    // ================= 저장소 =================

    static File dir(Activity a) {
        File d = new File(a.getFilesDir(), "stickers");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File imgDir(Activity a) {
        File d = new File(dir(a), "img");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 사진·그림을 내 스티커로 저장 (투명 배경 유지, 긴 변 900px) */
    public static void addImage(Activity a, Uri uri) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = a.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > 1800) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bm;
        try (InputStream in = a.getContentResolver().openInputStream(uri)) {
            bm = BitmapFactory.decodeStream(in, null, o2);
        }
        if (bm == null) throw new IOException("이미지를 읽을 수 없습니다");
        float k = Math.min(1f, 900f / Math.max(bm.getWidth(), bm.getHeight()));
        Bitmap sc = Bitmap.createScaledBitmap(bm, Math.max(1, Math.round(bm.getWidth() * k)), Math.max(1, Math.round(bm.getHeight() * k)), true);
        File f = new File(dir(a), "img_" + UUID.randomUUID().toString().substring(0, 10) + ".png");
        try (OutputStream os = new FileOutputStream(f)) {
            sc.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
    }

    /** 선택한 필기·텍스트·이미지를 내 스티커로 저장 */
    public static void addClip(Activity a, List<Element> elems, RenderContext rc) throws IOException {
        if (elems.isEmpty()) return;
        String id = UUID.randomUUID().toString().substring(0, 10);
        // 들어있는 이미지 파일도 함께 보관
        for (Element e : elems) {
            if (e instanceof ImageElem) {
                File src = new File(rc.imagesDir(), ((ImageElem) e).file);
                File dst = new File(imgDir(a), ((ImageElem) e).file);
                if (src.exists() && !dst.exists()) copy(src, dst);
            }
        }
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(dir(a), "clip_" + id + ".bin"))))) {
            Document.writeElems(o, elems);
        }
        Bitmap th = thumbnail(elems, rc, 240);
        try (OutputStream os = new FileOutputStream(new File(dir(a), "clip_" + id + ".png"))) {
            th.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
    }

    /** 요소 묶음 미리보기 */
    static Bitmap thumbnail(List<Element> elems, RenderContext rc, int size) {
        RectF b = null;
        for (Element e : elems) {
            if (b == null) b = new RectF(e.bounds());
            else b.union(e.bounds());
        }
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        if (b == null) return bmp;
        b.inset(-8, -8);
        float s = Math.min(size / b.width(), size / b.height());
        Canvas c = new Canvas(bmp);
        c.translate((size - b.width() * s) / 2f, (size - b.height() * s) / 2f);
        c.scale(s, s);
        c.translate(-b.left, -b.top);
        for (Element e : elems) e.draw(c, rc);
        return bmp;
    }

    private static void copy(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
    }

    // ================= 기본 세트 =================

    private static List<Element> builtinElems(int i) {
        ArrayList<Element> l = new ArrayList<>();
        int[] tapeColors = {0xFFFF8A65, 0xFF81D4FA, 0xFFA5D6A7, 0xFFF48FB1, 0xFFFFE082, 0xFFB0BEC5};
        int[] tapePat = {TapeElem.STRIPE, TapeElem.DOT, TapeElem.GRID, TapeElem.STRIPE, TapeElem.SOLID, TapeElem.SOLID};
        if (i < 6) {
            l.add(new TapeElem(0, 0, 420, 0, 52, tapeColors[i], tapePat[i]));
            return l;
        }
        int m = i - 6;
        if (m < 4) {
            int[] mc = {MemoElem.COLORS[0], MemoElem.COLORS[3], MemoElem.COLORS[2], MemoElem.COLORS[4]};
            int[] mp = {MemoElem.LINED, MemoElem.GRID, MemoElem.CHECK, MemoElem.BLANK};
            MemoElem me = new MemoElem(0, 0, 360, 320, mc[m], mp[m]);
            me.group = CanvasView.newGroup();
            l.add(me);
        }
        return l;
    }

    private static final int BUILTIN_ELEM_COUNT = 10;
    private static final String[] DECOS = {"★", "♥", "✔", "말풍선", "라벨", "→", "중요!", "TODO", "참 잘했어요", "D-DAY"};

    /** 꾸미기 스티커 그림 */
    static Bitmap drawDeco(int i, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float s = size;
        switch (i) {
            case 0: { // 별
                Path path = new Path();
                for (int k = 0; k < 10; k++) {
                    double ang = Math.PI / 2 + k * Math.PI / 5;
                    float r = k % 2 == 0 ? s * 0.46f : s * 0.2f;
                    float x = s / 2 + (float) Math.cos(ang) * r, y = s / 2 - (float) Math.sin(ang) * r;
                    if (k == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                path.close();
                p.setColor(0xFFFFC107);
                c.drawPath(path, p);
                break;
            }
            case 1: { // 하트
                Path path = new Path();
                path.moveTo(s / 2, s * 0.85f);
                path.cubicTo(s * 0.05f, s * 0.5f, s * 0.15f, s * 0.08f, s / 2, s * 0.3f);
                path.cubicTo(s * 0.85f, s * 0.08f, s * 0.95f, s * 0.5f, s / 2, s * 0.85f);
                p.setColor(0xFFEF5350);
                c.drawPath(path, p);
                break;
            }
            case 2: { // 체크
                p.setColor(0xFF43A047);
                c.drawCircle(s / 2, s / 2, s * 0.44f, p);
                p.setColor(Color.WHITE);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.1f);
                p.setStrokeCap(Paint.Cap.ROUND);
                Path path = new Path();
                path.moveTo(s * 0.28f, s * 0.52f);
                path.lineTo(s * 0.44f, s * 0.68f);
                path.lineTo(s * 0.74f, s * 0.36f);
                c.drawPath(path, p);
                break;
            }
            case 3: { // 말풍선
                p.setColor(Color.WHITE);
                RectF r = new RectF(s * 0.06f, s * 0.12f, s * 0.94f, s * 0.7f);
                c.drawRoundRect(r, s * 0.18f, s * 0.18f, p);
                Path tail = new Path();
                tail.moveTo(s * 0.3f, s * 0.66f);
                tail.lineTo(s * 0.22f, s * 0.9f);
                tail.lineTo(s * 0.48f, s * 0.68f);
                c.drawPath(tail, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.025f);
                p.setColor(0xFF424242);
                c.drawRoundRect(r, s * 0.18f, s * 0.18f, p);
                break;
            }
            case 4: { // 라벨(태그)
                Path path = new Path();
                path.moveTo(s * 0.05f, s * 0.3f);
                path.lineTo(s * 0.75f, s * 0.3f);
                path.lineTo(s * 0.95f, s * 0.5f);
                path.lineTo(s * 0.75f, s * 0.7f);
                path.lineTo(s * 0.05f, s * 0.7f);
                path.close();
                p.setColor(0xFF7E57C2);
                c.drawPath(path, p);
                p.setColor(Color.WHITE);
                c.drawCircle(s * 0.76f, s * 0.5f, s * 0.04f, p);
                break;
            }
            case 5: { // 화살표
                p.setColor(0xFF1E88E5);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.09f);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                c.drawLine(s * 0.1f, s / 2, s * 0.85f, s / 2, p);
                Path path = new Path();
                path.moveTo(s * 0.6f, s * 0.26f);
                path.lineTo(s * 0.88f, s / 2);
                path.lineTo(s * 0.6f, s * 0.74f);
                c.drawPath(path, p);
                break;
            }
            default: { // 글자 스탬프
                String t = DECOS[i];
                int[] bg = {0xFFE53935, 0xFF3949AB, 0xFFF4511E, 0xFF00897B};
                int col = bg[(i - 6) % bg.length];
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.035f);
                p.setColor(col);
                RectF r = new RectF(s * 0.06f, s * 0.28f, s * 0.94f, s * 0.72f);
                c.drawRoundRect(r, s * 0.08f, s * 0.08f, p);
                p.setStyle(Paint.Style.FILL);
                p.setTypeface(Typeface.DEFAULT_BOLD);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(s * 0.2f);
                while (p.measureText(t) > s * 0.8f) p.setTextSize(p.getTextSize() * 0.92f);
                c.drawText(t, s / 2, s / 2 + p.getTextSize() * 0.35f, p);
            }
        }
        return b;
    }

    /** 그림 스티커를 노트 이미지로 저장해 이미지 요소로 */
    private static ImageElem bitmapToElem(Bitmap b, RenderContext rc, float w) throws IOException {
        String name = "st_" + UUID.randomUUID().toString().substring(0, 10) + ".png";
        try (OutputStream os = new FileOutputStream(new File(rc.imagesDir(), name))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
        return new ImageElem(name, 0, 0, w, w * b.getHeight() / Math.max(1f, b.getWidth()));
    }

    // ================= 창 =================

    public static void show(Activity a, RenderContext rc, InsertCb cb, Runnable pickImage) {
        LinearLayout root = Ui.col(a);
        int pad = Ui.dp(a, 16);
        root.setPadding(pad, Ui.dp(a, 8), pad, 0);
        AlertDialog[] dlg = new AlertDialog[1];

        root.addView(header(a, "기본 스티커 · 테이프"));
        GridLayout g1 = grid(a);
        for (int i = 0; i < 6; i++) {
            final int k = i;
            List<Element> el = builtinElems(i);
            addCell(a, g1, thumbnail(el, rc, 200), v -> {
                cb.insert(builtinElems(k));
                dlg[0].dismiss();
            }, null);
        }
        root.addView(g1);
        root.addView(header(a, "기본 스티커 · 메모지"));
        GridLayout g2 = grid(a);
        for (int i = 6; i < BUILTIN_ELEM_COUNT; i++) {
            final int k = i;
            addCell(a, g2, thumbnail(builtinElems(i), rc, 200), v -> {
                cb.insert(builtinElems(k));
                dlg[0].dismiss();
            }, null);
        }
        root.addView(g2);
        root.addView(header(a, "기본 스티커 · 꾸미기"));
        GridLayout g3 = grid(a);
        for (int i = 0; i < DECOS.length; i++) {
            final int k = i;
            addCell(a, g3, drawDeco(i, 200), v -> {
                try {
                    ArrayList<Element> l = new ArrayList<>();
                    l.add(bitmapToElem(drawDeco(k, 300), rc, 160));
                    cb.insert(l);
                } catch (IOException e) {
                    Toast.makeText(a, "스티커를 넣지 못했습니다", Toast.LENGTH_SHORT).show();
                }
                dlg[0].dismiss();
            }, null);
        }
        root.addView(g3);

        root.addView(header(a, "내 스티커  (길게 누르면 삭제)"));
        GridLayout g4 = grid(a);
        File[] files = dir(a).listFiles();
        if (files != null) Arrays.sort(files, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
        int mine = 0;
        if (files != null) for (File f : files) {
            String n = f.getName();
            if (n.startsWith("img_") && n.endsWith(".png")) {
                mine++;
                Bitmap th = decodeThumb(f, 200);
                addCell(a, g4, th, v -> {
                    try {
                        Bitmap full = BitmapFactory.decodeFile(f.getPath());
                        ArrayList<Element> l = new ArrayList<>();
                        l.add(bitmapToElem(full, rc, Math.min(400, full.getWidth())));
                        cb.insert(l);
                    } catch (Exception e) {
                        Toast.makeText(a, "스티커를 넣지 못했습니다", Toast.LENGTH_SHORT).show();
                    }
                    dlg[0].dismiss();
                }, () -> confirmDelete(a, dlg[0], f, null));
            } else if (n.startsWith("clip_") && n.endsWith(".bin")) {
                mine++;
                File thf = new File(f.getPath().replace(".bin", ".png"));
                Bitmap th = thf.exists() ? BitmapFactory.decodeFile(thf.getPath()) : null;
                addCell(a, g4, th, v -> {
                    try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
                        ArrayList<Element> l = Document.readElems(in);
                        for (Element e : l) {
                            if (e instanceof ImageElem) {
                                File src = new File(imgDir(a), ((ImageElem) e).file);
                                File dst = new File(rc.imagesDir(), ((ImageElem) e).file);
                                if (src.exists() && !dst.exists()) copy(src, dst);
                            }
                        }
                        cb.insert(l);
                    } catch (Exception e) {
                        Toast.makeText(a, "스티커를 넣지 못했습니다", Toast.LENGTH_SHORT).show();
                    }
                    dlg[0].dismiss();
                }, () -> confirmDelete(a, dlg[0], f, thf));
            }
        }
        root.addView(g4);
        if (mine == 0) {
            root.addView(Ui.label(a, "아직 내 스티커가 없어요.\n• 아래 버튼으로 사진·그림을 스티커로 만들거나\n• 올가미로 필기를 선택한 뒤 위쪽 막대의 '스티커로'를 누르세요.", 12, Ui.SUBTEXT, false));
        }
        TextView add = Ui.pill(a, "＋ 내 이미지로 스티커 만들기", false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(a, 10), 0, Ui.dp(a, 6));
        root.addView(add, lp);
        add.setOnClickListener(v -> {
            dlg[0].dismiss();
            pickImage.run();
        });

        ScrollView sv = new ScrollView(a);
        sv.addView(root);
        dlg[0] = new AlertDialog.Builder(a).setTitle("스티커").setView(sv).setNegativeButton("닫기", null).show();
    }

    private static void confirmDelete(Activity a, AlertDialog parent, File f, File thumb) {
        new AlertDialog.Builder(a).setTitle("이 스티커를 삭제할까요?")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (d, w) -> {
                    f.delete();
                    if (thumb != null) thumb.delete();
                    parent.dismiss();
                    Toast.makeText(a, "삭제했습니다", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private static Bitmap decodeThumb(File f, int size) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > size * 2) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), o2);
    }

    private static TextView header(Activity a, String s) {
        TextView t = Ui.label(a, s, 13, Ui.SUBTEXT, true);
        t.setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 4));
        return t;
    }

    private static GridLayout grid(Activity a) {
        GridLayout g = new GridLayout(a);
        g.setColumnCount(Ui.fitCols(a, 84, 96, 5));
        return g;
    }

    private static void addCell(Activity a, GridLayout g, Bitmap thumb, View.OnClickListener click, Runnable longPress) {
        ImageView iv = new ImageView(a);
        iv.setImageBitmap(thumb);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int p = Ui.dp(a, 6);
        iv.setPadding(p, p, p, p);
        iv.setBackground(Ui.ripple(0xFFF7F5F0, Ui.dp(a, 12)));
        iv.setOnClickListener(click);
        if (longPress != null) iv.setOnLongClickListener(v -> {
            longPress.run();
            return true;
        });
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = Ui.dp(a, 76);
        lp.height = Ui.dp(a, 76);
        lp.setMargins(Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
        g.addView(iv, lp);
    }
}
