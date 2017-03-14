package com.halilibo.bettervideoplayer.subtitle;

import android.content.Context;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.support.annotation.Nullable;
import android.support.annotation.RawRes;
import android.support.v7.widget.AppCompatTextView;
import android.text.Html;
import android.text.Spanned;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.TextView;

import com.halilibo.bettervideoplayer.HelperMethods;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class CaptionsView extends AppCompatTextView implements Runnable{
    private static final String TAG = "SubtitleView";
    private static final boolean DEBUG = false;
    private static final int UPDATE_INTERVAL = 50;
    private MediaPlayer player;
    private TreeMap<Long, Line> track;
    private CMime mimeType;

    public CaptionsView(Context context) {
        super(context);
    }

    public CaptionsView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CaptionsView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    @SuppressWarnings("deprecated")
    public void run() {
        if (player !=null && track!= null){
            int seconds = player.getCurrentPosition() / 1000;
            String stringText = (DEBUG?"[" + HelperMethods.secondsToDuration(seconds) + "] ":"") +
                getTimedText(player.getCurrentPosition());
            Spanned htmlText = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                htmlText = Html.fromHtml(stringText, Html.FROM_HTML_MODE_LEGACY);
            } else {
                htmlText = Html.fromHtml(stringText);
            }
            setText(htmlText);
        }
        postDelayed(this, UPDATE_INTERVAL);
    }

    private String getTimedText(long currentPosition) {
        String result = "";
        for(Map.Entry<Long, Line> entry: track.entrySet()){
            if (currentPosition < entry.getKey()) break;
            if (currentPosition < entry.getValue().to) result = entry.getValue().text;
        }
        return result;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        postDelayed(this, 300);
        this.setShadowLayer(6,6,6, Color.BLACK);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(this);
    }
    public void setPlayer(MediaPlayer player) {
        this.player = player;
    }

    public void setCaptionsSource(@RawRes int ResID, CMime mime){
        this.mimeType = mime;
        track = getSubtitleFile(ResID);

    }

    public void setCaptionsSource(@Nullable Uri path, CMime mime){
        this.mimeType = mime;
        if(path == null){
            track = new TreeMap<>();
        }
        if (HelperMethods.isRemotePath(path)) {
            try {
                URL url = new URL(path.toString());
                getSubtitleFile(url);
            } catch (MalformedURLException | NullPointerException e) {
                e.printStackTrace();
            }
        } else {
            track = getSubtitleFile(path.toString());
        }

    }

    /////////////Utility Methods:
    //Based on https://github.com/sannies/mp4parser/
    //Apache 2.0 Licence at: https://github.com/sannies/mp4parser/blob/master/LICENSE

    public static TreeMap<Long, Line> parse(InputStream in, CMime mime) throws IOException {
        if(mime == CMime.SUBRIP){
            return parseSrt(in);
        }
        else if(mime == CMime.WEBVTT){
            return parseVtt(in);
        }

        return parseSrt(in);
    }

    public static TreeMap<Long, Line> parseSrt(InputStream is) throws IOException {
        LineNumberReader r = new LineNumberReader(new InputStreamReader(is, "UTF-8"));
        TreeMap<Long, Line> track = new TreeMap<>();
        while ((r.readLine()) != null) /*Read cue number*/{
            String timeString = r.readLine();
            String lineString = "";
            String s;
            while (!((s = r.readLine()) == null || s.trim().equals(""))) {
                lineString += s + "\n";
            }
            // Remove unnecessary \n at the end of the string
            lineString = lineString.substring(0, lineString.length()-1);

            long startTime = parseSrt(timeString.split("-->")[0]);
            long endTime = parseSrt(timeString.split("-->")[1]);
            track.put(startTime, new Line(startTime, endTime, lineString));
        }
        return track;
    }

    private static long parseSrt(String in) {
        long hours = Long.parseLong(in.split(":")[0].trim());
        long minutes = Long.parseLong(in.split(":")[1].trim());
        long seconds = Long.parseLong(in.split(":")[2].split(",")[0].trim());
        long millies = Long.parseLong(in.split(":")[2].split(",")[1].trim());

        return hours * 60 * 60 * 1000 + minutes * 60 * 1000 + seconds * 1000 + millies;

    }

    public static TreeMap<Long, Line> parseVtt(InputStream is) throws IOException {
        LineNumberReader r = new LineNumberReader(new InputStreamReader(is, "UTF-8"));
        TreeMap<Long, Line> track = new TreeMap<>();
        r.readLine(); // Read first WEBVTT FILE cue
        r.readLine(); // Empty line after cue
        String timeString;
        while ((timeString = r.readLine()) != null) /*Read cue number*/{
            String lineString = "";
            String s;
            while (!((s = r.readLine()) == null || s.trim().equals(""))) {
                lineString += s + "\n";
            }
            // Remove unnecessary \n at the end of the string
            lineString = lineString.substring(0, lineString.length()-1);

            // Support 00:22:28.681 --> 00:22:30.265 position:95% align:right
            String[] tokens = timeString.split(" ");
            for (int i = 0; i < tokens.length; ++i) {
                if (i > 0 && i < tokens.length - 1 && tokens[i].equalsIgnoreCase("-->")) {
                    String startTimeString = tokens[i - 1];
                    String endTimeString = tokens[i + 1];
                    long startTime = parseVtt(startTimeString);
                    long endTime = parseVtt(endTimeString);
                    track.put(startTime, new Line(startTime, endTime, lineString));
                    break;
                }
            }
        }
        return track;
    }

    private static long parseVtt(String in) {
        boolean hoursAvailable = in.split(":").length == 3;
        if(hoursAvailable) {
            long hours = Long.parseLong(in.split(":")[0].trim());
            long minutes = Long.parseLong(in.split(":")[1].trim());
            long seconds = Long.parseLong(in.split(":")[2].split("\\.")[0].trim());
            long millies = Long.parseLong(in.split(":")[2].split("\\.")[1].trim());
            return hours * 60 * 60 * 1000 + minutes * 60 * 1000 + seconds * 1000 + millies;
        }
        else{
            long minutes = Long.parseLong(in.split(":")[0].trim());
            long seconds = Long.parseLong(in.split(":")[1].split("\\.")[0].trim());
            long millies = Long.parseLong(in.split(":")[1].split("\\.")[1].trim());
            return minutes * 60 * 1000 + seconds * 1000 + millies;
        }

    }

    private TreeMap<Long, Line> getSubtitleFile(String path) {
        InputStream inputStream = null;
        try {
            inputStream = new FileInputStream(new File(path));
            return parse(inputStream, mimeType);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    private TreeMap<Long, Line> getSubtitleFile(int resId) {
        InputStream inputStream = null;
        try {
            inputStream = getResources().openRawResource(resId);
            return parse(inputStream, mimeType);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    private void getSubtitleFile(URL url) {
        DownloadFile downloader = new DownloadFile(getContext(), new DownloadCallback() {
            @Override
            public void onDownload(File file) {
                try {
                    track = getSubtitleFile(file.getPath());
                }catch (Exception ignored){} // Possibility of download returning 500
            }

            @Override
            public void onFail(Exception e) {
                Log.d(TAG, e.getMessage());
            }
        });
        Log.d(TAG, "url: " + url.toString());
        downloader.execute(url.toString(), "subtitle.srt");
    }

    public static class Line {
        long from;
        long to;
        String text;


        public Line(long from, long to, String text) {
            this.from = from;
            this.to = to;
            this.text = text;
        }
    }

    public enum CMime {
        SUBRIP, WEBVTT
    }
}