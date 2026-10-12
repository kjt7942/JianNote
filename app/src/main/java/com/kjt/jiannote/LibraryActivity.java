package com.kjt.jiannote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.kjt.jiannote.model.NotebookMeta;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

/** 서재 (노트북 목록) */
public class LibraryActivity extends Activity {
    private Store store;
    private Premium premium;
    private final ArrayList<NotebookMeta> all = new ArrayList<>();
    private final ArrayList<NotebookMeta> shown = new ArrayList<>();
    private final HashMap<String, Bitmap> thumbs = new HashMap<>();
    private Adapter adapter;
    private TextView banner;
    private TextView badge;
    private TextView empty;
    private String query = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        premium = new Premium(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);

        // 헤더
        LinearLayout header = Ui.row(this);
        int p = Ui.dp(this, 20);
        header.setPadding(p, Ui.dp(this, 14), p, Ui.dp(this, 8));
        TextView title = Ui.label(this, "JianNote", 26, Ui.TEXT, true);
        header.addView(title);
        badge = Ui.label(this, "", 12, 0xFFFFFFFF, true);
        badge.setPadding(Ui.dp(this, 8), Ui.dp(this, 3), Ui.dp(this, 8), Ui.dp(this, 3));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(Ui.dp(this, 10), 0, 0, 0);
        header.addView(badge, blp);
        badge.setOnClickListener(v -> Dialogs.premium(this, premium, this::refresh));

        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        EditText search = new EditText(this);
        search.setHint("🔍 노트 검색");
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setBackground(Ui.round(0xFFFFFFFF, Ui.dp(this, 20)));
        search.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        header.addView(search, new LinearLayout.LayoutParams(Ui.dp(this, 260), ViewGroup.LayoutParams.WRAP_CONTENT));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int bf, int c) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                filter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        TextView add = Ui.pill(this, "＋ 새 노트", true);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.setMargins(Ui.dp(this, 12), 0, 0, 0);
        header.addView(add, alp);
        add.setOnClickListener(v -> createNotebook());

        TextView settings = Ui.toolButton(this, "⚙", null);
        header.addView(settings);
        settings.setOnClickListener(v -> showSettings());
        root.addView(header);

        banner = Ui.label(this, "", 13, Ui.TEXT, false);
        banner.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));
        banner.setBackgroundColor(0xFFFFF3D6);
        banner.setOnClickListener(v -> Dialogs.premium(this, premium, this::refresh));
        root.addView(banner);

        FrameLayout body = new FrameLayout(this);
        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(this, 170));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(Ui.dp(this, 18));
        grid.setHorizontalSpacing(Ui.dp(this, 14));
        grid.setPadding(p, Ui.dp(this, 16), p, Ui.dp(this, 24));
        grid.setClipToPadding(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, pos, id) -> open(shown.get(pos)));
        grid.setOnItemLongClickListener((parent, view, pos, id) -> {
            options(shown.get(pos));
            return true;
        });
        body.addView(grid);

        empty = Ui.label(this, "아직 노트가 없습니다.\n오른쪽 위 ＋ 새 노트 를 눌러 시작하세요 ✍️", 16, Ui.SUBTEXT, false);
        empty.setGravity(Gravity.CENTER);
        body.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        all.clear();
        all.addAll(store.list());
        thumbs.clear();
        boolean isP = premium.isPremium();
        badge.setText(isP ? "👑 PREMIUM" : "FREE");
        badge.setBackground(Ui.round(isP ? Ui.GOLD : 0xFF9E9E9E, Ui.dp(this, 10)));
        if (isP) {
            banner.setVisibility(View.GONE);
        } else {
            banner.setVisibility(View.VISIBLE);
            banner.setText("무료 버전 · 노트 " + all.size() + " / " + Premium.FREE_NOTEBOOKS + "권 사용 중   —   👑 프리미엄으로 노트·레이어 무제한 (탭하여 업그레이드)");
        }
        filter();
    }

    private void filter() {
        shown.clear();
        for (NotebookMeta m : all) {
            if (query.isEmpty() || m.title.toLowerCase(Locale.ROOT).contains(query)) shown.add(m);
        }
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        if (!shown.isEmpty() || !query.isEmpty()) empty.setText(query.isEmpty() ? "" : "검색 결과가 없습니다");
        if (all.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            empty.setText("아직 노트가 없습니다.\n오른쪽 위 ＋ 새 노트 를 눌러 시작하세요 ✍️");
        }
        adapter.notifyDataSetChanged();
    }

    private void createNotebook() {
        if (all.size() >= premium.notebookLimit()) {
            Dialogs.limit(this, premium, "무료 버전에서는 노트를 " + Premium.FREE_NOTEBOOKS + "권까지 만들 수 있습니다.", this::refresh);
            return;
        }
        Dialogs.newNotebook(this, premium, m -> {
            try {
                NotebookMeta created = store.create(m);
                open(created);
            } catch (Exception e) {
                Toast.makeText(this, "생성 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void open(NotebookMeta m) {
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra("id", m.id);
        startActivity(i);
    }

    private void options(NotebookMeta m) {
        String[] items = {"열기", "이름 바꾸기", m.favorite ? "즐겨찾기 해제" : "즐겨찾기", "표지 색 바꾸기", "복제", "삭제"};
        new AlertDialog.Builder(this)
                .setTitle(m.title)
                .setItems(items, (d, which) -> {
                    try {
                        switch (which) {
                            case 0: open(m); break;
                            case 1:
                                Dialogs.input(this, "이름 바꾸기", m.title, false, s -> {
                                    if (s.trim().isEmpty()) return;
                                    m.title = s.trim();
                                    save(m);
                                });
                                break;
                            case 2:
                                m.favorite = !m.favorite;
                                save(m);
                                break;
                            case 3: pickCover(m); break;
                            case 4:
                                if (all.size() >= premium.notebookLimit()) {
                                    Dialogs.limit(this, premium, "무료 버전에서는 노트를 " + Premium.FREE_NOTEBOOKS + "권까지 만들 수 있습니다.", this::refresh);
                                } else {
                                    store.duplicate(m);
                                    refresh();
                                }
                                break;
                            case 5:
                                new AlertDialog.Builder(this)
                                        .setTitle("노트 삭제")
                                        .setMessage("'" + m.title + "' 노트를 삭제할까요? 되돌릴 수 없습니다.")
                                        .setNegativeButton("취소", null)
                                        .setPositiveButton("삭제", (dd, w) -> {
                                            store.delete(m.id);
                                            refresh();
                                        })
                                        .show();
                                break;
                            default: break;
                        }
                    } catch (Exception e) {
                        Toast.makeText(this, "오류: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void pickCover(NotebookMeta m) {
        LinearLayout row = Ui.row(this);
        int p = Ui.dp(this, 16);
        row.setPadding(p, p, p, p);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.addView(row);
        AlertDialog d = new AlertDialog.Builder(this).setTitle("표지 색").setView(hs).setNegativeButton("닫기", null).create();
        for (int c : Ui.COVER_COLORS) {
            Ui.Swatch s = new Ui.Swatch(this, c);
            s.setChosen(c == m.coverColor);
            row.addView(s, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
            s.setOnClickListener(v -> {
                m.coverColor = c;
                save(m);
                d.dismiss();
            });
        }
        d.show();
    }

    private void save(NotebookMeta m) {
        try {
            store.saveMeta(m);
        } catch (Exception ignored) {
        }
        refresh();
    }

    private void showSettings() {
        Prefs prefs = new Prefs(this);
        boolean finger = prefs.b("fingerDraw", false);
        String[] items = {
                "👑 프리미엄 / 해제 코드",
                "손가락으로 그리기: " + (finger ? "켜짐" : "꺼짐 (S펜 전용)"),
                "앱 정보"
        };
        new AlertDialog.Builder(this)
                .setTitle("설정")
                .setItems(items, (d, w) -> {
                    if (w == 0) Dialogs.premium(this, premium, this::refresh);
                    else if (w == 1) {
                        prefs.put("fingerDraw", !finger);
                        Toast.makeText(this, !finger ? "손가락 필기를 켰습니다" : "S펜으로만 필기합니다 (손가락은 이동/확대)", Toast.LENGTH_SHORT).show();
                    } else {
                        String ver = "";
                        try {
                            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                        } catch (Exception ignored) {
                        }
                        new AlertDialog.Builder(this)
                                .setTitle("JianNote Premium")
                                .setMessage("버전 " + ver + "\n\nS펜 필압 · 레이어 · 무한 캔버스를 지원하는 손글씨 노트 앱\n\n" +
                                        "• S펜: 필기 / 측면 버튼 누르고 쓰면 지우개\n" +
                                        "• 손가락: 한 손가락 이동, 두 손가락 확대/축소\n" +
                                        "• 페이지 화면에서 좌우로 빠르게 밀면 페이지 넘김\n" +
                                        "• 펜을 그은 끝에서 잠시 멈추면 도형으로 자동 보정")
                                .setPositiveButton("확인", null)
                                .show();
                    }
                })
                .show();
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int i) {
            return shown.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout box;
            CoverView cover;
            TextView name, sub;
            if (convert == null) {
                box = Ui.col(LibraryActivity.this);
                cover = new CoverView(LibraryActivity.this);
                box.addView(cover, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                name = Ui.label(LibraryActivity.this, "", 14, Ui.TEXT, true);
                name.setSingleLine(true);
                name.setGravity(Gravity.CENTER_HORIZONTAL);
                box.addView(name);
                sub = Ui.label(LibraryActivity.this, "", 11, Ui.SUBTEXT, false);
                sub.setGravity(Gravity.CENTER_HORIZONTAL);
                box.addView(sub);
                box.setTag(new Object[]{cover, name, sub});
            } else {
                box = (LinearLayout) convert;
                Object[] t = (Object[]) box.getTag();
                cover = (CoverView) t[0];
                name = (TextView) t[1];
                sub = (TextView) t[2];
            }
            NotebookMeta m = shown.get(pos);
            Bitmap th = thumbs.get(m.id);
            if (th == null) {
                File f = store.thumbFile(m.id);
                if (f.exists()) {
                    th = BitmapFactory.decodeFile(f.getPath());
                    if (th != null) thumbs.put(m.id, th);
                }
            }
            cover.bind(m, th);
            name.setText(m.title);
            String date = new SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(new Date(m.modified));
            sub.setText((m.infinite ? "무한 캔버스" : m.pageCount + "페이지") + " · " + date);
            return box;
        }
    }
}
