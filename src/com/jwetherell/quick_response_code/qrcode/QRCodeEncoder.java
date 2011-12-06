/*
 * Copyright (C) 2008 ZXing authors
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

package com.jwetherell.quick_response_code.qrcode;

import android.provider.ContactsContract;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;

import android.net.Uri;
import android.os.Bundle;
import android.telephony.PhoneNumberUtils;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;

import com.jwetherell.quick_response_code.Contents;
import com.jwetherell.quick_response_code.Intents;
import com.jwetherell.quick_response_code.R;
import com.jwetherell.quick_response_code.core.BarcodeFormat;
import com.jwetherell.quick_response_code.core.EncodeHintType;
import com.jwetherell.quick_response_code.core.MultiFormatWriter;
import com.jwetherell.quick_response_code.core.Result;
import com.jwetherell.quick_response_code.core.WriterException;
import com.jwetherell.quick_response_code.core.common.BitMatrix;
import com.jwetherell.quick_response_code.core.result.AddressBookParsedResult;
import com.jwetherell.quick_response_code.core.result.ParsedResult;
import com.jwetherell.quick_response_code.core.result.ResultParser;


/**
 * This class does the work of decoding the user's request and extracting all the data
 * to be encoded in a barcode.
 * 
 * @author Justin Wetherell (phishman3579@gmail.com )
 * @author dswitkin@google.com (Daniel Switkin)
 */
public final class QRCodeEncoder {
    private static final String TAG = QRCodeEncoder.class.getSimpleName();
    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;

    private int dimension = Integer.MIN_VALUE;
    private Activity activity = null;
    private String contents = null;
    private String displayContents = null;
    private String title = null;
    private BarcodeFormat format = null;
    private boolean encoded = false;

    public QRCodeEncoder(Activity activity, Intent intent, int dimension) {
        this.activity = activity;
        this.dimension = dimension;
        
        String action = intent.getAction();
        if (action.equals(Intents.Encode.ACTION)) {
            encoded = encodeContentsFromZXingIntent(intent);
            if (!encoded) throw new IllegalArgumentException("No valid data to encode.");
        } else if (action.equals(Intent.ACTION_SEND)) {
            encoded = encodeContentsFromShareIntent(intent);
            if (!encoded) throw new IllegalArgumentException("No valid data to encode.");
        }
    }

    // It would be nice if the string encoding lived in the core ZXing library,
    // but we use platform specific code like PhoneNumberUtils, so it can't.
    private boolean encodeContentsFromZXingIntent(Intent intent) {
        // Default to QR_CODE if no format given.
        String formatString = intent.getStringExtra(Intents.Encode.FORMAT);
        format = null;
        if (formatString != null) {
            try {
                format = BarcodeFormat.valueOf(formatString);
            } catch (IllegalArgumentException iae) {
                // Ignore it then
            }
        }
        if (format == null || format == BarcodeFormat.QR_CODE) {
            String type = intent.getStringExtra(Intents.Encode.TYPE);
            if (type == null || type.length() == 0) {
                return false;
            }
            this.format = BarcodeFormat.QR_CODE;
            encodeQRCodeContents(intent, type);
        } else {
            String data = intent.getStringExtra(Intents.Encode.DATA);
            if (data != null && data.length() > 0) {
                contents = data;
                displayContents = data;
                title = activity.getString(R.string.contents_text);
            }
        }
        return contents != null && contents.length() > 0;
    }

    // Handles send intents from multitude of Android applications
    private boolean encodeContentsFromShareIntent(Intent intent) {
        // Check if this is a plain text encoding, or contact
        if (intent.hasExtra(Intent.EXTRA_TEXT)) {
            return encodeContentsFromShareIntentPlainText(intent);
        }
        // Attempt default sharing.
        return encodeContentsFromShareIntentDefault(intent);
    }

