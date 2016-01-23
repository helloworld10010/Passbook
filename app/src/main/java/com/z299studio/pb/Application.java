/*
* Copyright 2015 Qianqian Zhu <zhuqianqian.299@gmail.com> All rights reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.z299studio.pb;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Random;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

public class Application{
    
    private static final String DATA_FILE = "data";
    private static final int APP_VERSION = 2;
    
    private static Application __instance;
    
    public SharedPreferences mSP;
    private SharedPreferences mFpData;
    
    public static class Options {
        public static boolean mAlwaysShowPwd;
        public static int mAutoLock;
        public static boolean mEnableCopyPwd;
        public static int mFpStatus;
        public static boolean mShowOther;
        public static int mSync;
        public static boolean mSyncMsg;
        public static int mSyncVersion;
        public static int mTheme;
        public static boolean mTour;
        public static boolean mWarnCopyPwd;
        public static Date mSyncTime;
    }
    
    public static class FileHeader {
        public int version;  // 1 byte
        public int iterationCount; // 1 byte
        public int keyLength;  //1 byte
        public int ivLength;  // 1 byte
        public int revision;  // 10 bytes
        public int size;
        public boolean valid;
        public static final int HEADER_SIZE = 16;
        public static final int RESERVED = 10;
        
        public static FileHeader parse(byte[] buffer) {
            FileHeader fh = new FileHeader();
            fh.valid = false;
            if(buffer!=null) {
                if(buffer[0] == 0x50 && buffer[1] == 0x42) {
                    int i = 2;
                    fh.valid = true;
                    fh.version = buffer[i++];
                    fh.iterationCount = 100 * (buffer[i++]);
                    fh.keyLength = buffer[i++];
                    fh.ivLength =  buffer[i++];
                    fh.revision = Integer.parseInt(new String(buffer, i, RESERVED).trim());
                    i += RESERVED;
                    fh.size = i;                
                }
            }
            return fh;
        }
        
        public static byte[] build(int version, int count, int keyLength, int ivLength, int revision) {
            byte[] header = new byte[HEADER_SIZE];
            int i = 0;
            header[i++] = 0x50; header[i++] = 0x42;
            header[i++] = (byte) version;
            header[i++] = (byte) (count / 100);
            header[i++] = (byte) keyLength;
            header[i++] = (byte) ivLength;
            byte[] revBytes = String.format(Locale.ENGLISH, "%10d", revision).getBytes();
            System.arraycopy(revBytes, 0, header, i, revBytes.length);
           // i += RESERVED;
            return header;
        }
    }
    
    private Activity mContext;
    private byte[] mBuffer;
    private int mDataSize;
    private FileHeader mFileHeader;
    private long mLastPause;
    private boolean mIgnoreNextPause;
    private String mPassword;
    private int mLocalVersion;
    private Crypto mCrypto;
    private Hashtable<Integer, Boolean> mChanges;
    private AccountManager mAccountManager;

    public static final Integer THEME = 0;
    public static final Integer DATA_OTHER = 1;
    public static final Integer DATA_ALL = 2;
    
    public static Application getInstance(Activity context) {
        if(__instance == null) {
            __instance = new Application(context);
        }
        __instance.mContext = context;
        return __instance;
    }
    
    public static Application getInstance() {
        return __instance;
    }
    
    private Application(Activity context) {
        mContext = context;
        mSP = PreferenceManager.getDefaultSharedPreferences(context);
        mFpData = context.getSharedPreferences(C.Fingerprint.FILE, Context.MODE_PRIVATE);
        Options.mTheme = mSP.getInt(C.Keys.THEME, 2);
        Options.mTour = mSP.getBoolean(C.Keys.TOUR, false);
        mChanges = new Hashtable<>();
    }
    
    public void onStart() {
        Options.mAutoLock = mSP.getInt(C.Keys.AUTO_LOCK_TIME, -1);
        if(Options.mAutoLock == -1) {
            boolean autolock_v1 = mSP.getBoolean(C.Keys.AUTO_LOCK, false);
            Options.mAutoLock = autolock_v1 ? 1000 : 0;
        }
        Options.mAlwaysShowPwd = mSP.getBoolean(C.Keys.SHOW_PWD, false);
        Options.mEnableCopyPwd = mSP.getBoolean(C.Keys.ENABLE_COPY, true);
        Options.mShowOther = mSP.getBoolean(C.Keys.SHOW_OTHER, false);
        Options.mSync = mSP.getInt(C.Sync.SERVER, C.Sync.NONE);
        Options.mSyncMsg = mSP.getBoolean(C.Sync.MSG, true);
        Options.mSyncVersion = mSP.getInt(C.Sync.VERSION, 0);
        Options.mWarnCopyPwd = mSP.getBoolean(C.Keys.WARN_COPY, true);
        String syncTime = mSP.getString(C.Sync.TIME, "0000-00-00 00:00:00");
        DateFormat df = DateFormat.getDateTimeInstance();
        try {
            Options.mSyncTime = df.parse(syncTime);
        }
        catch (ParseException e) {
            Options.mSyncTime = new Date(0L);
        }
    }

    public AccountManager getAccountManager() {
        return mAccountManager;
    }

    public void setAccountManager(AccountManager mgr, int imgCode, String defCategoryName) {
        mAccountManager = mgr;
        mAccountManager.setDefaultCategory(imgCode, defCategoryName);
    }

    public void setCrypto(Crypto crypto) {
        mCrypto = crypto;
    }

    public boolean queryChange(int what) {
        return mChanges.get(what) != null;
    }
    
    public void notifyChange(int what) {
        mChanges.put(what, Boolean.TRUE);
        if(what == DATA_ALL || what == DATA_OTHER) {
            reset();
        }
    }
    
    public void handleChange(int what) {
        mChanges.remove(what);
    }
    
    public boolean hasDataFile() {
        boolean success = false;
        try {
            File file = new File(mContext.getFilesDir()+"/"+DATA_FILE);
            mDataSize = (int) file.length();
            if(mDataSize > 0) {
                success = true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return success;    
    }
    
    public byte[] getData() {
        if(mBuffer==null) {
            try {
                if(mDataSize < 1) {
                    saveData();
                    hasDataFile();
                }
                if(mDataSize > 0) {
                    mBuffer = new byte[mDataSize];
                    FileInputStream fis = mContext.openFileInput(DATA_FILE);
                    fis.read(mBuffer, 0, mDataSize);
                    fis.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return mBuffer;
    }

    public FileHeader getAppHeaderData() {
        if(mBuffer == null) {
            getData();
        }
        if(mBuffer!=null) {
            mFileHeader = FileHeader.parse(mBuffer);
            mLocalVersion = mFileHeader.revision;
        }
        return mFileHeader;
    }
    
    public int getLocalVersion() {
        int version = 0;
        if (mFileHeader != null) {
            version = mFileHeader.revision;
        }
        return version;
    }
    
    public String getPassword() {
        return mPassword;
    }
    
    public void setPassword(String password, boolean reset) {
        mPassword = password;
        if(reset) {
            mCrypto = new Crypto();
            mCrypto.resetPassword(password);
        }
    }
    
    public void saveData() {
        if(mLocalVersion <= Options.mSyncVersion) {
            mLocalVersion++;
        }
        byte[] cipher = mCrypto.encrypt(mAccountManager.getBytes());
        byte[] header = FileHeader.build(APP_VERSION, mCrypto.getIterationCount(),
                Crypto.SALT_LENGTH, mCrypto.getIvLength(), mLocalVersion);
        byte[] keyInfo = mCrypto.getSaltAndIvBytes();
        try {
            FileOutputStream fos = mContext.openFileOutput(DATA_FILE, Context.MODE_PRIVATE);
            fos.write(header);
            fos.write(keyInfo);
            fos.write(cipher);
            int size = header.length + keyInfo.length + cipher.length;
            if (mBuffer == null || mBuffer.length != size) {
                mBuffer = new byte[size];
            }
            System.arraycopy(header, 0, mBuffer, 0, header.length);
            System.arraycopy(keyInfo, 0, mBuffer, header.length, keyInfo.length);
            System.arraycopy(cipher, 0, mBuffer, header.length + keyInfo.length, cipher.length);
            fos.close();
            mFileHeader = FileHeader.parse(mBuffer);
            mAccountManager.onSaved();
        }catch (FileNotFoundException e) {
            Log.w("Passbook", "File not found");
        }
        catch(IOException ioe) {
            Log.e("Passbook", "IOException");
        }
    }
    
    public void onPause() {
        if(mAccountManager.saveRequired()) {
            saveData();
        }
        mLastPause = System.currentTimeMillis();
    }
    
    public boolean needAuth() {
        if(mIgnoreNextPause || Options.mAutoLock < 1) {
            mIgnoreNextPause = false;
            return false;
        }
        return (System.currentTimeMillis() - mLastPause) > Options.mAutoLock;
    }
    
    public void ignoreNextPause() {
        mIgnoreNextPause = true;
    }
    
    public void saveData(byte[] data, int version) {
        try {
            mLocalVersion = version;
            mFileHeader.revision = version;
            FileOutputStream fos = mContext.openFileOutput(DATA_FILE, Context.MODE_PRIVATE);
            fos.write(data);
            fos.close();
        } catch (Exception e) {            
            e.printStackTrace();
        }
    }
    
    public void onVersionUpdated(int revision) {
        Options.mSyncVersion = revision;
        mSP.edit().putInt(C.Sync.VERSION, revision).apply();
    }

    public String onSyncSucceed() {
        Options.mSyncTime = new Date();
        DateFormat df = DateFormat.getDateTimeInstance();
        String time = df.format(Options.mSyncTime);
        mSP.edit().putString(C.Sync.TIME, time).apply();
        return time;
    }

    public int queryFpStatus() {
        Options.mFpStatus = mFpData.getInt(C.Fingerprint.STATUS, C.Fingerprint.UNKNOWN);
        return Options.mFpStatus;
    }

    public byte[] getFpIv() {
        return Base64.decode(mFpData.getString(C.Fingerprint.IV, "1234"), Base64.DEFAULT);
    }

    public byte[] getFpData() {
        return Base64.decode(mFpData.getString(C.Fingerprint.DATA, "1234"), Base64.DEFAULT);
    }

    public void setFpData(byte[] data, byte[] iv) {
        mFpData.edit()
                .putInt(C.Fingerprint.STATUS, C.Fingerprint.ENABLED)
                .putString(C.Fingerprint.DATA, Base64.encodeToString(data, Base64.DEFAULT))
                .putString(C.Fingerprint.IV, Base64.encodeToString(iv, Base64.DEFAULT))
                .apply();
        Options.mFpStatus = C.Fingerprint.ENABLED;
    }

    public void clearFpData() {
        mFpData.edit().clear()
                .putInt(C.Fingerprint.STATUS, C.Fingerprint.DISABLED)
                .apply();
        Options.mFpStatus = C.Fingerprint.DISABLED;
    }

    public static AccountManager decrypt(Crypto crypto, String password,
                                         FileHeader header, byte[]data)
            throws GeneralSecurityException{
        if(data!=null) {
            int total = header.keyLength + header.ivLength;
            crypto.setPassword(password, data, header.size, total);
            total += header.size;
            byte[] textData = new byte[data.length - total];
            System.arraycopy(data, total, textData, 0, textData.length);
            byte[] text = crypto.decrypt(textData);
            Log.i("Passbook", "Decrypt complete successfully.");
            return new AccountManager(new String(text));
        }
        return null;
    }

    public static void showToast(Activity context, int stringId, int duration) {
        if(android.os.Build.VERSION.SDK_INT>=android.os.Build.VERSION_CODES.KITKAT) {
            Toast.makeText(context, stringId, duration).show();
        }
        else {
            LayoutInflater inflater = context.getLayoutInflater();
            View layout = inflater.inflate(R.layout.toast,
                    (ViewGroup)context.findViewById(R.id.toast_layout_root));
            TextView desc = (TextView)layout.findViewById(R.id.description);
            desc.setText(stringId);
            Toast toast = new Toast(context.getApplicationContext());
            toast.setView(layout);
            toast.setDuration(duration);
            toast.show();
        }
    }
    
    public static void showToast(Activity context, String text, int duration) {
        if(android.os.Build.VERSION.SDK_INT>=android.os.Build.VERSION_CODES.KITKAT) {
            Toast.makeText(context, text, duration).show();
        }
        else {
            LayoutInflater inflater = context.getLayoutInflater();
            View layout = inflater.inflate(R.layout.toast,
                    (ViewGroup)context.findViewById(R.id.toast_layout_root));
            TextView desc = (TextView)layout.findViewById(R.id.description);
            desc.setText(text);
            Toast toast = new Toast(context.getApplicationContext());
            toast.setView(layout);
            toast.setDuration(duration);
            toast.show();
        }
    }

    private static int[] __icons__= {
            R.drawable.pb_bank, R.drawable.pb_creditcard, R.drawable.pb_desktop,
            R.drawable.pb_shop, R.drawable.pb_email, R.drawable.pb_web,
            R.drawable.pb_wallet, R.drawable.pb_atm, R.drawable.pb_bag,
            R.drawable.pb_gift, R.drawable.pb_school, R.drawable.pb_folder,
            R.drawable.pb_briefcase, R.drawable.pb_chat, R.drawable.pb_lock,
            R.drawable.pb_user
    };
    public static int[] getThemedIcons() {
        return __icons__;
    }

    private static String[] sCategoryNames;
    private static int[] sCategoryIcons;
    private static int[] sCategoryIds;
    public String[] getSortedCategoryNames() {
        if(sCategoryNames == null) {
            int size;
            ArrayList<AccountManager.Category> categories =
                    mAccountManager.getCategoryList(false, true);
            size = categories.size() + 1;
            sCategoryNames = new String[size];
            sCategoryIds = new int[size];
            sCategoryIcons = new int[size];
            int i = 0;
            AccountManager.Category defaultCategory =
                    mAccountManager.getCategory(AccountManager.DEFAULT_CATEGORY_ID);
            sCategoryNames[i] = defaultCategory.mName;
            sCategoryIds[i] = defaultCategory.mId;
            sCategoryIcons[i++] = defaultCategory.mImgCode;

            for(AccountManager.Category category : categories) {
                sCategoryNames[i] = category.mName;
                sCategoryIds[i] = category.mId;
                sCategoryIcons[i++] = category.mImgCode;
            }
        }
        return sCategoryNames;
    }

    public int[] getSortedCategoryIds() {
        if(sCategoryNames == null) {
            getSortedCategoryNames();
        }
        return sCategoryIds;
    }

    public int[] getSortedCategoryIcons() {
        if(sCategoryNames == null) {
            getSortedCategoryNames();
        }
        return sCategoryIcons;
    }
    
    public static void reset() { sCategoryNames = null; }

    private static Random random = new Random();
    private static char[] candidates = new char[96];
    public static String generate(boolean hasA2Z, boolean has_a2z, boolean hasDigits,
                                  boolean hasChars, boolean hasSpace, int minLen, int maxLen) {
        if(!hasA2Z && !has_a2z && !hasDigits && !hasChars && !hasSpace) {
            return "";
        }
        int length;
        if(minLen == maxLen) {
            length = minLen;
        }
        else {
            int min = minLen > maxLen ? maxLen : minLen;
            int max = minLen > maxLen ? minLen : maxLen;
            length = min;
            length += random.nextInt(max-min+1);
        }
        int index = 0, i;
        char[] specChars = {'~', '`', '!', '@', '#', '$', '%', '^', '&', '*', '(', ')', '-', '_',
                '=', '+', '[', '{', ']', '}', '\\', '|', ';', ':', '\'', '"', '<', ',', '.', '>',
                '/', '?', ' '};
        char result[] = new char[length];
        if(hasA2Z) {
            for(i = 0; i < 26; ++i) {
                candidates[index++] = (char) ('A' + i) ;
            }
        }
        if(has_a2z) {
            for(i = 0; i < 26; ++i) {
                candidates[index++] = (char)('a'+i);
            }
        }
        if(hasDigits) {
            for(i = 0; i < 10; ++i) {
                candidates[index++] = (char)('0' + i);
            }
        }
        if(hasChars) {
            System.arraycopy(specChars, 0, candidates, index, specChars.length);
            index += specChars.length;
        }
        if(hasSpace) {
            candidates[index++] = 0x20;
        }
        for(i = 0; i < length; ++i) {
            result[i] = candidates[random.nextInt(index)];
        }

        return String.valueOf(result);
    }
}
