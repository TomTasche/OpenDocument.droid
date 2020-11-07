package at.tomtasche.reader.ui.activity;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.kobakei.ratethisapp.RateThisApp;
import com.suddenh4x.ratingdialog.AppRating;
import com.suddenh4x.ratingdialog.buttons.ConfirmButtonClickListener;
import com.suddenh4x.ratingdialog.buttons.RateDialogClickListener;
import com.suddenh4x.ratingdialog.preferences.MailSettings;
import com.suddenh4x.ratingdialog.preferences.RatingThreshold;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentTransaction;
import at.tomtasche.reader.BuildConfig;
import at.tomtasche.reader.R;
import at.tomtasche.reader.background.AndroidFileCache;
import at.tomtasche.reader.background.KitKatPrinter;
import at.tomtasche.reader.nonfree.AdManager;
import at.tomtasche.reader.nonfree.AnalyticsManager;
import at.tomtasche.reader.nonfree.BillingManager;
import at.tomtasche.reader.nonfree.CrashManager;
import at.tomtasche.reader.nonfree.HelpManager;
import at.tomtasche.reader.ui.EditActionModeCallback;
import at.tomtasche.reader.ui.FindActionModeCallback;
import at.tomtasche.reader.ui.SnackbarHelper;
import at.tomtasche.reader.ui.TtsActionModeCallback;
import at.tomtasche.reader.ui.widget.RecentDocumentDialogFragment;

public class MainActivity extends AppCompatActivity {

    // taken from: https://stackoverflow.com/a/36829889/198996
    private static boolean isTesting() {
        try {
            Class.forName("at.tomtasche.reader.test.PdfActivityTest");
            return true;
        } catch (ClassNotFoundException e) {
        }

        try {
            Class.forName("at.tomtasche.reader.test.OdfActivityTest");
            return true;
        } catch (ClassNotFoundException e) {
        }

        return false;
    }

    protected static boolean IS_GOOGLE_ECOSYSTEM = true;

    private static final boolean IS_TESTING = isTesting();
    private static final boolean USE_PROPRIETARY_LIBRARIES = true;
    private static final int GOOGLE_REQUEST_CODE = 1993;
    private static final String DOCUMENT_FRAGMENT_TAG = "document_fragment";
    private static int PERMISSION_CODE = 1353;
    private static int CREATE_CODE = 4213;

    private boolean didTriggerPermissionDialogAgain = false;

    private Handler handler;

    private View landingContainer;
    private View documentContainer;
    private LinearLayout adContainer;
    private DocumentFragment documentFragment;

    private boolean fullscreen;
    private TtsActionModeCallback ttsActionMode;
    private EditActionModeCallback editActionMode;

    private CrashManager crashManager;
    private AnalyticsManager analyticsManager;
    private AdManager adManager;
    private BillingManager billingManager;
    private HelpManager helpManager;