    private boolean encodeContentsFromShareIntentPlainText(Intent intent) {
        // Notice: Google Maps shares both URL and details in one text, bummer!
        String theContents = intent.getStringExtra(Intent.EXTRA_TEXT);
        // We only support non-empty and non-blank texts.
        // Trim text to avoid URL breaking.
        if (theContents == null) {
            return false;
        }
        theContents = theContents.trim();
        if (theContents.length() == 0) {
            return false;
        }
        contents = theContents;
        // We only do QR code.
        format = BarcodeFormat.QR_CODE;
        if (intent.hasExtra(Intent.EXTRA_SUBJECT)) {
            displayContents = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        } else if (intent.hasExtra(Intent.EXTRA_TITLE)) {
            displayContents = intent.getStringExtra(Intent.EXTRA_TITLE);
        } else {
            displayContents = contents;
        }
        title = activity.getString(R.string.contents_text);
        return true;
    }

    // Handles send intents from the Contacts app, retrieving a contact as a VCARD.
    // Note: Does not work on HTC devices due to broken custom Contacts application.
    private boolean encodeContentsFromShareIntentDefault(Intent intent) {
        format = BarcodeFormat.QR_CODE;
        try {
            Uri uri = (Uri)intent.getExtras().getParcelable(Intent.EXTRA_STREAM);
            InputStream stream = activity.getContentResolver().openInputStream(uri);
            int length = stream.available();
            if (length <= 0) {
                Log.w(TAG, "Content stream is empty");
                return false;
            }
            byte[] vcard = new byte[length];
            int bytesRead = stream.read(vcard, 0, length);
            if (bytesRead < length) {
                Log.w(TAG, "Unable to fully read available bytes from content stream");
                return false;
            }
            String vcardString = new String(vcard, 0, bytesRead, "UTF-8");
            Log.d(TAG, "Encoding share intent content:");
            Log.d(TAG, vcardString);
            Result result = new Result(vcardString, vcard, null, BarcodeFormat.QR_CODE);
            ParsedResult parsedResult = ResultParser.parseResult(result);
            if (!(parsedResult instanceof AddressBookParsedResult)) {
                Log.d(TAG, "Result was not an address");
                return false;
            }
            if (!encodeQRCodeContents((AddressBookParsedResult) parsedResult)) {
                Log.d(TAG, "Unable to encode contents");
                return false;
            }
        } catch (IOException e) {
            Log.w(TAG, e);
            return false;
        } catch (NullPointerException e) {
            Log.w(TAG, e);
            // In case the uri was not found in the Intent.
            return false;
        }
        return contents != null && contents.length() > 0;
    }

