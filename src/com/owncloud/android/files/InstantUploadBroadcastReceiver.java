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

    static class MediaContentEntry
    {
        String file_path = null;
        String file_name = null;
        String mime_type = null;
    }

    private static final String[] PICTURE_CONTENT_PROJECTION = { Images.Media.DATA, Images.Media.DISPLAY_NAME, Images.Media.MIME_TYPE, Images.Media.SIZE };
    private static final String[] VIDEO_CONTENT_PROJECTION   = {  Video.Media.DATA,  Video.Media.DISPLAY_NAME,  Video.Media.MIME_TYPE,  Video.Media.SIZE  };

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

    private void handleNewPictureAction(Context context, Intent intent) {
        Log_OC.w(TAG, "New photo received");
        
        if (!instantPictureUploadEnabled(context)) {
            Log_OC.d(TAG, "Instant picture upload disabled, ignoring new picture");
            return;
        }

        Account account = AccountUtils.getCurrentOwnCloudAccount(context);
        if (account == null) {
            Log_OC.w(TAG, "No ownCloud account found for instant upload, aborting");
            return;
        }

        MediaContentEntry mediaContentEntry = resolveMediaContent(context, intent, PICTURE_CONTENT_PROJECTION);
        if (mediaContentEntry == null) {
            Log_OC.e(TAG, "Failed to resolve new picture!");
            return;
        }

        Log_OC.d(TAG, mediaContentEntry.file_path + "");

        // save always temporally the picture to upload
        // JPG TODO: Q: why is this done for pictures only? why not for videos?
        DbHandler db = new DbHandler(context);
        db.putFileForLater(mediaContentEntry.file_path, account.name, null);
        db.close();

        if (!isOnline(context) || (instantPictureUploadViaWiFiOnly(context) && !isConnectedViaWiFi(context))) {
            return;
        }

        // JPG TODO: consider extracting this logic to another method, e.g. createFileUploadingIntent(..)
        Intent i = new Intent(context, FileUploader.class);
        i.putExtra(FileUploader.KEY_ACCOUNT, account);
        i.putExtra(FileUploader.KEY_LOCAL_FILE, mediaContentEntry.file_path);
        i.putExtra(FileUploader.KEY_REMOTE_FILE, FileStorageUtils.getInstantUploadFilePath(context, mediaContentEntry.file_name));
        i.putExtra(FileUploader.KEY_UPLOAD_TYPE, FileUploader.UPLOAD_SINGLE_FILE);
        i.putExtra(FileUploader.KEY_MIME_TYPE, mediaContentEntry.mime_type);
        i.putExtra(FileUploader.KEY_INSTANT_UPLOAD, true);

        // instant upload behaviour
        i = addInstantUploadBehaviour(i, context);

        context.startService(i);
    }

    private Intent addInstantUploadBehaviour(Intent i, Context context){
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

    private void handleNewVideoAction(Context context, Intent intent) {
        Log_OC.w(TAG, "New video received");
        
        if (!instantVideoUploadEnabled(context)) {
            Log_OC.d(TAG, "Instant video upload disabled, ignoring new video");
            return;
        }

        Account account = AccountUtils.getCurrentOwnCloudAccount(context);
        if (account == null) {
            Log_OC.w(TAG, "No owncloud account found for instant upload, aborting");
            return;
        }

        MediaContentEntry mediaContentEntry = resolveMediaContent(context, intent, VIDEO_CONTENT_PROJECTION);
        if (mediaContentEntry == null) {
            Log_OC.e(TAG, "Failed to resolve new video!");
            return;
        }

        if (!isOnline(context) || (instantVideoUploadViaWiFiOnly(context) && !isConnectedViaWiFi(context))) {
            return;
        }

        Intent i = new Intent(context, FileUploader.class);
        i.putExtra(FileUploader.KEY_ACCOUNT, account);
        i.putExtra(FileUploader.KEY_LOCAL_FILE, mediaContentEntry.file_path);
        i.putExtra(FileUploader.KEY_REMOTE_FILE, FileStorageUtils.getInstantVideoUploadFilePath(context, mediaContentEntry.file_name));
        i.putExtra(FileUploader.KEY_UPLOAD_TYPE, FileUploader.UPLOAD_SINGLE_FILE);
        i.putExtra(FileUploader.KEY_MIME_TYPE, mediaContentEntry.mime_type);
        i.putExtra(FileUploader.KEY_INSTANT_UPLOAD, true);

        // instant upload behaviour
        i = addInstantUploadBehaviour(i, context);

        context.startService(i);
    }

    private void handleConnectivityAction(Context context, Intent intent) {
        if (!instantPictureUploadEnabled(context)) {
            Log_OC.d(TAG, "Instant upload disabled, don't upload anything");
            return;
        }

        if (!intent.hasExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY)
                && isOnline(context)
                && (!instantPictureUploadViaWiFiOnly(context) || (instantPictureUploadViaWiFiOnly(context) == isConnectedViaWiFi(context) == true))) {
            DbHandler db = new DbHandler(context);
            Cursor c = db.getAwaitingFiles();
            if (c.moveToFirst()) {
                do {
                    String account_name = c.getString(c.getColumnIndex("account"));
                    String file_path = c.getString(c.getColumnIndex("path"));
                    File f = new File(file_path);
                    if (f.exists()) {
                        Account account = new Account(account_name, MainApp.getAccountType());

                        String mimeType = null;
                        try {
                            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                    f.getName().substring(f.getName().lastIndexOf('.') + 1));

                        } catch (Throwable e) {
                            Log_OC.e(TAG, "Trying to find out MIME type of a file without extension: " + f.getName());
                        }
                        if (mimeType == null)
                            mimeType = "application/octet-stream";

                        Intent i = new Intent(context, FileUploader.class);
                        i.putExtra(FileUploader.KEY_ACCOUNT, account);
                        i.putExtra(FileUploader.KEY_LOCAL_FILE, file_path);
                        i.putExtra(FileUploader.KEY_REMOTE_FILE, FileStorageUtils.getInstantUploadFilePath(context, f.getName()));
                        i.putExtra(FileUploader.KEY_UPLOAD_TYPE, FileUploader.UPLOAD_SINGLE_FILE);
                        i.putExtra(FileUploader.KEY_INSTANT_UPLOAD, true);

                        // instant upload behaviour
                        i = addInstantUploadBehaviour(i, context);

                        context.startService(i);

                    } else {
                        Log_OC.w(TAG, "Instant upload file " + f.getAbsolutePath() + " dont exist anymore");
                    }
                } while (c.moveToNext());
            }
            c.close();
            db.close();
        }

    }

    public static boolean isOnline(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm.getActiveNetworkInfo() != null && cm.getActiveNetworkInfo().isConnected();
    }

    public static boolean isConnectedViaWiFi(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm != null && cm.getActiveNetworkInfo() != null
                && cm.getActiveNetworkInfo().getType() == ConnectivityManager.TYPE_WIFI
                && cm.getActiveNetworkInfo().getState() == State.CONNECTED;
    }

    public static boolean instantPictureUploadEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("instant_uploading", false);
    }

    public static boolean instantVideoUploadEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("instant_video_uploading", false);
    }

    public static boolean instantPictureUploadViaWiFiOnly(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("instant_upload_on_wifi", false);
    }
    
    public static boolean instantVideoUploadViaWiFiOnly(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("instant_video_upload_on_wifi", false);
    }
}