    private Runnable onPermissionRunnable;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.main);

        setTitle("");

        handler = new Handler();

        adContainer = findViewById(R.id.ad_container);
        landingContainer = findViewById(R.id.landing_container);
        documentContainer = findViewById(R.id.document_container);

        findViewById(R.id.landing_intro_open).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                findDocument();
            }
        });

        initializeProprietaryLibraries();

        if (!IS_TESTING) {
            showIntroActivity();

            initializeRatingDialog();
        }

        initializeCatchAllSwitch();
    }

    private void initializeRatingDialog() {
        if (!IS_GOOGLE_ECOSYSTEM) {
            return;
        }

        // TODO: use custom condition for opened documents

        new AppRating.Builder(this)
                .setMinimumLaunchTimes(2)
                .setMinimumDays(3)
                .setMinimumLaunchTimesToShowAgain(5)
                .setMinimumDaysToShowAgain(10)
                .setShowOnlyFullStars(true)
                .setDebug(true)
                .setMailSettingsForFeedbackDialog(new MailSettings("support@opendocument.app", "OpenDocument Reader - Android Feedback", "", ""))
                .setRatingThreshold(RatingThreshold.FOUR)
                .setConfirmButtonClickListener(new ConfirmButtonClickListener() {
                    @Override
                    public void onClick(float v) {
                        analyticsManager.report("rating_yes", "rating", v);
                    }
                })
                .setRateLaterButtonClickListener(new RateDialogClickListener() {
                    @Override
                    public void onClick() {
                        analyticsManager.report("rating_no");
                    }
                })
                .showIfMeetsConditions();
    }

    private void initializeCatchAllSwitch() {
        ComponentName catchAllComponent = new ComponentName(this, "at.tomtasche.reader.ui.activity.MainActivity.CATCH_ALL");
        ComponentName strictCatchComponent = new ComponentName(this, "at.tomtasche.reader.ui.activity.MainActivity.STRICT_CATCH");

        boolean isCatchAllEnabled = getPackageManager().getComponentEnabledSetting(catchAllComponent) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED && IS_GOOGLE_ECOSYSTEM;

        // retoggle components for users upgrading to latest version of app
        toggleComponent(catchAllComponent, isCatchAllEnabled);
        toggleComponent(strictCatchComponent, !isCatchAllEnabled);

        Switch catchAllSwitch = findViewById(R.id.landing_catch_all);
        if (!IS_GOOGLE_ECOSYSTEM) {
            LinearLayout parent = (LinearLayout) catchAllSwitch.getParent();
            parent.setVisibility(View.GONE);
        }

        catchAllSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                toggleComponent(catchAllComponent, isChecked);
                toggleComponent(strictCatchComponent, !isChecked);
            }
        });

        catchAllSwitch.setChecked(isCatchAllEnabled);

        analyticsManager.report(isCatchAllEnabled ? "catch_all_enabled" : "catch_all_disabled");
    }

    private void toggleComponent(ComponentName component, boolean enabled) {
        int newState = enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        getPackageManager().setComponentEnabledSetting(component, newState, PackageManager.DONT_KILL_APP);
    }

    @Override
    protected void onStart() {
        super.onStart();

        // app was started from another app, but make sure not to load it twice
        // (i.e. after bringing app back from background)
        if (getSupportFragmentManager().findFragmentByTag(DOCUMENT_FRAGMENT_TAG) == null) {
            analyticsManager.setCurrentScreen(this, "screen_main");

            handleIntent(getIntent());
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        adManager.showGoogleAds();
    }

    @Override
    protected void onResume() {
        super.onResume();

        documentFragment = (DocumentFragment) getSupportFragmentManager().findFragmentByTag(DOCUMENT_FRAGMENT_TAG);

        if (getSupportFragmentManager().findFragmentByTag(DOCUMENT_FRAGMENT_TAG) == null) {
            // setCurrentScreen not ready to call before that
            analyticsManager.setCurrentScreen(this, "screen_main");
        }
    }

    private void showIntroActivity() {
        SharedPreferences getPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        boolean wasIntroShown = getPrefs.getBoolean("introShown", false);
        if (!wasIntroShown) {
            Intent intent = new Intent(MainActivity.this, IntroActivity.class);
            startActivity(intent);

            SharedPreferences.Editor editor = getPrefs.edit();
            editor.putBoolean("introShown", true);
            editor.apply();
        }
    }

    public boolean requestPermission(String permission, Runnable onPermissionRunnable) {
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{permission}, PERMISSION_CODE);

            this.onPermissionRunnable = onPermissionRunnable;

            return false;
        }

        this.onPermissionRunnable = null;

        return true;
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    public boolean requestSave() {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);

            intent.setType(documentFragment.getLastFileType());

            startActivityForResult(intent, CREATE_CODE);

            return true;
        } catch (ActivityNotFoundException e) {
            // happens on a variety devices, e.g. Samsung Galaxy Tab4 7.0 with Android 4.4.2
            crashManager.log(e);

            return false;
        }
    }

    private void initializeProprietaryLibraries() {
        if (USE_PROPRIETARY_LIBRARIES && IS_GOOGLE_ECOSYSTEM) {
            GoogleApiAvailability googleApi = GoogleApiAvailability.getInstance();
            int googleAvailability = googleApi.isGooglePlayServicesAvailable(this);
            if (googleAvailability != ConnectionResult.SUCCESS) {
                IS_GOOGLE_ECOSYSTEM = false;
                googleApi.getErrorDialog(this, googleAvailability, GOOGLE_REQUEST_CODE).show();
            }
        }

        crashManager = new CrashManager();
        crashManager.setEnabled(USE_PROPRIETARY_LIBRARIES);
        crashManager.initialize();

        analyticsManager = new AnalyticsManager();
        analyticsManager.setEnabled(USE_PROPRIETARY_LIBRARIES);
        analyticsManager.initialize(this);

        adManager = new AdManager();
        adManager.setEnabled(!IS_TESTING && USE_PROPRIETARY_LIBRARIES);
        adManager.setAdContainer(adContainer);
        adManager.setOnAdFailedCallback(new Runnable() {

            @Override
            public void run() {
                offerPurchase();
            }
        });
        adManager.initialize(this, analyticsManager, crashManager);

        billingManager = new BillingManager();
        billingManager.setEnabled(USE_PROPRIETARY_LIBRARIES && IS_GOOGLE_ECOSYSTEM);
        billingManager.initialize(this, analyticsManager, adManager, crashManager);

        helpManager = new HelpManager();
        helpManager.setEnabled(USE_PROPRIETARY_LIBRARIES);
        helpManager.initialize(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent.getData() != null) {
            loadUri(intent.getData(), false);

            analyticsManager.report(FirebaseAnalytics.Event.SELECT_CONTENT, FirebaseAnalytics.Param.CONTENT_TYPE, "other");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        getMenuInflater().inflate(R.menu.menu_main, menu);

        if (billingManager.hasPurchased()) {
            menu.findItem(R.id.menu_remove_ads).setVisible(false);
        }

        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);

        if (requestCode == GOOGLE_REQUEST_CODE) {
            initializeProprietaryLibraries();
        } else if (requestCode == CREATE_CODE && intent != null) {
            documentFragment.save(intent.getData());
        } else if (intent != null) {
            Uri uri = intent.getData();
            if (requestCode == 42 && resultCode == Activity.RESULT_OK && uri != null) {
                loadUri(uri, true);
            }
        }
    }

    public void loadUri(Uri uri, boolean showAd) {
        Runnable onPermission = new Runnable() {
            @Override
            public void run() {
                loadUri(uri, showAd);
            }
        };

        boolean hasPermission = requestPermission(Manifest.permission.READ_EXTERNAL_STORAGE, onPermission);
        if (!hasPermission) {
            return;
        }

        if (showAd) {
            adManager.showInterstitial();
        }

        if (documentFragment == null) {
            documentFragment = (DocumentFragment) getSupportFragmentManager().findFragmentByTag(DOCUMENT_FRAGMENT_TAG);

            landingContainer.setVisibility(View.GONE);
            documentContainer.setVisibility(View.VISIBLE);

            if (documentFragment == null) {
                documentFragment = new DocumentFragment();
                getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.document_container, documentFragment, DOCUMENT_FRAGMENT_TAG)
                        .commit();
            }
        }

        crashManager.log("loading document at: " + uri.toString());
        analyticsManager.report(FirebaseAnalytics.Event.VIEW_ITEM, FirebaseAnalytics.Param.ITEM_NAME, uri.toString());

        boolean isPersistentUri = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try {
                grantUriPermission(getPackageName(), uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception e) {
                // some providers dont support persisted permissions
                crashManager.log(e);

                if (!uri.toString().startsWith("content://at.tomtasche.reader")) {
                    isPersistentUri = false;
                }
            }
        }

        // delay loadUri to allow for DocumentFragment.onActivityCreated to be called
        boolean finalIsPersistentUri = isPersistentUri;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                documentFragment.loadUri(uri, finalIsPersistentUri);
            }
        }, 250);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_search: {
                FindActionModeCallback findActionModeCallback = new FindActionModeCallback(this);
                findActionModeCallback.setWebView(documentFragment.getPageView());
                startSupportActionMode(findActionModeCallback);

                analyticsManager.report("menu_search");
                analyticsManager.report(FirebaseAnalytics.Event.SEARCH);

                break;
            }
            case R.id.menu_open: {
                findDocument();

                analyticsManager.report("menu_open");
                analyticsManager.report(FirebaseAnalytics.Event.SELECT_CONTENT, FirebaseAnalytics.Param.CONTENT_TYPE, "choose");

                break;
            }
            case R.id.menu_open_with: {
                documentFragment.openWith(this);

                analyticsManager.report("menu_open_with");

                break;
            }
            case R.id.menu_share: {
                documentFragment.share(this);

                analyticsManager.report("menu_share");

                break;
            }
            case R.id.menu_remove_ads: {
                analyticsManager.report("menu_remove_ads");

                buyAdRemoval();

                break;
            }
            case R.id.menu_fullscreen: {
                if (fullscreen) {
                    analyticsManager.report("menu_fullscreen_leave");

                    leaveFullscreen();
                } else {
                    analyticsManager.report("menu_fullscreen_enter");

                    getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                            WindowManager.LayoutParams.FLAG_FULLSCREEN);
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);

                    getSupportActionBar().hide();

                    if (!billingManager.hasPurchased()) {
                        // delay offer to wait for fullscreen animation to finish
                        handler.postDelayed(new Runnable() {

                            @Override
                            public void run() {
                                if (isFinishing()) {
                                    return;
                                }

                                offerPurchase();
                            }
                        }, 1000);
                    }
                }

                fullscreen = !fullscreen;

                break;
            }
            case R.id.menu_print: {
                analyticsManager.report("menu_print");

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    KitKatPrinter.print(this, documentFragment.getPageView());
                } else {
                    SnackbarHelper.show(this, R.string.crouton_print_unavailable, null, true, true);
                }

                break;
            }
            case R.id.menu_tts: {
                analyticsManager.report("menu_tts");

                ttsActionMode = new TtsActionModeCallback(this, documentFragment.getPageView());
                startSupportActionMode(ttsActionMode);

                break;
            }
            case R.id.menu_edit: {
                analyticsManager.report("menu_edit");

                editActionMode = new EditActionModeCallback(this, documentFragment, helpManager);
                startSupportActionMode(editActionMode);

                break;
            }
            case R.id.edit_help: {
                helpManager.show();

                break;
            }
            default: {
                return super.onOptionsItemSelected(item);
            }
        }

        return true;
    }

    private void offerPurchase() {
        if (billingManager.hasPurchased() || !billingManager.isEnabled()) {
            return;
        }

        analyticsManager.report(FirebaseAnalytics.Event.PRESENT_OFFER);
        SnackbarHelper.show(this, R.string.crouton_remove_ads, new Runnable() {

            @Override
            public void run() {
                buyAdRemoval();
            }
        }, true, false);
    }

    @Override
    public void onActionModeFinished(ActionMode mode) {
        super.onActionModeFinished(mode);

        editActionMode = null;
        ttsActionMode = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (requestCode == PERMISSION_CODE && onPermissionRunnable != null) {
                onPermissionRunnable.run();
                onPermissionRunnable = null;

                return;
            }
        }

        String permission;
        if (permissions.length > 0) {
            permission = permissions[0];
        } else {
            // https://stackoverflow.com/q/50770955/198996
            permission = Manifest.permission.WRITE_EXTERNAL_STORAGE;
        }

        if (!didTriggerPermissionDialogAgain) {
            requestPermission(permission, onPermissionRunnable);

            didTriggerPermissionDialogAgain = true;
        } else {
            SnackbarHelper.show(this, R.string.toast_error_permission_required, new Runnable() {
                @Override
                public void run() {
                    requestPermission(permission, onPermissionRunnable);
                }
            }, true, true);
        }
    }

    private void showRecent() {
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();

        DialogFragment chooserDialog = new RecentDocumentDialogFragment();
        chooserDialog.show(transaction, RecentDocumentDialogFragment.FRAGMENT_TAG);

        analyticsManager.report(FirebaseAnalytics.Event.SELECT_CONTENT, FirebaseAnalytics.Param.CONTENT_TYPE, "recent");
    }

    private void buyAdRemoval() {
        adManager.loadVideo();

        analyticsManager.report(FirebaseAnalytics.Event.BEGIN_CHECKOUT);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.dialog_remove_ads_title);

        final boolean isBillingEnabled = billingManager.isEnabled();

        String[] optionStrings = getResources().getStringArray(R.array.dialog_remove_ads_options);
        if (!isBillingEnabled) {
            optionStrings = new String[]{optionStrings[1]};
        }

        builder.setItems(optionStrings, new OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                String product;

                if (!isBillingEnabled) {
                    which = 99;
                }

                analyticsManager.report(FirebaseAnalytics.Event.ADD_TO_CART);

                switch (which) {
                    case 0:
                        product = BillingManager.BILLING_PRODUCT_FOREVER;

                        break;

                    default:
                        dialog.dismiss();

                        adManager.showVideo();

                        return;
                }

                billingManager.startPurchase(MainActivity.this);

                dialog.dismiss();
            }
        });
        builder.show();
    }

    private void leaveFullscreen() {
        getSupportActionBar().show();

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        fullscreen = false;

        analyticsManager.report("fullscreen_end");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (fullscreen && keyCode == KeyEvent.KEYCODE_BACK) {
            leaveFullscreen();

            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    public void findDocument() {
        adManager.loadInterstitial();

        final Intent intent;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            intent = new Intent(Intent.ACTION_GET_CONTENT);
            // remove mime-type because most apps don't support ODF mime-types
            intent.setType("application/*");
        } else {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        }
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        PackageManager pm = getPackageManager();
        final List<ResolveInfo> targets = pm.queryIntentActivities(intent, 0);
        int size = targets.size() + 1;
        String[] targetNames = new String[size];
        for (int i = 0; i < targets.size(); i++) {
            targetNames[i] = targets.get(i).loadLabel(pm).toString();
        }

        targetNames[size - 1] = getString(R.string.menu_recent);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.dialog_choose_filemanager);
        builder.setItems(targetNames, new OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which == size - 1) {
                    showRecent();

                    return;
                }

                ResolveInfo target = targets.get(which);
                if (target == null) {
                    return;
                }

                intent.setComponent(new ComponentName(target.activityInfo.packageName, target.activityInfo.name));

                try {
                    startActivityForResult(intent, 42);
                } catch (Exception e) {
                    crashManager.log(e);

                    SnackbarHelper.show(MainActivity.this, R.string.crouton_error_open_app, new Runnable() {

                        @Override
                        public void run() {
                            findDocument();
                        }
                    }, true, true);
                }

                analyticsManager.report(FirebaseAnalytics.Event.SELECT_CONTENT, FirebaseAnalytics.Param.CONTENT_TYPE, target.activityInfo.packageName);

                dialog.dismiss();
            }
        });
        builder.show();
    }

    @Override
    public void onPause() {
        super.onPause();

        if (ttsActionMode != null) {
            ttsActionMode.stop();
        }
    }

    @Override
    protected void onStop() {
        billingManager.close();

        super.onStop();
    }

    @Override
    protected void onDestroy() {
        adManager.destroyAds();

        try {
            // keeps throwing exceptions for some users:
            // Caused by: java.lang.NullPointerException
            // android.webkit.WebViewClassic.requestFocus(WebViewClassic.java:9898)
            // android.webkit.WebView.requestFocus(WebView.java:2133)
            // android.view.ViewGroup.onRequestFocusInDescendants(ViewGroup.java:2384)

            super.onDestroy();
        } catch (Exception e) {
            crashManager.log(e);
        }
    }

    public CrashManager getCrashManager() {
        return crashManager;
    }

    public AnalyticsManager getAnalyticsManager() {
        return analyticsManager;
    }
}