    private void encodeQRCodeContents(Intent intent, String type) {
        if (type.equals(Contents.Type.TEXT)) {
            String data = intent.getStringExtra(Intents.Encode.DATA);
            if (data != null && data.length() > 0) {
                contents = data;
                displayContents = data;
                title = activity.getString(R.string.contents_text);
            }
        } else if (type.equals(Contents.Type.EMAIL)) {
            String data = trim(intent.getStringExtra(Intents.Encode.DATA));
            if (data != null) {
                contents = "mailto:" + data;
                displayContents = data;
                title = activity.getString(R.string.contents_email);
            }
        } else if (type.equals(Contents.Type.PHONE)) {
            String data = trim(intent.getStringExtra(Intents.Encode.DATA));
            if (data != null) {
                contents = "tel:" + data;
                displayContents = PhoneNumberUtils.formatNumber(data);
                title = activity.getString(R.string.contents_phone);
            }
        } else if (type.equals(Contents.Type.SMS)) {
            String data = trim(intent.getStringExtra(Intents.Encode.DATA));
            if (data != null) {
                contents = "sms:" + data;
                displayContents = PhoneNumberUtils.formatNumber(data);
                title = activity.getString(R.string.contents_sms);
            }
        } else if (type.equals(Contents.Type.CONTACT)) {
            Bundle bundle = intent.getBundleExtra(Intents.Encode.DATA);
            if (bundle != null) {

                StringBuilder newContents = new StringBuilder(100);
                StringBuilder newDisplayContents = new StringBuilder(100);

                newContents.append("MECARD:");

                String name = trim(bundle.getString(ContactsContract.Intents.Insert.NAME));
                if (name != null) {
                    newContents.append("N:").append(escapeMECARD(name)).append(';');
                    newDisplayContents.append(name);
                }

                String address = trim(bundle.getString(ContactsContract.Intents.Insert.POSTAL));
                if (address != null) {
                    newContents.append("ADR:").append(escapeMECARD(address)).append(';');
                    newDisplayContents.append('\n').append(address);
                }

                Collection<String> uniquePhones = new HashSet<String>(Contents.PHONE_KEYS.length);
                for (int x = 0; x < Contents.PHONE_KEYS.length; x++) {
                    String phone = trim(bundle.getString(Contents.PHONE_KEYS[x]));
                    if (phone != null) {
                        uniquePhones.add(phone);
                    }
                }
                for (String phone : uniquePhones) {
                    newContents.append("TEL:").append(escapeMECARD(phone)).append(';');
                    newDisplayContents.append('\n').append(PhoneNumberUtils.formatNumber(phone));
                }

                Collection<String> uniqueEmails = new HashSet<String>(Contents.EMAIL_KEYS.length);
                for (int x = 0; x < Contents.EMAIL_KEYS.length; x++) {
                    String email = trim(bundle.getString(Contents.EMAIL_KEYS[x]));
                    if (email != null) {
                        uniqueEmails.add(email);
                    }
                }
                for (String email : uniqueEmails) {
                    newContents.append("EMAIL:").append(escapeMECARD(email)).append(';');
                    newDisplayContents.append('\n').append(email);
                }

                String url = trim(bundle.getString(Contents.URL_KEY));
                if (url != null) {
                    // escapeMECARD(url) -> wrong escape e.g. http\://zxing.google.com
                    newContents.append("URL:").append(url).append(';');
                    newDisplayContents.append('\n').append(url);
                }

                String note = trim(bundle.getString(Contents.NOTE_KEY));
                if (note != null) {
                    newContents.append("NOTE:").append(escapeMECARD(note)).append(';');
                    newDisplayContents.append('\n').append(note);
                }

                // Make sure we've encoded at least one field.
                if (newDisplayContents.length() > 0) {
                    newContents.append(';');
                    contents = newContents.toString();
                    displayContents = newDisplayContents.toString();
                    title = activity.getString(R.string.contents_contact);
                } else {
                    contents = null;
                    displayContents = null;
                }

            }
        } else if (type.equals(Contents.Type.LOCATION)) {
            Bundle bundle = intent.getBundleExtra(Intents.Encode.DATA);
            if (bundle != null) {
                // These must use Bundle.getFloat(), not getDouble(), it's part of the API.
                float latitude = bundle.getFloat("LAT", Float.MAX_VALUE);
                float longitude = bundle.getFloat("LONG", Float.MAX_VALUE);
                if (latitude != Float.MAX_VALUE && longitude != Float.MAX_VALUE) {
                    contents = "geo:" + latitude + ',' + longitude;
                    displayContents = latitude + "," + longitude;
                    title = activity.getString(R.string.contents_location);
                }
            }
        }
    }

