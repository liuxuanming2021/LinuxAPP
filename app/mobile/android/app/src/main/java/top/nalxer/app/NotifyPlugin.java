package top.nalxer.app;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "BackgroundNotify")
public class NotifyPlugin extends Plugin {

    @PluginMethod
    public void start(PluginCall call) {
        String server = call.getString("server", "");
        String token = call.getString("token", "");
        long since = call.getLong("since", 0L);
        if (server == null || server.isEmpty() || token == null || token.isEmpty()) {
            call.reject("missing server or token");
            return;
        }
        NotifyService.start(getContext(), server, token, since);
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        NotifyService.stop(getContext());
        call.resolve();
    }
}
