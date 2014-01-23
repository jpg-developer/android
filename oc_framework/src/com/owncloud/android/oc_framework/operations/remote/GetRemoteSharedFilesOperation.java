/* ownCloud Android Library is available under MIT license
 *   Copyright (C) 2014 ownCloud (http://www.owncloud.org/)
 *   
 *   Permission is hereby granted, free of charge, to any person obtaining a copy
 *   of this software and associated documentation files (the "Software"), to deal
 *   in the Software without restriction, including without limitation the rights
 *   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions:
 *   
 *   The above copyright notice and this permission notice shall be included in
 *   all copies or substantial portions of the Software.
 *   
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, 
 *   EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 *   MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND 
 *   NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS 
 *   BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN 
 *   ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN 
 *   CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *   THE SOFTWARE.
 *
 */

package com.owncloud.android.oc_framework.operations.remote;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.http.HttpStatus;
import org.xmlpull.v1.XmlPullParserException;

import android.util.Log;

import com.owncloud.android.oc_framework.network.webdav.WebdavClient;
import com.owncloud.android.oc_framework.operations.RemoteOperation;
import com.owncloud.android.oc_framework.operations.RemoteOperationResult;
import com.owncloud.android.oc_framework.operations.RemoteOperationResult.ResultCode;
import com.owncloud.android.oc_framework.operations.ShareRemoteFile;
import com.owncloud.android.oc_framework.utils.ShareXMLParser;

/** 
 * Get the data from the server to know shared files/folders
 * 
 * @author masensio
 *
 */

public class GetRemoteSharedFilesOperation extends RemoteOperation {

	private static final String TAG = GetRemoteSharedFilesOperation.class.getSimpleName();

	// OCS Route
	private static final String SHAREAPI_ROUTE ="/ocs/v1.php/apps/files_sharing/api/v1/shares"; 

	private ArrayList<ShareRemoteFile> mSharedFiles;  // List of files for result

	private String mUrlServer;

	public ArrayList<ShareRemoteFile> getSharedFiles() {
		return mSharedFiles;
	}
	
	public GetRemoteSharedFilesOperation(String urlServer) {
		mUrlServer = urlServer;
	}

	@Override
	protected RemoteOperationResult run(WebdavClient client) {
		RemoteOperationResult result = null;
		int status = -1;

		// Get Method        
		GetMethod get = new GetMethod(mUrlServer + SHAREAPI_ROUTE);
		Log.d(TAG, "URL ------> " + mUrlServer + SHAREAPI_ROUTE);

		// Get the response
		try{
			status = client.executeMethod(get);
			if(isSuccess(status)) {
				Log.d(TAG, "Obtain RESPONSE");
				String response = get.getResponseBodyAsString();
				Log.d(TAG, response);

				// Parse xml response --> obtain the response in ShareFiles ArrayList
				// convert String into InputStream
				InputStream is = new ByteArrayInputStream(response.getBytes());
				ShareXMLParser xmlParser = new ShareXMLParser();
				mSharedFiles = xmlParser.parseXMLResponse(is);
				if (mSharedFiles != null) {
					Log.d(TAG, "Shared Files: " + mSharedFiles.size());
					result = new RemoteOperationResult(ResultCode.OK);
				}
			}
		} catch (HttpException e) {
			result = new RemoteOperationResult(e);
			e.printStackTrace();
		} catch (IOException e) {
			result = new RemoteOperationResult(e);
			e.printStackTrace();
		} catch (XmlPullParserException e) {
			result = new RemoteOperationResult(e);
			e.printStackTrace();
		} finally {
			get.releaseConnection();
		}
		return result;
	}

	private boolean isSuccess(int status) {
		return (status == HttpStatus.SC_OK);
	}


}