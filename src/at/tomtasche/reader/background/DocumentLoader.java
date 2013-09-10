package at.tomtasche.reader.background;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import android.content.Context;
import android.net.Uri;
import android.support.v4.content.AsyncTaskLoader;
import at.stefl.commons.lwxml.writer.LWXMLWriter;
import at.stefl.commons.math.vector.Vector2i;
import at.stefl.commons.util.collection.OrderedPair;
import at.stefl.opendocument.java.odf.LocatedOpenDocumentFile;
import at.stefl.opendocument.java.odf.OpenDocument;
import at.stefl.opendocument.java.odf.OpenDocumentPresentation;
import at.stefl.opendocument.java.odf.OpenDocumentSpreadsheet;
import at.stefl.opendocument.java.odf.OpenDocumentText;
import at.stefl.opendocument.java.translator.document.BulkPresentationTranslator;
import at.stefl.opendocument.java.translator.document.BulkSpreadsheetTranslator;
import at.stefl.opendocument.java.translator.document.DocumentTranslatorUtil;
import at.stefl.opendocument.java.translator.document.GenericBulkDocumentTranslator;
import at.stefl.opendocument.java.translator.document.GenericDocumentTranslator;
import at.stefl.opendocument.java.translator.document.TextTranslator;
import at.stefl.opendocument.java.translator.settings.ImageStoreMode;
import at.stefl.opendocument.java.translator.settings.TranslationSettings;
import at.tomtasche.reader.background.Document.Page;

public class DocumentLoader extends AsyncTaskLoader<Document> implements
		FileLoader {

	public static final Uri URI_INTRO = Uri.parse("reader://intro.odt");
	public static final Uri URI_ABOUT = Uri.parse("reader://about.odt");

	private Throwable lastError;
	private Uri uri;
	private boolean limit;
	private String password;
	private Document document;
	private GenericDocumentTranslator<?, ?, ?> translator;

	// support File parameter too (saves us from copying the file
	// unnecessarily)!
	// https://github.com/iPaulPro/aFileChooser/issues/4#issuecomment-16226515
	public DocumentLoader(Context context, Uri uri) {
		super(context);

		this.uri = uri;
		this.limit = true;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public void setLimit(boolean limit) {
		this.limit = limit;
	}

	@Override
	public Throwable getLastError() {
		return lastError;
	}

	@Override
	public Uri getLastUri() {
		return uri;
	}

	@Override
	public double getProgress() {
		if (translator != null)
			return translator.getCurrentProgress();

		return 0;
	}

	@Override
	protected void onStartLoading() {
		super.onStartLoading();

		if (document != null && lastError == null) {
			deliverResult(document);
		} else {
			forceLoad();
		}
	}

	@Override
	protected void onReset() {
		super.onReset();

		onStopLoading();

		document = null;
		lastError = null;
		uri = null;
		translator = null;
		password = null;
		limit = true;
	}

	@Override
	protected void onStopLoading() {
		super.onStopLoading();

		cancelLoad();
	}

	@Override
	public Document loadInBackground() {
		InputStream stream = null;

		LocatedOpenDocumentFile documentFile = null;
		try {
			// cleanup uri
			if ("/./".equals(uri.toString().substring(0, 2))) {
				uri = Uri.parse(uri.toString().substring(2,
						uri.toString().length()));
			}

			// TODO: don't delete file being displayed at the moment, but keep
			// it until the new document has finished loading
			AndroidFileCache.cleanup(getContext());

			if (URI_INTRO.equals(uri)) {
				stream = getContext().getAssets().open("intro.odt");
			} else if (URI_ABOUT.equals(uri)) {
				stream = getContext().getAssets().open("about.odt");
			} else {
				stream = getContext().getContentResolver().openInputStream(uri);
			}

			try {
				RecentDocumentsUtil.addRecentDocument(getContext(),
						uri.getLastPathSegment(), uri);
			} catch (IOException e) {
				// not a showstopper, so just continue
				e.printStackTrace();
			}

			AndroidFileCache cache = new AndroidFileCache(getContext());

			String cachedFileName = cache.create(stream);
			documentFile = new LocatedOpenDocumentFile(
					cache.getFile(cachedFileName));

			if (documentFile.isEncrypted()) {
				if (password == null)
					throw new EncryptedDocumentException();

				documentFile.setPassword(password);
				if (!documentFile.isPasswordValid())
					throw new EncryptedDocumentException();
			}

			document = new Document();
			OpenDocument openDocument = documentFile.getAsDocument();

			TranslationSettings settings = new TranslationSettings();
			settings.setCache(cache);
			settings.setImageStoreMode(ImageStoreMode.CACHE);
			if (limit) {
				settings.setMaxTableDimension(new Vector2i(300, 50));
				document.setLimited(true);
			}

			List<String> pageNames = null;
			if (openDocument instanceof OpenDocumentText) {
				pageNames = new LinkedList<String>();
				pageNames.add("Document");

				translator = new TextTranslator();
			} else {
				GenericBulkDocumentTranslator<?, ?, ?> bulkTranslator = null;
				if (openDocument instanceof OpenDocumentSpreadsheet) {
					bulkTranslator = new BulkSpreadsheetTranslator();

					OpenDocumentSpreadsheet spreadsheet = (OpenDocumentSpreadsheet) openDocument;

					pageNames = new ArrayList<String>(
							spreadsheet.getTableNames());
				} else if (openDocument instanceof OpenDocumentPresentation) {
					bulkTranslator = new BulkPresentationTranslator();

					OpenDocumentPresentation presentation = (OpenDocumentPresentation) openDocument;

					pageNames = new ArrayList<String>(
							presentation.getPageNames());
				}

				translator = bulkTranslator;
			}

			OrderedPair<String[], LWXMLWriter> output = DocumentTranslatorUtil
					.provideOutput(openDocument, cache, "temp", ".html");
			try {
				translator.translate(openDocument, output.getElement2(),
						settings);
			} finally {
				output.getElement2().close();
			}

			for (int i = 0; i < output.getElement1().length; i++) {
				File htmlFile = cache.getFile(output.getElement1()[i]);

				document.addPage(new Page(pageNames.get(i), htmlFile.toURI(), i));
			}

			return document;
		} catch (Throwable e) {
			e.printStackTrace();

			lastError = e;
		} finally {
			try {
				if (stream != null)
					stream.close();
			} catch (IOException e) {
			}

			try {
				if (documentFile != null)
					documentFile.close();
			} catch (IOException e) {
			}
		}

		return null;
	}

	@SuppressWarnings("serial")
	public static class EncryptedDocumentException extends Exception {
	}
}
