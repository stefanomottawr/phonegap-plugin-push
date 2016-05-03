package com.adobe.phonegap.push;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.StrictMode;
import android.provider.Settings.Secure;
import android.support.v4.app.NotificationCompat;
import android.text.Html;
import android.util.Log;

import com.google.android.gcm.GCMBaseIntentService;

@SuppressLint({ "NewApi", "UseSparseArrays" })
public class GCMIntentService extends GCMBaseIntentService implements PushConstants {

    private static final String LOG_TAG = "PushPlugin_GCMIntentService";
    private static HashMap<Integer, ArrayList<String>> messageMap = new HashMap<Integer, ArrayList<String>>();

    public void setNotification(int notId, String message){
        ArrayList<String> messageList = messageMap.get(notId);
        if(messageList == null) {
            messageList = new ArrayList<String>();
            messageMap.put(notId, messageList);
        }

        if(message.isEmpty()){
            messageList.clear();
        }else{
            messageList.add(message);
        }
    }

    public GCMIntentService() {
        super("GCMIntentService");
    }

    @Override
    public void onRegistered(Context context, String regId) {
        Log.v(TAG, "onRegistered: " + regId);
        if (PushPlugin.isActive()) {
            try {
                JSONObject json = new JSONObject().put(REGISTRATION_ID, regId);
                Log.v(TAG, "onRegistered: " + json.toString());
                PushPlugin.sendEvent(json);
            } catch (JSONException e) {
                // No message to the user is sent, JSON failed
                Log.e(TAG, "onRegistered: JSON exception");
            }
        } else {
            try {
                String baseUrl = getBackendUrl(context);
                String packageId = getAccountManagerPackageId(context);
                Log.d(TAG, "Backend baseUrl=" + baseUrl);
                Log.d(TAG, "AccountManager packageId=" + packageId);
                if (baseUrl == null || packageId == null) {
                    Log.d(TAG, "Unable to perform backend login due to missing backend URL");
                    return;
                }

                /* retrieves current username and password */
                Log.d(TAG, "Retrieving login info");
                Context pkgContext = context.getApplicationContext().createPackageContext(packageId, 0);
                SharedPreferences settings = pkgContext.getSharedPreferences("LoginPrefs", 0);
                if (settings == null) {
                    Log.d(TAG, "Unable to perform backend login due to missing login preferences");
                    return;
                }
                String username = settings.getString("__USERNAME__", null);
                String password = settings.getString("__PASSWORD__", null);
                if (username == null || password == null) {
                    Log.d(TAG, "Unable to perform backend login due to missing username or password");
                    return;
                }
                String deviceId = Secure.getString(context.getContentResolver(), Secure.ANDROID_ID);
                BackendLoginRunnable runnable = new BackendLoginRunnable(username, password, baseUrl, regId, deviceId);
                new Thread(runnable).start();
            } catch (Exception e) {
                Log.e(TAG, "An error corred performing silent login", e);
            }
        }
    }

    private static class BackendLoginRunnable implements Runnable {

        private static final int MAX_RETRY = 10;

        private final String username;
        private final String password;
        private final String baseUrl;
        private final String registrationId;
        private final String deviceId;

        BackendLoginRunnable(String username, String password, String baseUrl, String registrationId, String deviceId) {
            super();
            this.username = username;
            this.password = password;
            this.baseUrl = baseUrl;
            this.registrationId = registrationId;
            this.deviceId = deviceId;
        }

