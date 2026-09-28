package com.mathnote

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

/** All notes remain in this app's private files directory. */
class NoteStore(context: Context) {
    data class Page(val id: String, val title: String)
    data class Book(val id: String, val title: String, val pages: MutableList<Page> = mutableListOf())

    private val directory = File(context.filesDir, "notes")
    val books = mutableListOf<Book>()
    lateinit var currentBook: Book
    lateinit var currentPage: Page

    init {
        if (!directory.exists() && !directory.mkdirs()) error("Cannot create notes directory")
        loadIndex()
        if (books.isEmpty()) createBook("Calculus 2")
        currentBook = books.first()
        if (currentBook.pages.isEmpty()) createPage(currentBook, "Page 1")
        currentPage = currentBook.pages.first()
    }

    fun createBook(title: String): Book {
        val book = Book(UUID.randomUUID().toString(), title)
        books.add(book)
        createPage(book, "Page 1")
        return book
    }

    fun createPage(book: Book, title: String): Page {
        val page = Page(UUID.randomUUID().toString(), title)
        book.pages.add(page)
        saveIndex()
        return page
    }

    fun loadPage(page: Page): MutableList<InkView.Stroke> {
        val file = File(directory, "${page.id}.json")
        return try {
            if (file.exists()) InkCodec.decode(file.readText(Charsets.UTF_8)) else mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun savePage(page: Page, strokes: List<InkView.Stroke>) {
        write(File(directory, "${page.id}.json"), InkCodec.encode(strokes))
    }

    private fun loadIndex() {
        val file = File(directory, "index.json")
        if (!file.exists()) return
        try {
            val all = JSONArray(file.readText(Charsets.UTF_8))
            for (i in 0 until all.length()) {
                val entry = all.getJSONObject(i)
                val book = Book(entry.getString("id"), entry.getString("title"))
                val pages = entry.getJSONArray("pages")
                for (j in 0 until pages.length()) {
                    val page = pages.getJSONObject(j)
                    book.pages.add(Page(page.getString("id"), page.getString("title")))
                }
                books.add(book)
            }
        } catch (error: Exception) {
            throw IllegalStateException("Cannot read notebook index", error)
        }
    }

    private fun saveIndex() {
        val all = JSONArray()
        books.forEach { book ->
            val pages = JSONArray()
            book.pages.forEach { page -> pages.put(JSONObject().put("id", page.id).put("title", page.title)) }
            all.put(JSONObject().put("id", book.id).put("title", book.title).put("pages", pages))
        }
        write(File(directory, "index.json"), all.toString())
    }

    private fun write(file: File, value: String) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(value.toByteArray(StandardCharsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw IllegalStateException("Cannot save notes", error)
        }
    }
}
