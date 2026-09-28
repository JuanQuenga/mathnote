package com.mathnote

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val pairingWorker = Executors.newSingleThreadExecutor()
    private lateinit var store: NoteStore
    private lateinit var ink: InkView
    private lateinit var title: TextView
    private lateinit var colorSwatch: TextView
    private lateinit var toolLabel: TextView
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
    private var connectionProbePending = false
    private var connectionGeneration = 0L
    private var syncRevision = ""
    private var remoteFeedbackShown = false
    private var editGeneration = 0L
    private var resumed = false
    private var savedTool = InkView.Tool.PEN
    private var savedPenColor = 0xff192337.toInt()
    private var savedHighlighterColor = 0xffffd54f.toInt()
    private var savedPenWidth = 4.4f
    private var savedHighlighterWidth = 20f

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
        handlePairingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingIntent(intent)
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
        colorSwatch = TextView(this).apply {
            text = " "
        }
        header.addView(colorSwatch, LinearLayout.LayoutParams(dp(20), dp(20)))
        toolLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(toolLabel, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(header)
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val bar = LinearLayout(this).apply { setPadding(dp(4), 0, dp(4), 0) }
        scroll.addView(bar)
        root.addView(scroll)
        button("Notebooks", bar) { chooseBook() }
        button("Pages", bar) { choosePage() }
        button("Connect", bar) { enterPairingLink() }
        button("+ Page", bar) {
            askName("New page", "Page ${store.currentBook.pages.size + 1}") { name ->
                val page = store.createPage(store.currentBook, name)
                openPage(store.currentBook, page)
            }
        }
        button("Pen", bar) { selectTool(InkView.Tool.PEN) }
        button("Highlighter", bar) { selectTool(InkView.Tool.HIGHLIGHTER) }
        button("Eraser", bar) { selectTool(InkView.Tool.ERASER) }
        button("Width", bar) { chooseWidth() }
        button("Black", bar) { selectColor(0xff192337.toInt()) }
        button("Blue", bar) { selectColor(0xff1559bf.toInt()) }
        button("Red", bar) { selectColor(0xffbc364a.toInt()) }
        button("Green", bar) { selectColor(0xff16835d.toInt()) }
        button("Purple", bar) { selectColor(0xff7944af.toInt()) }
        button("Orange", bar) { selectColor(0xffef8b27.toInt()) }
        button("Yellow", bar) { selectColor(0xffffd54f.toInt()) }
        button("Undo", bar) { ink.undo() }
        button("Redo", bar) { ink.redo() }
        button("Check in ChatGPT", bar) { sharePage(false) }
        button("Local check", bar) { check(false, false) }
        liveButton = button("Live sync off", bar) { toggleLiveSync() }
        button("Settings", bar) { settings() }
        ink = InkView(this).apply {
            restoreTools(savedTool, savedPenColor, savedHighlighterColor, savedPenWidth, savedHighlighterWidth)
            setFingerWriting(fingerWriting)
            setListener { edited() }
        }
        updateToolLabel()
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
    private fun updateToolLabel() {
        toolLabel.text = " ${ink.selectionDescription()}  "
        val color = when (ink.selectedTool) {
            InkView.Tool.PEN -> ink.penColor
            InkView.Tool.HIGHLIGHTER -> ink.highlighterColor
            InkView.Tool.ERASER -> Color.LTGRAY
        }
        colorSwatch.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1), Color.WHITE)
        }
    }
    private fun saveToolSettings() {
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putString("selected_tool", ink.selectedTool.name)
            .putInt("pen_color", ink.penColor)
            .putInt("highlighter_color", ink.highlighterColor)
            .putFloat("pen_width", ink.penWidth)
            .putFloat("highlighter_width", ink.highlighterWidth)
            .apply()
        updateToolLabel()
    }
    private fun selectTool(tool: InkView.Tool) {
        ink.setTool(tool)
        saveToolSettings()
        showState(if (tool == InkView.Tool.ERASER) "Eraser removes a touched stroke."
            else "${ink.selectionDescription()} selected. Hold the S Pen side button to erase temporarily.")
    }
    private fun selectColor(color: Int) {
        ink.setColor(color)
        saveToolSettings()
    }
    private fun chooseWidth() {
        if (ink.selectedTool == InkView.Tool.ERASER) selectTool(InkView.Tool.PEN)
        val highlighter = ink.selectedTool == InkView.Tool.HIGHLIGHTER
        val minWidth = if (highlighter) 8 else 1
        val maxWidth = if (highlighter) 40 else 16
        val initial = if (highlighter) ink.highlighterWidth else ink.penWidth
        val label = TextView(this).apply { text = "${initial.toInt()} px"; textSize = 18f }
        val slider = SeekBar(this).apply {
            max = maxWidth - minWidth
            progress = initial.toInt() - minWidth
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    label.text = "${minWidth + progress} px"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
            addView(label)
            addView(slider)
        }
        AlertDialog.Builder(this).setTitle(if (highlighter) "Highlighter width" else "Pen width")
            .setView(form).setPositiveButton("Apply") { _, _ ->
                ink.setStrokeWidth((minWidth + slider.progress).toFloat())
                saveToolSettings()
            }.setNegativeButton("Cancel", null).show()
    }
    private fun showState(message: String) { if (::state.isInitialized) state.text = message }
    private fun cancel(task: Runnable?) { task?.let { handler.removeCallbacks(it) } }

    private fun enterPairingLink() {
        val input = EditText(this).apply {
            setSingleLine(false)
            maxLines = 3
            hint = "mathnote://pair?..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this).setTitle("Connect to your computer")
            .setMessage("Scan the QR from MathNote setup with your tablet Camera. If it does not open MathNote, paste the pairing link here. This connects your tablet to the local server; connecting ChatGPT or Codex is a separate step.")
            .setView(input)
            .setPositiveButton("Review link") { _, _ -> reviewPairingLink(Uri.parse(input.text.toString().trim())) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun handlePairingIntent(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_VIEW) return
        val link = incoming.data ?: return
        // Do not retain the device token in the Activity intent after the prompt appears.
        setIntent(Intent(this, MainActivity::class.java))
        reviewPairingLink(link)
    }

    private fun reviewPairingLink(link: Uri) {
        val config = PairingLink.parse(link)
        if (config == null) {
            AlertDialog.Builder(this).setTitle("Invalid pairing link")
                .setMessage("Use a fresh QR or pairing link from MathNote setup on your computer. No connection was changed.")
                .setPositiveButton("OK", null).show()
            return
        }
        val syncNow = CheckBox(this).apply {
            text = "Start live sync now (send this page to my computer)"
            isChecked = false
            setPadding(dp(18), 0, dp(18), 0)
        }
        AlertDialog.Builder(this).setTitle("Pair with ${Uri.parse(config.server).host}?")
            .setMessage("Server: ${config.server}\n\nThe link contains a secret device token. Pair only with your own computer on a trusted network. The token grants access to your synced pages. Your notes remain on this tablet when you disconnect.")
            .setView(syncNow)
            .setPositiveButton("Pair") { _, _ -> applyPairing(config, syncNow.isChecked) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun applyPairing(config: PairingConfig, syncNow: Boolean) {
        val saved = getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putString("server", config.server).putString("token", config.token).commit()
        if (!saved) {
            showState("Could not save the connection. Try pairing again.")
            return
        }
        connectionGeneration++
        connectionProbePending = true
        cancel(pendingSync)
        cancel(pollMarks)
        server = config.server
        token = config.token
        liveSync = false
        liveButton.text = "Live sync off"
        syncRevision = ""
        ink.setAnnotations(null)
        showState("Connection saved. Checking ${Uri.parse(server).host} without sending your page...")
        val connection = connectionGeneration
        pairingWorker.execute {
            try {
                TutorClient.probe(config.server, config.token)
                runOnUiThread {
                    if (connection != connectionGeneration) return@runOnUiThread
                    connectionProbePending = false
                    liveSync = syncNow
                    liveButton.text = if (liveSync) "Live sync on" else "Live sync off"
                    showState("Computer connected. ${if (liveSync) "Sending current page..." else "Live sync is off."}")
                    if (liveSync) syncPage()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (connection == connectionGeneration) {
                        connectionProbePending = false
                        showState("Connection saved, but server unavailable: ${error.message}. Start the bridge on your computer, then tap Live sync.")
                    }
                }
            }
        }
    }

    private fun edited() {
        editGeneration++
        remoteFeedbackShown = false
        if (::feedback.isInitialized) feedback.text = "Page changed. Ask for a fresh check."
        cancel(pendingSave)
        pendingSave = Runnable { saveNow() }.also { handler.postDelayed(it, 450) }
        ink.setAnnotations(null)
        syncRevision = ""
        cancel(pendingSync)
        cancel(pollMarks)
        if (liveSync) pendingSync = Runnable { syncPage() }.also { handler.postDelayed(it, 2500) }
        cancel(pendingCheck)
        if (autoEnabled && ink.hasInk()) scheduleAutoCheck(editGeneration)
    }

    private fun scheduleAutoCheck(generation: Long) {
        cancel(pendingCheck)
        val remaining = if (lastAutoCheck == 0L) 0L
            else intervalSeconds * 1000L - (SystemClock.elapsedRealtime() - lastAutoCheck)
        pendingCheck = Runnable {
            if (generation != editGeneration || !autoEnabled || !ink.hasInk()) return@Runnable
            if (busy) scheduleAutoCheck(generation) else check(true, false)
        }.also { handler.postDelayed(it, max(2500L, remaining)) }
    }

    private fun saveNow() {
        cancel(pendingSave)
        pendingSave = null
        store.savePage(store.currentPage, ink.strokes())
        showState("Saved locally. Local AI needs your configured server.")
    }

    private fun openPage(book: NoteStore.Book, page: NoteStore.Page) {
        editGeneration++
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
        remoteFeedbackShown = false
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
        savedTool = runCatching {
            InkView.Tool.valueOf(prefs.getString("selected_tool", InkView.Tool.PEN.name) ?: InkView.Tool.PEN.name)
        }.getOrDefault(InkView.Tool.PEN)
        savedPenColor = prefs.getInt("pen_color", savedPenColor)
        savedHighlighterColor = prefs.getInt("highlighter_color", savedHighlighterColor)
        savedPenWidth = prefs.getFloat("pen_width", savedPenWidth)
        savedHighlighterWidth = prefs.getFloat("highlighter_width", savedHighlighterWidth)
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
        form.addView(TextView(this).apply {
            text = "For easier setup, use Connect and scan the QR from your computer. Tablet pairing alone does not connect ChatGPT mobile; that requires a separate remote MCP setup."
        })
        val scroll = ScrollView(this).apply { addView(form) }
        AlertDialog.Builder(this).setTitle("MathNote settings").setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val newServer = address.text.toString().trim()
                val newToken = secret.text.toString().trim()
                val connectionChanged = newServer != server || newToken != token
                server = newServer
                token = newToken
                autoEnabled = auto.isChecked
                fingerWriting = finger.isChecked
                intervalSeconds = intArrayOf(30, 60, 120, 300)[spinner.selectedItemPosition]
                ink.setFingerWriting(fingerWriting)
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putString("server", server).putString("token", token)
                    .putBoolean("auto", autoEnabled).putInt("interval", intervalSeconds)
                    .putBoolean("finger", fingerWriting).apply()
                if (connectionChanged) {
                    connectionGeneration++
                    connectionProbePending = false
                    cancel(pollMarks)
                    syncRevision = ""
                    ink.setAnnotations(null)
                    if (liveSync) syncPage()
                }
                showState("Settings saved. Notes remain available offline.")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun snapshot(): List<InkView.Stroke> = InkCodec.decode(InkCodec.encode(ink.strokes()))

    private fun toggleLiveSync() {
        if (connectionProbePending) {
            showState("Checking the computer connection. Try Live sync again in a moment.")
            return
        }
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
        val generation = editGeneration
        val connection = connectionGeneration
        val strokes = snapshot()
        val address = server
        val auth = token
        worker.execute {
            try {
                val request = JSONObject().put("page_id", pageId).put("title", pageTitle)
                    .put("image", TutorClient.image(strokes))
                val response = TutorClient.post(address, auth, "/sync", request)
                runOnUiThread {
                    if (!liveSync || pageId != store.currentPage.id || generation != editGeneration ||
                        connection != connectionGeneration) return@runOnUiThread
                    syncRevision = response.optString("revision")
                    showState("Page synced. Waiting for tutor marks from ChatGPT or Codex.")
                    refreshMarks()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (liveSync && connection == connectionGeneration)
                        showState("Page sync unavailable: ${error.message}")
                }
            }
        }
    }

    private fun refreshMarks() {
        if (!liveSync || syncRevision.isEmpty()) return
        val pageId = store.currentPage.id
        val revision = syncRevision
        val connection = connectionGeneration
        val address = server
        val auth = token
        worker.execute {
            try {
                val result = TutorClient.annotations(address, auth, pageId)
                runOnUiThread {
                    if (liveSync && connection == connectionGeneration &&
                        pageId == store.currentPage.id && revision == syncRevision &&
                        revision == result.optString("revision")) {
                        val marks = result.optJSONArray("marks")
                        ink.setAnnotations(marks)
                        if (marks != null && marks.length() > 0) showState("Tutor marks are displayed over your work.")
                        val tutorText = result.optString("feedback")
                        if (tutorText.isNotBlank()) {
                            feedback.text = tutorText
                            remoteFeedbackShown = true
                        } else if (remoteFeedbackShown) {
                            feedback.text = "Tutor feedback was cleared."
                            remoteFeedbackShown = false
                        }
                    }
                    if (connection == connectionGeneration) scheduleMarkPoll()
                }
            } catch (_: Exception) {
                runOnUiThread { if (connection == connectionGeneration) scheduleMarkPoll() }
            }
        }
    }

    private fun scheduleMarkPoll() {
        if (liveSync && resumed) pollMarks = Runnable { refreshMarks() }.also { handler.postDelayed(it, 5000) }
    }

    private fun check(automatic: Boolean, reveal: Boolean) {
        if (busy) return
        if (!ink.hasInk()) { showState("Write a step on this page before checking."); return }
        if (token.isEmpty()) { showState("Set the server address and device token in Settings. Notes still work offline."); return }
        cancel(pendingCheck)
        if (automatic) lastAutoCheck = SystemClock.elapsedRealtime()
        busy = true
        showState(if (automatic) "Automatic check in progress..." else "Checking this page...")
        val generation = editGeneration
        val pageId = store.currentPage.id
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
                    if (generation != editGeneration || pageId != store.currentPage.id) {
                        showState("Page changed while checking. Ask for fresh feedback.")
                        return@runOnUiThread
                    }
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
        val generation = editGeneration
        val pageId = store.currentPage.id
        val strokes = snapshot()
        val address = server
        val auth = token
        worker.execute {
            try {
                val request = JSONObject().put("image", TutorClient.image(strokes)).put("transcript", question)
                val response = TutorClient.post(address, auth, "/voice", request)
                runOnUiThread {
                    busy = false
                    if (generation != editGeneration || pageId != store.currentPage.id) {
                        showState("Page changed while the tutor answered. Ask again for this version.")
                        return@runOnUiThread
                    }
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
        resumed = false
        cancel(pollMarks)
        cancel(pendingSync)
        if (::ink.isInitialized) {
            saveNow()
            if (liveSync) syncPage()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
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
        pairingWorker.shutdown()
    }

    companion object { private const val AUDIO_PERMISSION = 42 }
}
