/*
 * Copyright (C) 2015 Jacob Klinker
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

package com.klinker.android.send_message;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import java.io.File;
import java.io.FileNotFoundException;

public class MmsFileProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        // Don't support queries.
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        // Don't support inserts.
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        // Don't support deletes.
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        // Don't support updates.
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        // For this sample, assume all files have no type.
        return null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String fileMode) throws FileNotFoundException {
        // uri.getPath() returns "/download.<rand>.dat" (leading slash). Passing an
        // absolute child to new File(parent, child) drops the parent and resolves
        // against the filesystem root, so the file is never created inside the
        // cache dir. Strip the leading slash so the download lands in cacheDir and
        // the MmsReceivedReceiver callback can find it.
        String relativePath = uri.getPath().replaceAll("^/+", "");
        File file = new File(getContext().getCacheDir(), relativePath);
        int mode = (TextUtils.equals(fileMode, "r") ? ParcelFileDescriptor.MODE_READ_ONLY :
                ParcelFileDescriptor.MODE_WRITE_ONLY
                        |ParcelFileDescriptor.MODE_TRUNCATE
                        |ParcelFileDescriptor.MODE_CREATE);
        return ParcelFileDescriptor.open(file, mode);
    }
}