package dev.lightning;

/** Kept apart so Config Panel's classes load only when the mod is installed. */
final class PanelHook {
    private PanelHook() {}

    static void register(net.neoforged.fml.ModContainer container) {
        dev.configpanel.ConfigPanel.register(container);
    }
}
