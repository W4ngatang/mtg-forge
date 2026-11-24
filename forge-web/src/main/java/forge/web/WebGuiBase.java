package forge.web;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.jupnp.UpnpServiceConfiguration;

import forge.gamemodes.match.HostedMatch;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;

/**
 * Forge's platform hooks for a server with no screen. There is no UI thread: code that would run
 * on one runs on the calling thread, or on a background thread when asked to run later. Players'
 * screens are {@link WebGuiGame}s, one per browser seat, so nothing here shows anything.
 */
public class WebGuiBase implements IGuiBase {
    /** Where Forge's res folder lives; tests and development point this at forge-gui. */
    public static final String ASSETS_PROPERTY = "forge.web.assets";

    private final String assetsDir;
    private final ExecutorService later = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "forge-web-later");
        t.setDaemon(true);
        return t;
    });

    public WebGuiBase() {
        String dir = System.getProperty(ASSETS_PROPERTY, "");
        this.assetsDir = dir.isEmpty() || dir.endsWith(File.separator) ? dir : dir + File.separator;
    }

    @Override public boolean isRunningOnDesktop() { return true; }
    @Override public boolean isLibgdxPort() { return false; }
    @Override public String getCurrentVersion() { return forge.util.BuildInfo.getVersionString(); }
    @Override public String getUserAgent() { return "Forge/" + forge.util.BuildInfo.getVersionString() + " (web server)"; }
    @Override public void invokeInEdtNow(Runnable runnable) { runnable.run(); }
    @Override public void invokeInEdtLater(Runnable runnable) { later.execute(runnable); }
    @Override public void invokeInEdtAndWait(Runnable proc) { proc.run(); }
    @Override public void runBackgroundTask(String message, Runnable task) { later.execute(task); }
    // No thread is a UI thread. Forge's input handling refuses to wait for a player on a UI thread.
    @Override public boolean isGuiThread() { return false; }
    @Override public String getAssetsDir() { return assetsDir; }
    @Override public ImageFetcher getImageFetcher() { return null; }
    @Override public ISkinImage getSkinIcon(FSkinProp skinProp) { return null; }
    @Override public ISkinImage getUnskinnedIcon(String path) { return null; }
    @Override public ISkinImage getCardArt(PaperCard card, boolean backFace) { return null; }
    @Override public ISkinImage createLayeredImage(PaperCard card, FSkinProp background, String overlayFilename, float opacity) { return null; }
    @Override public void clearImageCache() { }
    @Override public String encodeSymbols(String str, boolean formatReminderText) { return str; }
    @Override public int getAvatarCount() { return 0; }
    @Override public int getSleevesCount() { return 0; }
    @Override public float getScreenScale() { return 1.0f; }
    @Override public void preventSystemSleep(boolean preventSleep) { }
    @Override public void download(GuiDownloadService service, Consumer<Boolean> callback) { callback.accept(false); }
    @Override public void copyToClipboard(String text) { }
    @Override public void browseToUrl(String url) { }
    @Override public void showCardList(String title, String message, List<PaperCard> list) { }
    @Override public boolean showBoxedProduct(String title, String message, List<PaperCard> list) { return false; }
    @Override public void showBugReportDialog(String title, String text, boolean showExitAppBtn) {
        System.err.println("[forge-web] " + title + "\n" + text);
    }
    @Override public void showImageDialog(ISkinImage image, String message, String title) { }
    // Dialogs outside a game have nobody to ask; game dialogs go to the player's WebGuiGame.
    @Override public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) {
        System.err.println("[forge-web] unanswered dialog: " + title + ": " + message);
        return defaultOption;
    }
    @Override public String showInputDialog(String message, String title, FSkinProp icon, String initialInput, List<String> inputOptions, boolean isNumeric) { return initialInput; }
    @Override public String showFileDialog(String title, String defaultDir) { return null; }
    @Override public File getSaveFile(File defaultFile) { return null; }
    @Override public <T> List<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax, List<T> sourceChoices, List<T> destChoices) { return destChoices; }
    @Override public <T> List<T> getChoices(String message, int min, int max, Collection<T> choices, Collection<T> selected, FSerializableFunction<T, String> display) { return new ArrayList<>(selected); }
    @Override public PaperCard chooseCard(String title, String message, List<PaperCard> list) { return list.isEmpty() ? null : list.get(0); }
    @Override public boolean isSupportedAudioFormat(File file) { return false; }
    @Override public IAudioClip createAudioClip(String filename) { return null; }
    @Override public IAudioMusic createAudioMusic(String filename) { return null; }
    @Override public void startAltSoundSystem(String filename, boolean isSynchronized) { }
    @Override public void showSpellShop() { }
    @Override public void showBazaar() { }
    @Override public IGuiGame getNewGuiGame() { return null; }
    @Override public HostedMatch hostMatch() { return new HostedMatch(); }
    @Override public UpnpServiceConfiguration getUpnpPlatformService() { return null; }
    @Override public boolean hasNetGame() { return false; }
}