    private boolean encodeQRCodeContents(AddressBookParsedResult contact) {
        StringBuilder newContents = new StringBuilder(100);
        StringBuilder newDisplayContents = new StringBuilder(100);

        newContents.append("MECARD:");

        String[] names = contact.getNames();
        if (names != null && names.length > 0) {
            String name = trim(names[0]);
            if (name != null) {
                newContents.append("N:").append(escapeMECARD(name)).append(';');
                newDisplayContents.append(name);
            }
        }

        for (String address : trimAndDeduplicate(contact.getAddresses())) {
            newContents.append("ADR:").append(escapeMECARD(address)).append(';');
            newDisplayContents.append('\n').append(address);
        }

        for (String phone : trimAndDeduplicate(contact.getPhoneNumbers())) {
            newContents.append("TEL:").append(escapeMECARD(phone)).append(';');
            newDisplayContents.append('\n').append(PhoneNumberUtils.formatNumber(phone));
        }

        for (String email : trimAndDeduplicate(contact.getEmails())) {
            newContents.append("EMAIL:").append(escapeMECARD(email)).append(';');
            newDisplayContents.append('\n').append(email);
        }

        String url = trim(contact.getURL());
        if (url != null) {
            newContents.append("URL:").append(escapeMECARD(url)).append(';');
            newDisplayContents.append('\n').append(url);
        }

        // Make sure we've encoded at least one field.
        if (newDisplayContents.length() > 0) {
            newContents.append(';');
            contents = newContents.toString();
            displayContents = newDisplayContents.toString();
            title = activity.getString(R.string.contents_contact);
            return true;
        } else {
            contents = null;
            displayContents = null;
            return false;
        }
    }

    private static Iterable<String> trimAndDeduplicate(String[] values) {
        if (values == null || values.length == 0) {
            return Collections.emptySet();
        }
        Collection<String> uniqueValues = new HashSet<String>(values.length);
        for (String value : values) {
            uniqueValues.add(trim(value));
        }
        return uniqueValues;
    }

    public QRCodeEncoder(String data, Bundle bundle, String type, String format, int dimension) {
        this.dimension = dimension;
        encoded = encodeContents(data, bundle, type, format);
    }

    public String getContents() {
        return contents;
    }

    public String getDisplayContents() {
        return displayContents;
    }

    public String getTitle() {
        return title;
    }

    private boolean encodeContents(String data, Bundle bundle, String type, String formatString) {
        // Default to QR_CODE if no format given.
        format = null;
        if (formatString != null) {
            try {
                format = BarcodeFormat.valueOf(formatString);
            } catch (IllegalArgumentException iae) {
                // Ignore it then
            }
        }
        if (format == null || format == BarcodeFormat.QR_CODE) {
            this.format = BarcodeFormat.QR_CODE;
            encodeQRCodeContents(data, bundle, type);
        } else if (data != null && data.length() > 0) {
            contents = data;
            displayContents = data;
            title = "Text";
        }
        return contents != null && contents.length() > 0;
    }

