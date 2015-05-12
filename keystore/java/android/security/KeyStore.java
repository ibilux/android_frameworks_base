/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.security;

import com.android.org.conscrypt.NativeConstants;

import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.security.keymaster.ExportResult;
import android.security.keymaster.KeyCharacteristics;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterBlob;
import android.security.keymaster.KeymasterDefs;
import android.security.keymaster.OperationResult;
import android.util.Log;

import java.security.InvalidKeyException;
import java.util.Locale;

/**
 * @hide This should not be made public in its present form because it
 * assumes that private and secret key bytes are available and would
 * preclude the use of hardware crypto.
 */
public class KeyStore {
    private static final String TAG = "KeyStore";

    // ResponseCodes
    public static final int NO_ERROR = 1;
    public static final int LOCKED = 2;
    public static final int UNINITIALIZED = 3;
    public static final int SYSTEM_ERROR = 4;
    public static final int PROTOCOL_ERROR = 5;
    public static final int PERMISSION_DENIED = 6;
    public static final int KEY_NOT_FOUND = 7;
    public static final int VALUE_CORRUPTED = 8;
    public static final int UNDEFINED_ACTION = 9;
    public static final int WRONG_PASSWORD = 10;

    /**
     * Per operation authentication is needed before this operation is valid.
     * This is returned from {@link #begin} when begin succeeds but the operation uses
     * per-operation authentication and must authenticate before calling {@link #update} or
     * {@link #finish}.
     */
    public static final int OP_AUTH_NEEDED = 15;

    // Used for UID field to indicate the calling UID.
    public static final int UID_SELF = -1;

    // Flags for "put" "import" and "generate"
    public static final int FLAG_NONE = 0;
    public static final int FLAG_ENCRYPTED = 1;

    // States
    public enum State { UNLOCKED, LOCKED, UNINITIALIZED };

    private int mError = NO_ERROR;

    private final IKeystoreService mBinder;

    private IBinder mToken;

    private KeyStore(IKeystoreService binder) {
        mBinder = binder;
    }

    public static KeyStore getInstance() {
        IKeystoreService keystore = IKeystoreService.Stub.asInterface(ServiceManager
                .getService("android.security.keystore"));
        return new KeyStore(keystore);
    }

    private synchronized IBinder getToken() {
        if (mToken == null) {
            mToken = new Binder();
        }
        return mToken;
    }

    static int getKeyTypeForAlgorithm(String keyType) {
        if ("RSA".equalsIgnoreCase(keyType)) {
            return NativeConstants.EVP_PKEY_RSA;
        } else if ("EC".equalsIgnoreCase(keyType)) {
            return NativeConstants.EVP_PKEY_EC;
        } else {
            return -1;
        }
    }

    public State state() {
        final int ret;
        try {
            ret = mBinder.test();
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            throw new AssertionError(e);
        }

        switch (ret) {
            case NO_ERROR: return State.UNLOCKED;
            case LOCKED: return State.LOCKED;
            case UNINITIALIZED: return State.UNINITIALIZED;
            default: throw new AssertionError(mError);
        }
    }

    public boolean isUnlocked() {
        return state() == State.UNLOCKED;
    }

    public byte[] get(String key) {
        try {
            return mBinder.get(key);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return null;
        }
    }

