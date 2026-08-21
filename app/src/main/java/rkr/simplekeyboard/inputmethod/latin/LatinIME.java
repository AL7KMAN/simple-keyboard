/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.inputmethod.latin;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Debug;
import android.os.Message;
import android.os.SystemClock;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.InputConnection;

import com.android.inputmethod.accessibility.AccessibilityUtils;
import com.android.inputmethod.compat.CompatUtils;
import com.android.inputmethod.compat.EditorInfoCompatUtils;
import com.android.inputmethod.compat.InputConnectionCompatUtils;
import com.android.inputmethod.compat.InputMethodManagerCompatWrapper;
import com.android.inputmethod.compat.InputMethodServiceCompatWrapper;
import com.android.inputmethod.compat.InputTypeCompatUtils;
import com.android.inputmethod.compat.SuggestionSpanUtils;
import com.android.inputmethod.compat.VibratorCompatWrapper;
import com.android.inputmethod.deprecated.LanguageSwitcherProxy;
import com.android.inputmethod.deprecated.VoiceProxy;
import com.android.inputmethod.keyboard.Key;
import com.android.inputmethod.keyboard.Keyboard;
import com.android.inputmethod.keyboard.KeyboardActionListener;
import com.android.inputmethod.keyboard.KeyboardSwitcher;
import com.android.inputmethod.keyboard.KeyboardView;
import com.android.inputmethod.keyboard.LatinKeyboard;
import com.android.inputmethod.keyboard.LatinKeyboardView;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Locale;

/**
 * Input method implementation for Qwerty'ish keyboard.
 */
