package com.mathnote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.content.ClipData;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int AUDIO_PERMISSION = 42;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private NoteStore store;
    private InkView ink;
    private TextView title, state, feedback;
    private Button voiceButton, liveButton;
    private Runnable pendingSave, pendingCheck, pendingSync, pollMarks;
    private SpeechRecognizer recognizer;
    private TextToSpeech speaker;
    private boolean busy, autoEnabled, fingerWriting;
    private int intervalSeconds = 60;
    private long lastAutoCheck;
    private String server, token;
    private boolean liveSync;
    private String syncRevision = "";

    @Override public void onCreate(Bundle stateBundle) {
        super.onCreate(stateBundle);
        getWindow().setStatusBarColor(0xff17243a);
        getWindow().setNavigationBarColor(0xff17243a);
        store = new NoteStore(this);
        loadSettings();
        buildScreen();
        ink.load(store.loadPage(store.currentPage));
        updateTitle();
        showState("Notes are saved on this tablet. Share manually with ChatGPT or use a local model.");
    }
    private int dp(float n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }
    private Button button(String label, LinearLayout bar, View.OnClickListener click) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false);
        b.setTextSize(13); b.setMinHeight(dp(42)); b.setMinimumHeight(dp(42));
        bar.addView(b, new LinearLayout.LayoutParams(-2, dp(48)));
        b.setOnClickListener(click); return b;
    }
    private void buildScreen() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff4f7fa);
        setContentView(root);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(6), dp(8), dp(4)); header.setBackgroundColor(0xff17243a);
        title = new TextView(this); title.setTextColor(Color.WHITE); title.setTextSize(20);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(header);
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout bar = new LinearLayout(this); bar.setPadding(dp(4), 0, dp(4), 0); scroll.addView(bar);
        root.addView(scroll);
        button("Notebooks", bar, v -> chooseBook());
        button("Pages", bar, v -> choosePage());
        button("+ Page", bar, v -> askName("New page", "Page " + (store.currentBook.pages.size() + 1),
                name -> { NoteStore.Page page = store.createPage(store.currentBook, name); openPage(store.currentBook, page); }));
        button("Black", bar, v -> ink.setColor(0xff192337));
        button("Blue", bar, v -> ink.setColor(0xff1559bf));
        button("Red", bar, v -> ink.setColor(0xffbc364a));
        button("Green", bar, v -> ink.setColor(0xff16835d));
        button("Eraser", bar, v -> { ink.setEraser(); showState("Eraser removes a touched stroke."); });
        button("Undo", bar, v -> ink.undo());
        button("Redo", bar, v -> ink.redo());
        button("Check in ChatGPT", bar, v -> sharePage(false));
        button("Local check", bar, v -> check(false, false));
        liveButton = button("Live sync off", bar, v -> toggleLiveSync());
        button("Settings", bar, v -> settings());
        ink = new InkView(this); ink.setFingerWriting(fingerWriting);
        ink.setListener(this::edited);
        root.addView(ink, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bottom = new LinearLayout(this); bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(12), dp(5), dp(12), dp(6)); bottom.setBackgroundColor(0xffeaf0f6);
        LinearLayout actions = new LinearLayout(this);
        voiceButton = button("Talk locally", actions, v -> startVoice());
        button("ChatGPT voice", actions, v -> sharePage(true));
        button("Show answer", actions, v -> check(false, true));
        bottom.addView(actions);
        state = new TextView(this); state.setTextColor(0xff42536a); state.setTextSize(12);
        bottom.addView(state);
        ScrollView answerScroll = new ScrollView(this);
        feedback = new TextView(this); feedback.setTextSize(15); feedback.setTextColor(0xff17243a);
        feedback.setText("Local tutor feedback appears here. ChatGPT opens in its own app with a page snapshot.");
        feedback.setPadding(0, dp(6), 0, dp(6)); answerScroll.addView(feedback);
        bottom.addView(answerScroll, new LinearLayout.LayoutParams(-1, dp(112)));
        root.addView(bottom);
    }
    private void updateTitle() { title.setText(store.currentBook.title + "  /  " + store.currentPage.title); }
    private void showState(String message) { if (state != null) state.setText(message); }
    private void edited() {
        if (pendingSave != null) handler.removeCallbacks(pendingSave);
        pendingSave = this::saveNow;
        handler.postDelayed(pendingSave, 450);
        ink.setAnnotations(null); syncRevision = "";
        if (pendingSync != null) handler.removeCallbacks(pendingSync);
        if (pollMarks != null) handler.removeCallbacks(pollMarks);
        if (liveSync) {
            pendingSync = this::syncPage;
            handler.postDelayed(pendingSync, 2500);
        }
        if (pendingCheck != null) handler.removeCallbacks(pendingCheck);
        if (autoEnabled && ink.hasInk()) {
            pendingCheck = () -> {
                long now = SystemClock.elapsedRealtime();
                if (!busy && now - lastAutoCheck >= intervalSeconds * 1000L) check(true, false);
            };
            handler.postDelayed(pendingCheck, 2500);
        }
    }
    private void saveNow() {
        if (pendingSave != null) handler.removeCallbacks(pendingSave);
        pendingSave = null;
        store.savePage(store.currentPage, ink.strokes());
        showState("Saved locally. Local AI needs your configured server.");
    }
    private void openPage(NoteStore.Book book, NoteStore.Page page) {
        if (pendingCheck != null) handler.removeCallbacks(pendingCheck);
        if (pendingSync != null) handler.removeCallbacks(pendingSync);
        if (pollMarks != null) handler.removeCallbacks(pollMarks);
        saveNow(); store.currentBook = book; store.currentPage = page;
        ink.load(store.loadPage(page)); updateTitle();
        ink.setAnnotations(null); syncRevision = "";
        feedback.setText("Ready to check this page when you ask.");
        if (liveSync) syncPage();
    }
    private interface Named { void use(String name); }
    private void askName(String heading, String initial, Named action) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setText(initial);
        new AlertDialog.Builder(this).setTitle(heading).setView(input)
            .setPositiveButton("Create", (dialog, which) -> {
                String name = input.getText().toString().trim();
                if (!name.isEmpty()) action.use(name);
            }).setNegativeButton("Cancel", null).show();
    }
    private void chooseBook() {
        ArrayList<String> names = new ArrayList<>();
        for (NoteStore.Book book : store.books) names.add(book.title);
        names.add("+ New notebook");
        new AlertDialog.Builder(this).setTitle("Notebooks")
            .setItems(names.toArray(new String[0]), (d, index) -> {
                if (index == store.books.size()) askName("New notebook", "Calculus 2", name -> {
                    NoteStore.Book book = store.createBook(name); openPage(book, book.pages.get(0));
                });
                else { NoteStore.Book book = store.books.get(index); openPage(book, book.pages.get(0)); }
            }).show();
    }
    private void choosePage() {
        ArrayList<String> names = new ArrayList<>();
        for (NoteStore.Page page : store.currentBook.pages) names.add(page.title);
        new AlertDialog.Builder(this).setTitle(store.currentBook.title + " pages")
            .setItems(names.toArray(new String[0]), (d, index) -> openPage(store.currentBook,
                    store.currentBook.pages.get(index))).show();
    }
    private void loadSettings() {
        android.content.SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        server = prefs.getString("server", "http://192.168.1.10:8765");
        token = prefs.getString("token", "");
        autoEnabled = prefs.getBoolean("auto", false);
        intervalSeconds = prefs.getInt("interval", 60);
        fingerWriting = prefs.getBoolean("finger", false);
    }
    private void settings() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(8), dp(18), 0);
        TextView addressLabel = new TextView(this); addressLabel.setText("Server address (tablet and Linux box on same network)");
        form.addView(addressLabel);
        EditText address = new EditText(this); address.setSingleLine(true); address.setText(server);
        form.addView(address);
        TextView tokenLabel = new TextView(this); tokenLabel.setText("Device token from MATHNOTE_TOKEN");
        form.addView(tokenLabel);
        EditText secret = new EditText(this); secret.setSingleLine(true);
        secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setText(token); form.addView(secret);
        CheckBox auto = new CheckBox(this); auto.setText("Automatic feedback after 2.5 seconds without writing");
        auto.setChecked(autoEnabled); form.addView(auto);
        TextView frequency = new TextView(this); frequency.setText("Minimum time between automatic checks"); form.addView(frequency);
        Spinner spinner = new Spinner(this);
        String[] choices = {"30 seconds", "60 seconds", "2 minutes", "5 minutes"};
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices));
        spinner.setSelection(intervalSeconds == 30 ? 0 : intervalSeconds == 120 ? 2 : intervalSeconds == 300 ? 3 : 1);
        form.addView(spinner);
        CheckBox finger = new CheckBox(this); finger.setText("Allow finger writing (S Pen only is safer for palm rejection)");
        finger.setChecked(fingerWriting); form.addView(finger);
        TextView limits = new TextView(this);
        limits.setText("Auto checks use only your local model, at most 40 per day. Local voice uses Android speech recognition and speech output. ChatGPT checks require a manual share.");
        form.addView(limits);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        new AlertDialog.Builder(this).setTitle("MathNote settings").setView(scroll)
            .setPositiveButton("Save", (d, which) -> {
                server = address.getText().toString().trim(); token = secret.getText().toString().trim();
                autoEnabled = auto.isChecked(); fingerWriting = finger.isChecked();
                intervalSeconds = new int[]{30,60,120,300}[spinner.getSelectedItemPosition()];
                ink.setFingerWriting(fingerWriting);
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putString("server", server).putString("token", token)
                    .putBoolean("auto", autoEnabled).putInt("interval", intervalSeconds)
                    .putBoolean("finger", fingerWriting).apply();
                showState("Settings saved. Notes remain available offline.");
            }).setNegativeButton("Cancel", null).show();
    }
    private ArrayList<InkView.Stroke> snapshot() {
        return InkCodec.decode(InkCodec.encode(ink.strokes()));
    }
    private void toggleLiveSync() {
        if (!liveSync && token.isEmpty()) {
            showState("Set the Linux server address and device token first."); return;
        }
        liveSync = !liveSync;
        liveButton.setText(liveSync ? "Live sync on" : "Live sync off");
        if (liveSync) {
            showState("This page is syncing to your Linux server. ChatGPT can read it through MCP when connected.");
            syncPage();
        } else {
            if (pendingSync != null) handler.removeCallbacks(pendingSync);
            if (pollMarks != null) handler.removeCallbacks(pollMarks);
            showState("Live sync stopped. Notes remain local.");
        }
    }
    private void syncPage() {
        if (!liveSync) return;
        if (pollMarks != null) handler.removeCallbacks(pollMarks);
        String pageId = store.currentPage.id, pageTitle = store.currentPage.title;
        ArrayList<InkView.Stroke> strokes = snapshot();
        String address = server, auth = token;
        worker.execute(() -> {
            try {
                JSONObject request = new JSONObject(); request.put("page_id", pageId);
                request.put("title", pageTitle); request.put("image", TutorClient.image(strokes));
                JSONObject response = TutorClient.post(address, auth, "/sync", request);
                runOnUiThread(() -> {
                    if (!liveSync || !pageId.equals(store.currentPage.id)) return;
                    syncRevision = response.optString("revision");
                    showState("Page synced. Waiting for tutor marks from ChatGPT or Codex.");
                    refreshMarks();
                });
            } catch (Exception error) { runOnUiThread(() -> {
                if (liveSync) showState("Page sync unavailable: " + error.getMessage());
            }); }
        });
    }
    private void refreshMarks() {
        if (!liveSync || syncRevision.isEmpty()) return;
        String pageId = store.currentPage.id, revision = syncRevision;
        String address = server, auth = token;
        worker.execute(() -> {
            try {
                JSONObject result = TutorClient.annotations(address, auth, pageId);
                runOnUiThread(() -> {
                    if (liveSync && pageId.equals(store.currentPage.id) && revision.equals(syncRevision) &&
                            revision.equals(result.optString("revision"))) {
                        ink.setAnnotations(result.optJSONArray("marks"));
                        if (result.optJSONArray("marks") != null && result.optJSONArray("marks").length() > 0)
                            showState("Tutor marks are displayed over your work.");
                    }
                    if (liveSync) {
                        pollMarks = this::refreshMarks; handler.postDelayed(pollMarks, 5000);
                    }
                });
            } catch (Exception error) { runOnUiThread(() -> {
                if (liveSync) {
                    pollMarks = this::refreshMarks; handler.postDelayed(pollMarks, 5000);
                }
            }); }
        });
    }
    private void check(boolean automatic, boolean reveal) {
        if (busy) return;
        if (!ink.hasInk()) { showState("Write a step on this page before checking."); return; }
        if (token.isEmpty()) { showState("Set the server address and device token in Settings. Notes still work offline."); return; }
        if (pendingCheck != null) handler.removeCallbacks(pendingCheck);
        if (automatic) lastAutoCheck = SystemClock.elapsedRealtime();
        busy = true; showState(automatic ? "Automatic check in progress..." : "Checking this page...");
        ArrayList<InkView.Stroke> strokes = snapshot();
        String address = server, auth = token;
        worker.execute(() -> {
            try {
                JSONObject request = new JSONObject(); request.put("image", TutorClient.image(strokes));
                request.put("automatic", automatic); request.put("reveal", reveal);
                JSONObject response = TutorClient.post(address, auth, "/check", request);
                runOnUiThread(() -> {
                    busy = false;
                    String status = response.optString("status");
                    String heading = status.equals("issue") ? "Possible issue" :
                                     status.equals("unclear") ? "Writing unclear" : "No clear error found";
                    feedback.setText(heading + "\n" + response.optString("step") + "\n" +
                        response.optString("explanation") + "\nHint: " + response.optString("hint") +
                        (reveal ? "\nAnswer: " + response.optString("answer") : ""));
                    showState((automatic ? "Automatic" : "Manual") + " check complete. Notes saved locally.");
                });
            } catch (Exception error) { runOnUiThread(() -> {
                busy = false; showState("AI unavailable: " + error.getMessage() + ". Notes still work offline.");
            }); }
        });
    }
    private void startVoice() {
        if (busy) return;
        if (token.isEmpty()) { showState("Configure the local server and device token before local voice."); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION); return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showState("Android speech recognition is unavailable on this device."); return;
        }
        if (recognizer != null) recognizer.destroy();
        recognizer = android.os.Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
                ? SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                : SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { showState("Listening. Ask about a step on this page."); }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() { showState("Thinking about your question..."); }
            @Override public void onError(int error) { showState("Speech recognition failed (" + error + "). Try again."); }
            @Override public void onResults(Bundle results) {
                ArrayList<String> words = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (words == null || words.isEmpty()) { showState("I did not hear a question."); return; }
                askLocally(words.get(0));
            }
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        recognizer.startListening(intent);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == AUDIO_PERMISSION && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
            startVoice();
    }
    private void askLocally(String question) {
        busy = true; showState("Local tutor is reading the page...");
        ArrayList<InkView.Stroke> strokes = snapshot();
        String address = server, auth = token;
        worker.execute(() -> {
            try {
                JSONObject request = new JSONObject(); request.put("image", TutorClient.image(strokes));
                request.put("transcript", question);
                JSONObject response = TutorClient.post(address, auth, "/voice", request);
                runOnUiThread(() -> {
                    busy = false;
                    feedback.setText("You: " + response.optString("transcript") + "\nTutor: " + response.optString("reply"));
                    showState("Speaking tutor reply.");
                    speak(response.optString("reply"));
                });
            } catch (Exception error) { runOnUiThread(() -> {
                busy = false; showState("Voice unavailable: " + error.getMessage() + ". Notes still work offline.");
            }); }
        });
    }
    private void speak(String text) {
        if (speaker == null) speaker = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                speaker.setLanguage(Locale.US);
                speaker.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tutor_reply");
            } else showState("Reply text is ready, but Android speech output is unavailable.");
        });
        else speaker.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tutor_reply");
    }
    private void sharePage(boolean voice) {
        if (!ink.hasInk()) { showState("Write on this page before sharing it."); return; }
        try {
            File output = new File(getCacheDir(), "shared_page.png");
            byte[] png = android.util.Base64.decode(TutorClient.image(snapshot()), android.util.Base64.DEFAULT);
            try (java.io.FileOutputStream stream = new java.io.FileOutputStream(output)) { stream.write(png); }
            Uri uri = Uri.parse("content://com.mathnote.share/page.png");
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("image/png"); send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_TEXT, "Please inspect my handwritten Calculus II work. Point to the first specific questionable step, explain why, and give me a hint before the answer. If any handwriting is unclear, ask me instead of guessing." +
                (voice ? " I would like to discuss it by voice." : ""));
            send.setClipData(ClipData.newUri(getContentResolver(), "MathNote page", uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Share page with ChatGPT"));
        } catch (Exception error) { showState("Could not share page: " + error.getMessage()); }
    }
    @Override protected void onPause() {
        super.onPause();
        if (pollMarks != null) handler.removeCallbacks(pollMarks);
        if (pendingSync != null) handler.removeCallbacks(pendingSync);
        if (ink != null) saveNow();
    }
    @Override protected void onResume() {
        super.onResume();
        if (liveSync && ink != null) syncPage();
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        if (pendingCheck != null) handler.removeCallbacks(pendingCheck);
        if (pendingSync != null) handler.removeCallbacks(pendingSync);
        if (pollMarks != null) handler.removeCallbacks(pollMarks);
        if (recognizer != null) recognizer.destroy();
        if (speaker != null) speaker.shutdown();
        worker.shutdown();
    }
}
