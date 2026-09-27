package com.mathnote;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;

/** All notes remain in this app's private files directory. */
public final class NoteStore {
    public static final class Book {
        String id, title;
        final ArrayList<Page> pages = new ArrayList<>();
    }
    public static final class Page {
        String id, title;
    }
    private final File directory;
    public final ArrayList<Book> books = new ArrayList<>();
    public Book currentBook;
    public Page currentPage;

    public NoteStore(Context context) {
        directory = new File(context.getFilesDir(), "notes");
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Cannot create notes directory");
        loadIndex();
        if (books.isEmpty()) createBook("Calculus 2");
        currentBook = books.get(0);
        if (currentBook.pages.isEmpty()) createPage(currentBook, "Page 1");
        currentPage = currentBook.pages.get(0);
    }
    public Book createBook(String title) {
        Book book = new Book(); book.id = UUID.randomUUID().toString(); book.title = title;
        books.add(book); createPage(book, "Page 1"); saveIndex(); return book;
    }
    public Page createPage(Book book, String title) {
        Page page = new Page(); page.id = UUID.randomUUID().toString(); page.title = title;
        book.pages.add(page); saveIndex(); return page;
    }
    public ArrayList<InkView.Stroke> loadPage(Page page) {
        File file = new File(directory, page.id + ".json");
        if (!file.exists()) return new ArrayList<>();
        try { return InkCodec.decode(read(file)); }
        catch (Exception error) { return new ArrayList<>(); }
    }
    public void savePage(Page page, ArrayList<InkView.Stroke> strokes) {
        write(new File(directory, page.id + ".json"), InkCodec.encode(strokes));
    }
    private void loadIndex() {
        File file = new File(directory, "index.json");
        if (!file.exists()) return;
        try {
            JSONArray all = new JSONArray(read(file));
            for (int i = 0; i < all.length(); i++) {
                JSONObject object = all.getJSONObject(i);
                Book book = new Book(); book.id = object.getString("id"); book.title = object.getString("title");
                JSONArray pages = object.getJSONArray("pages");
                for (int j = 0; j < pages.length(); j++) {
                    JSONObject entry = pages.getJSONObject(j);
                    Page page = new Page(); page.id = entry.getString("id"); page.title = entry.getString("title");
                    book.pages.add(page);
                }
                books.add(book);
            }
        } catch (Exception error) { throw new IllegalStateException("Cannot read notebook index", error); }
    }
    private void saveIndex() {
        try {
            JSONArray all = new JSONArray();
            for (Book book : books) {
                JSONObject object = new JSONObject(); object.put("id", book.id); object.put("title", book.title);
                JSONArray pages = new JSONArray();
                for (Page page : book.pages) {
                    JSONObject entry = new JSONObject(); entry.put("id", page.id); entry.put("title", page.title);
                    pages.put(entry);
                }
                object.put("pages", pages); all.put(object);
            }
            write(new File(directory, "index.json"), all.toString());
        } catch (Exception error) { throw new IllegalStateException("Cannot save notebook index", error); }
    }
    private static String read(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static void write(File file, String value) {
        AtomicFile atomic = new AtomicFile(file);
        java.io.FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            output.write(value.getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(output);
        } catch (Exception error) {
            if (output != null) atomic.failWrite(output);
            throw new IllegalStateException("Cannot save notes", error);
        }
    }
}
