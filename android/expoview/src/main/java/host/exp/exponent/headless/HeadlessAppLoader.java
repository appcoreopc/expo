package host.exp.exponent.headless;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.facebook.react.ReactPackage;
import com.facebook.react.common.MapBuilder;
import com.facebook.soloader.SoLoader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import expo.core.interfaces.Package;
import expo.loaders.provider.AppLoaderProvider;
import expo.loaders.provider.interfaces.AppLoaderInterface;
import expo.loaders.provider.interfaces.AppRecordInterface;
import host.exp.exponent.ABIVersion;
import host.exp.exponent.AppLoader;
import host.exp.exponent.Constants;
import host.exp.exponent.ExponentManifest;
import host.exp.exponent.RNObject;
import host.exp.exponent.di.NativeModuleDepsProvider;
import host.exp.exponent.kernel.ExperienceId;
import host.exp.exponent.kernel.ExponentUrls;
import host.exp.exponent.kernel.services.ErrorRecoveryManager;
import host.exp.exponent.notifications.ExponentNotification;
import host.exp.exponent.storage.ExponentSharedPreferences;
import host.exp.exponent.utils.AsyncCondition;
import host.exp.exponent.utils.JSONBundleConverter;
import host.exp.expoview.Exponent;
import versioned.host.exp.exponent.ExponentPackage;
import versioned.host.exp.exponent.ExponentPackageDelegate;

import static com.facebook.react.bridge.UiThreadUtil.runOnUiThread;
import static host.exp.exponent.kernel.KernelConstants.INTENT_URI_KEY;
import static host.exp.exponent.kernel.KernelConstants.LINKING_URI_KEY;
import static host.exp.exponent.kernel.KernelConstants.MANIFEST_URL_KEY;

// @tsapeta: Most parts of this class was just copied from ReactNativeActivity and ExperienceActivity,
// however it allows launching apps in the background, without the activity.
// I've found it pretty hard to make just one implementation that can be used in both cases,
// so I decided to go with a copy until we refactor these activity classes.

public class HeadlessAppLoader implements AppLoaderInterface, Exponent.StartReactInstanceDelegate {
  private static String READY_FOR_BUNDLE = "readyForBundle";

  private JSONObject mManifest;
  private String mManifestUrl;
  private String mSDKVersion;
  private String mDetachSdkVersion;
  private boolean mIsShellApp;
  private String mExperienceIdString;
  private ExperienceId mExperienceId;
  private RNObject mReactInstanceManager = new RNObject("com.facebook.react.ReactInstanceManager");
  private Context mContext;
  private String mIntentUri;
  private boolean mIsReadyForBundle;
  private String mJSBundlePath;
  private HeadlessAppRecord mAppRecord;
  private AppLoaderProvider.Callback mCallback;

  @Inject
  private ExponentSharedPreferences mExponentSharedPreferences;

  public HeadlessAppLoader(Context context) {
    super();

    mContext = context;
    NativeModuleDepsProvider.getInstance().inject(HeadlessAppLoader.class, this);
  }

  @Override
  public AppRecordInterface loadApp(String appUrl, Map<String, Object> options, AppLoaderProvider.Callback callback) {
    mManifestUrl = appUrl;
    mAppRecord = new HeadlessAppRecord();
    mCallback = callback;

    new AppLoader(mManifestUrl) {
      @Override
      public void onOptimisticManifest(final JSONObject optimisticManifest) {

      }

      @Override
      public void onManifestCompleted(final JSONObject manifest) {
        Exponent.getInstance().runOnUiThread(new Runnable() {
          @Override
          public void run() {
            try {
              String bundleUrl = ExponentUrls.toHttp(manifest.getString("bundleUrl"));

              setManifest(mManifestUrl, manifest, bundleUrl, null);
            } catch (JSONException e) {
              mCallback.onComplete(false, new Error(e.getMessage()));
            }
          }
        });
      }

      @Override
      public void onBundleCompleted(String localBundlePath) {
        setBundle(localBundlePath);
      }

      @Override
      public void emitEvent(JSONObject params) {}

      @Override
      public void onError(Exception e) {
        mCallback.onComplete(false, new Error(e.getMessage()));
      }

      @Override
      public void onError(String e) {
        mCallback.onComplete(false, new Error(e));
      }
    }.start();

    return mAppRecord;
  }

