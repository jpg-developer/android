/**
 *   ownCloud Android client application
 *
 *   Copyright (C) 2012  Bartek Przybylski
 *   Copyright (C) 2015 ownCloud Inc.
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License version 2,
 *   as published by the Free Software Foundation.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.owncloud.android.files;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.owncloud.android.MainApp;
import com.owncloud.android.authentication.AccountUtils;
import com.owncloud.android.db.DbHandler;
import com.owncloud.android.files.services.FileUploader;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.utils.FileStorageUtils;


import android.accounts.Account;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo.State;
import android.preference.PreferenceManager;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Video;
import android.webkit.MimeTypeMap;

// JPG TODO:    This name could be argued to be misleading,
//              Reason is, this class does not receive broadcasts regarding instant-uploads.
//              It receives broadcasts regarding new photos/videos; instant-upload is what the class
//              does out of the information it receives.
//              The following name describes IMO this class in a better way: NewPictureOrVideoBroadcastReceiver
public class InstantUploadBroadcastReceiver extends BroadcastReceiver {

    private static String TAG = InstantUploadBroadcastReceiver.class.getName();
    // Image action
    // Unofficial action, works for most devices but not HTC. See: https://github.com/owncloud/android/issues/6
    private static String NEW_PHOTO_ACTION_UNOFFICIAL = "com.android.camera.NEW_PICTURE";
    // Officially supported action since SDK 14: http://developer.android.com/reference/android/hardware/Camera.html#ACTION_NEW_PICTURE
    private static String NEW_PHOTO_ACTION = "android.hardware.action.NEW_PICTURE";
    // Video action
    // Officially supported action since SDK 14: http://developer.android.com/reference/android/hardware/Camera.html#ACTION_NEW_VIDEO
    private static String NEW_VIDEO_ACTION = "android.hardware.action.NEW_VIDEO";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log_OC.d(TAG, "Received: " + intent.getAction());
        if (intent.getAction().equals(android.net.ConnectivityManager.CONNECTIVITY_ACTION)) {
            handleConnectivityAction(context, intent);
        }else if (intent.getAction().equals(NEW_PHOTO_ACTION_UNOFFICIAL)) {
            handleNewPictureAction(context, intent); 
            Log_OC.d(TAG, "UNOFFICIAL processed: com.android.camera.NEW_PICTURE");
        } else if (intent.getAction().equals(NEW_PHOTO_ACTION)) {
            handleNewPictureAction(context, intent); 
            Log_OC.d(TAG, "OFFICIAL processed: android.hardware.action.NEW_PICTURE");
        } else if (intent.getAction().equals(NEW_VIDEO_ACTION)) {
            Log_OC.d(TAG, "OFFICIAL processed: android.hardware.action.NEW_VIDEO");
            handleNewVideoAction(context, intent);
        } else {
            Log_OC.e(TAG, "Incorrect intent sent: " + intent.getAction());
        }
    }

    private void handleNewPictureAction(Context context, Intent intent) {
        Log_OC.w(TAG, "New photo received");
        
        if (!instantPictureUploadEnabled(context)) {
            Log_OC.d(TAG, "Instant picture upload disabled, ignoring new picture");
            return;
        }

        // JPG TODO: review the order of the statements in this method
        final List<Account> targetAccounts = resolveTargetAccounts(context);
        if (targetAccounts.size() == 0) {
            Log_OC.w(TAG, "No ownCloud account found for instant upload, aborting");
            return;
        }

        Log_OC.d(TAG, "Target account(s) include: " + targetAccounts.toString());

        MediaContentEntry mediaContentEntry = resolveMediaContent(context, intent, PICTURE_CONTENT_PROJECTION);
        if (mediaContentEntry == null) {
            Log_OC.e(TAG, "Failed to resolve new picture!");
            return;
        }

        Log_OC.d(TAG, mediaContentEntry.file_path + "");

        if (needToPosponeFileUploading(context)) {
            Log_OC.d(TAG, "Not a good time to upload a file, postpone!");
            return;
        }

        for (Account account: targetAccounts) {
            // save always temporally the picture to upload
            // JPG TODO: Q: why is this done for pictures only? why not for videos?
            saveFileToUploadIntoDatabase(context, account, mediaContentEntry);

            context.startService(createFileUploadingIntent(context, account, mediaContentEntry));
        }
    }

    private void handleNewVideoAction(Context context, Intent intent) {
        Log_OC.w(TAG, "New video received");
        
        if (!instantVideoUploadEnabled(context)) {
            Log_OC.d(TAG, "Instant video upload disabled, ignoring new video");
            return;
        }

        // JPG TODO: review the order of the statements in this method
        final List<Account> targetAccounts = resolveTargetAccounts(context);
        if (targetAccounts.size() == 0) {
            Log_OC.w(TAG, "No ownCloud account found for instant upload, aborting");
            return;
        }

        MediaContentEntry mediaContentEntry = resolveMediaContent(context, intent, VIDEO_CONTENT_PROJECTION);
        if (mediaContentEntry == null) {
            Log_OC.e(TAG, "Failed to resolve new video!");
            return;
        }

        if (needToPosponeFileUploading(context)) {
            Log_OC.d(TAG, "Not a good time to upload a file, postpone!");
            return;
        }

        for (Account account: targetAccounts) {
            context.startService(createFileUploadingIntent(context, account, mediaContentEntry));
        }
    }

    private void handleConnectivityAction(Context context, Intent intent) {
        Log_OC.w(TAG, "Connectivity state change");

        if (!instantPictureUploadEnabled(context)) {
            Log_OC.d(TAG, "Instant upload disabled, don't upload anything");
            return;
        }

        if (intent.hasExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY)) {
            Log_OC.d(TAG, "No connectivity, do nothing");
            return;
        }

        if (!isOnline(context)) {
            Log_OC.d(TAG, "Not online, do nothing");
            return;
        }

        if (instantPictureUploadViaWiFiOnly(context) && !isConnectedViaWiFi(context)) {
            Log_OC.d(TAG, "Not connected to wifi, do nothing");
            return;
        }

        for (AwaitingFileRecord awaitingFileRecord: getAwaitingFileRecords(context)) {
            File f = new File(awaitingFileRecord.file_path);
            if (f.exists()) {
                Account account = new Account(awaitingFileRecord.account_name, MainApp.getAccountType());

                // JPG TODO: mimeType does not seem to be actually used, any reason to keep it??
                String mimeType = null;
                try {
                    mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                            f.getName().substring(f.getName().lastIndexOf('.') + 1));

                } catch (Throwable e) {
                    Log_OC.e(TAG, "Trying to find out MIME type of a file without extension: " + f.getName());
                }

                if (mimeType == null) {
                    mimeType = "application/octet-stream";
                }

                Intent i = new Intent(context, FileUploader.class);
                i.putExtra(FileUploader.KEY_ACCOUNT, account);
                i.putExtra(FileUploader.KEY_LOCAL_FILE, awaitingFileRecord.file_path);
                i.putExtra(FileUploader.KEY_REMOTE_FILE, FileStorageUtils.getInstantUploadFilePath(context, f.getName()));
                i.putExtra(FileUploader.KEY_UPLOAD_TYPE, FileUploader.UPLOAD_SINGLE_FILE);
                // JPG TODO: FileUploader.KEY_MIME_TYPE is not specified, Q: bug or intended??
                i.putExtra(FileUploader.KEY_INSTANT_UPLOAD, true);

                // instant upload behaviour
                i = addInstantUploadBehaviour(i, context);

                context.startService(i);
            } else {
                Log_OC.w(TAG, "Instant upload file " + f.getAbsolutePath() + " does not exist anymore");
                // JPG TODO: is recorg being removed from database? or does it stay there forever...?
            }
        }
    }

    static class AwaitingFileRecord {
        String account_name;
        String file_path;
    }

    private List<AwaitingFileRecord> getAwaitingFileRecords(Context context) {
        List<AwaitingFileRecord> result = new ArrayList<>();

        DbHandler db = new DbHandler(context);
        Cursor c = db.getAwaitingFiles();
        if (c.moveToFirst()) {
            do {
                AwaitingFileRecord record = new AwaitingFileRecord();
                record.account_name = c.getString(c.getColumnIndex("account"));
                record.file_path    = c.getString(c.getColumnIndex("path"));
            } while (c.moveToNext());
        }
        c.close();
        db.close();

        return result;
    }

    static class MediaContentEntry
    {
        String file_path = null;
        String file_name = null;
        String mime_type = null;
    }

    private static final String[] PICTURE_CONTENT_PROJECTION = { Images.Media.DATA, Images.Media.DISPLAY_NAME, Images.Media.MIME_TYPE, Images.Media.SIZE };
    private static final String[] VIDEO_CONTENT_PROJECTION   = {  Video.Media.DATA,  Video.Media.DISPLAY_NAME,  Video.Media.MIME_TYPE,  Video.Media.SIZE };

    private static MediaContentEntry resolveMediaContent(Context context, Intent intent, String[] contentProjection)
    {
        MediaContentEntry result = null;

        Cursor cursor = context.getContentResolver().query(intent.getData(), contentProjection, null, null, null);

        if (cursor.moveToFirst()) {
            result = new MediaContentEntry();
            result.file_path = cursor.getString(cursor.getColumnIndex(Images.Media.DATA));
            result.file_name = cursor.getString(cursor.getColumnIndex(Images.Media.DISPLAY_NAME));
            result.mime_type = cursor.getString(cursor.getColumnIndex(Images.Media.MIME_TYPE));
        } else {
            Log_OC.e(TAG, "Couldn't resolve given uri: " + intent.getDataString());
        }

        cursor.close();

        return result;
    }

    // JPG TODO: current implementation of this method mimics original behavior, i.e. target account
    //           equals current account; need to expand this method so that it supports the following
    //           options too:
    //              - targe t all
    //              - target whitelist
    private List<Account> resolveTargetAccounts(Context context) {

        // DEFAULT IMPLEMENTATION: current account
        //List<Account> result = new ArrayList<>();
        //result.add( AccountUtils.getCurrentOwnCloudAccount(context) );
        //return result;

        // ALTERNATIVE IMPLEMENTATION 1: all accounts
        //return AccountUtils.getAllOwnCloudAccounts(context);

        // ALTERNATIVE IMPLEMENTATION 1: white-list
        return getSampleWhiteListedAccounts();
    }

    // JPG TODO: debug only!
    private List<Account> getSampleWhiteListedAccounts() {
        List<Account> result = new ArrayList<>();
        result.add( new Account(  "jp@192.168.1.105/owncloud", "owncloud") );
        result.add( new Account("javi@192.168.1.105/owncloud", "owncloud") );
        return result;
    }

    private void saveFileToUploadIntoDatabase(Context context, Account account, MediaContentEntry mediaContentEntry) {
        DbHandler db = new DbHandler(context);
        db.putFileForLater(mediaContentEntry.file_path, account.name, null);
        db.close();
    }

    private Intent createFileUploadingIntent(Context context, Account account, MediaContentEntry mediaContentEntry) {
        Intent intent = new Intent(context, FileUploader.class);
        intent.putExtra(FileUploader.KEY_ACCOUNT, account);
        intent.putExtra(FileUploader.KEY_LOCAL_FILE, mediaContentEntry.file_path);
        intent.putExtra(FileUploader.KEY_REMOTE_FILE, FileStorageUtils.getInstantVideoUploadFilePath(context, mediaContentEntry.file_name));
        intent.putExtra(FileUploader.KEY_UPLOAD_TYPE, FileUploader.UPLOAD_SINGLE_FILE);
        intent.putExtra(FileUploader.KEY_MIME_TYPE, mediaContentEntry.mime_type);
        intent.putExtra(FileUploader.KEY_INSTANT_UPLOAD, true);

        intent = addInstantUploadBehaviour(intent, context);

        return intent;
    }

    private Intent addInstantUploadBehaviour(Intent i, Context context) {
        SharedPreferences appPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        String behaviour = appPreferences.getString("prefs_instant_behaviour", "NOTHING");

        if (behaviour.equalsIgnoreCase("NOTHING")) {
            Log_OC.d(TAG, "upload file and do nothing");
            i.putExtra(FileUploader.KEY_LOCAL_BEHAVIOUR, FileUploader.LOCAL_BEHAVIOUR_FORGET);
        } else if (behaviour.equalsIgnoreCase("MOVE")) {
            i.putExtra(FileUploader.KEY_LOCAL_BEHAVIOUR, FileUploader.LOCAL_BEHAVIOUR_MOVE);
            Log_OC.d(TAG, "upload file and move file to oc folder");
            // JPG TODO: Q: is "oc folder" account specific or common to all accounts??
        }
        return i;
    }

    private boolean needToPosponeFileUploading(Context context) {
        return !isOnline(context) || (instantVideoUploadViaWiFiOnly(context) && !isConnectedViaWiFi(context));
    }

    // JPG TODO: why public? whe else is using this code??
    public static boolean isOnline(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm.getActiveNetworkInfo() != null && cm.getActiveNetworkInfo().isConnected();
    }

    // JPG TODO: why public? whe else is using this code??
    public static boolean isConnectedViaWiFi(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm != null && cm.getActiveNetworkInfo() != null
                && cm.getActiveNetworkInfo().getType() == ConnectivityManager.TYPE_WIFI
                && cm.getActiveNetworkInfo().getState() == State.CONNECTED;
    }

    // JPG TODO: why public? whe else is using this code??
    public static boolean instantPictureUploadEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("instant_uploading", false);
    }

    // JPG TODO: why public? whe else is using this code??
    public static boolean instantVideoUploadEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("instant_video_uploading", false);
    }

    // JPG TODO: why public? whe else is using this code??
    public static boolean instantPictureUploadViaWiFiOnly(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("instant_upload_on_wifi", false);
    }

    // JPG TODO: why public? whe else is using this code??
    public static boolean instantVideoUploadViaWiFiOnly(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("instant_video_upload_on_wifi", false);
    }
}
