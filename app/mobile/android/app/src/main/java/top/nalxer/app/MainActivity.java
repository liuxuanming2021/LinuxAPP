package top.nalxer.app;

import android.net.http.SslError;
import android.os.Bundle;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NotifyPlugin.class);
        super.onCreate(savedInstanceState);

        // 自签名证书直接放行：这样服务器用 HTTPS 也不用手机装证书。
        // 只在 App 内生效，系统浏览器仍然按正常规则校验。
        try {
            Bridge bridge = getBridge();
            if (bridge != null && bridge.getWebView() != null) {
                bridge.getWebView().setWebViewClient(new BridgeWebViewClient(bridge) {
                    @Override
                    public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                        handler.proceed();
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }
}
