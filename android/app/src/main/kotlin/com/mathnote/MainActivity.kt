package com.mathnote

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var store: NoteStore
    private lateinit var ink: InkView
    private lateinit var title: TextView
    private lateinit var state: TextView
    private lateinit var feedback: TextView
    private lateinit var liveButton: Button
    private var pendingSave: Runnable? = null
    private var pendingCheck: Runnable? = null
    private var pendingSync: Runnable? = null
    private var pollMarks: Runnable? = null
    private var recognizer: SpeechRecognizer? = null
    private var speaker: TextToSpeech? = null
    private var busy = false
    private var autoEnabled = false
    private var fingerWriting = false
    private var intervalSeconds = 60
    private var lastAutoCheck = 0L
    private var server = ""
    private var token = ""
    private var liveSync = false
    private var syncRevision = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xff17243a.toInt()
        window.navigationBarColor = 0xff17243a.toInt()
        store = NoteStore(this)
        loadSettings()
        restoreLastPage()
        buildScreen()
        ink.load(store.loadPage(store.currentPage))
        updateTitle()
        showState("Notes are saved on this tablet. Share manually with ChatGPT or use a local model.")
    }

    private fun dp(n: Float) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun dp(n: Int) = dp(n.toFloat())

    private fun button(label: String, bar: LinearLayout, click: () -> Unit): Button = Button(this).also { b ->
        b.text = label
        b.isAllCaps = false
        b.textSize = 13f
        b.minHeight = dp(42)
        b.minimumHeight = dp(42)
        bar.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)))
        b.setOnClickListener { click() }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xfff4f7fa.toInt())
        }
        setContentView(root)
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(8), dp(4))
            setBackgroundColor(0xff17243a.toInt())
        }
        title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(header)
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val bar = LinearLayout(this).apply { setPadding(dp(4), 0, dp(4), 0) }
        scroll.addView(bar)
        root.addView(scroll)
        button("Notebooks", bar) { chooseBook() }
        button("Pages", bar) { choosePage() }
        button("+ Page", bar) {
            askName("New page", "Page ${store.currentBook.pages.size + 1}") { name ->
                val page = store.createPage(store.currentBook, name)
                openPage(store.currentBook, page)
            }
        }
        button("Black", bar) { ink.setColor(0xff192337.toInt()) }
        button("Blue", bar) { ink.setColor(0xff1559bf.toInt()) }
        button("Red", bar) { ink.setColor(0xffbc364a.toInt()) }
        button("Green", bar) { ink.setColor(0xff16835d.toInt()) }
        button("Eraser", bar) { ink.setEraser(); showState("Eraser removes a touched stroke.") }
        button("Undo", bar) { ink.undo() }
        button("Redo", bar) { ink.redo() }
        button("Check in ChatGPT", bar) { sharePage(false) }
        button("Local check", bar) { check(false, false) }
        liveButton = button("Live sync off", bar) { toggleLiveSync() }
        button("Settings", bar) { settings() }
        ink = InkView(this).apply {
            setFingerWriting(fingerWriting)
            setListener { edited() }
        }
        root.addView(ink, LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(5), dp(12), dp(6))
            setBackgroundColor(0xffeaf0f6.toInt())
        }
        val actions = LinearLayout(this)
        button("Talk locally", actions) { startVoice() }
        button("ChatGPT voice", actions) { sharePage(true) }
        button("Show answer", actions) { check(false, true) }
        bottom.addView(actions)
        state = TextView(this).apply { setTextColor(0xff42536a.toInt()); textSize = 12f }
        bottom.addView(state)
        val answerScroll = ScrollView(this)
        feedback = TextView(this).apply {
            textSize = 15f
            setTextColor(0xff17243a.toInt())
            text = "Local tutor feedback appears here. ChatGPT opens in its own app with a page snapshot."
            setPadding(0, dp(6), 0, dp(6))
        }
        answerScroll.addView(feedback)
        bottom.addView(answerScroll, LinearLayout.LayoutParams(-1, dp(112)))
        root.addView(bottom)
    }

    private fun updateTitle() { title.text = "${store.currentBook.title}  /  ${store.currentPage.title}" }
    private fun showState(message: String) { if (::state.isInitialized) state.text = message }
    private fun cancel(task: Runnable?) { task?.let { handler.removeCallbacks(it) } }

    private fun edited() {
        cancel(pendingSave)
        pendingSave = Runnable { saveNow() }.also { handler.postDelayed(it, 450) }
        ink.setAnnotations(null)
        syncRevision = ""
        cancel(pendingSync)
        cancel(pollMarks)
        if (liveSync) pendingSync = Runnable { syncPage() }.also { handler.postDelayed(it, 2500) }
        cancel(pendingCheck)
        if (autoEnabled && ink.hasInk()) {
            pendingCheck = Runnable {
                val now = SystemClock.elapsedRealtime()
                if (!busy && now - lastAutoCheck >= intervalSeconds * 1000L) check(true, false)
            }.also { handler.postDelayed(it, 2500) }
        }
    }

    private fun saveNow() {
        cancel(pendingSave)
        pendingSave = null
        store.savePage(store.currentPage, ink.strokes())
        showState("Saved locally. Local AI needs your configured server.")
    }

    private fun openPage(book: NoteStore.Book, page: NoteStore.Page) {
        cancel(pendingCheck)
        cancel(pendingSync)
        cancel(pollMarks)
        saveNow()
        store.currentBook = book
        store.currentPage = page
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putString("last_book", book.id).putString("last_page", page.id).apply()
        ink.load(store.loadPage(page))
        updateTitle()
        ink.setAnnotations(null)
        syncRevision = ""
        feedback.text = "Ready to check this page when you ask."
        if (liveSync) syncPage()
    }

    private fun askName(heading: String, initial: String, action: (String) -> Unit) {
        val input = EditText(this).apply { setSingleLine(true); setText(initial) }
        AlertDialog.Builder(this).setTitle(heading).setView(input)
            .setPositiveButton("Create") { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(action)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseBook() {
        val names = store.books.map { it.title }.toMutableList().apply { add("+ New notebook") }
        AlertDialog.Builder(this).setTitle("Notebooks")
            .setItems(names.toTypedArray()) { _, index ->
                if (index == store.books.size) askName("New notebook", "Calculus 2") { name ->
                    val book = store.createBook(name)
                    openPage(book, book.pages.first())
                } else {
                    val book = store.books[index]
                    openPage(book, book.pages.first())
                }
            }.show()
    }

    private fun choosePage() {
        val names = store.currentBook.pages.map { it.title }.toTypedArray()
        AlertDialog.Builder(this).setTitle("${store.currentBook.title} pages")
            .setItems(names) { _, index -> openPage(store.currentBook, store.currentBook.pages[index]) }.show()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        server = prefs.getString("server", "http://192.168.1.10:8765") ?: ""
        token = prefs.getString("token", "") ?: ""
        autoEnabled = prefs.getBoolean("auto", false)
        intervalSeconds = prefs.getInt("interval", 60)
        fingerWriting = prefs.getBoolean("finger", false)
    }

    private fun restoreLastPage() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val bookId = prefs.getString("last_book", "")
        val pageId = prefs.getString("last_page", "")
        for (book in store.books) {
            if (book.id != bookId) continue
            for (page in book.pages) {
                if (page.id == pageId) {
                    store.currentBook = book
                    store.currentPage = page
                    return
                }
            }
        }
    }

    private fun settings() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
        }
        form.addView(TextView(this).apply { text = "Server address (tablet and Linux box on same network)" })
        val address = EditText(this).apply { setSingleLine(true); setText(server) }
        form.addView(address)
        form.addView(TextView(this).apply { text = "Device token from MATHNOTE_TOKEN" })
        val secret = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(token)
        }
        form.addView(secret)
        val auto = CheckBox(this).apply {
            text = "Automatic feedback after 2.5 seconds without writing"
            isChecked = autoEnabled
        }
        form.addView(auto)
        form.addView(TextView(this).apply { text = "Minimum time between automatic checks" })
        val choices = arrayOf("30 seconds", "60 seconds", "2 minutes", "5 minutes")
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, choices)
            setSelection(when (intervalSeconds) { 30 -> 0; 120 -> 2; 300 -> 3; else -> 1 })
        }
        form.addView(spinner)
        val finger = CheckBox(this).apply {
            text = "Allow finger writing (S Pen only is safer for palm rejection)"
            isChecked = fingerWriting
        }
        form.addView(finger)
        form.addView(TextView(this).apply {
            text = "Auto checks use only your local model, at most 40 per day. Local voice uses Android speech recognition and speech output. ChatGPT checks require a manual share."
        })
        val scroll = ScrollView(this).apply { addView(form) }
        AlertDialog.Builder(this).setTitle("MathNote settings").setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                server = address.text.toString().trim()
                token = secret.text.toString().trim()
                autoEnabled = auto.isChecked
                fingerWriting = finger.isChecked
                intervalSeconds = intArrayOf(30, 60, 120, 300)[spinner.selectedItemPosition]
                ink.setFingerWriting(fingerWriting)
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putString("server", server).putString("token", token)
                    .putBoolean("auto", autoEnabled).putInt("interval", intervalSeconds)
                    .putBoolean("finger", fingerWriting).apply()
                showState("Settings saved. Notes remain available offline.")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun snapshot(): List<InkView.Stroke> = InkCodec.decode(InkCodec.encode(ink.strokes()))

    private fun toggleLiveSync() {
        if (!liveSync && token.isEmpty()) {
            showState("Set the Linux server address and device token first.")
            return
        }
        liveSync = !liveSync
        liveButton.text = if (liveSync) "Live sync on" else "Live sync off"
        if (liveSync) {
            showState("This page is syncing to your Linux server. ChatGPT can read it through MCP when connected.")
            syncPage()
        } else {
            cancel(pendingSync)
            cancel(pollMarks)
            showState("Live sync stopped. Notes remain local.")
        }
    }

    private fun syncPage() {
        if (!liveSync) return
        cancel(pollMarks)
        val pageId = store.currentPage.id
        val pageTitle = store.currentPage.title
        val strokes = snapshot()
        val address = server
        val auth = token
        worker.execute {
            try {
                val request = JSONObject().put("page_id", pageId).put("title", pageTitle)
                    .put("image", TutorClient.image(strokes))
                val response = TutorClient.post(address, auth, "/sync", request)
                runOnUiThread {
                    if (!liveSync || pageId != store.currentPage.id) return@runOnUiThread
                    syncRevision = response.optString("revision")
                    showState("Page synced. Waiting for tutor marks from ChatGPT or Codex.")
                    refreshMarks()
                }
            } catch (error: Exception) {
                runOnUiThread { if (liveSync) showState("Page sync unavailable: ${error.message}") }
            }
        }
    }

    private fun refreshMarks() {
        if (!liveSync || syncRevision.isEmpty()) return
        val pageId = store.currentPage.id
        val revision = syncRevision
        val address = server
        val auth = token
        worker.execute {
            try {
                val result = TutorClient.annotations(address, auth, pageId)
                runOnUiThread {
                    if (liveSync && pageId == store.currentPage.id && revision == syncRevision &&
                        revision == result.optString("revision")) {
                        val marks = result.optJSONArray("marks")
                        ink.setAnnotations(marks)
                        if (marks != null && marks.length() > 0) showState("Tutor marks are displayed over your work.")
                    }
                    scheduleMarkPoll()
                }
            } catch (_: Exception) {
                runOnUiThread { scheduleMarkPoll() }
            }
        }
    }

    private fun scheduleMarkPoll() {
        if (liveSync) pollMarks = Runnable { refreshMarks() }.also { handler.postDelayed(it, 5000) }
    }

    private fun check(automatic: Boolean, reveal: Boolean) {
        if (busy) return
        if (!ink.hasInk()) { showState("Write a step on this page before checking."); return }
        if (token.isEmpty()) { showState("Set the server address and device token in Settings. Notes still work offline."); return }
        cancel(pendingCheck)
        if (automatic) lastAutoCheck = SystemClock.elapsedRealtime()
        busy = true
        showState(if (automatic) "Automatic check in progress..." else "Checking this page...")
        val strokes = snapshot()
        val address = server
        val auth = token
        worker.execute {
            try {
                val request = JSONObject().put("image", TutorClient.image(strokes))
                    .put("automatic", automatic).put("reveal", reveal)
                val response = TutorClient.post(address, auth, "/check", request)
                runOnUiThread {
                    busy = false
                    val heading = when (response.optString("status")) {
                        "issue" -> "Possible issue"
                        "unclear" -> "Writing unclear"
                        else -> "No clear error found"
                    }
                    feedback.text = "$heading\n${response.optString("step")}\n${response.optString("explanation")}\nHint: ${response.optString("hint")}" +
                        if (reveal) "\nAnswer: ${response.optString("answer")}" else ""
                    showState("${if (automatic) "Automatic" else "Manual"} check complete. Notes saved locally.")
                }
            } catch (error: Exception) {
                runOnUiThread {
                    busy = false
                    showState("AI unavailable: ${error.message}. Notes still work offline.")
                }
            }
        }
    }

    private fun startVoice() {
        if (busy) return
        if (token.isEmpty()) { showState("Configure the local server and device token before local voice."); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showState("Android speech recognition is unavailable on this device.")
            return
        }
        recognizer?.destroy()
        recognizer = if (android.os.Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { showState("Listening. Ask about a step on this page.") }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { showState("Thinking about your question...") }
            override fun onError(error: Int) { showState("Speech recognition failed ($error). Try again.") }
            override fun onResults(results: Bundle?) {
                val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (words.isNullOrEmpty()) { showState("I did not hear a question."); return }
                askLocally(words.first())
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer?.startListening(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_PERMISSION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoice()
    }

    private fun askLocally(question: String) {
        busy = true
        showState("Local tutor is reading the page...")
        val strokes = snapshot()
        val address = server
        val auth = token
        worker.execute {
            try {
                val request = JSONObject().put("image", TutorClient.image(strokes)).put("transcript", question)
                val response = TutorClient.post(address, auth, "/voice", request)
                runOnUiThread {
                    busy = false
                    feedback.text = "You: ${response.optString("transcript")}\nTutor: ${response.optString("reply")}"
                    showState("Speaking tutor reply.")
                    speak(response.optString("reply"))
                }
            } catch (error: Exception) {
                runOnUiThread {
                    busy = false
                    showState("Voice unavailable: ${error.message}. Notes still work offline.")
                }
            }
        }
    }

    private fun speak(text: String) {
        val current = speaker
        if (current == null) {
            speaker = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    speaker?.language = Locale.US
                    speaker?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tutor_reply")
                } else showState("Reply text is ready, but Android speech output is unavailable.")
            }
        } else current.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tutor_reply")
    }

    private fun sharePage(voice: Boolean) {
        if (!ink.hasInk()) { showState("Write on this page before sharing it."); return }
        try {
            val output = File(cacheDir, "shared_page.png")
            output.writeBytes(Base64.decode(TutorClient.image(snapshot()), Base64.DEFAULT))
            val uri = Uri.parse("content://com.mathnote.share/page.png")
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT,
                    "Please inspect my handwritten Calculus II work. Point to the first specific questionable step, explain why, and give me a hint before the answer. If any handwriting is unclear, ask me instead of guessing." +
                    if (voice) " I would like to discuss it by voice." else "")
                clipData = ClipData.newUri(contentResolver, "MathNote page", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share page with ChatGPT"))
        } catch (error: Exception) { showState("Could not share page: ${error.message}") }
    }

    override fun onPause() {
        super.onPause()
        cancel(pollMarks)
        cancel(pendingSync)
        if (::ink.isInitialized) saveNow()
    }

    override fun onResume() {
        super.onResume()
        if (liveSync && ::ink.isInitialized) syncPage()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel(pendingCheck)
        cancel(pendingSync)
        cancel(pollMarks)
        recognizer?.destroy()
        speaker?.shutdown()
        worker.shutdown()
    }

    companion object { private const val AUDIO_PERMISSION = 42 }
}