public class LatinIME extends InputMethodServiceCompatWrapper implements KeyboardActionListener,
        SuggestionsView.Listener {
    private static final String TAG = LatinIME.class.getSimpleName();
    // private static final String TAG = "PENGH";
    private static final boolean PERF_DEBUG = false;
    private static final boolean TRACE = false;
    private static boolean DEBUG;

    /**
     * The private IME option used to indicate that no microphone should be
     * shown for a given text field. For instance, this is specified by the
     * search dialog when the dialog is already showing a voice search button.
     *
     * @deprecated Use {@link LatinIME#IME_OPTION_NO_MICROPHONE} with package name prefixed.
     */
    @SuppressWarnings("dep-ann")
    public static final String IME_OPTION_NO_MICROPHONE_COMPAT = "nm";

    /**
     * The private IME option used to indicate that no microphone should be
     * shown for a given text field. For instance, this is specified by the
     * search dialog when the dialog is already showing a voice search button.
     */
    public static final String IME_OPTION_NO_MICROPHONE = "noMicrophoneKey";

    /**
     * The private IME option used to indicate that no settings key should be
     * shown for a given text field.
     */
    public static final String IME_OPTION_NO_SETTINGS_KEY = "noSettingsKey";

    /**
     * The private IME option used to indicate that the given text field needs
     * ASCII code points input.
     */
    public static final String IME_OPTION_FORCE_ASCII = "forceAscii";

    /**
     * The subtype extra value used to indicate that the subtype keyboard layout is capable for
     * typing ASCII characters.
     */
    public static final String SUBTYPE_EXTRA_VALUE_ASCII_CAPABLE = "AsciiCapable";

    /**
     * The subtype extra value used to indicate that the subtype keyboard layout supports touch
     * position correction.
     */
    public static final String SUBTYPE_EXTRA_VALUE_SUPPORT_TOUCH_POSITION_CORRECTION =
            "SupportTouchPositionCorrection";
    /**
     * The subtype extra value used to indicate that the subtype keyboard layout should be loaded
     * from the specified locale.
     */
    public static final String SUBTYPE_EXTRA_VALUE_KEYBOARD_LOCALE = "KeyboardLocale";

    private static final int EXTENDED_TOUCHABLE_REGION_HEIGHT = 100;

    // How many continuous deletes at which to start deleting at a higher speed.
    private static final int DELETE_ACCELERATE_AT = 20;
    // Key events coming any faster than this are long-presses.
    private static final int QUICK_PRESS = 200;

    private static final int PENDING_IMS_CALLBACK_DURATION = 800;

    /**
     * The name of the scheme used by the Package Manager to warn of a new package installation,
     * replacement or removal.
     */
    private static final String SCHEME_PACKAGE = "package";

    private int mSuggestionVisibility;
    private static final int SUGGESTION_VISIBILILTY_SHOW_VALUE
            = R.string.prefs_suggestion_visibility_show_value;
    private static final int SUGGESTION_VISIBILILTY_SHOW_ONLY_PORTRAIT_VALUE
            = R.string.prefs_suggestion_visibility_show_only_portrait_value;
    private static final int SUGGESTION_VISIBILILTY_HIDE_VALUE
            = R.string.prefs_suggestion_visibility_hide_value;

    private static final int[] SUGGESTION_VISIBILITY_VALUE_ARRAY = new int[] {
        SUGGESTION_VISIBILILTY_SHOW_VALUE,
        SUGGESTION_VISIBILILTY_SHOW_ONLY_PORTRAIT_VALUE,
        SUGGESTION_VISIBILILTY_HIDE_VALUE
    };

    private Settings.Values mSettingsValues;

    private View mExtractArea;
    private View mKeyPreviewBackingView;
    private View mSuggestionsContainer;
    private SuggestionsView mSuggestionsView;
    private Suggest mSuggest;
    private CompletionInfo[] mApplicationSpecifiedCompletions;

    private InputMethodManagerCompatWrapper mImm;
    private Resources mResources;
    private SharedPreferences mPrefs;
    private String mInputMethodId;
    private KeyboardSwitcher mKeyboardSwitcher;
    private SubtypeSwitcher mSubtypeSwitcher;
    private VoiceProxy mVoiceProxy;

    private UserDictionary mUserDictionary;
    private UserBigramDictionary mUserBigramDictionary;
    private UserUnigramDictionary mUserUnigramDictionary;
    private boolean mIsUserDictionaryAvaliable;

    // TODO: Create an inner class to group options and pseudo-options to improve readability.
    // These variables are initialized according to the {@link EditorInfo#inputType}.
    private boolean mShouldInsertMagicSpace;
    private boolean mInputTypeNoAutoCorrect;
    private boolean mIsSettingsSuggestionStripOn;
    private boolean mApplicationSpecifiedCompletionOn;

    private final StringBuilder mComposingStringBuilder = new StringBuilder();
    private WordComposer mWordComposer = new WordComposer();
    private CharSequence mBestWord;
    private boolean mHasUncommittedTypedChars;
    // Magic space: a space that should disappear on space/apostrophe insertion, move after the
    // punctuation on punctuation insertion, and become a real space on alpha char insertion.
    private boolean mJustAddedMagicSpace; // This indicates whether the last char is a magic space.
    // This indicates whether the last keypress resulted in processing of double space replacement
    // with period-space.
    private boolean mJustReplacedDoubleSpace;

    private int mCorrectionMode;
    private int mCommittedLength;
    // Keep track of the last selection range to decide if we need to show word alternatives
    private int mLastSelectionStart;
    private int mLastSelectionEnd;

    // Whether we are expecting an onUpdateSelection event to fire. If it does when we don't
    // "expect" it, it means the user actually moved the cursor.
    private boolean mExpectingUpdateSelection;
    private int mDeleteCount;
    private long mLastKeyTime;

    private AudioManager mAudioManager;
    private float mFxVolume = -1.0f; // default volume
    private boolean mSilentModeOn; // System-wide current configuration

    private VibratorCompatWrapper mVibrator;
    private long mKeypressVibrationDuration = -1;

    // TODO: Move this flag to VoiceProxy
    private boolean mConfigurationChanging;

    // Member variables for remembering the current device orientation.
    private int mDisplayOrientation;

    // Object for reacting to adding/removing a dictionary pack.
    private BroadcastReceiver mDictionaryPackInstallReceiver =
            new DictionaryPackInstallBroadcastReceiver(this);

    // Keeps track of most recently inserted text (multi-character key) for reverting
    private CharSequence mEnteredText;

    private final ComposingStateManager mComposingStateManager =
            ComposingStateManager.getInstance();

    public final UIHandler mHandler = new UIHandler(this);

    public static class UIHandler extends StaticInnerHandlerWrapper<LatinIME> {
        private static final int MSG_UPDATE_SUGGESTIONS = 0;
        private static final int MSG_UPDATE_SHIFT_STATE = 1;
        private static final int MSG_VOICE_RESULTS = 2;
        private static final int MSG_FADEOUT_LANGUAGE_ON_SPACEBAR = 3;
        private static final int MSG_DISMISS_LANGUAGE_ON_SPACEBAR = 4;
        private static final int MSG_SPACE_TYPED = 5;
        private static final int MSG_SET_BIGRAM_PREDICTIONS = 6;
        private static final int MSG_PENDING_IMS_CALLBACK = 7;

        public UIHandler(LatinIME outerInstance) {
            super(outerInstance);
        }

        @Override
        public void handleMessage(Message msg) {
            final LatinIME latinIme = getOuterInstance();
            final KeyboardSwitcher switcher = latinIme.mKeyboardSwitcher;
            final LatinKeyboardView inputView = switcher.getKeyboardView();
            switch (msg.what) {
            case MSG_UPDATE_SUGGESTIONS:
                latinIme.updateSuggestions();
                break;
            case MSG_UPDATE_SHIFT_STATE:
                switcher.updateShiftState();
                break;
            case MSG_SET_BIGRAM_PREDICTIONS:
                latinIme.updateBigramPredictions();
                break;
            case MSG_VOICE_RESULTS:
                latinIme.mVoiceProxy.handleVoiceResults(latinIme.preferCapitalization()
                        || (switcher.isAlphabetMode() && switcher.isShiftedOrShiftLocked()));
                break;
            case MSG_FADEOUT_LANGUAGE_ON_SPACEBAR:
                if (inputView != null) {
                    inputView.setSpacebarTextFadeFactor(
                            (1.0f + latinIme.mSettingsValues.
                                    mFinalFadeoutFactorOfLanguageOnSpacebar) / 2,
                            (LatinKeyboard)msg.obj);
                }
                sendMessageDelayed(obtainMessage(MSG_DISMISS_LANGUAGE_ON_SPACEBAR, msg.obj),
                        latinIme.mSettingsValues.mDurationOfFadeoutLanguageOnSpacebar);
                break;
            case MSG_DISMISS_LANGUAGE_ON_SPACEBAR:
                if (inputView != null) {
                    inputView.setSpacebarTextFadeFactor(
                            latinIme.mSettingsValues.mFinalFadeoutFactorOfLanguageOnSpacebar,
                            (LatinKeyboard)msg.obj);
                }
                break;
            }
        }

        public void postUpdateSuggestions() {
            removeMessages(MSG_UPDATE_SUGGESTIONS);
            sendMessageDelayed(obtainMessage(MSG_UPDATE_SUGGESTIONS),
                    getOuterInstance().mSettingsValues.mDelayUpdateSuggestions);
        }

        public void cancelUpdateSuggestions() {
            removeMessages(MSG_UPDATE_SUGGESTIONS);
        }

        public boolean hasPendingUpdateSuggestions() {
            return hasMessages(MSG_UPDATE_SUGGESTIONS);
        }

        public void postUpdateShiftKeyState() {
            removeMessages(MSG_UPDATE_SHIFT_STATE);
            sendMessageDelayed(obtainMessage(MSG_UPDATE_SHIFT_STATE),
                    getOuterInstance().mSettingsValues.mDelayUpdateShiftState);
        }

        public void cancelUpdateShiftState() {
            removeMessages(MSG_UPDATE_SHIFT_STATE);
        }

        public void postUpdateBigramPredictions() {
            removeMessages(MSG_SET_BIGRAM_PREDICTIONS);
            sendMessageDelayed(obtainMessage(MSG_SET_BIGRAM_PREDICTIONS),
                    getOuterInstance().mSettingsValues.mDelayUpdateSuggestions);
        }

        public void cancelUpdateBigramPredictions() {
            removeMessages(MSG_SET_BIGRAM_PREDICTIONS);
        }

        public void updateVoiceResults() {
            sendMessage(obtainMessage(MSG_VOICE_RESULTS));
        }

        public void startDisplayLanguageOnSpacebar(boolean localeChanged) {
            final LatinIME latinIme = getOuterInstance();
            removeMessages(MSG_FADEOUT_LANGUAGE_ON_SPACEBAR);
            removeMessages(MSG_DISMISS_LANGUAGE_ON_SPACEBAR);
            final LatinKeyboardView inputView = latinIme.mKeyboardSwitcher.getKeyboardView();
            if (inputView != null) {
                final LatinKeyboard keyboard = latinIme.mKeyboardSwitcher.getLatinKeyboard();
                // The language is always displayed when the delay is negative.
                final boolean needsToDisplayLanguage = localeChanged
                        || latinIme.mSettingsValues.mDelayBeforeFadeoutLanguageOnSpacebar < 0;
                // The language is never displayed when the delay is zero.
                if (latinIme.mSettingsValues.mDelayBeforeFadeoutLanguageOnSpacebar != 0) {
                    inputView.setSpacebarTextFadeFactor(needsToDisplayLanguage ? 1.0f
                            : latinIme.mSettingsValues.mFinalFadeoutFactorOfLanguageOnSpacebar,
                            keyboard);
                }
                // The fadeout animation will start when the delay is positive.
                if (localeChanged
                        && latinIme.mSettingsValues.mDelayBeforeFadeoutLanguageOnSpacebar > 0) {
                    sendMessageDelayed(obtainMessage(MSG_FADEOUT_LANGUAGE_ON_SPACEBAR, keyboard),
                            latinIme.mSettingsValues.mDelayBeforeFadeoutLanguageOnSpacebar);
                }
            }
        }

        public void startDoubleSpacesTimer() {
            removeMessages(MSG_SPACE_TYPED);
            sendMessageDelayed(obtainMessage(MSG_SPACE_TYPED),
                    getOuterInstance().mSettingsValues.mDoubleSpacesTurnIntoPeriodTimeout);
        }

        public void cancelDoubleSpacesTimer() {
            removeMessages(MSG_SPACE_TYPED);
        }

        public boolean isAcceptingDoubleSpaces() {
            return hasMessages(MSG_SPACE_TYPED);
        }

        // Working variables for the following methods.
        private boolean mIsOrientationChanging;
        private boolean mPendingSuccesiveImsCallback;
        private boolean mHasPendingStartInput;
        private boolean mHasPendingFinishInputView;
        private boolean mHasPendingFinishInput;

        public void startOrientationChanging() {
            mIsOrientationChanging = true;
            final LatinIME latinIme = getOuterInstance();
            latinIme.mKeyboardSwitcher.saveKeyboardState();
        }

        private void resetPendingImsCallback() {
            mHasPendingFinishInputView = false;
            mHasPendingFinishInput = false;
            mHasPendingStartInput = false;
        }

        private void executePendingImsCallback(LatinIME latinIme, EditorInfo attribute,
                boolean restarting) {
            if (mHasPendingFinishInputView)
                latinIme.onFinishInputViewInternal(mHasPendingFinishInput);
            if (mHasPendingFinishInput)
                latinIme.onFinishInputInternal();
            if (mHasPendingStartInput)
                latinIme.onStartInputInternal(attribute, restarting);
            resetPendingImsCallback();
        }

        public void onStartInput(EditorInfo attribute, boolean restarting) {
             Log.d(TAG, "onStartInput");
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the second onStartInput after orientation changed.
                mHasPendingStartInput = true;
            } else {
                if (mIsOrientationChanging && restarting) {
                    // This is the first onStartInput after orientation changed.
                    mIsOrientationChanging = false;
                    mPendingSuccesiveImsCallback = true;
                }
                final LatinIME latinIme = getOuterInstance();
                executePendingImsCallback(latinIme, attribute, restarting);
                latinIme.onStartInputInternal(attribute, restarting);
            }
        }

        public void onStartInputView(EditorInfo attribute, boolean restarting) {
             if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                 // Typically this is the second onStartInputView after orientation changed.
                 resetPendingImsCallback();
             } else {
                 if (mPendingSuccesiveImsCallback) {
                     // This is the first onStartInputView after orientation changed.
                     mPendingSuccesiveImsCallback = false;
                     resetPendingImsCallback();
                     sendMessageDelayed(obtainMessage(MSG_PENDING_IMS_CALLBACK),
                             PENDING_IMS_CALLBACK_DURATION);
                 }
                 final LatinIME latinIme = getOuterInstance();
                 executePendingImsCallback(latinIme, attribute, restarting);
                 latinIme.onStartInputViewInternal(attribute, restarting);
             }
        }

        public void onFinishInputView(boolean finishingInput) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the first onFinishInputView after orientation changed.
                mHasPendingFinishInputView = true;
            } else {
                final LatinIME latinIme = getOuterInstance();
                latinIme.onFinishInputViewInternal(finishingInput);
            }
        }

        public void onFinishInput() {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the first onFinishInput after orientation changed.
                mHasPendingFinishInput = true;
            } else {
                final LatinIME latinIme = getOuterInstance();
                executePendingImsCallback(latinIme, null, false);
                latinIme.onFinishInputInternal();
            }
        }
    }

    @Override
    public void onCreate() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        mPrefs = prefs;
        LatinImeLogger.init(this, prefs);
        LanguageSwitcherProxy.init(this, prefs);
        InputMethodManagerCompatWrapper.init(this);
        SubtypeSwitcher.init(this);
        KeyboardSwitcher.init(this, prefs);
        AccessibilityUtils.init(this, prefs);

        super.onCreate();

        mImm = InputMethodManagerCompatWrapper.getInstance();
        mInputMethodId = Utils.getInputMethodId(mImm, getPackageName());
        mSubtypeSwitcher = SubtypeSwitcher.getInstance();
        mKeyboardSwitcher = KeyboardSwitcher.getInstance();
        mVibrator = VibratorCompatWrapper.getInstance(this);
        DEBUG = LatinImeLogger.sDBG;

        final Resources res = getResources();
        mResources = res;

        loadSettings();

        Utils.GCUtils.getInstance().reset();
        boolean tryGC = true;
        for (int i = 0; i < Utils.GCUtils.GC_TRY_LOOP_MAX && tryGC; ++i) {
            try {
                initSuggest();
                tryGC = false;
            } catch (OutOfMemoryError e) {
                tryGC = Utils.GCUtils.getInstance().tryGCOrWait("InitSuggest", e);
            }
        }

        mDisplayOrientation = res.getConfiguration().orientation;

        // Register to receive ringer mode change and network state change.
        // Also receive installation and removal of a dictionary pack.
        final IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(mReceiver, filter);
        mVoiceProxy = VoiceProxy.init(this, prefs, mHandler);

        final IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addDataScheme(SCHEME_PACKAGE);
        registerReceiver(mDictionaryPackInstallReceiver, packageFilter);

        final IntentFilter newDictFilter = new IntentFilter();
        newDictFilter.addAction(
                DictionaryPackInstallBroadcastReceiver.NEW_DICTIONARY_INTENT_ACTION);
        registerReceiver(mDictionaryPackInstallReceiver, newDictFilter);
    }

    // Has to be package-visible for unit tests
    /* package */ void loadSettings() {
        if (null == mPrefs) mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (null == mSubtypeSwitcher) mSubtypeSwitcher = SubtypeSwitcher.getInstance();
        mSettingsValues = new Settings.Values(mPrefs, this, mSubtypeSwitcher.getInputLocaleStr());
        resetContactsDictionary(null == mSuggest ? null : mSuggest.getContactsDictionary());
        updateSoundEffectVolume();
        updateKeypressVibrationDuration();
    }

    private void initSuggest() {
        final String localeStr = mSubtypeSwitcher.getInputLocaleStr();
        final Locale keyboardLocale = LocaleUtils.constructLocaleFromString(localeStr);

        final Resources res = mResources;
        final Locale savedLocale = LocaleUtils.setSystemLocale(res, keyboardLocale);
        final ContactsDictionary oldContactsDictionary;
        if (mSuggest != null) {
            oldContactsDictionary = mSuggest.getContactsDictionary();
            mSuggest.close();
        } else {
            oldContactsDictionary = null;
        }

        int mainDicResId = Utils.getMainDictionaryResourceId(res);
        mSuggest = new Suggest(this, mainDicResId, keyboardLocale);
        if (mSettingsValues.mAutoCorrectEnabled) {
            mSuggest.setAutoCorrectionThreshold(mSettingsValues.mAutoCorrectionThreshold);
        }

        mUserDictionary = new UserDictionary(this, localeStr);
        mSuggest.setUserDictionary(mUserDictionary);
        mIsUserDictionaryAvaliable = mUserDictionary.isEnabled();

        resetContactsDictionary(oldContactsDictionary);

        mUserUnigramDictionary
                = new UserUnigramDictionary(this, this, localeStr, Suggest.DIC_USER_UNIGRAM);
        mSuggest.setUserUnigramDictionary(mUserUnigramDictionary);

        mUserBigramDictionary
                = new UserBigramDictionary(this, this, localeStr, Suggest.DIC_USER_BIGRAM);
        mSuggest.setUserBigramDictionary(mUserBigramDictionary);

        updateCorrectionMode();

        LocaleUtils.setSystemLocale(res, savedLocale);
    }

    /**
     * Resets the contacts dictionary in mSuggest according to the user settings.
     *
     * This method takes an optional contacts dictionary to use. Since the contacts dictionary
     * does not depend on the locale, it can be reused across different instances of Suggest.
     * The dictionary will also be opened or closed as necessary depending on the settings.
     *
     * @param oldContactsDictionary an optional dictionary to use, or null
     */
    private void resetContactsDictionary(final ContactsDictionary oldContactsDictionary) {
        final boolean shouldSetDictionary = (null != mSuggest && mSettingsValues.mUseContactsDict);

        final ContactsDictionary dictionaryToUse;
        if (!shouldSetDictionary) {
            // Make sure the dictionary is closed. If it is already closed, this is a no-op,
            // so it's safe to call it anyways.
            if (null != oldContactsDictionary) oldContactsDictionary.close();
            dictionaryToUse = null;
        } else if (null != oldContactsDictionary) {
            // Make sure the old contacts dictionary is opened. If it is already open, this is a
            // no-op, so it's safe to call it anyways.
            oldContactsDictionary.reopen(this);
            dictionaryToUse = oldContactsDictionary;
        } else {
            dictionaryToUse = new ContactsDictionary(this, Suggest.DIC_CONTACTS);
        }

        if (null != mSuggest) {
            mSuggest.setContactsDictionary(dictionaryToUse);
        }
    }

    /* package private */ void resetSuggestMainDict() {
        final String localeStr = mSubtypeSwitcher.getInputLocaleStr();
        final Locale keyboardLocale = LocaleUtils.constructLocaleFromString(localeStr);
        int mainDicResId = Utils.getMainDictionaryResourceId(mResources);
        mSuggest.resetMainDict(this, mainDicResId, keyboardLocale);
    }

    @Override
    public void onDestroy() {
        if (mSuggest != null) {
            mSuggest.close();
            mSuggest = null;
        }
        unregisterReceiver(mReceiver);
        unregisterReceiver(mDictionaryPackInstallReceiver);
        mVoiceProxy.destroy();
        LatinImeLogger.commit();
        LatinImeLogger.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration conf) {
        mSubtypeSwitcher.onConfigurationChanged(conf);
        mComposingStateManager.onFinishComposingText();
        // If orientation changed while predicting, commit the change
        if (mDisplayOrientation != conf.orientation) {
            mDisplayOrientation = conf.orientation;
            mHandler.startOrientationChanging();
            final InputConnection ic = getCurrentInputConnection();
            commitTyped(ic);
            if (ic != null) ic.finishComposingText(); // For voice input
            if (isShowingOptionDialog())
                mOptionsDialog.dismiss();
        }

        mConfigurationChanging = true;
        super.onConfigurationChanged(conf);
        mVoiceProxy.onConfigurationChanged(conf);
        mConfigurationChanging = false;

        // This will work only when the subtype is not supported.
        LanguageSwitcherProxy.onConfigurationChanged(conf);
    }

    @Override
    public View onCreateInputView() {
        return mKeyboardSwitcher.onCreateInputView();
    }

    @Override
    public void setInputView(View view) {
        super.setInputView(view);
        mExtractArea = getWindow().getWindow().getDecorView()
                .findViewById(android.R.id.extractArea);
        mKeyPreviewBackingView = view.findViewById(R.id.key_preview_backing);
        mSuggestionsContainer = view.findViewById(R.id.suggestions_container);
        mSuggestionsView = (SuggestionsView) view.findViewById(R.id.suggestions_view);
        if (mSuggestionsView != null)
            mSuggestionsView.setListener(this, view);
        if (LatinImeLogger.sVISUALDEBUG) {
            mKeyPreviewBackingView.setBackgroundColor(0x10FF0000);
        }
    }

    @Override
    public void setCandidatesView(View view) {
        // To ensure that CandidatesView will never be set.
        return;
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        mHandler.onStartInput(attribute, restarting);
    }

    @Override
    public void onStartInputView(EditorInfo attribute, boolean restarting) {
        mHandler.onStartInputView(attribute, restarting);
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        mHandler.onFinishInputView(finishingInput);
    }

    @Override
    public void onFinishInput() {
        mHandler.onFinishInput();
    }

    private void onStartInputInternal(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
    }

    private void onStartInputViewInternal(EditorInfo attribute, boolean restarting) {
        super.onStartInputView(attribute, restarting);
        final KeyboardSwitcher switcher = mKeyboardSwitcher;
        LatinKeyboardView inputView = switcher.getKeyboardView();

        if (DEBUG) {
            Log.d(TAG, "onStartInputView: attribute:" + ((attribute == null) ? "none"
                    : String.format("inputType=0x%08x imeOptions=0x%08x",
                            attribute.inputType, attribute.imeOptions)));
        }
        // In landscape mode, this method gets called without the input view being created.
        if (inputView == null) {
            return;
        }

        // Forward this event to the accessibility utilities, if enabled.
        final AccessibilityUtils accessUtils = AccessibilityUtils.getInstance();
        if (accessUtils.isTouchExplorationEnabled()) {
            accessUtils.onStartInputViewInternal(attribute, restarting);
        }

        mSubtypeSwitcher.updateParametersOnStartInputView();

        TextEntryState.reset();

        // Most such things we decide below in initializeInputAttributesAndGetMode, but we need to
        // know now whether this is a password text field, because we need to know now whether we
        // want to enable the voice button.
        final VoiceProxy voiceIme = mVoiceProxy;
        final int inputType = (attribute != null) ? attribute.inputType : 0;
        voiceIme.resetVoiceStates(InputTypeCompatUtils.isPasswordInputType(inputType)
                || InputTypeCompatUtils.isVisiblePasswordInputType(inputType));

        initializeInputAttributes(attribute);

        inputView.closing();
        mEnteredText = null;
        mComposingStringBuilder.setLength(0);
        mHasUncommittedTypedChars = false;
        mDeleteCount = 0;
        mJustAddedMagicSpace = false;
        mJustReplacedDoubleSpace = false;

        loadSettings();
        updateCorrectionMode();
        updateSuggestionVisibility(mPrefs, mResources);

        if (mSuggest != null && mSettingsValues.mAutoCorrectEnabled) {
            mSuggest.setAutoCorrectionThreshold(mSettingsValues.mAutoCorrectionThreshold);
         }
        mVoiceProxy.loadSettings(attribute, mPrefs);
        // This will work only when the subtype is not supported.
        LanguageSwitcherProxy.loadSettings();

        if (mSubtypeSwitcher.isKeyboardMode()) {
            switcher.loadKeyboard(attribute, mSettingsValues);
        }

        if (mSuggestionsView != null)
            mSuggestionsView.clear();
        // The EditorInfo might have a flag that affects fullscreen mode.
        updateFullscreenMode();
        setSuggestionStripShownInternal(
                isSuggestionsStripVisible(), /* needsInputViewShown */ false);
        // Delay updating suggestions because keyboard input view may not be shown at this point.
        mHandler.postUpdateSuggestions();

        inputView.setKeyPreviewPopupEnabled(mSettingsValues.mKeyPreviewPopupOn,
                mSettingsValues.mKeyPreviewPopupDismissDelay);
        inputView.setProximityCorrectionEnabled(true);

        voiceIme.onStartInputView(inputView.getWindowToken());

        if (TRACE) Debug.startMethodTracing("/data/trace/latinime");
    }

    private void initializeInputAttributes(EditorInfo attribute) {
        if (attribute == null)
            return;
        final int inputType = attribute.inputType;
        if (inputType == InputType.TYPE_NULL) {
            // TODO: We should honor TYPE_NULL specification.
            Log.i(TAG, "InputType.TYPE_NULL is specified");
        }
        final int inputClass = inputType & InputType.TYPE_MASK_CLASS;
        final int variation = inputType & InputType.TYPE_MASK_VARIATION;
        if (inputClass == 0) {
            Log.w(TAG, String.format("Unexpected input class: inputType=0x%08x imeOptions=0x%08x",
                    inputType, attribute.imeOptions));
        }

        mShouldInsertMagicSpace = false;
        mInputTypeNoAutoCorrect = false;
        mIsSettingsSuggestionStripOn = false;
        mApplicationSpecifiedCompletionOn = false;
        mApplicationSpecifiedCompletions = null;

        if (inputClass == InputType.TYPE_CLASS_TEXT) {
            mIsSettingsSuggestionStripOn = true;
            // Make sure that passwords are not displayed in {@link SuggestionsView}.
            if (InputTypeCompatUtils.isPasswordInputType(inputType)
                    || InputTypeCompatUtils.isVisiblePasswordInputType(inputType)) {
                mIsSettingsSuggestionStripOn = false;
            }
            if (InputTypeCompatUtils.isEmailVariation(variation)
                    || variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME) {
                mShouldInsertMagicSpace = false;
            } else {
                mShouldInsertMagicSpace = true;
            }
            if (InputTypeCompatUtils.isEmailVariation(variation)) {
                mIsSettingsSuggestionStripOn = false;
            } else if (variation == InputType.TYPE_TEXT_VARIATION_URI) {
                mIsSettingsSuggestionStripOn = false;
            } else if (variation == InputType.TYPE_TEXT_VARIATION_FILTER) {
                mIsSettingsSuggestionStripOn = false;
            } else if (variation == InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT) {
                // If it's a browser edit field and auto correct is not ON explicitly, then
                // disable auto correction, but keep suggestions on.
                if ((inputType & InputType.TYPE_TEXT_FLAG_AUTO_CORRECT) == 0) {
                    mInputTypeNoAutoCorrect = true;
                }
            }

            // If NO_SUGGESTIONS is set, don't do prediction.
            if ((inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) {
                mIsSettingsSuggestionStripOn = false;
                mInputTypeNoAutoCorrect = true;
            }
            // If it's not multiline and the autoCorrect flag is not set, then don't correct
            if ((inputType & InputType.TYPE_TEXT_FLAG_AUTO_CORRECT) == 0
                    && (inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0) {
                mInputTypeNoAutoCorrect = true;
            }
            if ((inputType & InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE) != 0) {
                mIsSettingsSuggestionStripOn = false;
                mApplicationSpecifiedCompletionOn = isFullscreenMode();
            }
        }
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        KeyboardView inputView = mKeyboardSwitcher.getKeyboardView();
        if (inputView != null) inputView.closing();
    }

    private void onFinishInputInternal() {
        super.onFinishInput();

        LatinImeLogger.commit();

        mVoiceProxy.flushVoiceInputLogs(mConfigurationChanging);

        KeyboardView inputView = mKeyboardSwitcher.getKeyboardView();
        if (inputView != null) inputView.closing();
        if (mUserUnigramDictionary != null) mUserUnigramDictionary.flushPendingWrites();
        if (mUserBigramDictionary != null) mUserBigramDictionary.flushPendingWrites();
    }

    private void onFinishInputViewInternal(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        mKeyboardSwitcher.onFinishInputView();
        KeyboardView inputView = mKeyboardSwitcher.getKeyboardView();
        if (inputView != null) inputView.cancelAllMessages();
        // Remove pending messages related to update suggestions
        mHandler.cancelUpdateSuggestions();
    }

    @Override
    public void onUpdateExtractedText(int token, ExtractedText text) {
        super.onUpdateExtractedText(token, text);
        mVoiceProxy.showPunctuationHintIfNecessary();
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd,
            int newSelStart, int newSelEnd,
            int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);

        if (DEBUG) {
            Log.i(TAG, "onUpdateSelection: oss=" + oldSelStart
                    + ", ose=" + oldSelEnd
                    + ", lss=" + mLastSelectionStart
                    + ", lse=" + mLastSelectionEnd
                    + ", nss=" + newSelStart
                    + ", nse=" + newSelEnd
                    + ", cs=" + candidatesStart
                    + ", ce=" + candidatesEnd);
        }

        mVoiceProxy.setCursorAndSelection(newSelEnd, newSelStart);

        // If the current selection in the text view changes, we should
        // clear whatever candidate text we have.
        final boolean selectionChanged = (newSelStart != candidatesEnd
                || newSelEnd != candidatesEnd) && mLastSelectionStart != newSelStart;
        final boolean candidatesCleared = candidatesStart == -1 && candidatesEnd == -1;
        if (!mExpectingUpdateSelection) {
            if (((mComposingStringBuilder.length() > 0 && mHasUncommittedTypedChars)
                    || mVoiceProxy.isVoiceInputHighlighted())
                    && (selectionChanged || candidatesCleared)) {
                mComposingStringBuilder.setLength(0);
                mHasUncommittedTypedChars = false;
                TextEntryState.reset();
                updateSuggestions();
                final InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    ic.finishComposingText();
                }
                mComposingStateManager.onFinishComposingText();
                mVoiceProxy.setVoiceInputHighlighted(false);
            } else if (!mHasUncommittedTypedChars) {
                TextEntryState.reset();
                updateSuggestions();
            }
            mJustAddedMagicSpace = false; // The user moved the cursor.
            mJustReplacedDoubleSpace = false;
        }
        mExpectingUpdateSelection = false;
        mHandler.postUpdateShiftKeyState();

        // Make a note of the cursor position
        mLastSelectionStart = newSelStart;
        mLastSelectionEnd = newSelEnd;
    }

    public void setLastSelection(int start, int end) {
        mLastSelectionStart = start;
        mLastSelectionEnd = end;
    }

    /**
     * This is called when the user has clicked on the extracted text view,
     * when running in fullscreen mode.  The default implementation hides
     * the suggestions view when this happens, but only if the extracted text
     * editor has a vertical scroll bar because its text doesn't fit.
     * Here we override the behavior due to the possibility that a re-correction could
     * cause the suggestions strip to disappear and re-appear.
     */
    @Override
    public void onExtractedTextClicked() {
        if (isSuggestionsRequested()) return;

        super.onExtractedTextClicked();
    }

    /**
     * This is called when the user has performed a cursor movement in the
     * extracted text view, when it is running in fullscreen mode.  The default
     * implementation hides the suggestions view when a vertical movement
     * happens, but only if the extracted text editor has a vertical scroll bar
     * because its text doesn't fit.
     * Here we override the behavior due to the possibility that a re-correction could
     * cause the suggestions strip to disappear and re-appear.
     */
    @Override
    public void onExtractedCursorMovement(int dx, int dy) {
        if (isSuggestionsRequested()) return;

        super.onExtractedCursorMovement(dx, dy);
    }

    @Override
    public void hideWindow() {
        LatinImeLogger.commit();
        mKeyboardSwitcher.onHideWindow();

        if (TRACE) Debug.stopMethodTracing();
        if (mOptionsDialog != null && mOptionsDialog.isShowing()) {
            mOptionsDialog.dismiss();
            mOptionsDialog = null;
        }
        mVoiceProxy.hideVoiceWindow(mConfigurationChanging);
        super.hideWindow();
    }

    @Override
    public void onDisplayCompletions(CompletionInfo[] applicationSpecifiedCompletions) {
        if (DEBUG) {
            Log.i(TAG, "Received completions:");
            if (applicationSpecifiedCompletions != null) {
                for (int i = 0; i < applicationSpecifiedCompletions.length; i++) {
                    Log.i(TAG, "  #" + i + ": " + applicationSpecifiedCompletions[i]);
                }
            }
        }
        if (mApplicationSpecifiedCompletionOn) {
            mApplicationSpecifiedCompletions = applicationSpecifiedCompletions;
            if (applicationSpecifiedCompletions == null) {
                clearSuggestions();
                return;
            }

            SuggestedWords.Builder builder = new SuggestedWords.Builder()
                    .setApplicationSpecifiedCompletions(applicationSpecifiedCompletions)
                    .setTypedWordValid(false)
                    .setHasMinimalSuggestion(false);
            // When in fullscreen mode, show completions generated by the application
            setSuggestions(builder.build());
            mBestWord = null;
            setSuggestionStripShown(true);
        }
    }

    private void setSuggestionStripShownInternal(boolean shown, boolean needsInputViewShown) {
        // TODO: Modify this if we support suggestions with hard keyboard
        if (onEvaluateInputViewShown() && mSuggestionsContainer != null) {
            final boolean shouldShowSuggestions = shown
                    && (needsInputViewShown ? mKeyboardSwitcher.isInputViewShown() : true);
            if (isFullscreenMode()) {
                mSuggestionsContainer.setVisibility(
                        shouldShowSuggestions ? View.VISIBLE : View.GONE);
            } else {
                mSuggestionsContainer.setVisibility(
                        shouldShowSuggestions ? View.VISIBLE : View.INVISIBLE);
            }
        }
    }

    private void setSuggestionStripShown(boolean shown) {
        setSuggestionStripShownInternal(shown, /* needsInputViewShown */true);
    }

    @Override
    public void onComputeInsets(InputMethodService.Insets outInsets) {
        super.onComputeInsets(outInsets);
        final KeyboardView inputView = mKeyboardSwitcher.getKeyboardView();
        if (inputView == null || mSuggestionsContainer == null)
            return;
        // In fullscreen mode, the height of the extract area managed by InputMethodService should
        // be considered.
        // See {@link android.inputmethodservice.InputMethodService#onComputeInsets}.
        final int extractHeight = isFullscreenMode() ? mExtractArea.getHeight() : 0;
        final int backingHeight = (mKeyPreviewBackingView.getVisibility() == View.GONE) ? 0
                : mKeyPreviewBackingView.getHeight();
        final int suggestionsHeight = (mSuggestionsContainer.getVisibility() == View.GONE) ? 0
                : mSuggestionsContainer.getHeight();
        final int extraHeight = extractHeight + backingHeight + suggestionsHeight;
        int touchY = extraHeight;
        // Need to set touchable region only if input view is being shown
        if (mKeyboardSwitcher.isInputViewShown()) {
            if (mSuggestionsContainer.getVisibility() == View.VISIBLE) {
                touchY -= suggestionsHeight;
            }
            final int touchWidth = inputView.getWidth();
            final int touchHeight = inputView.getHeight() + extraHeight
                    // Extend touchable region below the keyboard.
                    + EXTENDED_TOUCHABLE_REGION_HEIGHT;
            if (DEBUG) {
                Log.d(TAG, "Touchable region: y=" + touchY + " width=" + touchWidth
                        + " height=" + touchHeight);
            }
            setTouchableRegionCompat(outInsets, 0, touchY, touchWidth, touchHeight);
        }
        outInsets.contentTopInsets = touchY;
        outInsets.visibleTopInsets = touchY;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return super.onEvaluateFullscreenMode()
                && mResources.getBoolean(R.bool.config_use_fullscreen_mode);
    }

    @Override
    public void updateFullscreenMode() {
        super.updateFullscreenMode();

        if (mKeyPreviewBackingView == null) return;
        // In extract mode, no need to have extra space to show the key preview.
        // If not, we should have extra space above the keyboard to show the key preview.
        mKeyPreviewBackingView.setVisibility(isExtractViewShown() ? View.GONE : View.VISIBLE);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
        case KeyEvent.KEYCODE_BACK:
            if (event.getRepeatCount() == 0) {
                if (mSuggestionsView != null && mSuggestionsView.handleBack()) {
                    return true;
                }
                final LatinKeyboardView keyboardView = mKeyboardSwitcher.getKeyboardView();
                if (keyboardView != null && keyboardView.handleBack()) {
                    return true;
                }
            }
            break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
        case KeyEvent.KEYCODE_DPAD_DOWN:
        case KeyEvent.KEYCODE_DPAD_UP:
        case KeyEvent.KEYCODE_DPAD_LEFT:
        case KeyEvent.KEYCODE_DPAD_RIGHT:
            // Enable shift key and DPAD to do selections
            if (mKeyboardSwitcher.isInputViewShown()
                    && mKeyboardSwitcher.isShiftedOrShiftLocked()) {
                KeyEvent newEvent = new KeyEvent(event.getDownTime(), event.getEventTime(),
                        event.getAction(), event.getKeyCode(), event.getRepeatCount(),
                        event.getDeviceId(), event.getScanCode(),
                        KeyEvent.META_SHIFT_LEFT_ON | KeyEvent.META_SHIFT_ON);
                final InputConnection ic = getCurrentInputConnection();
                if (ic != null)
                    ic.sendKeyEvent(newEvent);
                return true;
            }
            break;
        }
        return super.onKeyUp(keyCode, event);
    }

    public void commitTyped(final InputConnection ic) {
        if (!mHasUncommittedTypedChars) return;
        mHasUncommittedTypedChars = false;
        if (mComposingStringBuilder.length() > 0) {
            if (ic != null) {
                ic.commitText(mComposingStringBuilder, 1);
            }
            mCommittedLength = mComposingStringBuilder.length();
            TextEntryState.acceptedTyped(mComposingStringBuilder);
            addToUserUnigramAndBigramDictionaries(mComposingStringBuilder,
                    UserUnigramDictionary.FREQUENCY_FOR_TYPED);
        }
        updateSuggestions();
    }

    public boolean getCurrentAutoCapsState() {
        final InputConnection ic = getCurrentInputConnection();
        EditorInfo ei = getCurrentInputEditorInfo();
        if (mSettingsValues.mAutoCap && ic != null && ei != null
                && ei.inputType != InputType.TYPE_NULL) {
            return ic.getCursorCapsMode(ei.inputType) != 0;
        }
        return false;
    }

    private void swapSwapperAndSpace() {
        final InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence lastTwo = ic.getTextBeforeCursor(2, 0);
        // It is guaranteed lastTwo.charAt(1) is a swapper - else this method is not called.
        if (lastTwo != null && lastTwo.length() == 2
                && lastTwo.charAt(0) == Keyboard.CODE_SPACE) {
            ic.beginBatchEdit();
            ic.deleteSurroundingText(2, 0);
            ic.commitText(lastTwo.charAt(1) + " ", 1);
            ic.endBatchEdit();
            mKeyboardSwitcher.updateShiftState();
        }
    }

    private void maybeDoubleSpace() {
        if (mCorrectionMode == Suggest.CORRECTION_NONE) return;
        final InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        final CharSequence lastThree = ic.getTextBeforeCursor(3, 0);
        if (lastThree != null && lastThree.length() == 3
                && Utils.canBeFollowedByPeriod(lastThree.charAt(0))
                && lastThree.charAt(1) == Keyboard.CODE_SPACE
                && lastThree.charAt(2) == Keyboard.CODE_SPACE
                && mHandler.isAcceptingDoubleSpaces()) {
            mHandler.cancelDoubleSpacesTimer();
            ic.beginBatchEdit();
            ic.deleteSurroundingText(2, 0);
            ic.commitText(". ", 1);
            ic.endBatchEdit();
            mKeyboardSwitcher.updateShiftState();
            mJustReplacedDoubleSpace = true;
        } else {
            mHandler.startDoubleSpacesTimer();
        }
    }

    // "ic" must not null
    private void maybeRemovePreviousPeriod(final InputC
