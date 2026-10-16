package com.kjt.jiannote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.kjt.jiannote.model.NotebookMeta;
import com.kjt.jiannote.render.Fonts;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

/** 서재 (노트북 목록) */
public class LibraryActivity extends Activity {
    private static final int REQ_PDF = 21, REQ_BACKUP = 22, REQ_IMAGES = 23, REQ_COVER = 24;
    private static final String CAT_ALL = "__all__", CAT_FAV = "__fav__", CAT_NONE = "__none__";
    public static final String PRIVACY_URL = "https://github.com/kjt7942/JianNote/blob/main/PRIVACY.md";

    private Store store;
    private Premium premium;
    private Prefs prefs;
    private final ArrayList<NotebookMeta> all = new ArrayList<>();
    private final ArrayList<NotebookMeta> shown = new ArrayList<>();
    private final HashMap<String, Bitmap> thumbs = new HashMap<>();
    private Adapter adapter;
    private LinearLayout banner;
    private TextView bannerText;
    private TextView badge;
    private LinearLayout empty;
    private TextView emptyText;
    private TextView countLabel;
    private String query = "";
    private LinearLayout catRow;
    private String category = CAT_ALL;
    private NotebookMeta coverTarget;
    /** 새 노트 창에서 '사진/PDF로 시작'을 고른 경우의 설정 */
    private NotebookMeta pendingNew;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Fonts.init(getAssets());
        store = new Store(this);
        premium = new Premium(this);
        prefs = new Prefs(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);