    public boolean put(String key, byte[] value, int uid, int flags) {
        try {
            return mBinder.insert(key, value, uid, flags) == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public boolean delete(String key, int uid) {
        try {
            return mBinder.del(key, uid) == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public boolean delete(String key) {
        return delete(key, UID_SELF);
    }

    public boolean contains(String key, int uid) {
        try {
            return mBinder.exist(key, uid) == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public boolean contains(String key) {
        return contains(key, UID_SELF);
    }

    public String[] saw(String prefix, int uid) {
        try {
            return mBinder.saw(prefix, uid);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return null;
        }
    }

    public String[] saw(String prefix) {
        return saw(prefix, UID_SELF);
    }

    public boolean reset() {
        try {
            return mBinder.reset() == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public boolean lock() {
        try {
            return mBinder.lock() == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    /**
     * Attempt to unlock the keystore for {@code user} with the password {@code password}.
     * This is required before keystore entries created with FLAG_ENCRYPTED can be accessed or
     * created.
     *
     * @param user Android user ID to operate on
     * @param password user's keystore password. Should be the most recent value passed to
     * {@link #onUserPasswordChanged} for the user.
     *
     * @return whether the keystore was unlocked.
     */
    public boolean unlock(int userId, String password) {
        try {
            mError = mBinder.unlock(userId, password);
            return mError == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public boolean unlock(String password) {
        return unlock(UserHandle.getUserId(Process.myUid()), password);
    }

    public boolean isEmpty() {
        try {
            return mBinder.zero() == KEY_NOT_FOUND;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public boolean generate(String key, int uid, int keyType, int keySize, int flags,
            byte[][] args) {
        try {
            return mBinder.generate(key, uid, keyType, keySize, flags,
                    new KeystoreArguments(args)) == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public boolean importKey(String keyName, byte[] key, int uid, int flags) {
        try {
            return mBinder.import_key(keyName, key, uid, flags) == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public byte[] getPubkey(String key) {
        try {
            return mBinder.get_pubkey(key);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return null;
        }
    }

    public boolean delKey(String key, int uid) {
        try {
            return mBinder.del_key(key, uid) == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public boolean delKey(String key) {
        return delKey(key, UID_SELF);
    }

    public byte[] sign(String key, byte[] data) {
        try {
            return mBinder.sign(key, data);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return null;
        }
    }

    public boolean verify(String key, byte[] data, byte[] signature) {
        try {
            return mBinder.verify(key, data, signature) == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public boolean grant(String key, int uid) {
        try {
            return mBinder.grant(key, uid) == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public boolean ungrant(String key, int uid) {
        try {
            return mBinder.ungrant(key, uid) == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    /**
     * Returns the last modification time of the key in milliseconds since the
     * epoch. Will return -1L if the key could not be found or other error.
     */
    public long getmtime(String key) {
        try {
            final long millis = mBinder.getmtime(key);
            if (millis == -1L) {
                return -1L;
            }

            return millis * 1000L;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return -1L;
        }
    }

    public boolean duplicate(String srcKey, int srcUid, String destKey, int destUid) {
        try {
            return mBinder.duplicate(srcKey, srcUid, destKey, destUid) == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    // TODO remove this when it's removed from Settings
    public boolean isHardwareBacked() {
        return isHardwareBacked("RSA");
    }

    public boolean isHardwareBacked(String keyType) {
        try {
            return mBinder.is_hardware_backed(keyType.toUpperCase(Locale.US)) == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public boolean clearUid(int uid) {
        try {
            return mBinder.clear_uid(uid) == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public boolean resetUid(int uid) {
        try {
            mError = mBinder.reset_uid(uid);
            return mError == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public boolean syncUid(int sourceUid, int targetUid) {
        try {
            mError = mBinder.sync_uid(sourceUid, targetUid);
            return mError == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public boolean passwordUid(String password, int uid) {
        try {
            mError = mBinder.password_uid(password, uid);
            return mError == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public int getLastError() {
        return mError;
    }

    public boolean addRngEntropy(byte[] data) {
        try {
            return mBinder.addRngEntropy(data) == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public int generateKey(String alias, KeymasterArguments args, byte[] entropy, int uid,
            int flags, KeyCharacteristics outCharacteristics) {
        try {
            return mBinder.generateKey(alias, args, entropy, uid, flags, outCharacteristics);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    public int generateKey(String alias, KeymasterArguments args, byte[] entropy, int flags,
            KeyCharacteristics outCharacteristics) {
        return generateKey(alias, args, entropy, UID_SELF, flags, outCharacteristics);
    }

    public int getKeyCharacteristics(String alias, KeymasterBlob clientId, KeymasterBlob appId,
            KeyCharacteristics outCharacteristics) {
        try {
            return mBinder.getKeyCharacteristics(alias, clientId, appId, outCharacteristics);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    public int importKey(String alias, KeymasterArguments args, int format, byte[] keyData,
            int uid, int flags, KeyCharacteristics outCharacteristics) {
        try {
            return mBinder.importKey(alias, args, format, keyData, uid, flags,
                    outCharacteristics);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    public int importKey(String alias, KeymasterArguments args, int format, byte[] keyData,
            int flags, KeyCharacteristics outCharacteristics) {
        return importKey(alias, args, format, keyData, UID_SELF, flags, outCharacteristics);
    }

    public ExportResult exportKey(String alias, int format, KeymasterBlob clientId,
            KeymasterBlob appId) {
        try {
            return mBinder.exportKey(alias, format, clientId, appId);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return null;
        }
    }

    public OperationResult begin(String alias, int purpose, boolean pruneable,
            KeymasterArguments args, byte[] entropy, KeymasterArguments outArgs) {
        try {
            return mBinder.begin(getToken(), alias, purpose, pruneable, args, entropy, outArgs);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return null;
        }
    }

    public OperationResult update(IBinder token, KeymasterArguments arguments, byte[] input) {
        try {
            return mBinder.update(token, arguments, input);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return null;
        }
    }

    public OperationResult finish(IBinder token, KeymasterArguments arguments, byte[] signature) {
        try {
            return mBinder.finish(token, arguments, signature);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return null;
        }
    }

    public int abort(IBinder token) {
        try {
            return mBinder.abort(token);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    /**
     * Check if the operation referenced by {@code token} is currently authorized.
     *
     * @param token An operation token returned by a call to {@link KeyStore.begin}.
     */
    public boolean isOperationAuthorized(IBinder token) {
        try {
            return mBinder.isOperationAuthorized(token);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    /**
     * Add an authentication record to the keystore authorization table.
     *
     * @param authToken The packed bytes of a hw_auth_token_t to be provided to keymaster.
     * @return {@code KeyStore.NO_ERROR} on success, otherwise an error value corresponding to
     * a {@code KeymasterDefs.KM_ERROR_} value or {@code KeyStore} ResponseCode.
     */
    public int addAuthToken(byte[] authToken) {
        try {
            return mBinder.addAuthToken(authToken);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    /**
     * Notify keystore that a user's password has changed.
     *
     * @param userId the user whose password changed.
     * @param newPassword the new password or "" if the password was removed.
     */
    public boolean onUserPasswordChanged(int userId, String newPassword) {
        // Parcel.cpp doesn't support deserializing null strings and treats them as "". Make that
        // explicit here.
        if (newPassword == null) {
            newPassword = "";
        }
        try {
            return mBinder.onUserPasswordChanged(userId, newPassword) == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    /**
     * Notify keystore that a user was added.
     *
     * @param userId the new user.
     * @param parentId the parent of the new user, or -1 if the user has no parent. If parentId is
     * specified then the new user's keystore will be intialized with the same secure lockscreen
     * password as the parent.
     */
    public void onUserAdded(int userId, int parentId) {
        try {
            mBinder.onUserAdded(userId, parentId);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
        }
    }

    /**
     * Notify keystore that a user was added.
     *
     * @param userId the new user.
     */
    public void onUserAdded(int userId) {
        onUserAdded(userId, -1);
    }

    /**
     * Notify keystore that a user was removed.
     *
     * @param userId the removed user.
     */
    public void onUserRemoved(int userId) {
        try {
            mBinder.onUserRemoved(userId);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
        }
    }

    public boolean onUserPasswordChanged(String newPassword) {
        return onUserPasswordChanged(UserHandle.getUserId(Process.myUid()), newPassword);
    }

    /**
     * Returns a {@link KeyStoreException} corresponding to the provided keystore/keymaster error
     * code.
     */
    static KeyStoreException getKeyStoreException(int errorCode) {
        if (errorCode > 0) {
            // KeyStore layer error
            switch (errorCode) {
                case NO_ERROR:
                    return new KeyStoreException(errorCode, "OK");
                case LOCKED:
                    return new KeyStoreException(errorCode, "Keystore locked");
                case UNINITIALIZED:
                    return new KeyStoreException(errorCode, "Keystore not initialized");
                case SYSTEM_ERROR:
                    return new KeyStoreException(errorCode, "System error");
                case PERMISSION_DENIED:
                    return new KeyStoreException(errorCode, "Permission denied");
                case KEY_NOT_FOUND:
                    return new KeyStoreException(errorCode, "Key not found");
                case VALUE_CORRUPTED:
                    return new KeyStoreException(errorCode, "Key blob corrupted");
                default:
                    return new KeyStoreException(errorCode, String.valueOf(errorCode));
            }
        } else {
            // Keymaster layer error
            switch (errorCode) {
                case KeymasterDefs.KM_ERROR_INVALID_AUTHORIZATION_TIMEOUT:
                    // The name of this parameter significantly differs between Keymaster and
                    // framework APIs. Use the framework wording to make life easier for developers.
                    return new KeyStoreException(errorCode,
                            "Invalid user authentication validity duration");
                default:
                    return new KeyStoreException(errorCode,
                            KeymasterDefs.getErrorMessage(errorCode));
            }
        }
    }

    /**
     * Returns an {@link InvalidKeyException} corresponding to the provided
     * {@link KeyStoreException}.
     */
    static InvalidKeyException getInvalidKeyException(KeyStoreException e) {
        switch (e.getErrorCode()) {
            case KeymasterDefs.KM_ERROR_KEY_EXPIRED:
                return new KeyExpiredException();
            case KeymasterDefs.KM_ERROR_KEY_NOT_YET_VALID:
                return new KeyNotYetValidException();
            case KeymasterDefs.KM_ERROR_KEY_USER_NOT_AUTHENTICATED:
                return new UserNotAuthenticatedException();
            default:
                return new InvalidKeyException("Keystore operation failed", e);
        }
    }

    /**
     * Returns an {@link InvalidKeyException} corresponding to the provided keystore/keymaster error
     * code.
     */
    static InvalidKeyException getInvalidKeyException(int errorCode) {
        return getInvalidKeyException(getKeyStoreException(errorCode));
    }
}
