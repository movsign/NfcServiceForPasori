/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.nfc;

import com.android.nfc.DeviceHost.DeviceHostListener;
import com.android.nfc.DeviceHost.LlcpServerSocket;
import com.android.nfc.DeviceHost.LlcpSocket;
import com.android.nfc.DeviceHost.NfcDepEndpoint;
import com.android.nfc.DeviceHost.TagEndpoint;
import com.android.nfc.hiro99ma.NativeNfcManager;


import android.app.Application;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.SoundPool;
import android.net.Uri;
import android.nfc.ErrorCodes;
import android.nfc.FormatException;
import android.nfc.INdefPushCallback;
import android.nfc.INfcAdapter;
import android.nfc.INfcAdapterExtras;
import android.nfc.INfcTag;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.TechListParcel;
import android.nfc.TransceiveResult;
import android.nfc.tech.Ndef;
import android.nfc.tech.TagTechnology;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class NfcService extends Application implements DeviceHostListener {
    private static final String ACTION_MASTER_CLEAR_NOTIFICATION = "android.intent.action.MASTER_CLEAR_NOTIFICATION";

    static final boolean DBG = false;
    static final String TAG = "NfcService";

    public static final String SERVICE_NAME = "nfc";

    /** Regular NFC permission */
    private static final String NFC_PERM = android.Manifest.permission.NFC;
    private static final String NFC_PERM_ERROR = "NFC permission required";

    /** NFC ADMIN permission - only for system apps */
    private static final String ADMIN_PERM = android.Manifest.permission.WRITE_SECURE_SETTINGS;
    private static final String ADMIN_PERM_ERROR = "WRITE_SECURE_SETTINGS permission required";

    public static final String PREF = "NfcServicePrefs";

    static final String PREF_NFC_ON = "nfc_on";
    static final boolean NFC_ON_DEFAULT = true;
    static final String PREF_NDEF_PUSH_ON = "ndef_push_on";
    static final boolean NDEF_PUSH_ON_DEFAULT = true;
    static final String PREF_FIRST_BEAM = "first_beam";
    static final String PREF_FIRST_BOOT = "first_boot";

    static final int MSG_NDEF_TAG = 0;
    static final int MSG_CARD_EMULATION = 1;
    static final int MSG_LLCP_LINK_ACTIVATION = 2;
    static final int MSG_LLCP_LINK_DEACTIVATED = 3;
    static final int MSG_TARGET_DESELECTED = 4;
    static final int MSG_MOCK_NDEF = 7;
    static final int MSG_SE_FIELD_ACTIVATED = 8;
    static final int MSG_SE_FIELD_DEACTIVATED = 9;
    static final int MSG_SE_APDU_RECEIVED = 10;
    static final int MSG_SE_EMV_CARD_REMOVAL = 11;
    static final int MSG_SE_MIFARE_ACCESS = 12;

    static final int TASK_ENABLE = 1;
    static final int TASK_DISABLE = 2;
    static final int TASK_BOOT = 3;
    static final int TASK_EE_WIPE = 4;

    // Screen state, used by mScreenState
    static final int SCREEN_STATE_UNKNOWN = 0;
    static final int SCREEN_STATE_OFF = 1;
    static final int SCREEN_STATE_ON_LOCKED = 2;
    static final int SCREEN_STATE_ON_UNLOCKED = 3;

    // Copied from com.android.nfc_extras to avoid library dependency
    // Must keep in sync with com.android.nfc_extras
    static final int ROUTE_OFF = 1;
    static final int ROUTE_ON_WHEN_SCREEN_ON = 2;

    /** minimum screen state that enables NFC polling (discovery) */
    static final int POLLING_MODE = SCREEN_STATE_ON_UNLOCKED;

    // for use with playSound()
    public static final int SOUND_START = 0;
    public static final int SOUND_END = 1;
    public static final int SOUND_ERROR = 2;

    public static final String ACTION_RF_FIELD_ON_DETECTED =
        "com.android.nfc_extras.action.RF_FIELD_ON_DETECTED";
    public static final String ACTION_RF_FIELD_OFF_DETECTED =
        "com.android.nfc_extras.action.RF_FIELD_OFF_DETECTED";
    public static final String ACTION_AID_SELECTED =
        "com.android.nfc_extras.action.AID_SELECTED";
    public static final String EXTRA_AID = "com.android.nfc_extras.extra.AID";

    public static final String ACTION_APDU_RECEIVED =
        "com.android.nfc_extras.action.APDU_RECEIVED";
    public static final String EXTRA_APDU_BYTES =
        "com.android.nfc_extras.extra.APDU_BYTES";

    public static final String ACTION_EMV_CARD_REMOVAL =
        "com.android.nfc_extras.action.EMV_CARD_REMOVAL";

    public static final String ACTION_MIFARE_ACCESS_DETECTED =
        "com.android.nfc_extras.action.MIFARE_ACCESS_DETECTED";
    public static final String EXTRA_MIFARE_BLOCK =
        "com.android.nfc_extras.extra.MIFARE_BLOCK";


    // fields below are used in multiple threads and protected by synchronized(this)
    final HashMap<Integer, Object> mObjectMap = new HashMap<Integer, Object>();
    HashSet<String> mSePackages = new HashSet<String>();
    int mScreenState;
    boolean mIsNdefPushEnabled = false;
    boolean mNfceeRouteEnabled;  // current Device Host state of NFC-EE routing
    boolean mNfcPollingEnabled;  // current Device Host state of NFC-C polling

    // mState is protected by this, however it is only modified in onCreate()
    // and the default AsyncTask thread so it is read unprotected from that
    // thread
    int mState;  // one of NfcAdapter.STATE_ON, STATE_TURNING_ON, etc

    // fields below are final after onCreate()
    Context mContext;
    private DeviceHost mDeviceHost;
    private SharedPreferences mPrefs;
    private SharedPreferences.Editor mPrefsEditor;
    int mStartSound;
    int mEndSound;
    int mErrorSound;
    SoundPool mSoundPool; // playback synchronized on this
    TagService mNfcTagService;
    NfcAdapterService mNfcAdapter;
    NfcAdapterExtrasService mExtrasService;
    boolean mIsAirplaneSensitive;
    boolean mIsAirplaneToggleable;

    private NfcDispatcher mNfcDispatcher;
    private KeyguardManager mKeyguard;

    private static NfcService sService;

    public static void enforceAdminPerm(Context context) {
        context.enforceCallingOrSelfPermission(ADMIN_PERM, ADMIN_PERM_ERROR);
    }

    public void enforceNfceeAdminPerm(String pkg) {
        throw new SecurityException("caller must pass a package name");
    }

    public static NfcService getInstance() {
        return sService;
    }

    @Override
    public void onRemoteEndpointDiscovered(TagEndpoint tag) {
        Log.d(TAG, "onRemoteEndpointDiscovered");
        sendMessage(NfcService.MSG_NDEF_TAG, tag);
    }

    /**
     * Notifies transaction
     */
    @Override
    public void onCardEmulationDeselected() {
        Log.d(TAG, "onCardEmulationDeselected");
        sendMessage(NfcService.MSG_TARGET_DESELECTED, null);
    }

    /**
     * Notifies transaction
     */
    @Override
    public void onCardEmulationAidSelected(byte[] aid) {
        Log.d(TAG, "onCardEmulationAidSelected");
        sendMessage(NfcService.MSG_CARD_EMULATION, aid);
    }

    /**
     * Notifies P2P Device detected, to activate LLCP link
     */
    @Override
    public void onLlcpLinkActivated(NfcDepEndpoint device) {
        Log.d(TAG, "onLlcpLinkActivated");
        sendMessage(NfcService.MSG_LLCP_LINK_ACTIVATION, device);
    }

    /**
     * Notifies P2P Device detected, to activate LLCP link
     */
    @Override
    public void onLlcpLinkDeactivated(NfcDepEndpoint device) {
        Log.d(TAG, "onLlcpLinkDeactivated");
        sendMessage(NfcService.MSG_LLCP_LINK_DEACTIVATED, device);
    }

    @Override
    public void onRemoteFieldActivated() {
        Log.d(TAG, "onRemoteFieldActivated");
        sendMessage(NfcService.MSG_SE_FIELD_ACTIVATED, null);
    }

    @Override
    public void onRemoteFieldDeactivated() {
        Log.d(TAG, "onRemoteFieldDeactivated");
        sendMessage(NfcService.MSG_SE_FIELD_DEACTIVATED, null);
    }

    @Override
    public void onSeApduReceived(byte[] apdu) {
        Log.d(TAG, "onSeApduReceived");
        sendMessage(NfcService.MSG_SE_APDU_RECEIVED, apdu);
    }

    @Override
    public void onSeEmvCardRemoval() {
        Log.d(TAG, "onSeEmvCardRemoval");
        sendMessage(NfcService.MSG_SE_EMV_CARD_REMOVAL, null);
    }

    @Override
    public void onSeMifareAccess(byte[] block) {
        Log.d(TAG, "onSeMifareAccess");
        sendMessage(NfcService.MSG_SE_MIFARE_ACCESS, block);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mNfcTagService = new TagService();
        mNfcAdapter = new NfcAdapterService();
        mExtrasService = new NfcAdapterExtrasService();

        Log.i(TAG, "Starting NFC service");

        sService = this;

        mContext = this;
        mDeviceHost = new NativeNfcManager(this, this);

        mNfcDispatcher = new NfcDispatcher(this);

        mPrefs = getSharedPreferences(PREF, Context.MODE_PRIVATE);
        mPrefsEditor = mPrefs.edit();

        mState = NfcAdapter.STATE_OFF;

        mKeyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        mScreenState = checkScreenState();

        ServiceManager.addService(SERVICE_NAME, mNfcAdapter);

        IntentFilter filter = new IntentFilter(NativeNfcManager.INTERNAL_TARGET_DESELECTED_ACTION);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        //filter.addAction(ACTION_MASTER_CLEAR_NOTIFICATION);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        //registerForAirplaneMode(filter);
        registerReceiver(mReceiver, filter);

//        filter = new IntentFilter();
//        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
//        filter.addDataScheme("package");

//        registerReceiver(mReceiver, filter);

        new EnableDisableTask().execute(TASK_BOOT);  // do blocking boot tasks
    }

    void initSoundPool() {
        synchronized(this) {
            if (mSoundPool == null) {
                mSoundPool = new SoundPool(1, AudioManager.STREAM_NOTIFICATION, 0);
                mStartSound = mSoundPool.load(this, R.raw.start, 1);
                mEndSound = mSoundPool.load(this, R.raw.end, 1);
                mErrorSound = mSoundPool.load(this, R.raw.error, 1);
            }
        }
    }

    void releaseSoundPool() {
        synchronized(this) {
            if (mSoundPool != null) {
                mSoundPool.release();
                mSoundPool = null;
            }
        }
    }

//    void registerForAirplaneMode(IntentFilter filter) {
//        final ContentResolver resolver = mContext.getContentResolver();
//        final String airplaneModeRadios = Settings.System.getString(resolver,
//                Settings.System.AIRPLANE_MODE_RADIOS);
//        final String toggleableRadios = Settings.System.getString(resolver,
//                Settings.System.AIRPLANE_MODE_TOGGLEABLE_RADIOS);
//
//        mIsAirplaneSensitive = airplaneModeRadios == null ? true :
//                airplaneModeRadios.contains(Settings.System.RADIO_NFC);
//        mIsAirplaneToggleable = toggleableRadios == null ? false :
//            toggleableRadios.contains(Settings.System.RADIO_NFC);
//
//        if (mIsAirplaneSensitive) {
//            filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
//        }
//    }

    int checkScreenState() {
        return SCREEN_STATE_ON_UNLOCKED;
    }

    /**
     * Manages tasks that involve turning on/off the NFC controller.
     *
     * <p>All work that might turn the NFC adapter on or off must be done
     * through this task, to keep the handling of mState simple.
     * In other words, mState is only modified in these tasks (and we
     * don't need a lock to read it in these tasks).
     *
     * <p>These tasks are all done on the same AsyncTask background
     * thread, so they are serialized. Each task may temporarily transition
     * mState to STATE_TURNING_OFF or STATE_TURNING_ON, but must exit in
     * either STATE_ON or STATE_OFF. This way each task can be guaranteed
     * of starting in either STATE_OFF or STATE_ON, without needing to hold
     * NfcService.this for the entire task.
     *
     * <p>AsyncTask's are also implicitly queued. This is useful for corner
     * cases like turning airplane mode on while TASK_ENABLE is in progress.
     * The TASK_DISABLE triggered by airplane mode will be correctly executed
     * immediately after TASK_ENABLE is complete. This seems like the most sane
     * way to deal with these situations.
     *
     * <p>{@link #TASK_ENABLE} enables the NFC adapter, without changing
     * preferences
     * <p>{@link #TASK_DISABLE} disables the NFC adapter, without changing
     * preferences
     * <p>{@link #TASK_BOOT} does first boot work and may enable NFC
     * <p>{@link #TASK_EE_WIPE} wipes the Execution Environment, and in the
     * process may temporarily enable the NFC adapter
     */
    class EnableDisableTask extends AsyncTask<Integer, Void, Void> {
        @Override
        protected Void doInBackground(Integer... params) {
            // Sanity check mState
            switch (mState) {
                case NfcAdapter.STATE_TURNING_OFF:
                case NfcAdapter.STATE_TURNING_ON:
                    Log.e(TAG, "Processing EnableDisable task " + params[0] + " from bad state " +
                            mState);
                    return null;
            }

            switch (params[0].intValue()) {
                case TASK_ENABLE:
                    enableInternal();
                    break;
                case TASK_DISABLE:
                    disableInternal();
                    break;
                case TASK_BOOT:
                    enableInternal();
                    if (mPrefs.getBoolean(PREF_FIRST_BOOT, true)) {
                        Log.i(TAG, "First Boot");
                        mPrefsEditor.putBoolean(PREF_FIRST_BOOT, false);
                        mPrefsEditor.apply();
                    }
                    break;
            }

            return null;
        }

        /**
         * Enable NFC adapter functions.
         * Does not toggle preferences.
         */
        boolean enableInternal() {
            if (mState == NfcAdapter.STATE_ON) {
                return true;
            }
            Log.i(TAG, "Enabling NFC");
            updateState(NfcAdapter.STATE_TURNING_ON);

            if (!mDeviceHost.initialize()) {
                Log.w(TAG, "Error enabling NFC");
                updateState(NfcAdapter.STATE_OFF);
                return false;
            }

            synchronized(NfcService.this) {
                mObjectMap.clear();

                updateState(NfcAdapter.STATE_ON);
            }

            initSoundPool();

            /* Start polling loop */
            applyRouting(true);
            return true;
        }

        /**
         * Disable all NFC adapter functions.
         * Does not toggle preferences.
         */
        boolean disableInternal() {
            if (mState == NfcAdapter.STATE_OFF) {
                return true;
            }
            Log.i(TAG, "Disabling NFC");
            updateState(NfcAdapter.STATE_TURNING_OFF);

            /* Sometimes mDeviceHost.deinitialize() hangs, use a watch-dog.
             * Implemented with a new thread (instead of a Handler or AsyncTask),
             * because the UI Thread and AsyncTask thread-pools can also get hung
             * when the NFC controller stops responding */
            WatchDogThread watchDog = new WatchDogThread();
            watchDog.start();

            // Stop watchdog if tag present
            // A convenient way to stop the watchdog properly consists of
            // disconnecting the tag. The polling loop shall be stopped before
            // to avoid the tag being discovered again.
            applyRouting(true);
            maybeDisconnectTarget();

            mNfcDispatcher.setForegroundDispatch(null, null, null);

            boolean result = mDeviceHost.deinitialize();
            if (DBG) Log.d(TAG, "mDeviceHost.deinitialize() = " + result);

            watchDog.cancel();

            updateState(NfcAdapter.STATE_OFF);

            releaseSoundPool();

            return result;
        }



        void updateState(int newState) {
            synchronized (this) {
                if (newState == mState) {
                    return;
                }
                mState = newState;
                Intent intent = new Intent(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
                intent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                intent.putExtra(NfcAdapter.EXTRA_ADAPTER_STATE, mState);
                mContext.sendBroadcast(intent);
            }
        }
    }

    void saveNfcOnSetting(boolean on) {
        synchronized (NfcService.this) {
            mPrefsEditor.putBoolean(PREF_NFC_ON, on);
            mPrefsEditor.apply();
        }
    }

    public void playSound(int sound) {
        synchronized (this) {
            if (mSoundPool == null) {
                Log.w(TAG, "Not playing sound when NFC is disabled");
                return;
            }
            switch (sound) {
                case SOUND_START:
                    mSoundPool.play(mStartSound, 1.0f, 1.0f, 0, 0, 1.0f);
                    break;
                case SOUND_END:
                    mSoundPool.play(mEndSound, 1.0f, 1.0f, 0, 0, 1.0f);
                    break;
                case SOUND_ERROR:
                    mSoundPool.play(mErrorSound, 1.0f, 1.0f, 0, 0, 1.0f);
                    break;
            }
        }
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        // NFC application is persistent, it should not be destroyed by framework
        Log.wtf(TAG, "NFC service is under attack!");
    }

    final class NfcAdapterService extends INfcAdapter.Stub {
        @Override
        public boolean enable() throws RemoteException {
            NfcService.enforceAdminPerm(mContext);

            saveNfcOnSetting(true);
            new EnableDisableTask().execute(TASK_ENABLE);

            return true;
        }

        @Override
        public boolean disable() throws RemoteException {
            NfcService.enforceAdminPerm(mContext);

            saveNfcOnSetting(false);
            new EnableDisableTask().execute(TASK_DISABLE);

            return true;
        }

        @Override
        public boolean isNdefPushEnabled() throws RemoteException {
            synchronized (NfcService.this) {
                return mIsNdefPushEnabled;
            }
        }

        @Override
        public boolean enableNdefPush() throws RemoteException {
            NfcService.enforceAdminPerm(mContext);
            return false;
        }

        @Override
        public boolean disableNdefPush() throws RemoteException {
            NfcService.enforceAdminPerm(mContext);
            return true;
        }

        @Override
        public void setForegroundDispatch(PendingIntent intent,
                IntentFilter[] filters, TechListParcel techListsParcel) {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            // Short-cut the disable path
            if (intent == null && filters == null && techListsParcel == null) {
                mNfcDispatcher.setForegroundDispatch(null, null, null);
                return;
            }

            // Validate the IntentFilters
            if (filters != null) {
                if (filters.length == 0) {
                    filters = null;
                } else {
                    for (IntentFilter filter : filters) {
                        if (filter == null) {
                            throw new IllegalArgumentException("null IntentFilter");
                        }
                    }
                }
            }

            // Validate the tech lists
            String[][] techLists = null;
            if (techListsParcel != null) {
                techLists = techListsParcel.getTechLists();
            }

            mNfcDispatcher.setForegroundDispatch(intent, filters, techLists);
        }

        @Override
        public void setForegroundNdefPush(NdefMessage msg, INdefPushCallback callback) {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
        }

        @Override
        public INfcTag getNfcTagInterface() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            return mNfcTagService;
        }

        @Override
        public INfcAdapterExtras getNfcAdapterExtrasInterface(String pkg) {
            NfcService.this.enforceNfceeAdminPerm(pkg);
            return mExtrasService;
        }

        @Override
        public int getState() throws RemoteException {
            synchronized (NfcService.this) {
                return mState;
            }
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            NfcService.this.dump(fd, pw, args);
        }
    };

    final class TagService extends INfcTag.Stub {
        @Override
        public int close(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            TagEndpoint tag = null;

            if (!isNfcEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag != null) {
                /* Remove the device from the hmap */
                unregisterObject(nativeHandle);
                tag.disconnect();
                return ErrorCodes.SUCCESS;
            }
            /* Restart polling loop for notification */
            applyRouting(true);
            return ErrorCodes.ERROR_DISCONNECT;
        }

        @Override
        public int connect(int nativeHandle, int technology) throws RemoteException {
        	Log.d(TAG, "connect1");
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            TagEndpoint tag = null;

            if (!isNfcEnabled()) {
        		Log.d(TAG, "connect2");
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag == null) {
        		Log.d(TAG, "connect3");
                return ErrorCodes.ERROR_DISCONNECT;
            }

            if (technology == TagTechnology.NFC_B) {
        		Log.d(TAG, "connect4");
                return ErrorCodes.ERROR_NOT_SUPPORTED;
            }

            // Note that on most tags, all technologies are behind a single
            // handle. This means that the connect at the lower levels
            // will do nothing, as the tag is already connected to that handle.
            if (tag.connect(technology)) {
                return ErrorCodes.SUCCESS;
            } else {
        		Log.d(TAG, "connect5");
                return ErrorCodes.ERROR_DISCONNECT;
            }
        }

        @Override
        public int reconnect(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            TagEndpoint tag = null;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag != null) {
                if (tag.reconnect()) {
                    return ErrorCodes.SUCCESS;
                } else {
                    return ErrorCodes.ERROR_DISCONNECT;
                }
            }
            return ErrorCodes.ERROR_DISCONNECT;
        }

        @Override
        public int[] getTechList(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return null;
            }

            /* find the tag in the hmap */
            TagEndpoint tag = (TagEndpoint) findObject(nativeHandle);
            if (tag != null) {
                return tag.getTechList();
            }
            return null;
        }

        @Override
        public byte[] getUid(int nativeHandle) throws RemoteException {
            TagEndpoint tag = null;
            byte[] uid;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag != null) {
                uid = tag.getUid();
                return uid;
            }
            return null;
        }

        @Override
        public boolean isPresent(int nativeHandle) throws RemoteException {
            TagEndpoint tag = null;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return false;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag == null) {
                return false;
            }

            return tag.isPresent();
        }

        @Override
        public boolean isNdef(int nativeHandle) throws RemoteException {
            TagEndpoint tag = null;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return false;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            int[] ndefInfo = new int[2];
            if (tag == null) {
                return false;
            }
            return tag.checkNdef(ndefInfo);
        }

        @Override
        public TransceiveResult transceive(int nativeHandle, byte[] data, boolean raw)
                throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            TagEndpoint tag = null;
            byte[] response;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag != null) {
                // Check if length is within limits
                if (data.length > getMaxTransceiveLength(tag.getConnectedTechnology())) {
                    return new TransceiveResult(TransceiveResult.RESULT_EXCEEDED_LENGTH, null);
                }
                int[] targetLost = new int[1];
                response = tag.transceive(data, raw, targetLost);
                int result;
                if (response != null) {
                    result = TransceiveResult.RESULT_SUCCESS;
                } else if (targetLost[0] == 1) {
                    result = TransceiveResult.RESULT_TAGLOST;
                } else {
                    result = TransceiveResult.RESULT_FAILURE;
                }
                return new TransceiveResult(result, response);
            }
            return null;
        }

        @Override
        public NdefMessage ndefRead(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            TagEndpoint tag;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag != null) {
                byte[] buf = tag.readNdef();
                if (buf == null) {
                    return null;
                }

                /* Create an NdefMessage */
                try {
                    return new NdefMessage(buf);
                } catch (FormatException e) {
                    return null;
                }
            }
            return null;
        }

        @Override
        public int ndefWrite(int nativeHandle, NdefMessage msg) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            TagEndpoint tag;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag == null) {
                return ErrorCodes.ERROR_IO;
            }

            if (tag.writeNdef(msg.toByteArray())) {
                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_IO;
            }

        }

        @Override
        public int getLastError(int nativeHandle) throws RemoteException {
            return mDeviceHost.doGetLastError();
        }

        @Override
        public boolean ndefIsWritable(int nativeHandle) throws RemoteException {
            throw new UnsupportedOperationException();
        }

        @Override
        public int ndefMakeReadOnly(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            TagEndpoint tag;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag == null) {
                return ErrorCodes.ERROR_IO;
            }

            if (tag.makeReadOnly()) {
                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_IO;
            }
        }

        @Override
        public int formatNdef(int nativeHandle, byte[] key) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            TagEndpoint tag;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag == null) {
                return ErrorCodes.ERROR_IO;
            }

            if (tag.formatNdef(key)) {
                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_IO;
            }
        }

        @Override
        public Tag rediscover(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            TagEndpoint tag = null;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag != null) {
                // For now the prime usecase for rediscover() is to be able
                // to access the NDEF technology after formatting without
                // having to remove the tag from the field, or similar
                // to have access to NdefFormatable in case low-level commands
                // were used to remove NDEF. So instead of doing a full stack
                // rediscover (which is poorly supported at the moment anyway),
                // we simply remove these two technologies and detect them
                // again.
                tag.removeTechnology(TagTechnology.NDEF);
                tag.removeTechnology(TagTechnology.NDEF_FORMATABLE);
                NdefMessage[] msgs = tag.findAndReadNdef();
                // Build a new Tag object to return
                Tag newTag = new Tag(tag.getUid(), tag.getTechList(),
                        tag.getTechExtras(), tag.getHandle(), this);
                return newTag;
            }
            return null;
        }

        @Override
        public int setTimeout(int tech, int timeout) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            boolean success = mDeviceHost.setTimeout(tech, timeout);
            if (success) {
                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_INVALID_PARAM;
            }
        }

        @Override
        public int getTimeout(int tech) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            return mDeviceHost.getTimeout(tech);
        }

        @Override
        public void resetTimeouts() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            mDeviceHost.resetTimeouts();
        }

        @Override
        public boolean canMakeReadOnly(int ndefType) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            return mDeviceHost.canMakeReadOnly(ndefType);
        }

        @Override
        public int getMaxTransceiveLength(int tech) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            return mDeviceHost.getMaxTransceiveLength(tech);
        }
    };

    final class NfcAdapterExtrasService extends INfcAdapterExtras.Stub {
        private Bundle writeNoException() {
            Bundle p = new Bundle();
            p.putInt("e", 0);
            return p;
        }
        private Bundle writeIoException(IOException e) {
            Bundle p = new Bundle();
            p.putInt("e", -1);
            p.putString("m", e.getMessage());
            return p;
        }

        @Override
        public Bundle open(String pkg, IBinder b) throws RemoteException {
	        throw new SecurityException("caller must pass a package name");
            //return null;
        }

        private void _open(IBinder b) throws IOException, RemoteException {
            throw new IOException("NFC adapter is disabled");
            //throw new IOException("NFC EE failed to open");
        }

        @Override
        public Bundle close(String pkg, IBinder binder) throws RemoteException {
	        throw new SecurityException("caller must pass a package name");
            //return null;
        }

        @Override
        public Bundle transceive(String pkg, byte[] in) throws RemoteException {
	        throw new SecurityException("caller must pass a package name");
            //return null;
        }

        private byte[] _transceive(byte[] data) throws IOException, RemoteException {
            throw new IOException("NFC is not enabled");
            //throw new IOException("NFC EE is not open");
            //return null;
        }

        @Override
        public int getCardEmulationRoute(String pkg) throws RemoteException {
            NfcService.this.enforceNfceeAdminPerm(pkg);
            return ROUTE_OFF;
        }

        @Override
        public void setCardEmulationRoute(String pkg, int route) throws RemoteException {
            NfcService.this.enforceNfceeAdminPerm(pkg);
            applyRouting(true);
        }

        @Override
        public void authenticate(String pkg, byte[] token) throws RemoteException {
            NfcService.this.enforceNfceeAdminPerm(pkg);
        }
    };
    boolean isNfcEnabled() {
        synchronized (this) {
            return mState == NfcAdapter.STATE_ON;
        }
    }

    class WatchDogThread extends Thread {
        boolean mWatchDogCanceled = false;
        @Override
        public void run() {
            boolean slept = false;
            while (!slept) {
                try {
                    Thread.sleep(10000);
                    slept = true;
                } catch (InterruptedException e) { }
            }
            synchronized (this) {
                if (!mWatchDogCanceled) {
                    // Trigger watch-dog
                    Log.e(TAG, "Watch dog triggered");
                    mDeviceHost.doAbort();
                }
            }
        }
        public synchronized void cancel() {
            mWatchDogCanceled = true;
        }
    }

    /**
     * Read mScreenState and apply NFC-C polling and NFC-EE routing
     */
    void applyRouting(boolean force) {
        synchronized (this) {
            if (!isNfcEnabled()) {
                return;
            }

            if (mScreenState == SCREEN_STATE_OFF) {
                /* TODO undo this after the LLCP stack is fixed.
                 * Use a different sequence when turning the screen off to
                 * workaround race conditions in pn544 libnfc. The race occurs
                 * when we change routing while there is a P2P target connect.
                 * The async LLCP callback will crash since the routing code
                 * is overwriting globals it relies on.
                 */
                if (POLLING_MODE > SCREEN_STATE_OFF) {
                    if (force || mNfcPollingEnabled) {
                        Log.d(TAG, "NFC-C OFF, disconnect");
                        mNfcPollingEnabled = false;
                        mDeviceHost.disableDiscovery();
                        maybeDisconnectTarget();
                    }
                }
                return;
            }

            // configure NFC-C polling
            if (mScreenState >= POLLING_MODE) {
                if (force || !mNfcPollingEnabled) {
                    Log.d(TAG, "NFC-C ON");
                    mNfcPollingEnabled = true;
                    mDeviceHost.enableDiscovery();
                }
            } else {
                if (force || mNfcPollingEnabled) {
                    Log.d(TAG, "NFC-C OFF");
                    mNfcPollingEnabled = false;
                    mDeviceHost.disableDiscovery();
                }
            }
        }
    }

    /** Disconnect any target if present */
    void maybeDisconnectTarget() {
        if (!isNfcEnabled()) {
            return;
        }
        Object[] objectsToDisconnect;
        synchronized (this) {
            Object[] objectValues = mObjectMap.values().toArray();
            // Copy the array before we clear mObjectMap,
            // just in case the HashMap values are backed by the same array
            objectsToDisconnect = Arrays.copyOf(objectValues, objectValues.length);
            mObjectMap.clear();
        }
        for (Object o : objectsToDisconnect) {
            if (DBG) Log.d(TAG, "disconnecting " + o.getClass().getName());
            if (o instanceof TagEndpoint) {
                // Disconnect from tags
                TagEndpoint tag = (TagEndpoint) o;
                tag.disconnect();
            }
        }
    }

    Object findObject(int key) {
        synchronized (this) {
            Object device = mObjectMap.get(key);
            if (device == null) {
                Log.w(TAG, "Handle not found");
            }
            return device;
        }
    }

    void registerTagObject(TagEndpoint tag) {
        synchronized (this) {
            mObjectMap.put(tag.getHandle(), tag);
        }
    }

    void unregisterObject(int handle) {
        synchronized (this) {
            mObjectMap.remove(handle);
        }
    }

    /** For use by code in this process */
    public LlcpSocket createLlcpSocket(int sap, int miu, int rw, int linearBufferLength)
            throws IOException, LlcpException {
        return mDeviceHost.createLlcpSocket(sap, miu, rw, linearBufferLength);
    }

    /** For use by code in this process */
    public LlcpServerSocket createLlcpServerSocket(int sap, String sn, int miu, int rw,
            int linearBufferLength) throws IOException, LlcpException {
        return mDeviceHost.createLlcpServerSocket(sap, sn, miu, rw, linearBufferLength);
    }

    public void sendMockNdefTag(NdefMessage msg) {
        sendMessage(MSG_MOCK_NDEF, msg);
    }

    void sendMessage(int what, Object obj) {
        Message msg = mHandler.obtainMessage();
        msg.what = what;
        msg.obj = obj;
        mHandler.sendMessage(msg);
    }

    final class NfcServiceHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_MOCK_NDEF: {
                    NdefMessage ndefMsg = (NdefMessage) msg.obj;
                    Bundle extras = new Bundle();
                    extras.putParcelable(Ndef.EXTRA_NDEF_MSG, ndefMsg);
                    extras.putInt(Ndef.EXTRA_NDEF_MAXLENGTH, 0);
                    extras.putInt(Ndef.EXTRA_NDEF_CARDSTATE, Ndef.NDEF_MODE_READ_ONLY);
                    extras.putInt(Ndef.EXTRA_NDEF_TYPE, Ndef.TYPE_OTHER);
                    Tag tag = Tag.createMockTag(new byte[] { 0x00 },
                            new int[] { TagTechnology.NDEF },
                            new Bundle[] { extras });
                    Log.d(TAG, "mock NDEF tag, starting corresponding activity");
                    Log.d(TAG, tag.toString());
                    boolean delivered = mNfcDispatcher.dispatchTag(tag,
                            new NdefMessage[] { ndefMsg });
                    if (delivered) {
                        playSound(SOUND_END);
                    } else {
                        playSound(SOUND_ERROR);
                    }
                    break;
                }

                case MSG_NDEF_TAG:
                    if (DBG) Log.d(TAG, "Tag detected, notifying applications");
                    TagEndpoint tag = (TagEndpoint) msg.obj;
                    playSound(SOUND_START);
                    NdefMessage[] ndefMsgs = tag.findAndReadNdef();

                    if (ndefMsgs != null) {
                        tag.startPresenceChecking();
                        dispatchTagEndpoint(tag, ndefMsgs);
                    } else {
                        if (tag.reconnect()) {
                            tag.startPresenceChecking();
                            dispatchTagEndpoint(tag, null);
                        } else {
                            tag.disconnect();
                            playSound(SOUND_ERROR);
                        }
                    }
                    break;

                case MSG_CARD_EMULATION:
                    if (DBG) Log.d(TAG, "Card Emulation message");
                    byte[] aid = (byte[]) msg.obj;
                    /* Send broadcast */
                    Intent aidIntent = new Intent();
                    aidIntent.setAction(ACTION_AID_SELECTED);
                    aidIntent.putExtra(EXTRA_AID, aid);
                    if (DBG) Log.d(TAG, "Broadcasting " + ACTION_AID_SELECTED);
                    sendSeBroadcast(aidIntent);
                    break;

                case MSG_SE_EMV_CARD_REMOVAL:
                    if (DBG) Log.d(TAG, "Card Removal message");
                    /* Send broadcast */
                    Intent cardRemovalIntent = new Intent();
                    cardRemovalIntent.setAction(ACTION_EMV_CARD_REMOVAL);
                    if (DBG) Log.d(TAG, "Broadcasting " + ACTION_EMV_CARD_REMOVAL);
                    sendSeBroadcast(cardRemovalIntent);
                    break;

                case MSG_SE_APDU_RECEIVED:
                    if (DBG) Log.d(TAG, "APDU Received message");
                    byte[] apduBytes = (byte[]) msg.obj;
                    /* Send broadcast */
                    Intent apduReceivedIntent = new Intent();
                    apduReceivedIntent.setAction(ACTION_APDU_RECEIVED);
                    if (apduBytes != null && apduBytes.length > 0) {
                        apduReceivedIntent.putExtra(EXTRA_APDU_BYTES, apduBytes);
                    }
                    if (DBG) Log.d(TAG, "Broadcasting " + ACTION_APDU_RECEIVED);
                    sendSeBroadcast(apduReceivedIntent);
                    break;

                case MSG_SE_MIFARE_ACCESS:
                    if (DBG) Log.d(TAG, "MIFARE access message");
                    /* Send broadcast */
                    byte[] mifareCmd = (byte[]) msg.obj;
                    Intent mifareAccessIntent = new Intent();
                    mifareAccessIntent.setAction(ACTION_MIFARE_ACCESS_DETECTED);
                    if (mifareCmd != null && mifareCmd.length > 1) {
                        int mifareBlock = mifareCmd[1] & 0xff;
                        if (DBG) Log.d(TAG, "Mifare Block=" + mifareBlock);
                        mifareAccessIntent.putExtra(EXTRA_MIFARE_BLOCK, mifareBlock);
                    }
                    if (DBG) Log.d(TAG, "Broadcasting " + ACTION_MIFARE_ACCESS_DETECTED);
                    sendSeBroadcast(mifareAccessIntent);
                    break;

                case MSG_TARGET_DESELECTED:
                    /* Broadcast Intent Target Deselected */
                    if (DBG) Log.d(TAG, "Target Deselected");
                    Intent intent = new Intent();
                    intent.setAction(NativeNfcManager.INTERNAL_TARGET_DESELECTED_ACTION);
                    if (DBG) Log.d(TAG, "Broadcasting Intent");
                    mContext.sendOrderedBroadcast(intent, NFC_PERM);
                    break;

                case MSG_SE_FIELD_ACTIVATED: {
                    if (DBG) Log.d(TAG, "SE FIELD ACTIVATED");
                    Intent eventFieldOnIntent = new Intent();
                    eventFieldOnIntent.setAction(ACTION_RF_FIELD_ON_DETECTED);
                    sendSeBroadcast(eventFieldOnIntent);
                    break;
                }

                case MSG_SE_FIELD_DEACTIVATED: {
                    if (DBG) Log.d(TAG, "SE FIELD DEACTIVATED");
                    Intent eventFieldOffIntent = new Intent();
                    eventFieldOffIntent.setAction(ACTION_RF_FIELD_OFF_DETECTED);
                    sendSeBroadcast(eventFieldOffIntent);
                    break;
                }

                default:
                    Log.e(TAG, "Unknown message received");
                    break;
            }
        }

        private void sendSeBroadcast(Intent intent) {
            PackageManager pm = getPackageManager();
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);

            // Resume app switches so the receivers can start activites without delay
            mNfcDispatcher.resumeAppSwitches();
        }

        private void dispatchTagEndpoint(TagEndpoint tagEndpoint, NdefMessage[] msgs) {
            Log.d(TAG, "dispatchTagEndpoint");
            Tag tag = new Tag(tagEndpoint.getUid(), tagEndpoint.getTechList(),
                    tagEndpoint.getTechExtras(), tagEndpoint.getHandle(), mNfcTagService);
            registerTagObject(tagEndpoint);
            if (!mNfcDispatcher.dispatchTag(tag, msgs)) {
                unregisterObject(tagEndpoint.getHandle());
                playSound(SOUND_ERROR);
            } else {
                playSound(SOUND_END);
            }
        }
    }

    private NfcServiceHandler mHandler = new NfcServiceHandler();

    class ApplyRoutingTask extends AsyncTask<Integer, Void, Void> {
        @Override
        protected Void doInBackground(Integer... params) {
            synchronized (NfcService.this) {
                if (params == null || params.length != 1) {
                    // force apply current routing
                    applyRouting(true);
                    return null;
                }
                mScreenState = params[0].intValue();
                return null;
            }
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(
                    NativeNfcManager.INTERNAL_TARGET_DESELECTED_ACTION)) {
                // Perform applyRouting() in AsyncTask to serialize blocking calls
                new ApplyRoutingTask().execute();
            } else {
                // Perform applyRouting() in AsyncTask to serialize blocking calls
                int screenState = SCREEN_STATE_OFF;
                if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                    screenState = SCREEN_STATE_OFF;
                } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
                    screenState = mKeyguard.isKeyguardLocked() ?
                            SCREEN_STATE_ON_LOCKED : SCREEN_STATE_ON_UNLOCKED;
                } else if (action.equals(Intent.ACTION_USER_PRESENT)) {
                    screenState = SCREEN_STATE_ON_UNLOCKED;
                }
                new ApplyRoutingTask().execute(Integer.valueOf(screenState));
            }
        }
    };

    /** Returns true if airplane mode is currently on */
    boolean isAirplaneModeOn() {
        return false;
    }

    /** for debugging only - no i18n */
    static String stateToString(int state) {
        switch (state) {
            case NfcAdapter.STATE_OFF:
                return "off";
            case NfcAdapter.STATE_TURNING_ON:
                return "turning on";
            case NfcAdapter.STATE_ON:
                return "on";
            case NfcAdapter.STATE_TURNING_OFF:
                return "turning off";
            default:
                return "<error>";
        }
    }

    /** For debugging only - no i18n */
    static String screenStateToString(int screenState) {
        switch (screenState) {
            case SCREEN_STATE_OFF:
                return "OFF";
            case SCREEN_STATE_ON_LOCKED:
                return "ON_LOCKED";
            case SCREEN_STATE_ON_UNLOCKED:
                return "ON_UNLOCKED";
            default:
                return "UNKNOWN";
        }
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump nfc from from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " without permission " + android.Manifest.permission.DUMP);
            return;
        }

        synchronized (this) {
            pw.println("mState=" + stateToString(mState));
            pw.println("mScreenState=" + screenStateToString(mScreenState));
            pw.println("mNfcPollingEnabled=" + mNfcPollingEnabled);
            pw.println("mNfceeRouteEnabled=" + mNfceeRouteEnabled);
            pw.println("mIsAirplaneSensitive=" + mIsAirplaneSensitive);
            pw.println("mIsAirplaneToggleable=" + mIsAirplaneToggleable);
        }
    }
}