        // ---- 헤더
        LinearLayout header = Ui.row(this);
        int p = Ui.dp(this, 24);
        header.setPadding(p, Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 8));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher_fg);
        logo.setBackground(Ui.round(Ui.ACCENT, Ui.dp(this, 12)));
        header.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        TextView title = Ui.label(this, "JianNote", 26, Ui.TEXT, true);
        title.setPadding(Ui.dp(this, 12), 0, 0, 0);
        header.addView(title);
        badge = Ui.label(this, "", 11, 0xFFFFFFFF, true);
        badge.setPadding(Ui.dp(this, 9), Ui.dp(this, 3), Ui.dp(this, 9), Ui.dp(this, 3));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(Ui.dp(this, 10), 0, 0, 0);
        header.addView(badge, blp);
        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        EditText search = new EditText(this);
        search.setHint("노트 검색");
        search.setSingleLine(true);
        search.setTextSize(15);
        android.graphics.drawable.Drawable sd = getDrawable(R.drawable.ic_search).mutate();
        sd.setBounds(0, 0, Ui.dp(this, 20), Ui.dp(this, 20));
        sd.setTint(Ui.SUBTEXT);
        search.setCompoundDrawables(sd, null, null, null);
        search.setCompoundDrawablePadding(Ui.dp(this, 8));
        search.setBackground(Ui.round(0xFFFFFFFF, Ui.dp(this, 22)));
        search.setPadding(Ui.dp(this, 14), Ui.dp(this, 9), Ui.dp(this, 16), Ui.dp(this, 9));
        header.addView(search, new LinearLayout.LayoutParams(Ui.dp(this, 240), ViewGroup.LayoutParams.WRAP_CONTENT));
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

        TextView sort = Ui.iconButton(this, R.drawable.ic_sort, null);
        sort.setOnClickListener(this::sortMenu);
        header.addView(sort);
        TextView imp = Ui.iconButton(this, R.drawable.ic_file_open, null);
        imp.setOnClickListener(this::importMenu);
        header.addView(imp);
        TextView settings = Ui.iconButton(this, R.drawable.ic_settings, null);
        settings.setOnClickListener(v -> showSettings());
        header.addView(settings);
        TextView add = Ui.pill(this, "＋ 새 노트", true);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        header.addView(add, alp);
        add.setOnClickListener(v -> createNotebook());
        root.addView(header);

        // ---- 프리미엄 배너
        banner = Ui.row(this);
        banner.setBackground(Ui.round(0xFFFFF3D6, Ui.dp(this, 14)));
        banner.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        ImageView crown = new ImageView(this);
        crown.setImageResource(R.drawable.ic_workspace_premium);
        crown.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.GOLD));
        banner.addView(crown, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        bannerText = Ui.label(this, "", 13, Ui.TEXT, false);
        bannerText.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        banner.addView(bannerText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        banner.addView(Ui.label(this, "업그레이드 ›", 13, 0xFFB26A00, true));
        LinearLayout.LayoutParams bnlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bnlp.setMargins(p, Ui.dp(this, 4), p, Ui.dp(this, 4));
        root.addView(banner, bnlp);

        // ---- 분류(카테고리) 탭
        android.widget.HorizontalScrollView chs = new android.widget.HorizontalScrollView(this);
        chs.setHorizontalScrollBarEnabled(false);
        catRow = Ui.row(this);
        catRow.setPadding(p, Ui.dp(this, 6), p, Ui.dp(this, 2));
        chs.addView(catRow);
        root.addView(chs);
        category = prefs.s("cat", CAT_ALL);

        countLabel = Ui.label(this, "", 13, Ui.SUBTEXT, true);
        countLabel.setPadding(p, Ui.dp(this, 8), p, 0);
        root.addView(countLabel);

        // ---- 목록
        FrameLayout body = new FrameLayout(this);
        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(this, 168));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(Ui.dp(this, 20));
        grid.setHorizontalSpacing(Ui.dp(this, 16));
        grid.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 32));
        grid.setClipToPadding(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, pos, id) -> open(shown.get(pos)));
        grid.setOnItemLongClickListener((parent, view, pos, id) -> {
            options(view, shown.get(pos));
            return true;
        });
        body.addView(grid);

        empty = Ui.col(this);
        empty.setGravity(Gravity.CENTER);
        ImageView ei = new ImageView(this);
        ei.setImageResource(R.drawable.ic_auto_stories);
        ei.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFB8B3A6));
        empty.addView(ei, new LinearLayout.LayoutParams(Ui.dp(this, 80), Ui.dp(this, 80)));
        emptyText = Ui.label(this, "", 16, Ui.SUBTEXT, false);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 16));
        empty.addView(emptyText);
        LinearLayout ebtn = Ui.row(this);
        TextView e1 = Ui.pill(this, "＋ 새 노트 만들기", true);
        e1.setOnClickListener(v -> createNotebook());
        TextView e2 = Ui.pill(this, "PDF 가져오기", false);
        e2.setOnClickListener(v -> pickPdf());
        ebtn.addView(e1);
        LinearLayout.LayoutParams e2lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        e2lp.setMargins(Ui.dp(this, 10), 0, 0, 0);
        ebtn.addView(e2, e2lp);
        empty.addView(ebtn);
        body.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        Ui.edgeToEdge(this, root);

        if (!prefs.b("onboarded", false)) root.post(this::onboarding);

        // 업데이트 완료 안내 + 새 버전 자동 확인
        long ver = Updater.currentVersion(this);
        long seen = Long.parseLong(prefs.s("seenVersion", "0"));
        if (seen != 0 && ver > seen) {
            String vn = "";
            try {
                vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception ignored) {
            }
            Toast.makeText(this, "JianNote " + vn + " 로 업데이트되었습니다", Toast.LENGTH_LONG).show();
        }
        prefs.put("seenVersion", String.valueOf(ver));
        root.postDelayed(() -> Updater.autoCheck(this), 1500);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    private void refresh() {
        all.clear();
        all.addAll(store.list());
        int sort = prefs.i("sort", 0);
        Collections.sort(all, (a, b) -> {
            if (a.favorite != b.favorite) return a.favorite ? -1 : 1;
            switch (sort) {
                case 1: return a.title.compareToIgnoreCase(b.title);
                case 2: return Long.compare(b.created, a.created);
                default: return Long.compare(b.modified, a.modified);
            }
        });
        thumbs.clear();
        badge.setVisibility(View.GONE);
        banner.setVisibility(View.GONE);
        buildCategoryRow();
        filter();
    }

    // ================= 분류 =================

    private ArrayList<String> categories() {
        ArrayList<String> out = new ArrayList<>();
        for (String c : prefs.s("categories", "").split("\n")) if (!c.trim().isEmpty() && !out.contains(c)) out.add(c);
        for (NotebookMeta m : all) if (!m.category.isEmpty() && !out.contains(m.category)) out.add(m.category);
        return out;
    }

    private void saveCategories(ArrayList<String> cats) {
        prefs.put("categories", android.text.TextUtils.join("\n", cats));
    }

    private boolean inCategory(NotebookMeta m) {
        switch (category) {
            case CAT_ALL: return true;
            case CAT_FAV: return m.favorite;
            case CAT_NONE: return m.category.isEmpty();
            default: return category.equals(m.category);
        }
    }

    private String categoryLabel() {
        switch (category) {
            case CAT_ALL: return "모든 노트";
            case CAT_FAV: return "즐겨찾기";
            case CAT_NONE: return "미분류";
            default: return category;
        }
    }

    private void buildCategoryRow() {
        catRow.removeAllViews();
        ArrayList<String> cats = categories();
        if (!category.equals(CAT_ALL) && !category.equals(CAT_FAV) && !category.equals(CAT_NONE) && !cats.contains(category)) {
            category = CAT_ALL;
        }
        addChip("전체", CAT_ALL, false);
        addChip("★ 즐겨찾기", CAT_FAV, false);
        for (String c : cats) addChip(c, c, true);
        if (!cats.isEmpty()) addChip("미분류", CAT_NONE, false);
        TextView add = Ui.pill(this, "＋ 분류", false);
        add.setTextSize(13);
        add.setPadding(Ui.dp(this, 14), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7));
        add.setOnClickListener(v -> Dialogs.input(this, "새 분류 이름", "", false, name -> {
            name = name.trim();
            if (name.isEmpty()) return;
            ArrayList<String> list = categories();
            if (!list.contains(name)) list.add(name);
            saveCategories(list);
            setCategory(name);
        }));
        catRow.addView(add);
    }

    private void addChip(String label, String key, boolean editable) {
        TextView t = Ui.pill(this, label, key.equals(category));
        t.setTextSize(13);
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, Ui.dp(this, 8), 0);
        t.setOnClickListener(v -> setCategory(key));
        if (editable) {
            t.setOnLongClickListener(v -> {
                categoryMenu(v, key);
                return true;
            });
        }
        catRow.addView(t, lp);
    }

    private void setCategory(String key) {
        category = key;
        prefs.put("cat", key);
        buildCategoryRow();
        filter();
    }

    private void categoryMenu(View anchor, String cat) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "분류 이름 바꾸기");
        pm.getMenu().add(0, 2, 1, "분류 삭제 (노트는 미분류로)");
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                Dialogs.input(this, "분류 이름", cat, false, name -> {
                    name = name.trim();
                    if (name.isEmpty() || name.equals(cat)) return;
                    ArrayList<String> list = categories();
                    int i = list.indexOf(cat);
                    if (list.contains(name)) list.remove(cat);
                    else if (i >= 0) list.set(i, name);
                    saveCategories(list);
                    for (NotebookMeta m : all) if (cat.equals(m.category)) {
                        m.category = name;
                        try {
                            store.saveMeta(m);
                        } catch (Exception ignored) {
                        }
                    }
                    if (cat.equals(category)) category = name;
                    prefs.put("cat", category);
                    refresh();
                });
            } else {
                new AlertDialog.Builder(this).setTitle("분류 삭제")
                        .setMessage("'" + cat + "' 분류를 삭제할까요?\n노트는 삭제되지 않고 미분류로 옮겨집니다.")
                        .setNegativeButton("취소", null)
                        .setPositiveButton("삭제", (d, w) -> {
                            ArrayList<String> list = categories();
                            list.remove(cat);
                            saveCategories(list);
                            for (NotebookMeta m : all) if (cat.equals(m.category)) {
                                m.category = "";
                                try {
                                    store.saveMeta(m);
                                } catch (Exception ignored) {
                                }
                            }
                            if (cat.equals(category)) category = CAT_ALL;
                            prefs.put("cat", category);
                            refresh();
                        }).show();
            }
            return true;
        });
        pm.show();
    }

    private void chooseCategory(NotebookMeta m) {
        ArrayList<String> cats = categories();
        String[] items = new String[cats.size() + 2];
        items[0] = (m.category.isEmpty() ? "✓  " : "     ") + "미분류";
        for (int i = 0; i < cats.size(); i++) items[i + 1] = (cats.get(i).equals(m.category) ? "✓  " : "     ") + cats.get(i);
        items[items.length - 1] = "＋ 새 분류 만들기";
        new AlertDialog.Builder(this).setTitle("'" + m.title + "' 분류")
                .setItems(items, (d, w) -> {
                    if (w == items.length - 1) {
                        Dialogs.input(this, "새 분류 이름", "", false, name -> {
                            name = name.trim();
                            if (name.isEmpty()) return;
                            ArrayList<String> list = categories();
                            if (!list.contains(name)) list.add(name);
                            saveCategories(list);
                            m.category = name;
                            save(m);
                        });
                    } else {
                        m.category = w == 0 ? "" : cats.get(w - 1);
                        save(m);
                    }
                }).show();
    }

    private void filter() {
        shown.clear();
        for (NotebookMeta m : all) {
            if (!inCategory(m)) continue;
            if (query.isEmpty() || m.title.toLowerCase(Locale.ROOT).contains(query)) shown.add(m);
        }
        String[] sortNames = {"최근 수정순", "이름순", "만든 날짜순"};
        countLabel.setText((query.isEmpty() ? categoryLabel() + " " : "검색 결과 ") + shown.size() + "  ·  " + sortNames[prefs.i("sort", 0)]);
        if (all.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            emptyText.setText("아직 노트가 없습니다\nS펜으로 첫 노트를 시작해 보세요");
            empty.getChildAt(2).setVisibility(View.VISIBLE);
        } else if (shown.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            emptyText.setText(query.isEmpty() ? "'" + categoryLabel() + "'에 노트가 없습니다" : "'" + query + "' 검색 결과가 없습니다");
            empty.getChildAt(2).setVisibility(View.GONE);
        } else {
            empty.setVisibility(View.GONE);
        }
        adapter.notifyDataSetChanged();
    }

    private boolean checkLimit() {
        if (all.size() >= premium.notebookLimit()) {
            Dialogs.limit(this, premium, "노트를 더 만들 수 없습니다.", this::refresh);
            return false;
        }
        return true;
    }

    private void createNotebook() {
        if (!checkLimit()) return;
        Dialogs.newNotebook(this, premium, m -> {
            if (!category.equals(CAT_ALL) && !category.equals(CAT_FAV) && !category.equals(CAT_NONE)) m.category = category;
            if (category.equals(CAT_FAV)) m.favorite = true;
            if (m.startFrom != 0) {
                // 사진·PDF 를 고른 뒤 그 페이지들로 노트 만들기
                pendingNew = m;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                if (m.startFrom == 1) {
                    i.setType("image/*");
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                } else {
                    i.setType("application/pdf");
                }
                startActivityForResult(i, m.startFrom == 1 ? REQ_IMAGES : REQ_PDF);
                return;
            }
            try {
                open(store.create(m));
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

    private void sortMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        String[] names = {"최근 수정순", "이름순", "만든 날짜순"};
        for (int i = 0; i < names.length; i++) {
            pm.getMenu().add(0, i, i, (prefs.i("sort", 0) == i ? "✓  " : "     ") + names[i]);
        }
        pm.setOnMenuItemClickListener(item -> {
            prefs.put("sort", item.getItemId());
            refresh();
            return true;
        });
        pm.show();
    }

    private void importMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "PDF 가져오기 (필기용)");
        pm.getMenu().add(0, 4, 1, "이미지 가져오기 (페이지로)");
        pm.getMenu().add(0, 2, 2, "백업 파일(.jnote) 가져오기");
        pm.getMenu().add(0, 3, 3, "모든 노트 백업하기");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: pickPdf(); break;
                case 4: pickImages(); break;
                case 2: pickBackup(); break;
                default: backupAll(); break;
            }
            return true;
        });
        pm.show();
    }

    // ================= 가져오기 / 백업 =================

    private void pickPdf() {
        if (!checkLimit()) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        startActivityForResult(i, REQ_PDF);
    }

    /** 새 노트 창 설정이 있으면 그것을 (제목이 비었으면 파일 이름으로) */
    private NotebookMeta takePending(String fallbackTitle) {
        NotebookMeta m = pendingNew;
        pendingNew = null;
        if (m != null && (m.title == null || m.title.isEmpty() || m.title.equals("새 노트"))) m.title = fallbackTitle;
        return m;
    }

    private void pickImages() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_IMAGES);
    }

    private void pickBackup() {
        if (!checkLimit()) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_BACKUP);
    }

    private void backupAll() {
        if (all.isEmpty()) {
            Toast.makeText(this, "백업할 노트가 없습니다", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog pd = progress("모든 노트를 백업하는 중…");
        ImportExport.exportBackup(this, store, new ArrayList<>(all), "JianNote_전체백업", new ImportExport.Done<Uri>() {
            @Override
            public void ok(Uri u) {
                pd.dismiss();
                backupDone(u);
            }

            @Override
            public void fail(String m) {
                pd.dismiss();
                Toast.makeText(LibraryActivity.this, "백업 실패: " + m, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void backupDone(Uri u) {
        new AlertDialog.Builder(this).setTitle("백업 완료")
                .setMessage("다운로드/JianNote 폴더에 .jnote 파일로 저장했습니다.\n기기를 바꿀 때 이 파일을 옮긴 뒤 '백업 파일 가져오기'로 복원하세요.")
                .setNegativeButton("확인", null)
                .setPositiveButton("공유", (d, w) -> Exporter.share(this, u, "application/octet-stream"))
                .show();
    }

    private AlertDialog progress(String msg) {
        LinearLayout box = Ui.row(this);
        int p = Ui.dp(this, 24);
        box.setPadding(p, p, p, p);
        box.addView(new ProgressBar(this));
        TextView t = Ui.label(this, msg, 15, Ui.TEXT, false);
        t.setPadding(Ui.dp(this, 16), 0, 0, 0);
        t.setTag("msg");
        box.addView(t);
        AlertDialog d = new AlertDialog.Builder(this).setView(box).setCancelable(false).create();
        d.show();
        return d;
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                int dot = n.lastIndexOf('.');
                return dot > 0 ? n.substring(0, dot) : n;
            }
        } catch (Exception ignored) {
        }
        return "가져온 PDF";
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) {
            pendingNew = null;
            return;
        }
        if (req == REQ_IMAGES) {
            ArrayList<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (uris.isEmpty()) return;
            AlertDialog pd = progress("이미지를 페이지로 가져오는 중…");
            TextView msg = pd.getWindow().getDecorView().findViewWithTag("msg");
            String title = uris.size() == 1 ? displayName(uris.get(0)) : "이미지 노트 (" + uris.size() + "장)";
            NotebookMeta base = takePending(title);
            ImportExport.importImages(this, store, uris, title, base,
                    (d, n) -> {
                        if (msg != null) msg.setText("이미지를 가져오는 중… " + d + " / " + n);
                    },
                    new ImportExport.Done<NotebookMeta>() {
                        @Override
                        public void ok(NotebookMeta m) {
                            pd.dismiss();
                            if (!category.startsWith("__")) {
                                m.category = category;
                                save(m);
                            }
                            refresh();
                            open(m);
                        }

                        @Override
                        public void fail(String m) {
                            pd.dismiss();
                            refresh();
                            Toast.makeText(LibraryActivity.this, "이미지 가져오기 실패: " + m, Toast.LENGTH_LONG).show();
                        }
                    });
            return;
        }
        if (data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_COVER && coverTarget != null) {
            try {
                ImportExport.saveCover(this, uri, store.dir(coverTarget.id));
                coverTarget.coverImage = true;
                save(coverTarget);
            } catch (Exception e) {
                Toast.makeText(this, "표지 이미지 설정 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            coverTarget = null;
            return;
        }
        if (req == REQ_PDF) {
            AlertDialog pd = progress("PDF를 가져오는 중…");
            TextView msg = pd.getWindow().getDecorView().findViewWithTag("msg");
            NotebookMeta base = takePending(displayName(uri));
            ImportExport.importPdf(this, store, uri, displayName(uri), base,
                    (d, n) -> {
                        if (msg != null) msg.setText("PDF를 가져오는 중… " + d + " / " + n + "페이지");
                    },
                    new ImportExport.Done<NotebookMeta>() {
                        @Override
                        public void ok(NotebookMeta m) {
                            pd.dismiss();
                            if (!category.startsWith("__")) {
                                m.category = category;
                                save(m);
                            }
                            refresh();
                            open(m);
                        }

                        @Override
                        public void fail(String m) {
                            pd.dismiss();
                            refresh();
                            Toast.makeText(LibraryActivity.this, "PDF 가져오기 실패: " + m, Toast.LENGTH_LONG).show();
                        }
                    });
        } else if (req == REQ_BACKUP) {
            int room = Integer.MAX_VALUE;
            AlertDialog pd = progress("백업을 복원하는 중…");
            ImportExport.importBackup(this, store, uri, room, new ImportExport.Done<Integer>() {
                @Override
                public void ok(Integer n) {
                    pd.dismiss();
                    refresh();
                    Toast.makeText(LibraryActivity.this, n + "개의 노트를 복원했습니다", Toast.LENGTH_LONG).show();
                }

                @Override
                public void fail(String m) {
                    pd.dismiss();
                    Toast.makeText(LibraryActivity.this, "복원 실패: " + m, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    // ================= 노트 메뉴 =================

    private void options(View anchor, NotebookMeta m) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 0, 0, "열기");
        pm.getMenu().add(0, 1, 1, "이름 바꾸기");
        pm.getMenu().add(0, 2, 2, m.favorite ? "즐겨찾기 해제" : "★ 즐겨찾기");
        pm.getMenu().add(0, 3, 3, "표지 색 바꾸기");
        pm.getMenu().add(0, 7, 3, "표지 이미지 설정");
        if (m.coverImage) pm.getMenu().add(0, 8, 3, "표지 이미지 제거");
        pm.getMenu().add(0, 9, 3, "분류 지정" + (m.category.isEmpty() ? "" : " (" + m.category + ")"));
        pm.getMenu().add(0, 4, 4, "복제");
        pm.getMenu().add(0, 5, 5, "백업 파일로 내보내기");
        pm.getMenu().add(0, 6, 6, "삭제");
        pm.setOnMenuItemClickListener(item -> {
            try {
                switch (item.getItemId()) {
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
                    case 7: {
                        coverTarget = m;
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("image/*");
                        startActivityForResult(i, REQ_COVER);
                        break;
                    }
                    case 8:
                        new File(store.dir(m.id), "cover.jpg").delete();
                        m.coverImage = false;
                        save(m);
                        break;
                    case 9: chooseCategory(m); break;
                    case 4:
                        if (checkLimit()) {
                            store.duplicate(m);
                            refresh();
                        }
                        break;
                    case 5: {
                        AlertDialog pd = progress("내보내는 중…");
                        ImportExport.exportBackup(this, store, Collections.singletonList(m), m.title, new ImportExport.Done<Uri>() {
                            @Override
                            public void ok(Uri u) {
                                pd.dismiss();
                                backupDone(u);
                            }

                            @Override
                            public void fail(String msg) {
                                pd.dismiss();
                                Toast.makeText(LibraryActivity.this, "내보내기 실패: " + msg, Toast.LENGTH_LONG).show();
                            }
                        });
                        break;
                    }
                    case 6:
                        new AlertDialog.Builder(this)
                                .setTitle("노트 삭제")
                                .setMessage("'" + m.title + "' 노트를 삭제할까요?\n삭제한 노트는 되돌릴 수 없습니다.")
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
            return true;
        });
        pm.show();
    }

    private void pickCover(NotebookMeta m) {
        LinearLayout row = Ui.row(this);
        int p = Ui.dp(this, 16);
        row.setPadding(p, p, p, p);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.addView(row);
        AlertDialog d = new AlertDialog.Builder(this).setTitle("표지 색").setView(hs).setNegativeButton("닫기", null)
                .setPositiveButton("직접 색 만들기", (dd, w) -> ColorPicker.show(this, m.coverColor, false, c -> {
                    m.coverColor = c | 0xFF000000;
                    save(m);
                })).create();
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

    // ================= 설정 / 안내 =================

    private void onboarding() {
        LinearLayout box = Ui.col(this);
        int p = Ui.dp(this, 24);
        box.setPadding(p, p, p, Ui.dp(this, 8));
        TextView t = Ui.label(this, "JianNote에 오신 것을 환영합니다", 20, Ui.TEXT, true);
        box.addView(t);
        Object[][] tips = {
                {R.drawable.ic_ink_pen, "S펜으로 쓰기", "누르는 힘에 따라 굵기가 달라집니다. 측면 버튼을 누른 채 쓰면 지우개가 됩니다."},
                {R.drawable.ic_touch_app, "손가락으로 넘기기", "한 손가락으로 스크롤, 두 손가락으로 확대·축소합니다. 펜을 대고 있을 때 손바닥은 무시됩니다."},
                {R.drawable.ic_shapes, "도형 자동 보정", "선을 긋고 펜을 잠깐 멈추면 직선·사각형·원·삼각형으로 반듯하게 바뀝니다."},
                {R.drawable.ic_layers, "레이어", "그림과 글씨를 레이어로 나눠 숨기고, 순서를 바꾸고, 투명도를 조절하세요."},
                {R.drawable.ic_picture_as_pdf, "PDF에 필기", "서재의 가져오기 버튼으로 PDF·사진을 불러와 바로 필기할 수 있습니다. 페이지 메뉴에서 사진·PDF로 속지도 만들 수 있어요."},
                {R.drawable.ic_rectangle, "암기 테이프", "외울 곳을 테이프로 가리고, 터치하면 비쳐 보이고 다시 터치하면 가려집니다."},
                {R.drawable.ic_sticky_note_2, "메모지 · 스티커", "메모지를 붙여 그 위에 쓰고, 스티커 버튼으로 꾸미세요. 필기도 스티커로 저장할 수 있어요."}
        };
        for (Object[] tip : tips) {
            LinearLayout r = Ui.row(this);
            r.setPadding(0, Ui.dp(this, 14), 0, 0);
            ImageView iv = new ImageView(this);
            iv.setImageResource((Integer) tip[0]);
            iv.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
            iv.setBackground(Ui.round(0xFFE3E7FF, Ui.dp(this, 12)));
            int ip = Ui.dp(this, 8);
            iv.setPadding(ip, ip, ip, ip);
            r.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
            LinearLayout col = Ui.col(this);
            col.setPadding(Ui.dp(this, 14), 0, 0, 0);
            col.addView(Ui.label(this, (String) tip[1], 15, Ui.TEXT, true));
            col.addView(Ui.label(this, (String) tip[2], 13, Ui.SUBTEXT, false));
            r.addView(col);
            box.addView(r);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setView(sv)
                .setCancelable(false)
                .setPositiveButton("시작하기", (d, w) -> prefs.put("onboarded", true))
                .show();
    }

    private void showSettings() {
        boolean finger = prefs.b("fingerDraw", false);
        String[] items = {
                "☝️  손가락으로 그리기: " + (finger ? "켜짐" : "꺼짐 (S펜 전용)"),
                "🔄  업데이트 확인",
                "⚙️  자동 업데이트: " + (Updater.autoEnabled(this) ? "켜짐" : "꺼짐"),
                "💾  모든 노트 백업하기",
                "📥  백업 파일 가져오기",
                "📖  사용 방법",
                "🔒  개인정보처리방침",
                "📜  오픈소스 라이선스",
                "ℹ️  앱 정보"
        };
        new AlertDialog.Builder(this)
                .setTitle("설정")
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0:
                            prefs.put("fingerDraw", !finger);
                            Toast.makeText(this, !finger ? "손가락 필기를 켰습니다" : "S펜으로만 필기합니다 (손가락은 스크롤/확대)", Toast.LENGTH_SHORT).show();
                            break;
                        case 1: Updater.check(this, true); break;
                        case 2: {
                            boolean on = !Updater.autoEnabled(this);
                            Updater.setAuto(this, on);
                            Toast.makeText(this, on ? "새 버전이 나오면 자동으로 받아 설치합니다" : "자동 업데이트를 껐습니다", Toast.LENGTH_SHORT).show();
                            break;
                        }
                        case 3: backupAll(); break;
                        case 4: pickBackup(); break;
                        case 5: onboarding(); break;
                        case 6:
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)));
                            } catch (Exception e) {
                                Toast.makeText(this, PRIVACY_URL, Toast.LENGTH_LONG).show();
                            }
                            break;
                        case 7: licenses(); break;
                        default: about(); break;
                    }
                })
                .show();
    }

    private void licenses() {
        new AlertDialog.Builder(this).setTitle("오픈소스 라이선스")
                .setMessage("• 나눔고딕, 나눔명조, 나눔손글씨 펜 — © NAVER Corp., SIL Open Font License 1.1\n"
                        + "• 개구쟁이(Gaegu), 도현, 주아, 푸어스토리, 싱글데이 — SIL Open Font License 1.1\n"
                        + "• Material Symbols 아이콘 — © Google, Apache License 2.0\n"
                        + "\n"
                        + "SIL OFL 1.1: 글꼴은 자유롭게 사용·재배포할 수 있으며 단독 판매는 할 수 없습니다.\nhttps://openfontlicense.org")
                .setPositiveButton("확인", null).show();
    }

    private void about() {
        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        LinearLayout box = Ui.col(this);
        int p = Ui.dp(this, 24);
        box.setPadding(p, p, p, 0);
        box.addView(Ui.label(this, "JianNote", 22, Ui.TEXT, true));
        TextView v = Ui.label(this, "버전 " + ver, 13, Ui.SUBTEXT, false);
        v.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 12));
        box.addView(v);
        box.addView(Ui.label(this, "S펜 필압 · 레이어 · 무한 캔버스 · PDF 필기를 지원하는 손글씨 노트\n\n"
                + "모든 노트는 이 기기 안에만 저장되며 외부로 전송되지 않습니다. 기기를 바꾸기 전에 '모든 노트 백업하기'를 이용하세요.", 14, Ui.TEXT, false));
        new AlertDialog.Builder(this).setView(box).setPositiveButton("확인", null).show();
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
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setGravity(Gravity.CENTER_HORIZONTAL);
                name.setPadding(0, Ui.dp(LibraryActivity.this, 4), 0, 0);
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
            Bitmap cimg = null;
            if (m.coverImage) {
                cimg = thumbs.get(m.id + "#cover");
                if (cimg == null) {
                    File cf = new File(store.dir(m.id), "cover.jpg");
                    if (cf.exists()) {
                        cimg = BitmapFactory.decodeFile(cf.getPath());
                        if (cimg != null) thumbs.put(m.id + "#cover", cimg);
                    }
                }
            }
            cover.bind(m, th, cimg);
            name.setText(m.title);
            String date = new SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(new Date(m.modified));
            sub.setText((m.infinite ? "무한 캔버스" : com.kjt.jiannote.model.PageSize.label(m.sizeWmm, m.sizeHmm) + " · " + m.pageCount + "페이지") + " · " + date);
            return box;
        }
    }
}