        @Override
        public void run() {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().permitAll().build());
            // Retries many time with increasing time lapse in order to grant backend availability.
            // The backend could be accessible only through the WIFI which can takes several time to be available, respect on the GSM
            // network which is ready on startup and which allows the GCM registration but not the backend access.
            for (int i = 1; i <= MAX_RETRY; i++) {
                try {
                    Log.d(TAG, "Performing backend login for '" + username + "' (retry " + i + ")");
                    HttpClient httpClient = new DefaultHttpClient();
                    HttpParams params = httpClient.getParams();
                    HttpConnectionParams.setConnectionTimeout(params, 4000);
                    HttpConnectionParams.setSoTimeout(params, 4000);
                    HttpPost request = new HttpPost(baseUrl + "/users/login");
                    request.addHeader("Accept", "application/json");
                    request.addHeader("content-type", "application/json");
                    JSONObject requestJson = new JSONObject();
                    requestJson.put("username", username);
                    requestJson.put("password", password);
                    JSONObject device = new JSONObject();
                    device.put("deviceId", deviceId);
                    device.put("devicePlatform", "Android");
                    device.put("notificationDeviceId", registrationId);
                    requestJson.put("device", device);
                    request.setEntity(new StringEntity(requestJson.toString()));
                    HttpResponse response = httpClient.execute(request);
                    if (200 == response.getStatusLine().getStatusCode()) {
                        return;
                    }
                    String msg = "Unable to perform backend login " + response.getStatusLine();
                    if (response.getEntity() != null) {
                        msg += "\n" + EntityUtils.toString(response.getEntity());
                    }
                    Log.e(TAG, msg);
                } catch (Throwable t) {
                    // ignores exceptions
                }
                try {
                    Thread.sleep(i * 2000L);
                } catch (Throwable t2) {
                    // ignores exceptions
                }
            }
        }
    }

    @Override
    public void onUnregistered(Context context, String regId) {
        Log.d(LOG_TAG, "onUnregistered - regId: " + regId);
    }

    @Override
    protected void onMessage(Context context, Intent intent) {
        Log.d(LOG_TAG, "onMessage - context: " + context);

        // Extract the payload from the message
        Bundle extras = intent.getExtras();
        if (extras != null) {

            SharedPreferences prefs = context.getSharedPreferences(PushPlugin.COM_ADOBE_PHONEGAP_PUSH, Context.MODE_PRIVATE);
            boolean forceShow = prefs.getBoolean(FORCE_SHOW, false);

            // if we are in the foreground and forceShow is `false` only send data
            if (!forceShow && PushPlugin.isInForeground()) {
                extras.putBoolean(FOREGROUND, true);
                PushPlugin.sendExtras(extras);
            }
            // if we are in the foreground and forceShow is `true`, force show the notification if the data has at least a message or title
            else if (forceShow && PushPlugin.isInForeground()) {
                extras.putBoolean(FOREGROUND, true);
                
                showNotificationIfPossible(context, extras);
            }
            // if we are not in the foreground always send notification if the data has at least a message or title
            else {
                extras.putBoolean(FOREGROUND, false);

                showNotificationIfPossible(context, extras);
            }
        }
    }
    
    private void showNotificationIfPossible (Context context, Bundle extras) {

        // Send a notification if there is a message or title, otherwise just send data
        String message = this.getMessageText(extras);
        String title = getString(extras, TITLE, "");
        if ((message != null && message.length() != 0) ||
                (title != null && title.length() != 0)) {
            createNotification(context, extras);
        } else {
            PushPlugin.sendExtras(extras);
        }
    }

    public void createNotification(Context context, Bundle extras) {
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        String appName = getAppName(this);
        String packageName = context.getPackageName();
        Resources resources = context.getResources();

        int notId = parseInt(NOT_ID, extras);
        Intent notificationIntent = new Intent(this, PushHandlerActivity.class);
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        notificationIntent.putExtra(PUSH_BUNDLE, extras);
        notificationIntent.putExtra(NOT_ID, notId);

        int requestCode = new Random().nextInt();
        PendingIntent contentIntent = PendingIntent.getActivity(this, requestCode, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(context)
                        .setWhen(System.currentTimeMillis())
                        .setContentTitle(getString(extras, TITLE))
                        .setTicker(getString(extras, TITLE))
                        .setContentIntent(contentIntent)
                        .setAutoCancel(true);

        SharedPreferences prefs = context.getSharedPreferences(PushPlugin.COM_ADOBE_PHONEGAP_PUSH, Context.MODE_PRIVATE);
        String localIcon = prefs.getString(ICON, null);
        String localIconColor = prefs.getString(ICON_COLOR, null);
        boolean soundOption = prefs.getBoolean(SOUND, true);
        boolean vibrateOption = prefs.getBoolean(VIBRATE, true);
        Log.d(LOG_TAG, "stored icon=" + localIcon);
        Log.d(LOG_TAG, "stored iconColor=" + localIconColor);
        Log.d(LOG_TAG, "stored sound=" + soundOption);
        Log.d(LOG_TAG, "stored vibrate=" + vibrateOption);

        /*
         * Notification Vibration
         */

        setNotificationVibration(extras, vibrateOption, mBuilder);

        /*
         * Notification Icon Color
         *
         * Sets the small-icon background color of the notification.
         * To use, add the `iconColor` key to plugin android options
         *
         */
        setNotificationIconColor(getString(extras,"color"), mBuilder, localIconColor);

        /*
         * Notification Icon
         *
         * Sets the small-icon of the notification.
         *
         * - checks the plugin options for `icon` key
         * - if none, uses the application icon
         *
         * The icon value must be a string that maps to a drawable resource.
         * If no resource is found, falls
         *
         */
        setNotificationSmallIcon(context, extras, packageName, resources, mBuilder, localIcon);

        /*
         * Notification Large-Icon
         *
         * Sets the large-icon of the notification
         *
         * - checks the gcm data for the `image` key
         * - checks to see if remote image, loads it.
         * - checks to see if assets image, Loads It.
         * - checks to see if resource image, LOADS IT!
         * - if none, we don't set the large icon
         *
         */
        setNotificationLargeIcon(extras, packageName, resources, mBuilder);

        /*
         * Notification Sound
         */
        if (soundOption) {
            setNotificationSound(context, extras, mBuilder);
        }

        /*
         *  LED Notification
         */
        setNotificationLedColor(extras, mBuilder);

        /*
         *  Priority Notification
         */
        setNotificationPriority(extras, mBuilder);

        /*
         * Notification message
         */
        setNotificationMessage(notId, extras, mBuilder);

        /*
         * Notification count
         */
        setNotificationCount(extras, mBuilder);

        /*
         * Notification add actions
         */
        createActions(extras, mBuilder, resources, packageName);

        mNotificationManager.notify(appName, notId, mBuilder.build());
    }

    private void createActions(Bundle extras, NotificationCompat.Builder mBuilder, Resources resources, String packageName) {
        Log.d(LOG_TAG, "create actions");
        String actions = getString(extras, ACTIONS);
        if (actions != null) {
            try {
                JSONArray actionsArray = new JSONArray(actions);
                for (int i=0; i < actionsArray.length(); i++) {
                    Log.d(LOG_TAG, "adding action");
                    JSONObject action = actionsArray.getJSONObject(i);
                    Log.d(LOG_TAG, "adding callback = " + action.getString(CALLBACK));
                    Intent intent = new Intent(this, PushHandlerActivity.class);
                    intent.putExtra(CALLBACK, action.getString(CALLBACK));
                    intent.putExtra(PUSH_BUNDLE, extras);
                    PendingIntent pIntent = PendingIntent.getActivity(this, i, intent, PendingIntent.FLAG_UPDATE_CURRENT);

                    mBuilder.addAction(resources.getIdentifier(action.getString(ICON), DRAWABLE, packageName),
                            action.getString(TITLE), pIntent);
                }
            } catch(JSONException e) {
                // nope
            }
        }
    }

    private void setNotificationCount(Bundle extras, NotificationCompat.Builder mBuilder) {
        String msgcnt = getString(extras, MSGCNT);
        if (msgcnt == null) {
            msgcnt = getString(extras, BADGE);
        }
        if (msgcnt != null) {
            mBuilder.setNumber(Integer.parseInt(msgcnt));
        }
    }

    private void setNotificationVibration(Bundle extras, Boolean vibrateOption, NotificationCompat.Builder mBuilder) {
        String vibrationPattern = getString(extras, VIBRATION_PATTERN);
        if (vibrationPattern != null) {
            String[] items = vibrationPattern.replaceAll("\\[", "").replaceAll("\\]", "").split(",");
            long[] results = new long[items.length];
            for (int i = 0; i < items.length; i++) {
                try {
                    results[i] = Long.parseLong(items[i]);
                } catch (NumberFormatException nfe) {}
            }
            mBuilder.setVibrate(results);
        } else {
            if (vibrateOption) {
                mBuilder.setDefaults(Notification.DEFAULT_VIBRATE);
            }
        }
    }

    private void setNotificationMessage(int notId, Bundle extras, NotificationCompat.Builder mBuilder) {
        String message = getMessageText(extras);

        String style = getString(extras, STYLE, STYLE_TEXT);
        if(STYLE_INBOX.equals(style)) {
            setNotification(notId, message);

            mBuilder.setContentText(message);

            ArrayList<String> messageList = messageMap.get(notId);
            Integer sizeList = messageList.size();
            if (sizeList > 1) {
                String sizeListMessage = sizeList.toString();
                String stacking = sizeList + " more";
                if (getString(extras, SUMMARY_TEXT) != null) {
                    stacking = getString(extras, SUMMARY_TEXT);
                    stacking = stacking.replace("%n%", sizeListMessage);
                }
                NotificationCompat.InboxStyle notificationInbox = new NotificationCompat.InboxStyle()
                        .setBigContentTitle(getString(extras, TITLE))
                        .setSummaryText(stacking);

                for (int i = messageList.size() - 1; i >= 0; i--) {
                    notificationInbox.addLine(Html.fromHtml(messageList.get(i)));
                }

                mBuilder.setStyle(notificationInbox);
            } else {
                NotificationCompat.BigTextStyle bigText = new NotificationCompat.BigTextStyle();
                if (message != null) {
                    bigText.bigText(message);
                    bigText.setBigContentTitle(getString(extras, TITLE));
                    mBuilder.setStyle(bigText);
                }
            }
        } else if (STYLE_PICTURE.equals(style)) {
            setNotification(notId, "");

            NotificationCompat.BigPictureStyle bigPicture = new NotificationCompat.BigPictureStyle();
            bigPicture.bigPicture(getBitmapFromURL(getString(extras, PICTURE)));
            bigPicture.setBigContentTitle(getString(extras, TITLE));
            bigPicture.setSummaryText(getString(extras, SUMMARY_TEXT));

            mBuilder.setContentTitle(getString(extras, TITLE));
            mBuilder.setContentText(message);

            mBuilder.setStyle(bigPicture);
        } else {
            setNotification(notId, "");

            NotificationCompat.BigTextStyle bigText = new NotificationCompat.BigTextStyle();

            if (message != null) {
                mBuilder.setContentText(Html.fromHtml(message));

                bigText.bigText(message);
                bigText.setBigContentTitle(getString(extras, TITLE));

                String summaryText = getString(extras, SUMMARY_TEXT);
                if (summaryText != null) {
                    bigText.setSummaryText(summaryText);
                }

                mBuilder.setStyle(bigText);
            }
            /*
            else {
                mBuilder.setContentText("<missing message content>");
            }
            */
        }
    }

    private String getString(Bundle extras,String key) {
        String message = extras.getString(key);
        if (message == null) {
            message = extras.getString(GCM_NOTIFICATION+"."+key);
        }
        return message;
    }

    private String getString(Bundle extras,String key, String defaultString) {
        String message = extras.getString(key);
        if (message == null) {
            message = extras.getString(GCM_NOTIFICATION+"."+key, defaultString);
        }
        return message;
    }

    private String getMessageText(Bundle extras) {
        String message = getString(extras, MESSAGE);
        if (message == null) {
            message = getString(extras, BODY);
        }
        return message;
    }

    private void setNotificationSound(Context context, Bundle extras, NotificationCompat.Builder mBuilder) {
        String soundname = getString(extras, SOUNDNAME);
        if (soundname == null) {
            soundname = getString(extras, SOUND);
        }
        if (soundname != null) {
            Uri sound = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE
                    + "://" + context.getPackageName() + "/raw/" + soundname);
            Log.d(LOG_TAG, sound.toString());
            mBuilder.setSound(sound);
        } else {
            mBuilder.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI);
        }
    }

    private void setNotificationLedColor(Bundle extras, NotificationCompat.Builder mBuilder) {
        String ledColor = getString(extras, LED_COLOR);
        if (ledColor != null) {
            // Converts parse Int Array from ledColor
            String[] items = ledColor.replaceAll("\\[", "").replaceAll("\\]", "").split(",");
            int[] results = new int[items.length];
            for (int i = 0; i < items.length; i++) {
                try {
                    results[i] = Integer.parseInt(items[i]);
                } catch (NumberFormatException nfe) {}
            }
            if (results.length == 4) {
                mBuilder.setLights(Color.argb(results[0], results[1], results[2], results[3]), 500, 500);
            } else {
                Log.e(LOG_TAG, "ledColor parameter must be an array of length == 4 (ARGB)");
            }
        }
    }

    private void setNotificationPriority(Bundle extras, NotificationCompat.Builder mBuilder) {
        String priorityStr = getString(extras, PRIORITY);
        if (priorityStr != null) {
            try {
                Integer priority = Integer.parseInt(priorityStr);
                if (priority >= NotificationCompat.PRIORITY_MIN && priority <= NotificationCompat.PRIORITY_MAX) {
                    mBuilder.setPriority(priority);
                } else {
                    Log.e(LOG_TAG, "Priority parameter must be between -2 and 2");
                }
            } catch (NumberFormatException e) {
                e.printStackTrace();
            }
        }
    }

    private void setNotificationLargeIcon(Bundle extras, String packageName, Resources resources, NotificationCompat.Builder mBuilder) {
        String gcmLargeIcon = getString(extras, IMAGE); // from gcm
        if (gcmLargeIcon != null) {
            if (gcmLargeIcon.startsWith("http://") || gcmLargeIcon.startsWith("https://")) {
                mBuilder.setLargeIcon(getBitmapFromURL(gcmLargeIcon));
                Log.d(LOG_TAG, "using remote large-icon from gcm");
            } else {
                AssetManager assetManager = getAssets();
                InputStream istr;
                try {
                    istr = assetManager.open(gcmLargeIcon);
                    Bitmap bitmap = BitmapFactory.decodeStream(istr);
                    mBuilder.setLargeIcon(bitmap);
                    Log.d(LOG_TAG, "using assets large-icon from gcm");
                } catch (IOException e) {
                    int largeIconId = 0;
                    largeIconId = resources.getIdentifier(gcmLargeIcon, DRAWABLE, packageName);
                    if (largeIconId != 0) {
                        Bitmap largeIconBitmap = BitmapFactory.decodeResource(resources, largeIconId);
                        mBuilder.setLargeIcon(largeIconBitmap);
                        Log.d(LOG_TAG, "using resources large-icon from gcm");
                    } else {
                        Log.d(LOG_TAG, "Not setting large icon");
                    }
                }
            }
        }
    }

    private void setNotificationSmallIcon(Context context, Bundle extras, String packageName, Resources resources, NotificationCompat.Builder mBuilder, String localIcon) {
        int iconId = 0;
        String icon = getString(extras, ICON);
        if (icon != null) {
            iconId = resources.getIdentifier(icon, DRAWABLE, packageName);
            Log.d(LOG_TAG, "using icon from plugin options");
        }
        else if (localIcon != null) {
            iconId = resources.getIdentifier(localIcon, DRAWABLE, packageName);
            Log.d(LOG_TAG, "using icon from plugin options");
        }
        if (iconId == 0) {
            Log.d(LOG_TAG, "no icon resource found - using application icon");
            iconId = context.getApplicationInfo().icon;
        }
        mBuilder.setSmallIcon(iconId);
    }

    private void setNotificationIconColor(String color, NotificationCompat.Builder mBuilder, String localIconColor) {
        int iconColor = 0;
        if (color != null) {
            try {
                iconColor = Color.parseColor(color);
            } catch (IllegalArgumentException e) {
                Log.e(LOG_TAG, "couldn't parse color from android options");
            }
        }
        else if (localIconColor != null) {
            try {
                iconColor = Color.parseColor(localIconColor);
            } catch (IllegalArgumentException e) {
                Log.e(LOG_TAG, "couldn't parse color from android options");
            }
        }
        if (iconColor != 0) {
            // mBuilder.setColor(iconColor);
        }
    }

    public Bitmap getBitmapFromURL(String strURL) {
        try {
            URL url = new URL(strURL);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.connect();
            InputStream input = connection.getInputStream();
            return BitmapFactory.decodeStream(input);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static String getAppName(Context context) {
        CharSequence appName =  context.getPackageManager().getApplicationLabel(context.getApplicationInfo());
        return (String)appName;
    }

    @Override
    public void onError(Context context, String errorId) {
        Log.e(LOG_TAG, "onError - errorId: " + errorId);
        // if we are in the foreground, just send the error
        if (PushPlugin.isInForeground()) {
            PushPlugin.sendError(errorId);
        }
    }

    private int parseInt(String value, Bundle extras) {
        int retval = 0;

        try {
            retval = Integer.parseInt(getString(extras, value));
        }
        catch(NumberFormatException e) {
            Log.e(LOG_TAG, "Number format exception - Error parsing " + value + ": " + e.getMessage());
        }
        catch(Exception e) {
            Log.e(LOG_TAG, "Number format exception - Error parsing " + value + ": " + e.getMessage());
        }

        return retval;
    }

    private String getBackendUrl(Context context) {
        try {
            JSONObject descr = readFromfile("www/services/_backend.json", context);
            return descr.getString("baseUrl");
        } catch (Exception e) {
            Log.e(TAG, "Unable to retrieve the backend base URL", e);
        }
        return null;
    }

    private String getAccountManagerPackageId(Context context) {
        try {
            JSONObject descr = readFromfile("www/services/_security.json", context);
            return descr.getJSONObject("accountManager").getString("packageName");
        } catch (Exception e) {
            Log.e(TAG, "Unable to retrieve the account-manager package-id", e);
        }
        return null;
    }

    public static JSONObject readFromfile(String fileName, Context context) throws Exception {
        InputStream inputStream = null;
        InputStreamReader streamReader = null;
        BufferedReader reader = null;
        try {
            inputStream = context.getResources().getAssets().open(fileName);
            streamReader = new InputStreamReader(inputStream);
            reader = new BufferedReader(streamReader);
            String line = "";
            StringBuilder sb = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            throw new Exception("Unable to read asset resource " + fileName, e);
        } finally {
            if (streamReader != null) {
                try {
                    streamReader.close();
                } catch (Throwable t) {
                }
            }
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Throwable t) {
                }
            }
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable t) {
                }
            }
        }
    }
}