    private void encodeQRCodeContents(String data, Bundle bundle, String type) {
        if (type.equals(Contents.Type.TEXT)) {
            if (data != null && data.length() > 0) {
                contents = data;
                displayContents = data;
                title = "Text";
            }
        } else if (type.equals(Contents.Type.EMAIL)) {
            data = trim(data);
            if (data != null) {
                contents = "mailto:" + data;
                displayContents = data;
                title = "E-Mail";
            }
        } else if (type.equals(Contents.Type.PHONE)) {
            data = trim(data);
            if (data != null) {
                contents = "tel:" + data;
                displayContents = PhoneNumberUtils.formatNumber(data);
                title = "Phone";
            }
        } else if (type.equals(Contents.Type.SMS)) {
            data = trim(data);
            if (data != null) {
                contents = "sms:" + data;
                displayContents = PhoneNumberUtils.formatNumber(data);
                title = "SMS";
            }
        } else if (type.equals(Contents.Type.CONTACT)) {
            if (bundle != null) {
                StringBuilder newContents = new StringBuilder(100);
                StringBuilder newDisplayContents = new StringBuilder(100);

                newContents.append("MECARD:");

                String name = trim(bundle.getString(ContactsContract.Intents.Insert.NAME));
                if (name != null) {
                    newContents.append("N:").append(escapeMECARD(name)).append(';');
                    newDisplayContents.append(name);
                }

                String address = trim(bundle.getString(ContactsContract.Intents.Insert.POSTAL));
                if (address != null) {
                    newContents.append("ADR:").append(escapeMECARD(address)).append(';');
                    newDisplayContents.append('\n').append(address);
                }

                Collection<String> uniquePhones = new HashSet<String>(Contents.PHONE_KEYS.length);
                for (int x = 0; x < Contents.PHONE_KEYS.length; x++) {
                    String phone = trim(bundle.getString(Contents.PHONE_KEYS[x]));
                    if (phone != null) {
                        uniquePhones.add(phone);
                    }
                }
                for (String phone : uniquePhones) {
                    newContents.append("TEL:").append(escapeMECARD(phone)).append(';');
                    newDisplayContents.append('\n').append(PhoneNumberUtils.formatNumber(phone));
                }

                Collection<String> uniqueEmails = new HashSet<String>(Contents.EMAIL_KEYS.length);
                for (int x = 0; x < Contents.EMAIL_KEYS.length; x++) {
                    String email = trim(bundle.getString(Contents.EMAIL_KEYS[x]));
                    if (email != null) {
                        uniqueEmails.add(email);
                    }
                }
                for (String email : uniqueEmails) {
                    newContents.append("EMAIL:").append(escapeMECARD(email)).append(';');
                    newDisplayContents.append('\n').append(email);
                }

                String url = trim(bundle.getString(Contents.URL_KEY));
                if (url != null) {
                    // escapeMECARD(url) -> wrong escape e.g. http\://zxing.google.com
                    newContents.append("URL:").append(url).append(';');
                    newDisplayContents.append('\n').append(url);
                }

                String note = trim(bundle.getString(Contents.NOTE_KEY));
                if (note != null) {
                    newContents.append("NOTE:").append(escapeMECARD(note)).append(';');
                    newDisplayContents.append('\n').append(note);
                }

                // Make sure we've encoded at least one field.
                if (newDisplayContents.length() > 0) {
                    newContents.append(';');
                    contents = newContents.toString();
                    displayContents = newDisplayContents.toString();
                    title = "Contact";
                } else {
                    contents = null;
                    displayContents = null;
                }

            }
        } else if (type.equals(Contents.Type.LOCATION)) {
            if (bundle != null) {
                // These must use Bundle.getFloat(), not getDouble(), it's part of the API.
                float latitude = bundle.getFloat("LAT", Float.MAX_VALUE);
                float longitude = bundle.getFloat("LONG", Float.MAX_VALUE);
                if (latitude != Float.MAX_VALUE && longitude != Float.MAX_VALUE) {
                    contents = "geo:" + latitude + ',' + longitude;
                    displayContents = latitude + "," + longitude;
                    title = "Location";
                }
            }
        }
    }

    public Bitmap encodeAsBitmap() throws WriterException {
        if (!encoded) return null;

        Map<EncodeHintType, Object> hints = null;
        String encoding = guessAppropriateEncoding(contents);
        if (encoding != null) {
            hints = new EnumMap<EncodeHintType, Object>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, encoding);
        }
        MultiFormatWriter writer = new MultiFormatWriter();
        BitMatrix result = writer.encode(contents, format, dimension, dimension, hints);
        int width = result.getWidth();
        int height = result.getHeight();
        int[] pixels = new int[width * height];
        // All are 0, or black, by default
        for (int y = 0; y < height; y++) {
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                pixels[offset + x] = result.get(x, y) ? BLACK : WHITE;
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private static String guessAppropriateEncoding(CharSequence contents) {
        // Very crude at the moment
        for (int i = 0; i < contents.length(); i++) {
            if (contents.charAt(i) > 0xFF) { return "UTF-8"; }
        }
        return null;
    }

    private static String trim(String s) {
        if (s == null) { return null; }
        String result = s.trim();
        return result.length() == 0 ? null : result;
    }

    private static String escapeMECARD(String input) {
        if (input == null || (input.indexOf(':') < 0 && input.indexOf(';') < 0)) { return input; }
        int length = input.length();
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = input.charAt(i);
            if (c == ':' || c == ';') {
                result.append('\\');
            }
            result.append(c);
        }
        return result.toString();
    }

}