  public void setManifest(String manifestUrl, final JSONObject manifest, final String bundleUrl, final JSONObject kernelOptions) {
    mManifestUrl = manifestUrl;
    mManifest = manifest;
    mSDKVersion = manifest.optString(ExponentManifest.MANIFEST_SDK_VERSION_KEY);
    mIsShellApp = manifestUrl.equals(Constants.INITIAL_URL);

    // Sometime we want to release a new version without adding a new .aar. Use TEMPORARY_ABI_VERSION
    // to point to the unversioned code in ReactAndroid.
    if (Constants.TEMPORARY_ABI_VERSION != null && Constants.TEMPORARY_ABI_VERSION.equals(mSDKVersion)) {
      mSDKVersion = RNObject.UNVERSIONED;
    }

    mDetachSdkVersion = Constants.IS_DETACHED ? RNObject.UNVERSIONED : mSDKVersion;

    if (!RNObject.UNVERSIONED.equals(mSDKVersion)) {
      boolean isValidVersion = false;
      for (final String version : Constants.SDK_VERSIONS_LIST) {
        if (version.equals(mSDKVersion)) {
          isValidVersion = true;
          break;
        }
      }

      if (!isValidVersion) {
        mCallback.onComplete(false, new Error(mSDKVersion + " is not a valid SDK version."));
        return;
      }
    }

    soloaderInit();

    try {
      mExperienceIdString = manifest.getString(ExponentManifest.MANIFEST_ID_KEY);
      mExperienceId = ExperienceId.create(mExperienceIdString);
    } catch (JSONException e) {
      mCallback.onComplete(false, new Error(e.getMessage()));
      return;
    }

    // if we have an embedded initial url, we never need any part of this in the initial url
    // passed to the JS, so we check for that and filter it out here.
    // this can happen in dev mode on a detached app, for example, because the intent will have
    // a url like customscheme://localhost:19000 but we don't care about the localhost:19000 part.
    if (mIntentUri == null || mIntentUri.equals(Constants.INITIAL_URL)) {
      if (Constants.SHELL_APP_SCHEME != null) {
        mIntentUri = Constants.SHELL_APP_SCHEME + "://";
      } else {
        mIntentUri = mManifestUrl;
      }
    }

    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (mReactInstanceManager.isNotNull()) {
          mReactInstanceManager.onHostDestroy();
          mReactInstanceManager.assign(null);
        }

        if (isDebugModeEnabled()) {
          mJSBundlePath = "";
          startReactInstance();
        } else {
          mIsReadyForBundle = true;
          AsyncCondition.notify(READY_FOR_BUNDLE);
        }
      }
    });
  }

  public void setBundle(final String localBundlePath) {
    if (!isDebugModeEnabled()) {
      final boolean finalIsReadyForBundle = mIsReadyForBundle;

      AsyncCondition.wait(READY_FOR_BUNDLE, new AsyncCondition.AsyncConditionListener() {
        @Override
        public boolean isReady() {
          return finalIsReadyForBundle;
        }

        @Override
        public void execute() {
          mJSBundlePath = localBundlePath;
          startReactInstance();
          AsyncCondition.remove(READY_FOR_BUNDLE);
        }
      });
    }
  }

  public boolean isDebugModeEnabled() {
    return ExponentManifest.isDebugModeEnabled(mManifest);
  }

  public void soloaderInit() {
    if (mDetachSdkVersion != null) {
      SoLoader.init(mContext, false);
    }
  }

  // Override
  public List<ReactPackage> reactPackages() {
    return null;
  }

  // Override
  public List<Package> expoPackages() {
    return Collections.emptyList();
  }

  //region StartReactInstanceDelegate

  @Override
  public boolean isInForeground() {
    return false;
  }

  @Override
  public ExponentPackageDelegate getExponentPackageDelegate() {
    return null;
  }

  @Override
  public void handleUnreadNotifications(JSONArray unreadNotifications) {

  }

  //endregion

  private void startReactInstance() {
    Exponent.getInstance().testPackagerStatus(isDebugModeEnabled(), mManifest, new Exponent.PackagerStatusCallback() {
      @Override
      public void onSuccess() {
        mReactInstanceManager = startReactInstance(HeadlessAppLoader.this, mIntentUri, null, mDetachSdkVersion, null, mIsShellApp, reactPackages(), expoPackages());
      }

      @Override
      public void onFailure(final String errorMessage) {
        mCallback.onComplete(false, new Error(errorMessage));
      }
    });
  }

  private RNObject startReactInstance(final Exponent.StartReactInstanceDelegate delegate, final String mIntentUri, final RNObject mLinkingPackage,
                                     final String mSDKVersion, final ExponentNotification mNotification, final boolean mIsShellApp,
                                     final List<? extends Object> extraNativeModules, final List<Package> extraExpoPackages) {
    String linkingUri = getLinkingUri();
    Map<String, Object> experienceProperties = MapBuilder.<String, Object>of(
        MANIFEST_URL_KEY, mManifestUrl,
        LINKING_URI_KEY, linkingUri,
        INTENT_URI_KEY, mIntentUri
    );

    Exponent.InstanceManagerBuilderProperties instanceManagerBuilderProperties = new Exponent.InstanceManagerBuilderProperties();
    instanceManagerBuilderProperties.application = (Application) mContext;
    instanceManagerBuilderProperties.jsBundlePath = mJSBundlePath;
    instanceManagerBuilderProperties.linkingPackage = mLinkingPackage;
    instanceManagerBuilderProperties.experienceProperties = experienceProperties;
    instanceManagerBuilderProperties.expoPackages = extraExpoPackages;
    instanceManagerBuilderProperties.exponentPackageDelegate = delegate.getExponentPackageDelegate();
    instanceManagerBuilderProperties.manifest = mManifest;
    instanceManagerBuilderProperties.singletonModules = ExponentPackage.createSingletonModules(mContext);

    RNObject versionedUtils = new RNObject("host.exp.exponent.VersionedUtils").loadVersion(mSDKVersion);
    RNObject builder = versionedUtils.callRecursive("getReactInstanceManagerBuilder", instanceManagerBuilderProperties);

    if (extraNativeModules != null) {
      for (Object nativeModule : extraNativeModules) {
        builder.call("addPackage", nativeModule);
      }
    }

    if (delegate.isDebugModeEnabled()) {
      String debuggerHost = mManifest.optString(ExponentManifest.MANIFEST_DEBUGGER_HOST_KEY);
      String mainModuleName = mManifest.optString(ExponentManifest.MANIFEST_MAIN_MODULE_NAME_KEY);
      Exponent.enableDeveloperSupport(mSDKVersion, debuggerHost, mainModuleName, builder);
    }

    Bundle bundle = new Bundle();
    JSONObject exponentProps = new JSONObject();
    if (mNotification != null) {
      bundle.putString("notification", mNotification.body); // Deprecated
      try {
        if (ABIVersion.toNumber(mSDKVersion) < ABIVersion.toNumber("10.0.0")) {
          exponentProps.put("notification", mNotification.body);
        } else {
          exponentProps.put("notification", mNotification.toJSONObject("selected"));
        }
      } catch (JSONException e) {
        e.printStackTrace();
      }
    }

    try {
      exponentProps.put("manifest", mManifest);
      exponentProps.put("shell", mIsShellApp);
      exponentProps.put("initialUri", mIntentUri == null ? null : mIntentUri.toString());
      exponentProps.put("errorRecovery", ErrorRecoveryManager.getInstance(mExperienceId).popRecoveryProps());
    } catch (JSONException e) {
      Log.e("Expo", "JSON exception occurred while putting expo props: " + e.getMessage());
    }

    JSONObject metadata = mExponentSharedPreferences.getExperienceMetadata(mExperienceIdString);
    if (metadata != null) {
      if (metadata.has(ExponentSharedPreferences.EXPERIENCE_METADATA_LAST_ERRORS)) {
        try {
          exponentProps.put(ExponentSharedPreferences.EXPERIENCE_METADATA_LAST_ERRORS,
              metadata.getJSONArray(ExponentSharedPreferences.EXPERIENCE_METADATA_LAST_ERRORS));
        } catch (JSONException e) {
          e.printStackTrace();
        }

        metadata.remove(ExponentSharedPreferences.EXPERIENCE_METADATA_LAST_ERRORS);
      }

      // TODO: fix this. this is the only place that EXPERIENCE_METADATA_UNREAD_REMOTE_NOTIFICATIONS is sent to the experience,
      // we need to sent them with the standard notification events so that you can get all the unread notification through an event
      // Copy unreadNotifications into exponentProps
      if (metadata.has(ExponentSharedPreferences.EXPERIENCE_METADATA_UNREAD_REMOTE_NOTIFICATIONS)) {
        try {
          JSONArray unreadNotifications = metadata.getJSONArray(ExponentSharedPreferences.EXPERIENCE_METADATA_UNREAD_REMOTE_NOTIFICATIONS);
          exponentProps.put(ExponentSharedPreferences.EXPERIENCE_METADATA_UNREAD_REMOTE_NOTIFICATIONS, unreadNotifications);

          delegate.handleUnreadNotifications(unreadNotifications);
        } catch (JSONException e) {
          e.printStackTrace();
        }

        metadata.remove(ExponentSharedPreferences.EXPERIENCE_METADATA_UNREAD_REMOTE_NOTIFICATIONS);
      }

      mExponentSharedPreferences.updateExperienceMetadata(mExperienceIdString, metadata);
    }

    bundle.putBundle("exp", JSONBundleConverter.JSONToBundle(exponentProps));

    if (!delegate.isInForeground()) {
      Log.i("EXPO", "DUPAAAA 0");
      return new RNObject("com.facebook.react.ReactInstanceManager");
    }
    Log.i("EXPO", "DUPAAAA 1");

    RNObject mReactInstanceManager = builder.callRecursive("build");
    mReactInstanceManager.callRecursive("createReactContextInBackground");
    mReactInstanceManager.onHostResume(this, this);

    // keep a reference in app record, so it can be invalidated through AppRecord.invalidate()
    mAppRecord.setReactInstanceManager(mReactInstanceManager);
    mCallback.onComplete(true, null);

    return mReactInstanceManager;
  }

  // deprecated in favor of Expo.Linking.makeUrl
  // TODO: remove this
  private String getLinkingUri() {
    if (Constants.SHELL_APP_SCHEME != null) {
      return Constants.SHELL_APP_SCHEME + "://";
    } else {
      if (ABIVersion.toNumber(mSDKVersion) < ABIVersion.toNumber("27.0.0")) {
        // keep old behavior on old projects to not introduce breaking changes
        return mManifestUrl + "/+";
      }
      Uri uri = Uri.parse(mManifestUrl);
      String host = uri.getHost();
      if (host != null && (host.equals("exp.host") || host.equals("expo.io") || host.equals("exp.direct") || host.equals("expo.test") ||
          host.endsWith(".exp.host") || host.endsWith(".expo.io") || host.endsWith(".exp.direct") || host.endsWith(".expo.test"))) {
        List<String> pathSegments = uri.getPathSegments();
        Uri.Builder builder = uri.buildUpon();
        builder.path(null);

        for (String segment : pathSegments) {
          if (ExponentManifest.DEEP_LINK_SEPARATOR.equals(segment)) {
            break;
          }
          builder.appendEncodedPath(segment);
        }

        return builder.appendEncodedPath(ExponentManifest.DEEP_LINK_SEPARATOR_WITH_SLASH).build().toString();
      } else {
        return mManifestUrl;
      }
    }
  }
}
