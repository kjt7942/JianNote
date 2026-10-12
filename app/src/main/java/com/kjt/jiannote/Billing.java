package com.kjt.jiannote;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Google Play 결제 (일회성 인앱 상품 "jiannote_premium").
 * 플레이 콘솔 → 수익 창출 → 인앱 상품에서 같은 ID로 상품을 만들어야 한다.
 */
public class Billing implements PurchasesUpdatedListener {
    public static final String PRODUCT_ID = "jiannote_premium";

    public interface Listener {
        void onPremiumChanged(boolean premium);
    }

    private static Billing instance;

    public static synchronized Billing get(Context c) {
        if (instance == null) instance = new Billing(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final BillingClient client;
    private final SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Listener> listeners = new ArrayList<>();
    private ProductDetails details;
    private boolean connecting;
    private Runnable afterConnect;

    private Billing(Context c) {
        app = c;
        sp = c.getSharedPreferences("premium", Context.MODE_PRIVATE);
        client = BillingClient.newBuilder(c)
                .setListener(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .build();
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** 플레이에서 구매가 확인된 상태(캐시) */
    public boolean owned() {
        return sp.getBoolean("play", false);
    }

    /** 앱 시작 시 호출: 연결 → 구매 내역/가격 갱신 */
    public void connect() {
        if (client.isReady()) {
            refresh();
            return;
        }
        if (connecting) return;
        connecting = true;
        try {
            client.startConnection(new BillingClientStateListener() {
                @Override
                public void onBillingSetupFinished(BillingResult r) {
                    connecting = false;
                    if (r.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        refresh();
                        Runnable a = afterConnect;
                        afterConnect = null;
                        if (a != null) main.post(a);
                    }
                }

                @Override
                public void onBillingServiceDisconnected() {
                    connecting = false;
                }
            });
        } catch (Exception e) {
            connecting = false;
        }
    }

    /** 구매 내역 복원 + 가격 정보 조회 */
    public void refresh() {
        if (!client.isReady()) {
            connect();
            return;
        }
        client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
                (r, purchases) -> {
                    if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) return;
                    boolean has = false;
                    for (Purchase p : purchases) {
                        if (p.getProducts().contains(PRODUCT_ID) && p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                            has = true;
                            acknowledge(p);
                        }
                    }
                    setOwned(has);
                });
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()))
                .build();
        client.queryProductDetailsAsync(params, (r, result) -> {
            if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) return;
            List<ProductDetails> list = result.getProductDetailsList();
            if (list != null && !list.isEmpty()) details = list.get(0);
        });
    }

    /** 스토어 가격 (예: ₩5,900). 아직 모르면 null */
    public String price() {
        if (details == null) return null;
        ProductDetails.OneTimePurchaseOfferDetails o = details.getOneTimePurchaseOfferDetails();
        return o == null ? null : o.getFormattedPrice();
    }

    public void buy(Activity a) {
        if (!client.isReady() || details == null) {
            afterConnect = () -> {
                if (details != null) buy(a);
            };
            connect();
            refresh();
            Toast.makeText(a, "구글 플레이에 연결하는 중입니다. 잠시 후 다시 눌러주세요.", Toast.LENGTH_LONG).show();
            return;
        }
        BillingFlowParams.ProductDetailsParams pdp = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .build();
        BillingFlowParams flow = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(pdp))
                .build();
        BillingResult r = client.launchBillingFlow(a, flow);
        if (r.getResponseCode() == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) refresh();
    }

    @Override
    public void onPurchasesUpdated(BillingResult r, List<Purchase> purchases) {
        int code = r.getResponseCode();
        if (code == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase p : purchases) {
                if (!p.getProducts().contains(PRODUCT_ID)) continue;
                if (p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                    acknowledge(p);
                    setOwned(true);
                    main.post(() -> Toast.makeText(app, "👑 프리미엄이 활성화되었습니다. 감사합니다!", Toast.LENGTH_LONG).show());
                } else if (p.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                    main.post(() -> Toast.makeText(app, "결제 대기 중입니다. 결제가 완료되면 자동으로 활성화됩니다.", Toast.LENGTH_LONG).show());
                }
            }
        } else if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            refresh();
        }
    }

    private void acknowledge(Purchase p) {
        if (p.isAcknowledged()) return;
        client.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.getPurchaseToken()).build(),
                r -> { });
    }

    private void setOwned(boolean b) {
        boolean old = owned();
        sp.edit().putBoolean("play", b).apply();
        if (old != b) {
            main.post(() -> {
                for (Listener l : new ArrayList<>(listeners)) l.onPremiumChanged(b);
            });
        }
    }
}
