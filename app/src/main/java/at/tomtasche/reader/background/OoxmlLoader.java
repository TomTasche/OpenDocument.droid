package at.tomtasche.reader.background;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

import at.tomtasche.reader.nonfree.AnalyticsManager;
import at.tomtasche.reader.nonfree.CrashManager;

public class OoxmlLoader extends FileLoader {

    private CoreWrapper lastCore;

    public OoxmlLoader(Context context) {
        super(context, LoaderType.OOXML);
    }

    @Override
    public boolean isSupported(Options options) {
        if (true) {
            // TODO: not stable enough at the moment
            return false;
        }

        return options.fileType.startsWith("application/vnd.openxmlformats-officedocument.wordprocessingml.document") || options.fileType.startsWith("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") || options.fileType.startsWith("application/vnd.openxmlformats-officedocument.spreadsheetml.presentation");
    }

    @Override
    public void loadSync(Options options) {
        final Result result = new Result();
        result.options = options;
        result.loaderType = type;

        try {
            File cachedFile = AndroidFileCache.getCacheFile(context);

            if (lastCore != null) {
                lastCore.close();
            }

            CoreWrapper core = new CoreWrapper();
            try {
                core.initialize();

                lastCore = core;
            } catch (Throwable e) {
                crashManager.log(e);
            }

            File cacheDirectory = AndroidFileCache.getCacheDirectory(context);

            File fakeHtmlFile = new File(cacheDirectory, "ooxml");

            CoreWrapper.CoreOptions coreOptions = new CoreWrapper.CoreOptions();
            coreOptions.inputPath = cachedFile.getPath();
            coreOptions.outputPath = fakeHtmlFile.getPath();
            coreOptions.password = options.password;
            coreOptions.ooxml = true;

            CoreWrapper.CoreResult coreResult = lastCore.parse(coreOptions);
            if (coreResult.errorCode == 0) {
                for (int i = 0; i < coreResult.pageNames.size(); i++) {
                    File entryFile = new File(fakeHtmlFile.getPath() + i + ".html");

                    result.partTitles.add(coreResult.pageNames.get(i));
                    result.partUris.add(Uri.fromFile(entryFile));
                }

                callOnSuccess(result);
            } else {
                if (coreResult.errorCode == -2) {
                    throw new EncryptedDocumentException();
                } else {
                    throw new RuntimeException("failed with code " + coreResult.errorCode);
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();

            callOnError(result, e);
        }
    }

    @Override
    public void close() {
        super.close();

        if (lastCore != null) {
            lastCore.close();
            lastCore = null;
        }
    }
}
